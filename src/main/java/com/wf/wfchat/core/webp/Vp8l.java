package com.wf.wfchat.core.webp;

/** VP8L (WebP lossless) decoder, RFC 9649. */
final class Vp8l {
    private static final int[] CL_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    private static final int[] ALPHABET = {256 + 24, 256, 256, 256, 40};
    private static final byte[] DIST_MAP = {
            0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2, 2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0, 1, 3, -1, 3,
            3, 1, -3, 1, 2, 3, -2, 3, 3, 2, -3, 2, 0, 4, 4, 0, 1, 4, -1, 4, 4, 1, -4, 1, 3, 3, -3, 3, 2, 4, -2, 4,
            4, 2, -4, 2, 0, 5, 3, 4, -3, 4, 4, 3, -4, 3, 5, 0, 1, 5, -1, 5, 5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2,
            4, 4, -4, 4, 3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0, 1, 6, -1, 6, 6, 1, -6, 1, 2, 6, -2, 6, 6, 2, -6, 2,
            4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6, 6, 3, -6, 3, 0, 7, 7, 0, 1, 7, -1, 7, 5, 5, -5, 5, 7, 1, -7, 1,
            4, 6, -4, 6, 6, 4, -6, 4, 2, 7, -2, 7, 7, 2, -7, 2, 3, 7, -3, 7, 7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5,
            8, 0, 4, 7, -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6, -6, 6, 8, 3, 5, 7, -5, 7, 7, 5, -7, 5, 8, 4, 6, 7,
            -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7};

    private final byte[] d;
    private int pos;
    private final int end;
    private long bits;
    private int nbits;

    private Vp8l(byte[] d, int off, int len) {
        this.d = d;
        this.pos = off;
        this.end = off + len;
    }

    /** Full VP8L chunk payload: signature, size, alpha hint, version, data. {@code out[0..1] = w,h}. */
    static int[] decode(byte[] d, int off, int len, int[] dims, int maxPx) throws Invalid {
        if (len < 5 || d[off] != 0x2F) {
            throw new Invalid("bad VP8L signature");
        }
        Vp8l r = new Vp8l(d, off + 1, len - 1);
        int w = r.read(14) + 1;
        int h = r.read(14) + 1;
        r.read(1);
        if (r.read(3) != 0) {
            throw new Invalid("bad VP8L version");
        }
        if (w > maxPx || h > maxPx) {
            throw new Invalid(w + "x" + h + " px above " + maxPx);
        }
        dims[0] = w;
        dims[1] = h;
        return r.image(w, h);
    }

    /** Header-less stream as used by ALPH; alpha lives in the green channel. */
    static int[] decodeHeaderless(byte[] d, int off, int len, int w, int h) throws Invalid {
        return new Vp8l(d, off, len).image(w, h);
    }

    private int read(int n) throws Invalid {
        while (nbits < n) {
            if (pos >= end) {
                throw new Invalid("VP8L data truncated");
            }
            bits |= (d[pos++] & 0xFFL) << nbits;
            nbits += 8;
        }
        int v = (int) (bits & ((1L << n) - 1));
        bits >>>= n;
        nbits -= n;
        return v;
    }

    private static int div(int a, int bits) {
        return (a + (1 << bits) - 1) >> bits;
    }

    private static final class Code {
        final int[] count = new int[16];
        int[] symbol;
        int single = -1;

        static Code build(int[] lengths, int n) throws Invalid {
            Code c = new Code();
            int nonzero = 0;
            int last = -1;
            for (int i = 0; i < n; i++) {
                if (lengths[i] > 0) {
                    nonzero++;
                    last = i;
                    c.count[lengths[i]]++;
                }
            }
            if (nonzero == 0) {
                throw new Invalid("empty prefix code");
            }
            if (nonzero == 1) {
                c.single = last;
                return c;
            }
            int left = 1;
            for (int len = 1; len < 16; len++) {
                left = (left << 1) - c.count[len];
                if (left < 0) {
                    throw new Invalid("over-subscribed prefix code");
                }
            }
            if (left != 0) {
                throw new Invalid("incomplete prefix code");
            }
            int[] offs = new int[16];
            for (int len = 1; len < 15; len++) {
                offs[len + 1] = offs[len] + c.count[len];
            }
            c.symbol = new int[nonzero];
            for (int i = 0; i < n; i++) {
                if (lengths[i] > 0) {
                    c.symbol[offs[lengths[i]]++] = i;
                }
            }
            return c;
        }

