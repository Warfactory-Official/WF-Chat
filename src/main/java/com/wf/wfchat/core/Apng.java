package com.wf.wfchat.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Animated PNG decoder for data already accepted by {@link EmojiFile}; composites frames onto the canvas. */
public final class Apng {
    private Apng() {
    }

    private static final class Frame {
        int w;
        int h;
        int x;
        int y;
        int delayNum;
        int delayDen;
        int dispose;
        int blend;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
    }

    public static Anim decode(byte[] d) throws IOException {
        try {
            return decodeUnchecked(d);
        } catch (RuntimeException | DataFormatException ex) {
            throw new IOException("corrupt animated PNG", ex);
        }
    }

    private static Anim decodeUnchecked(byte[] d) throws IOException, DataFormatException {
        int width = 0;
        int height = 0;
        int depth = 0;
        int colorType = 0;
        int interlace = 0;
        byte[] plte = null;
        byte[] trns = null;
        List<Frame> frames = new ArrayList<>();
        Frame cur = null;
        Frame idatFrame = null;
        boolean sawIdat = false;
        int pos = 8;
        while (pos + 12 <= d.length) {
            int n = (int) u32(d, pos);
            String type = new String(d, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int at = pos + 8;
            switch (type) {
                case "IHDR" -> {
                    width = (int) u32(d, at);
                    height = (int) u32(d, at + 4);
                    depth = d[at + 8] & 0xFF;
                    colorType = d[at + 9] & 0xFF;
                    interlace = d[at + 12] & 0xFF;
                }
                case "PLTE" -> plte = java.util.Arrays.copyOfRange(d, at, at + n);
                case "tRNS" -> trns = java.util.Arrays.copyOfRange(d, at, at + n);
                case "fcTL" -> {
                    Frame f = new Frame();
                    f.w = (int) u32(d, at + 4);
                    f.h = (int) u32(d, at + 8);
                    f.x = (int) u32(d, at + 12);
                    f.y = (int) u32(d, at + 16);
                    f.delayNum = (d[at + 20] & 0xFF) << 8 | d[at + 21] & 0xFF;
                    f.delayDen = (d[at + 22] & 0xFF) << 8 | d[at + 23] & 0xFF;
                    f.dispose = d[at + 24];
                    f.blend = d[at + 25];
                    frames.add(f);
                    cur = f;
                    if (!sawIdat) {
                        idatFrame = f;
                    }
                }
                case "IDAT" -> {
                    sawIdat = true;
                    if (idatFrame != null) {
                        idatFrame.data.write(d, at, n);
                    }
                }
                case "fdAT" -> {
                    if (cur == null) {
                        throw new IOException("fdAT before fcTL");
                    }
                    cur.data.write(d, at + 4, n - 4);
                }
                default -> {
                }
            }
            pos += 12 + n;
        }
        if (frames.isEmpty()) {
            throw new IOException("no animation frames");
        }
        int[] canvas = new int[width * height];
        List<int[]> out = new ArrayList<>(frames.size());
        int[] delays = new int[frames.size()];
        Frame prev = null;
        int[] saved = null;
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (prev != null) {
                if (prev.dispose == 2 && saved != null) {
                    System.arraycopy(saved, 0, canvas, 0, canvas.length);
                } else if (prev.dispose == 1 || prev.dispose == 2) {
                    clear(canvas, width, prev);
                }
            }
            saved = f.dispose == 2 && i > 0 ? canvas.clone() : null;
            int[] px = pixels(f.data.toByteArray(), f.w, f.h, depth, colorType, interlace, plte, trns);
            for (int y = 0; y < f.h; y++) {
                for (int x = 0; x < f.w; x++) {
                    int src = px[y * f.w + x];
                    int to = (f.y + y) * width + f.x + x;
                    canvas[to] = f.blend == 0 ? src : Anim.over(src, canvas[to]);
                }
            }
            out.add(canvas.clone());
            delays[i] = f.delayDen == 0 ? f.delayNum * 10 : f.delayNum * 1000 / f.delayDen;
            prev = f;
        }
        return new Anim(width, height, out, delays);
    }

    private static void clear(int[] canvas, int width, Frame f) {
        for (int y = 0; y < f.h; y++) {
            java.util.Arrays.fill(canvas, (f.y + y) * width + f.x, (f.y + y) * width + f.x + f.w, 0);
        }
    }

