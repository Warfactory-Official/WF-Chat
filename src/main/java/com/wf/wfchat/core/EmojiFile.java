package com.wf.wfchat.core;

import com.wf.wfchat.core.webp.Invalid;
import com.wf.wfchat.core.webp.WebP;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Structural verifier/sanitiser for emoji files, shared by server (load) and client (receive); pure Java, no decoding
 * into pixels. Output is a canonical re-serialisation: PNG keeps IHDR/PLTE/tRNS/IDAT/IEND, GIF keeps frames, graphic
 * control and the loop extension; all other metadata is dropped and counted in {@link Verified#strippedBytes()}.
 * WebP is verified by a full decode (lossy, lossless, alpha, animated) and rewritten without ICC/EXIF/XMP/unknown chunks.
 * Anything malformed, oversized, trailing-garbage or misnamed => {@link Rejected}.
 */
public final class EmojiFile {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{2,32}");
    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private EmojiFile() {
    }

    public enum Kind {
        PNG("png"), GIF("gif"), WEBP("webp");

        public final String extension;

        Kind(String extension) {
            this.extension = extension;
        }
    }

    public record Limits(int maxBytes, int minPx, int maxPx, int maxFrames, boolean square) {
        public static final Limits DEFAULT = new Limits(131072, 8, 128, 128, true);
    }

    public record Verified(String name, Kind kind, int width, int height, int frames, byte[] data, String sha256,
                           int strippedBytes) {
    }

    public static final class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    /** {@code fileName} supplies name and claimed type: {@code pepe.png}. */
    public static Verified verify(String fileName, byte[] raw, Limits limits) throws Rejected {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            throw new Rejected("no file extension");
        }
        String name = fileName.substring(0, dot).toLowerCase(Locale.ROOT);
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!validName(name)) {
            throw new Rejected("name must match [a-z0-9_]{2,32}");
        }
        Kind claimed = switch (ext) {
            case "png" -> Kind.PNG;
            case "gif" -> Kind.GIF;
            case "webp" -> Kind.WEBP;
            default -> throw new Rejected("extension ." + ext + " not allowed (png, gif, webp)");
        };
        return verify(name, claimed, raw, limits);
    }

    /** Type taken from the content; for sources with no file name. */
    public static Verified verifyContent(String name, byte[] raw, Limits limits) throws Rejected {
        Kind kind = raw.length == 0 ? null : sniff(raw);
        if (kind == null) {
            throw new Rejected(raw.length == 0 ? "empty file" : "content is not PNG, GIF or WebP");
        }
        return verify(name, kind, raw, limits);
    }

    public static Verified verify(String name, Kind claimed, byte[] raw, Limits limits) throws Rejected {
        if (raw.length == 0) {
            throw new Rejected("empty file");
        }
        if (raw.length > limits.maxBytes) {
            throw new Rejected("file is " + raw.length + " bytes, limit " + limits.maxBytes);
        }
        Kind actual = sniff(raw);
        if (actual == null) {
            throw new Rejected("content is not PNG, GIF or WebP");
        }
        if (actual != claimed) {
            throw new Rejected("content is " + actual + " but file claims " + claimed);
        }
        Parsed p = switch (actual) {
            case PNG -> png(raw, limits);
            case GIF -> gif(raw, limits);
            case WEBP -> webp(raw, limits);
        };
        if (limits.square && p.width != p.height) {
            throw new Rejected("must be square, is " + p.width + "x" + p.height);
        }
        byte[] out = p.out.toByteArray();
        return new Verified(name, actual, p.width, p.height, p.frames, out, sha256(out), raw.length - out.length);
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Kind sniff(byte[] d) {
        if (d.length >= 8) {
            boolean png = true;
            for (int i = 0; i < 8; i++) {
                png &= d[i] == PNG_SIG[i];
            }
            if (png) {
                return Kind.PNG;
            }
        }
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8'
                && (d[4] == '7' || d[4] == '9') && d[5] == 'a') {
            return Kind.GIF;
        }
        if (WebP.sniff(d)) {
            return Kind.WEBP;
        }
        return null;
    }

    private record Parsed(ByteArrayOutputStream out, int width, int height, int frames) {
    }

    private static void dims(int w, int h, Limits l) throws Rejected {
        if (w < l.minPx || h < l.minPx || w > l.maxPx || h > l.maxPx) {
            throw new Rejected(w + "x" + h + " px outside " + l.minPx + ".." + l.maxPx);
        }
    }

    // ----------------------------------------------------------------- WebP

    private static Parsed webp(byte[] d, Limits limits) throws Rejected {
        try {
            WebP.Result r = WebP.read(d, limits.maxPx, limits.maxFrames);
            dims(r.width(), r.height(), limits);
            ByteArrayOutputStream out = new ByteArrayOutputStream(r.canonical().length);
            out.writeBytes(r.canonical());
            return new Parsed(out, r.width(), r.height(), r.frames());
        } catch (Invalid ex) {
            throw new Rejected(ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ PNG

    private static Parsed png(byte[] d, Limits limits) throws Rejected {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(PNG_SIG, 0, 8);
        int pos = 8;
        int width = 0;
        int height = 0;
        int colorType = -1;
        int depth = 0;
        int interlace = 0;
        boolean sawIhdr = false;
        boolean sawPlte = false;
        boolean sawIdat = false;
        boolean idatClosed = false;
        boolean sawIend = false;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int paletteEntries = 0;
        boolean animated = false;
        int numFrames = 0;
        int fcCount = 0;
        long seq = 0;
        boolean fdatFrame = false;
        int fw = 0;
        int fh = 0;
        ByteArrayOutputStream fdat = new ByteArrayOutputStream();
        while (pos < d.length) {
            if (sawIend) {
                throw new Rejected("data after IEND");
            }
            if (pos + 12 > d.length) {
                throw new Rejected("truncated chunk header");
            }
            long len = u32be(d, pos);
            if (len > d.length - pos - 12L) {
                throw new Rejected("chunk length past end of file");
            }
            String type = new String(d, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            for (int i = 0; i < 4; i++) {
                if (!Character.isLetter(type.charAt(i)) || type.charAt(i) > 'z') {
                    throw new Rejected("invalid chunk type");
                }
            }
            int dataStart = pos + 8;
            int n = (int) len;
            CRC32 crc = new CRC32();
            crc.update(d, pos + 4, 4 + n);
            if (crc.getValue() != u32be(d, dataStart + n)) {
                throw new Rejected("CRC mismatch in " + type);
            }
            if (!sawIhdr && !type.equals("IHDR")) {
                throw new Rejected("first chunk is " + type + ", not IHDR");
            }
            boolean keep = true;
            switch (type) {
                case "IHDR" -> {
                    if (sawIhdr || n != 13) {
                        throw new Rejected("bad IHDR");
                    }
                    sawIhdr = true;
                    long w = u32be(d, dataStart);
                    long h = u32be(d, dataStart + 4);
                    if (w > limits.maxPx || h > limits.maxPx) {
                        throw new Rejected(w + "x" + h + " px outside " + limits.minPx + ".." + limits.maxPx);
                    }
                    width = (int) w;
                    height = (int) h;
                    dims(width, height, limits);
                    depth = d[dataStart + 8] & 0xFF;
                    colorType = d[dataStart + 9] & 0xFF;
                    interlace = d[dataStart + 12] & 0xFF;
                    if (!validDepth(colorType, depth) || d[dataStart + 10] != 0 || d[dataStart + 11] != 0
                            || interlace > 1) {
                        throw new Rejected("unsupported IHDR (colour type " + colorType + ", depth " + depth + ")");
                    }
                }
                case "PLTE" -> {
                    if (sawPlte || sawIdat || n == 0 || n % 3 != 0 || n / 3 > 256 || n / 3 > (1 << depth)
                            || colorType == 0 || colorType == 4) {
                        throw new Rejected("bad PLTE");
                    }
                    sawPlte = true;
                    paletteEntries = n / 3;
                }
                case "tRNS" -> {
                    if (sawIdat || colorType == 4 || colorType == 6) {
                        throw new Rejected("bad tRNS");
                    }
                    int expect = colorType == 0 ? 2 : colorType == 2 ? 6 : -1;
                    if (colorType == 3 ? n > paletteEntries || !sawPlte : n != expect) {
                        throw new Rejected("bad tRNS length");
                    }
                }
                case "IDAT" -> {
                    if (idatClosed) {
                        throw new Rejected("IDAT chunks not contiguous");
                    }
                    if (colorType == 3 && !sawPlte) {
                        throw new Rejected("palette image without PLTE");
                    }
                    sawIdat = true;
                    idat.write(d, dataStart, n);
                    if (idat.size() > limits.maxBytes) {
                        throw new Rejected("pixel data exceeds size limit");
                    }
                }
                case "IEND" -> {
                    if (n != 0 || !sawIdat) {
                        throw new Rejected("bad IEND");
                    }
                    sawIend = true;
                }
                case "acTL" -> {
                    if (animated || sawIdat || n != 8) {
                        throw new Rejected("bad acTL");
                    }
                    long frames = u32be(d, dataStart);
                    if (frames < 1 || frames > limits.maxFrames) {
                        throw new Rejected("animated PNG frame count " + frames + " outside 1.." + limits.maxFrames);
                    }
                    animated = true;
                    numFrames = (int) frames;
                }
                case "fcTL" -> {
                    if (!animated || n != 26 || u32be(d, dataStart) != seq++) {
                        throw new Rejected("bad fcTL");
                    }
                    if (fdatFrame) {
                        checkInflate(fdat.toByteArray(), rawSize(fw, fh, colorType, depth, interlace));
                        fdat.reset();
                    }
                    long w = u32be(d, dataStart + 4);
                    long h = u32be(d, dataStart + 8);
                    long x = u32be(d, dataStart + 12);
                    long y = u32be(d, dataStart + 16);
                    if (++fcCount > numFrames || w < 1 || h < 1 || x + w > width || y + h > height
                            || (d[dataStart + 24] & 0xFF) > 2 || (d[dataStart + 25] & 0xFF) > 1
                            || !sawIdat && (fcCount > 1 || w != width || h != height || x != 0 || y != 0)) {
                        throw new Rejected("bad frame " + (fcCount - 1) + " geometry");
                    }
                    fw = (int) w;
                    fh = (int) h;
                    fdatFrame = sawIdat;
                }
                case "fdAT" -> {
                    if (!fdatFrame || n < 4 || u32be(d, dataStart) != seq++) {
                        throw new Rejected("bad fdAT");
                    }
                    fdat.write(d, dataStart + 4, n - 4);
                    if (fdat.size() > limits.maxBytes) {
                        throw new Rejected("pixel data exceeds size limit");
                    }
                }
                default -> {
                    if (Character.isUpperCase(type.charAt(0))) {
                        throw new Rejected("unknown critical chunk " + type);
                    }
                    keep = false;
                }
            }
            if (sawIdat && !type.equals("IDAT")) {
                idatClosed = true;
            }
            if (keep) {
                out.write(d, pos, 12 + n);
            }
            pos += 12 + n;
        }
        if (!sawIend) {
            throw new Rejected("missing IEND");
        }
        long expected = rawSize(width, height, colorType, depth, interlace);
        checkInflate(idat.toByteArray(), expected);
        if (fdatFrame) {
            checkInflate(fdat.toByteArray(), rawSize(fw, fh, colorType, depth, interlace));
        }
        if (animated && fcCount != numFrames) {
            throw new Rejected("acTL declares " + numFrames + " frames, found " + fcCount);
        }
        return new Parsed(out, width, height, animated ? numFrames : 1);
    }

    private static boolean validDepth(int colorType, int depth) {
        return switch (colorType) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            default -> false;
        };
    }

    private static int channels(int colorType) {
        return switch (colorType) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            default -> 4;
        };
    }

    private static long rawSize(int w, int h, int colorType, int depth, int interlace) {
        int bits = channels(colorType) * depth;
        if (interlace == 0) {
            return (long) h * (1 + ((long) w * bits + 7) / 8);
        }
        int[] xs = {0, 4, 0, 2, 0, 1, 0};
        int[] ys = {0, 0, 4, 0, 2, 0, 1};
        int[] dx = {8, 8, 4, 4, 2, 2, 1};
        int[] dy = {8, 8, 8, 4, 4, 2, 2};
        long total = 0;
        for (int p = 0; p < 7; p++) {
            int pw = (w - xs[p] + dx[p] - 1) / dx[p];
            int ph = (h - ys[p] + dy[p] - 1) / dy[p];
            if (pw > 0 && ph > 0) {
                total += (long) ph * (1 + ((long) pw * bits + 7) / 8);
            }
        }
        return total;
    }

    private static void checkInflate(byte[] zlib, long expected) throws Rejected {
        Inflater inf = new Inflater();
        try {
            inf.setInput(zlib);
            byte[] buf = new byte[8192];
            long total = 0;
            while (!inf.finished()) {
                int got = inf.inflate(buf);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    throw new Rejected("pixel stream truncated");
                }
                total += got;
                if (total > expected) {
                    throw new Rejected("pixel stream larger than declared size");
                }
            }
            if (total != expected) {
                throw new Rejected("pixel stream is " + total + " bytes, declared size needs " + expected);
            }
            if (inf.getRemaining() != 0) {
                throw new Rejected("trailing bytes after pixel stream");
            }
        } catch (DataFormatException ex) {
            throw new Rejected("corrupt pixel stream");
        } finally {
            inf.end();
        }
    }

    private static long u32be(byte[] d, int at) {
        return (d[at] & 0xFFL) << 24 | (d[at + 1] & 0xFFL) << 16 | (d[at + 2] & 0xFFL) << 8 | (d[at + 3] & 0xFFL);
    }

    // ------------------------------------------------------------------ GIF

    private static Parsed gif(byte[] d, Limits limits) throws Rejected {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        if (d.length < 13) {
            throw new Rejected("truncated GIF header");
        }
        int w = u16le(d, 6);
        int h = u16le(d, 8);
        dims(w, h, limits);
        int flags = d[10] & 0xFF;
        int pos = 13;
        boolean globalTable = (flags & 0x80) != 0;
        if (globalTable) {
            pos += 3 << ((flags & 7) + 1);
            if (pos > d.length) {
                throw new Rejected("truncated global colour table");
            }
        }
        out.write('G');
        out.write('I');
        out.write('F');
        out.write('8');
        out.write('9');
        out.write('a');
        out.write(d, 6, pos - 6);
        int frames = 0;
        boolean sawTrailer = false;
        while (pos < d.length && !sawTrailer) {
            int block = d[pos] & 0xFF;
            switch (block) {
                case 0x3B -> {
                    sawTrailer = true;
                    pos++;
                }
                case 0x21 -> {
                    if (pos + 2 > d.length) {
                        throw new Rejected("truncated extension");
                    }
                    int label = d[pos + 1] & 0xFF;
                    int start = pos;
                    pos = skipSubBlocks(d, pos + 2);
                    boolean keep = false;
                    if (label == 0xF9) {
                        if ((d[start + 2] & 0xFF) != 4 || pos - start != 8) {
                            throw new Rejected("bad graphic control extension");
                        }
                        keep = true;
                    } else if (label == 0xFF) {
                        int idLen = d[start + 2] & 0xFF;
                        String id = idLen == 11 && start + 14 <= d.length
                                ? new String(d, start + 3, 11, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
                        keep = id.equals("NETSCAPE2.0") || id.equals("ANIMEXTS1.0");
                    } else if (label != 0xFE && label != 0x01) {
                        throw new Rejected("unknown extension 0x" + Integer.toHexString(label));
                    }
                    if (keep) {
                        out.write(d, start, pos - start);
                    }
                }
                case 0x2C -> {
                    if (pos + 10 > d.length) {
                        throw new Rejected("truncated image descriptor");
                    }
                    int fl = u16le(d, pos + 1);
                    int ft = u16le(d, pos + 3);
                    int fw = u16le(d, pos + 5);
                    int fh = u16le(d, pos + 7);
                    if (fw == 0 || fh == 0 || fl + fw > w || ft + fh > h) {
                        throw new Rejected("frame " + frames + " outside the logical screen");
                    }
                    int ff = d[pos + 9] & 0xFF;
                    int p = pos + 10;
                    if ((ff & 0x80) != 0) {
                        p += 3 << ((ff & 7) + 1);
                    } else if (!globalTable) {
                        throw new Rejected("frame " + frames + " has no colour table");
                    }
                    if (p >= d.length) {
                        throw new Rejected("truncated frame");
                    }
                    int minCode = d[p] & 0xFF;
                    if (minCode < 2 || minCode > 8) {
                        throw new Rejected("bad LZW code size");
                    }
                    int end = skipSubBlocks(d, p + 1);
                    if (++frames > limits.maxFrames) {
                        throw new Rejected("more than " + limits.maxFrames + " frames");
                    }
                    out.write(d, pos, end - pos);
                    pos = end;
                }
                default -> throw new Rejected("unexpected block 0x" + Integer.toHexString(block));
            }
        }
        if (!sawTrailer) {
            throw new Rejected("missing GIF trailer");
        }
        if (pos != d.length) {
            throw new Rejected("data after GIF trailer");
        }
        if (frames == 0) {
            throw new Rejected("no frames");
        }
        out.write(0x3B);
        return new Parsed(out, w, h, frames);
    }

    private static int skipSubBlocks(byte[] d, int pos) throws Rejected {
        while (true) {
            if (pos >= d.length) {
                throw new Rejected("unterminated data sub-blocks");
            }
            int len = d[pos++] & 0xFF;
            if (len == 0) {
                return pos;
            }
            pos += len;
        }
    }

    private static int u16le(byte[] d, int at) {
        return d[at] & 0xFF | (d[at + 1] & 0xFF) << 8;
    }
}