        int decode(Vp8l r) throws Invalid {
            if (single >= 0) {
                return single;
            }
            int code = 0;
            int first = 0;
            int index = 0;
            for (int len = 1; len < 16; len++) {
                code |= r.read(1);
                int cnt = count[len];
                if (code - cnt < first) {
                    return symbol[index + (code - first)];
                }
                index += cnt;
                first += cnt;
                first <<= 1;
                code <<= 1;
            }
            throw new Invalid("bad prefix code");
        }
    }

    private Code readCode(int alphabet) throws Invalid {
        int[] lengths = new int[Math.max(alphabet, 256)];
        if (read(1) == 1) {
            int num = read(1) + 1;
            int s0 = read(read(1) == 0 ? 1 : 8);
            if (s0 >= alphabet) {
                throw new Invalid("symbol outside alphabet");
            }
            lengths[s0] = 1;
            if (num == 2) {
                int s1 = read(8);
                if (s1 >= alphabet) {
                    throw new Invalid("symbol outside alphabet");
                }
                lengths[s1] = 1;
            }
            return Code.build(lengths, alphabet);
        }
        int[] cl = new int[19];
        int numCl = 4 + read(4);
        for (int i = 0; i < numCl; i++) {
            cl[CL_ORDER[i]] = read(3);
        }
        Code clCode = Code.build(cl, 19);
        int maxSymbol = alphabet;
        if (read(1) == 1) {
            int lenBits = 2 + 2 * read(3);
            maxSymbol = 2 + read(lenBits);
            if (maxSymbol > alphabet) {
                throw new Invalid("bad max symbol");
            }
        }
        int symbol = 0;
        int prev = 8;
        while (symbol < alphabet) {
            if (maxSymbol-- == 0) {
                break;
            }
            int c = clCode.decode(this);
            if (c < 16) {
                lengths[symbol++] = c;
                if (c != 0) {
                    prev = c;
                }
            } else {
                int rep;
                int val = 0;
                if (c == 16) {
                    rep = 3 + read(2);
                    val = prev;
                } else if (c == 17) {
                    rep = 3 + read(3);
                } else {
                    rep = 11 + read(7);
                }
                if (symbol + rep > alphabet) {
                    throw new Invalid("code length run past alphabet");
                }
                while (rep-- > 0) {
                    lengths[symbol++] = val;
                }
            }
        }
        return Code.build(lengths, alphabet);
    }

    private int[] image(int w, int h) throws Invalid {
        int[] type = new int[4];
        int[][] data = new int[4][];
        int[] bitsOf = new int[4];
        int[] widthBefore = new int[4];
        int count = 0;
        boolean[] used = new boolean[4];
        int xs = w;
        while (read(1) == 1) {
            int t = read(2);
            if (used[t]) {
                throw new Invalid("transform repeated");
            }
            used[t] = true;
            type[count] = t;
            widthBefore[count] = xs;
            switch (t) {
                case 0, 1 -> {
                    int b = read(3) + 2;
                    bitsOf[count] = b;
                    data[count] = entropyImage(div(xs, b), div(h, b));
                }
                case 3 -> {
                    int n = read(8) + 1;
                    int[] table = entropyImage(n, 1);
                    for (int i = 1; i < n; i++) {
                        table[i] = add(table[i], table[i - 1]);
                    }
                    int wb = n <= 2 ? 3 : n <= 4 ? 2 : n <= 16 ? 1 : 0;
                    int[] full = new int[1 << (8 >> wb)];
                    System.arraycopy(table, 0, full, 0, Math.min(n, full.length));
                    bitsOf[count] = wb;
                    data[count] = full;
                    xs = div(xs, wb);
                }
                default -> {
                }
            }
            count++;
        }
        int[] px = codedImage(xs, h, true);
        for (int i = count - 1; i >= 0; i--) {
            switch (type[i]) {
                case 0 -> predictor(px, widthBefore[i], h, bitsOf[i], data[i]);
                case 1 -> crossColor(px, widthBefore[i], h, bitsOf[i], data[i]);
                case 2 -> subtractGreen(px);
                default -> px = unindex(px, widthBefore[i], h, bitsOf[i], data[i]);
            }
        }
        return px;
    }