    private static int channels(int colorType) {
        return switch (colorType) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            default -> 4;
        };
    }

    private static int[] pixels(byte[] zlib, int w, int h, int depth, int colorType, int interlace, byte[] plte,
                                byte[] trns) throws DataFormatException, IOException {
        int bits = channels(colorType) * depth;
        int bpp = Math.max(1, bits / 8);
        Inflater inf = new Inflater();
        try {
            inf.setInput(zlib);
            int[] out = new int[w * h];
            int[] xs = interlace == 0 ? new int[]{0} : new int[]{0, 4, 0, 2, 0, 1, 0};
            int[] ys = interlace == 0 ? new int[]{0} : new int[]{0, 0, 4, 0, 2, 0, 1};
            int[] dx = interlace == 0 ? new int[]{1} : new int[]{8, 8, 4, 4, 2, 2, 1};
            int[] dy = interlace == 0 ? new int[]{1} : new int[]{8, 8, 8, 4, 4, 2, 2};
            for (int p = 0; p < xs.length; p++) {
                int pw = (w - xs[p] + dx[p] - 1) / dx[p];
                int ph = (h - ys[p] + dy[p] - 1) / dy[p];
                if (pw <= 0 || ph <= 0) {
                    continue;
                }
                int stride = (pw * bits + 7) / 8;
                byte[] prior = new byte[stride];
                byte[] row = new byte[stride];
                for (int y = 0; y < ph; y++) {
                    int filter = readByte(inf);
                    readFully(inf, row);
                    unfilter(filter, row, prior, bpp);
                    for (int x = 0; x < pw; x++) {
                        out[(ys[p] + y * dy[p]) * w + xs[p] + x * dx[p]] = pixel(row, x, depth, colorType, plte, trns);
                    }
                    byte[] t = prior;
                    prior = row;
                    row = t;
                }
            }
            return out;
        } finally {
            inf.end();
        }
    }

    private static int readByte(Inflater inf) throws DataFormatException, IOException {
        byte[] b = new byte[1];
        readFully(inf, b);
        return b[0] & 0xFF;
    }

    private static void readFully(Inflater inf, byte[] b) throws DataFormatException, IOException {
        int got = 0;
        while (got < b.length) {
            int n = inf.inflate(b, got, b.length - got);
            if (n == 0 && (inf.finished() || inf.needsInput())) {
                throw new IOException("pixel stream truncated");
            }
            got += n;
        }
    }

    private static void unfilter(int filter, byte[] row, byte[] prior, int bpp) throws IOException {
        for (int i = 0; i < row.length; i++) {
            int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
            int b = prior[i] & 0xFF;
            int c = i >= bpp ? prior[i - bpp] & 0xFF : 0;
            int add = switch (filter) {
                case 0 -> 0;
                case 1 -> a;
                case 2 -> b;
                case 3 -> a + b >> 1;
                case 4 -> paeth(a, b, c);
                default -> throw new IOException("bad PNG filter " + filter);
            };
            row[i] = (byte) (row[i] + add);
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static int sample(byte[] row, int index, int depth) {
        return switch (depth) {
            case 16 -> (row[index * 2] & 0xFF) << 8 | row[index * 2 + 1] & 0xFF;
            case 8 -> row[index] & 0xFF;
            default -> {
                int per = 8 / depth;
                int shift = 8 - depth * (index % per + 1);
                yield row[index / per] >> shift & (1 << depth) - 1;
            }
        };
    }

    private static int to8(int v, int depth) {
        return switch (depth) {
            case 16 -> v >> 8;
            case 8 -> v;
            default -> v * 255 / ((1 << depth) - 1);
        };
    }

    private static int pixel(byte[] row, int x, int depth, int colorType, byte[] plte, byte[] trns) throws IOException {
        switch (colorType) {
            case 0 -> {
                int v = sample(row, x, depth);
                int g = to8(v, depth);
                boolean clear = trns != null && trns.length >= 2 && v == ((trns[0] & 0xFF) << 8 | trns[1] & 0xFF);
                return (clear ? 0 : 0xFF) << 24 | g << 16 | g << 8 | g;
            }
            case 2 -> {
                int r = sample(row, x * 3, depth);
                int g = sample(row, x * 3 + 1, depth);
                int b = sample(row, x * 3 + 2, depth);
                boolean clear = trns != null && trns.length >= 6 && r == ((trns[0] & 0xFF) << 8 | trns[1] & 0xFF)
                        && g == ((trns[2] & 0xFF) << 8 | trns[3] & 0xFF) && b == ((trns[4] & 0xFF) << 8 | trns[5] & 0xFF);
                return (clear ? 0 : 0xFF) << 24 | to8(r, depth) << 16 | to8(g, depth) << 8 | to8(b, depth);
            }
            case 3 -> {
                int i = sample(row, x, depth);
                if (plte == null || i * 3 + 2 >= plte.length) {
                    throw new IOException("palette index out of range");
                }
                int a = trns != null && i < trns.length ? trns[i] & 0xFF : 255;
                return a << 24 | (plte[i * 3] & 0xFF) << 16 | (plte[i * 3 + 1] & 0xFF) << 8 | plte[i * 3 + 2] & 0xFF;
            }
            case 4 -> {
                int g = to8(sample(row, x * 2, depth), depth);
                return to8(sample(row, x * 2 + 1, depth), depth) << 24 | g << 16 | g << 8 | g;
            }
            default -> {
                int r = to8(sample(row, x * 4, depth), depth);
                int g = to8(sample(row, x * 4 + 1, depth), depth);
                int b = to8(sample(row, x * 4 + 2, depth), depth);
                return to8(sample(row, x * 4 + 3, depth), depth) << 24 | r << 16 | g << 8 | b;
            }
        }
    }

    private static long u32(byte[] d, int at) {
        return (d[at] & 0xFFL) << 24 | (d[at + 1] & 0xFFL) << 16 | (d[at + 2] & 0xFFL) << 8 | d[at + 3] & 0xFFL;
    }
}
