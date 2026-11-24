package com.wf.wfchat.core.webp;

import com.wf.wfchat.core.Anim;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** WebP container (still and animated, lossy, lossless, alpha): structural verification, canonical rewrite, decode. */
public final class WebP {
    private WebP() {
    }

    /** {@code canonical} drops ICC/EXIF/XMP and unknown chunks; {@code anim} is the decoded, composited result. */
    public record Result(int width, int height, int frames, byte[] canonical, Anim anim) {
    }

    private record Chunk(String id, int off, int len) {
    }

    private record Image(int w, int h, int[] argb) {
    }

    public static boolean sniff(byte[] d) {
        return d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E'
                && d[10] == 'B' && d[11] == 'P';
    }

    public static Anim decode(byte[] raw, int maxPx) throws Invalid {
        return read(raw, maxPx, Integer.MAX_VALUE).anim;
    }

    public static Result read(byte[] d, int maxPx, int maxFrames) throws Invalid {
        try {
            return parse(d, maxPx, maxFrames);
        } catch (RuntimeException ex) {
            throw new Invalid("corrupt WebP data");
        }
    }

    private static Result parse(byte[] d, int maxPx, int maxFrames) throws Invalid {
        if (!sniff(d)) {
            throw new Invalid("not a WebP file");
        }
        if (u32(d, 4) + 8L != d.length) {
            throw new Invalid("RIFF size does not match file size");
        }
        List<Chunk> chunks = new ArrayList<>();
        int pos = 12;
        while (pos < d.length) {
            if (pos + 8 > d.length) {
                throw new Invalid("truncated chunk header");
            }
            long len = u32(d, pos + 4);
            long padded = len + (len & 1);
            if (pos + 8 + padded > d.length) {
                throw new Invalid("chunk length past end of file");
            }
            chunks.add(new Chunk(new String(d, pos, 4, java.nio.charset.StandardCharsets.ISO_8859_1), pos + 8, (int) len));
            pos += 8 + (int) padded;
        }
        if (chunks.isEmpty()) {
            throw new Invalid("no chunks");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int cw;
        int ch;
        int frames;
        Anim anim;
        Chunk first = chunks.get(0);
        if (first.id.equals("VP8X")) {
            if (first.len != 10) {
                throw new Invalid("bad VP8X");
            }
            int flags = d[first.off] & 0xFF;
            boolean animated = (flags & 0x02) != 0;
            cw = u24(d, first.off + 4) + 1;
            ch = u24(d, first.off + 7) + 1;
            limits(cw, ch, maxPx);
            byte[] vp8x = new byte[10];
            System.arraycopy(d, first.off, vp8x, 0, 10);
            vp8x[0] = (byte) (flags & 0x12);
            vp8x[1] = vp8x[2] = vp8x[3] = 0;
            chunk(body, "VP8X", vp8x, 0, 10);
            if (animated) {
                Chunk animChunk = null;
                List<Chunk> frameChunks = new ArrayList<>();
                for (Chunk c : chunks.subList(1, chunks.size())) {
                    switch (c.id) {
                        case "ANIM" -> {
                            if (animChunk != null || c.len != 6 || !frameChunks.isEmpty()) {
                                throw new Invalid("bad ANIM");
                            }
                            animChunk = c;
                        }
                        case "ANMF" -> frameChunks.add(c);
                        case "ALPH", "VP8 ", "VP8L" -> throw new Invalid("bare image data in animation");
                        default -> {
                        }
                    }
                }
                if (animChunk == null || frameChunks.isEmpty()) {
                    throw new Invalid("animation without ANIM or frames");
                }
                if (frameChunks.size() > maxFrames) {
                    throw new Invalid("more than " + maxFrames + " frames");
                }
                chunk(body, "ANIM", d, animChunk.off, 6);
                List<int[]> out = new ArrayList<>();
                int[] delays = new int[frameChunks.size()];
                int[] canvas = new int[cw * ch];
                int i = 0;
                for (Chunk f : frameChunks) {
                    if (f.len < 16) {
                        throw new Invalid("bad ANMF");
                    }
                    int fx = u24(d, f.off) * 2;
                    int fy = u24(d, f.off + 3) * 2;
                    int fw = u24(d, f.off + 6) + 1;
                    int fh = u24(d, f.off + 9) + 1;
                    delays[i] = u24(d, f.off + 12);
                    int fl = d[f.off + 15] & 0xFF;
                    if (fx + fw > cw || fy + fh > ch) {
                        throw new Invalid("frame outside canvas");
                    }
                    ByteArrayOutputStream inner = new ByteArrayOutputStream();
                    inner.write(d, f.off, 16);
                    Image img = frame(d, f.off + 16, f.len - 16, inner, maxPx);
                    if (img.w != fw || img.h != fh) {
                        throw new Invalid("frame size does not match bitstream");
                    }
                    chunk(body, "ANMF", inner.toByteArray(), 0, inner.size());
                    int[] before = (fl & 1) != 0 ? canvas.clone() : null;
                    for (int y = 0; y < fh; y++) {
                        for (int x = 0; x < fw; x++) {
                            int src = img.argb[y * fw + x];
                            int at = (fy + y) * cw + fx + x;
                            canvas[at] = (fl & 2) != 0 ? src : Anim.over(src, canvas[at]);
                        }
                    }
                    out.add(canvas.clone());
                    if (before != null) {
                        for (int y = 0; y < fh; y++) {
                            java.util.Arrays.fill(canvas, (fy + y) * cw + fx, (fy + y) * cw + fx + fw, 0);
                        }
                    }
                    i++;
                }
                frames = out.size();
                anim = new Anim(cw, ch, out, delays);
            } else {
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                Image img = still(d, chunks, inner, maxPx);
                if (img.w != cw || img.h != ch) {
                    throw new Invalid("canvas does not match bitstream");
                }
                body.writeBytes(inner.toByteArray());
                frames = 1;
                anim = new Anim(cw, ch, List.of(img.argb), new int[]{0});
            }
        } else {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            Image img = still(d, chunks, inner, maxPx);
            cw = img.w;
            ch = img.h;
            limits(cw, ch, maxPx);
            body.writeBytes(inner.toByteArray());
            frames = 1;
            anim = new Anim(cw, ch, List.of(img.argb), new int[]{0});
        }
        byte[] b = body.toByteArray();
        byte[] out = new byte[12 + b.length];
        System.arraycopy(d, 0, out, 0, 12);
        put32(out, 4, 4 + b.length);
        System.arraycopy(b, 0, out, 12, b.length);
        return new Result(cw, ch, frames, out, anim);
    }

    private static void limits(int w, int h, int maxPx) throws Invalid {
        if (w > maxPx || h > maxPx) {
            throw new Invalid(w + "x" + h + " px above " + maxPx);
        }
    }

    /** Image data of a still file: optional ALPH + VP8, or VP8L. Other chunks are dropped. */
    private static Image still(byte[] d, List<Chunk> chunks, ByteArrayOutputStream out, int maxPx) throws Invalid {
        List<Chunk> data = new ArrayList<>();
        for (Chunk c : chunks) {
            switch (c.id) {
                case "ANIM", "ANMF" -> throw new Invalid("animation chunk in still image");
                case "ALPH", "VP8 ", "VP8L" -> {
                    data.add(c);
                    out.write(d, c.off - 8, 8 + c.len + (c.len & 1));
                }
                default -> {
                }
            }
        }
        return image(d, data, maxPx);
    }

    /** ANMF payload after its 16 byte header: image chunks are copied to {@code out}. */
    private static Image frame(byte[] d, int off, int len, ByteArrayOutputStream out, int maxPx) throws Invalid {
        List<Chunk> data = new ArrayList<>();
        int pos = off;
        int end = off + len;
        while (pos < end) {
            if (pos + 8 > end) {
                throw new Invalid("truncated frame chunk");
            }
            long l = u32(d, pos + 4);
            long padded = l + (l & 1);
            if (pos + 8 + padded > end) {
                throw new Invalid("frame chunk past end");
            }
            String id = new String(d, pos, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (id.equals("ALPH") || id.equals("VP8 ") || id.equals("VP8L")) {
                data.add(new Chunk(id, pos + 8, (int) l));
                out.write(d, pos, 8 + (int) padded);
            }
            pos += 8 + (int) padded;
        }
        return image(d, data, maxPx);
    }

    private static Image image(byte[] d, List<Chunk> data, int maxPx) throws Invalid {
        if (data.isEmpty()) {
            throw new Invalid("no image data");
        }
        Chunk alph = null;
        Chunk img = null;
        for (Chunk c : data) {
            if (c.id.equals("ALPH")) {
                if (alph != null || img != null) {
                    throw new Invalid("misplaced ALPH");
                }
                alph = c;
            } else {
                if (img != null) {
                    throw new Invalid("multiple bitstreams");
                }
                img = c;
            }
        }
        if (img == null) {
            throw new Invalid("ALPH without bitstream");
        }
        if (img.id.equals("VP8L")) {
            if (alph != null) {
                throw new Invalid("ALPH with VP8L");
            }
            int[] dims = new int[2];
            int[] px = Vp8l.decode(d, img.off, img.len, dims, maxPx);
            return new Image(dims[0], dims[1], px);
        }
        Vp8.Frame f = Vp8.decode(d, img.off, img.len, maxPx);
        int[] px = f.argb();
        if (alph != null) {
            applyAlpha(d, alph, f.width(), f.height(), px);
        }
        return new Image(f.width(), f.height(), px);
    }

    private static void applyAlpha(byte[] d, Chunk c, int w, int h, int[] px) throws Invalid {
        if (c.len < 1) {
            throw new Invalid("empty ALPH");
        }
        int hdr = d[c.off] & 0xFF;
        int compression = hdr & 3;
        int filter = hdr >> 2 & 3;
        byte[] a = new byte[w * h];
        if (compression == 0) {
            if (c.len - 1 < w * h) {
                throw new Invalid("ALPH data truncated");
            }
            System.arraycopy(d, c.off + 1, a, 0, w * h);
        } else if (compression == 1) {
            int[] g = Vp8l.decodeHeaderless(d, c.off + 1, c.len - 1, w, h);
            for (int i = 0; i < a.length; i++) {
                a[i] = (byte) (g[i] >> 8);
            }
        } else {
            throw new Invalid("bad ALPH compression");
        }
        if (filter != 0) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    int pred;
                    if (x == 0 && y == 0) {
                        pred = 0;
                    } else if (y == 0) {
                        pred = a[i - 1] & 0xFF;
                    } else if (x == 0) {
                        pred = a[i - w] & 0xFF;
                    } else {
                        int l = a[i - 1] & 0xFF;
                        int t = a[i - w] & 0xFF;
                        int tl = a[i - w - 1] & 0xFF;
                        pred = switch (filter) {
                            case 1 -> l;
                            case 2 -> t;
                            default -> Math.max(0, Math.min(255, l + t - tl));
                        };
                    }
                    a[i] = (byte) (a[i] + pred);
                }
            }
        }
        for (int i = 0; i < px.length; i++) {
            px[i] = px[i] & 0xFFFFFF | (a[i] & 0xFF) << 24;
        }
    }

    private static void chunk(ByteArrayOutputStream out, String id, byte[] data, int off, int len) {
        byte[] head = new byte[8];
        for (int i = 0; i < 4; i++) {
            head[i] = (byte) id.charAt(i);
        }
        put32(head, 4, len);
        out.write(head, 0, 8);
        out.write(data, off, len);
        if ((len & 1) != 0) {
            out.write(0);
        }
    }

    private static void put32(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    private static long u32(byte[] d, int at) {
        return (d[at] & 0xFFL) | (d[at + 1] & 0xFFL) << 8 | (d[at + 2] & 0xFFL) << 16 | (d[at + 3] & 0xFFL) << 24;
    }

    private static int u24(byte[] d, int at) {
        return d[at] & 0xFF | (d[at + 1] & 0xFF) << 8 | (d[at + 2] & 0xFF) << 16;
    }
}