    private int[] entropyImage(int w, int h) throws Invalid {
        return codedImage(w, h, false);
    }

    private int[] codedImage(int w, int h, boolean level0) throws Invalid {
        int cacheBits = 0;
        if (read(1) == 1) {
            cacheBits = read(4);
            if (cacheBits < 1 || cacheBits > 11) {
                throw new Invalid("bad colour cache size");
            }
        }
        int metaBits = 0;
        int[] meta = null;
        int groups = 1;
        int mw = 0;
        if (level0 && read(1) == 1) {
            metaBits = read(3) + 2;
            mw = div(w, metaBits);
            int[] e = entropyImage(mw, div(h, metaBits));
            meta = new int[e.length];
            for (int i = 0; i < e.length; i++) {
                meta[i] = e[i] >> 8 & 0xFFFF;
                groups = Math.max(groups, meta[i] + 1);
            }
            if (groups > 4096) {
                throw new Invalid("too many prefix groups");
            }
        }
        int cacheSize = cacheBits == 0 ? 0 : 1 << cacheBits;
        Code[][] codes = new Code[groups][5];
        for (int g = 0; g < groups; g++) {
            for (int k = 0; k < 5; k++) {
                codes[g][k] = readCode(k == 0 ? ALPHABET[0] + cacheSize : ALPHABET[k]);
            }
        }
        int total = w * h;
        int[] out = new int[total];
        int[] cache = cacheBits == 0 ? null : new int[cacheSize];
        int mask = (1 << metaBits) - 1;
        int p = 0;
        int x = 0;
        int y = 0;
        Code[] g = codes[0];
        while (p < total) {
            if (meta != null) {
                g = codes[meta[(y >> metaBits) * mw + (x >> metaBits)]];
            }
            int s = g[0].decode(this);
            if (s < 256) {
                int red = g[1].decode(this);
                int blue = g[2].decode(this);
                int alpha = g[3].decode(this);
                int argb = alpha << 24 | red << 16 | s << 8 | blue;
                out[p++] = argb;
                if (cache != null) {
                    cache[hash(argb, cacheBits)] = argb;
                }
                if (++x >= w) {
                    x = 0;
                    y++;
                }
            } else if (s < 256 + 24) {
                int length = copyValue(s - 256);
                int dist = distance(copyValue(g[4].decode(this)), w);
                if (dist > p || p + length > total) {
                    throw new Invalid("backward reference out of range");
                }
                for (int i = 0; i < length; i++) {
                    int argb = out[p - dist];
                    out[p++] = argb;
                    if (cache != null) {
                        cache[hash(argb, cacheBits)] = argb;
                    }
                }
                x += length;
                while (x >= w) {
                    x -= w;
                    y++;
                }
            } else {
                int idx = s - 280;
                if (cache == null || idx >= cacheSize) {
                    throw new Invalid("bad colour cache index");
                }
                int argb = cache[idx];
                out[p++] = argb;
                cache[hash(argb, cacheBits)] = argb;
                if (++x >= w) {
                    x = 0;
                    y++;
                }
            }
        }
        return out;
    }

    private static int hash(int argb, int bits) {
        return (0x1e35a7bd * argb) >>> (32 - bits);
    }

    private int copyValue(int prefix) throws Invalid {
        if (prefix < 4) {
            return prefix + 1;
        }
        int extra = (prefix - 2) >> 1;
        int offset = (2 + (prefix & 1)) << extra;
        return offset + read(extra) + 1;
    }

    private static int distance(int code, int width) {
        if (code > 120) {
            return code - 120;
        }
        int dx = DIST_MAP[2 * (code - 1)];
        int dy = DIST_MAP[2 * (code - 1) + 1];
        return Math.max(1, dx + dy * width);
    }

    // -------------------------------------------------------------- transforms

    static int add(int a, int b) {
        int ag = (a & 0xFF00FF00) + (b & 0xFF00FF00);
        int rb = (a & 0x00FF00FF) + (b & 0x00FF00FF);
        return ag & 0xFF00FF00 | rb & 0x00FF00FF;
    }

    private static int avg2(int a, int b) {
        return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
    }

    private static int clip(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static int select(int a, int b, int c) {
        int s = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int ca = a >>> sh & 0xFF;
            int cb = b >>> sh & 0xFF;
            int cc = c >>> sh & 0xFF;
            s += Math.abs(cb - cc) - Math.abs(ca - cc);
        }
        return s <= 0 ? a : b;
    }

    private static int addSubFull(int a, int b, int c) {
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            r |= clip((a >>> sh & 0xFF) + (b >>> sh & 0xFF) - (c >>> sh & 0xFF)) << sh;
        }
        return r;
    }

    private static int addSubHalf(int a, int b) {
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int ca = a >>> sh & 0xFF;
            r |= clip(ca + (ca - (b >>> sh & 0xFF)) / 2) << sh;
        }
        return r;
    }

    private static int predict(int mode, int[] px, int i, int w) {
        int l = px[i - 1];
        int t = px[i - w];
        int tl = px[i - w - 1];
        int tr = px[i - w + 1];
        return switch (mode) {
            case 1 -> l;
            case 2 -> t;
            case 3 -> tr;
            case 4 -> tl;
            case 5 -> avg2(avg2(l, tr), t);
            case 6 -> avg2(l, tl);
            case 7 -> avg2(l, t);
            case 8 -> avg2(tl, t);
            case 9 -> avg2(t, tr);
            case 10 -> avg2(avg2(l, tl), avg2(t, tr));
            case 11 -> select(t, l, tl);
            case 12 -> addSubFull(l, t, tl);
            case 13 -> addSubHalf(avg2(l, t), tl);
            default -> 0xFF000000;
        };
    }

    private static void predictor(int[] px, int w, int h, int bits, int[] data) {
        int bw = div(w, bits);
        px[0] = add(px[0], 0xFF000000);
        for (int x = 1; x < w; x++) {
            px[x] = add(px[x], px[x - 1]);
        }
        for (int y = 1; y < h; y++) {
            int row = y * w;
            px[row] = add(px[row], px[row - w]);
            for (int x = 1; x < w; x++) {
                int mode = data[(y >> bits) * bw + (x >> bits)] >> 8 & 0xF;
                px[row + x] = add(px[row + x], predict(mode, px, row + x, w));
            }
        }
    }

    private static int delta(int t, int c) {
        return (byte) t * (byte) c >> 5;
    }

    private static void crossColor(int[] px, int w, int h, int bits, int[] data) {
        int bw = div(w, bits);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int m = data[(y >> bits) * bw + (x >> bits)];
                int g2r = m & 0xFF;
                int g2b = m >> 8 & 0xFF;
                int r2b = m >> 16 & 0xFF;
                int argb = px[y * w + x];
                int green = argb >> 8 & 0xFF;
                int red = (argb >> 16 & 0xFF) + delta(g2r, green) & 0xFF;
                int blue = (argb & 0xFF) + delta(g2b, green) + delta(r2b, red) & 0xFF;
                px[y * w + x] = argb & 0xFF00FF00 | red << 16 | blue;
            }
        }
    }

    private static void subtractGreen(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int argb = px[i];
            int green = argb >> 8 & 0xFF;
            int red = (argb >> 16 & 0xFF) + green & 0xFF;
            int blue = (argb & 0xFF) + green & 0xFF;
            px[i] = argb & 0xFF00FF00 | red << 16 | blue;
        }
    }

    private static int[] unindex(int[] packed, int w, int h, int wb, int[] table) {
        int pw = div(w, wb);
        int[] out = new int[w * h];
        int per = 1 << wb;
        int bitsPer = 8 >> wb;
        int mask = (1 << bitsPer) - 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int g = packed[y * pw + (x >> wb)] >> 8 & 0xFF;
                int idx = wb == 0 ? g : g >> (bitsPer * (x & (per - 1))) & mask;
                out[y * w + x] = idx < table.length ? table[idx] : 0;
            }
        }
        return out;
    }
}
