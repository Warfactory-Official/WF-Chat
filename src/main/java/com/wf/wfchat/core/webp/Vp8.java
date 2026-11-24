package com.wf.wfchat.core.webp;

/** VP8 key-frame decoder (RFC 6386) producing opaque ARGB, matching libwebp's output including fancy chroma upsampling. */
final class Vp8 {
    private static final int B_DC = 0;
    private static final int B_TM = 1;
    private static final int B_VE = 2;
    private static final int B_HE = 3;
    private static final int B_RD = 4;
    private static final int B_VR = 5;
    private static final int B_LD = 6;
    private static final int B_VL = 7;
    private static final int B_HD = 8;
    private static final int B_HU = 9;
    private static final int B_PRED = 10;
    private static final int[] I4_TREE = {-B_DC, 1, -B_TM, 2, -B_VE, 3, 4, 6, -B_HE, 5, -B_RD, -B_VR, -B_LD, 7, -B_VL, 8,
            -B_HD, -B_HU};
    private static final int[] ZIGZAG = {0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15};
    private static final int[] BANDS = {0, 1, 2, 3, 6, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 7, 0};
    private static final int[][] CAT = {{173, 148, 140}, {176, 155, 140, 135}, {180, 157, 141, 134, 130},
            {254, 254, 243, 230, 196, 177, 153, 140, 133, 130, 129}};

    /** Decoded frame: dimensions and opaque ARGB. */
    record Frame(int width, int height, int[] argb) {
    }

    private static final class Bool {
        private final byte[] d;
        private int pos;
        private final int end;
        private int value;
        private int range = 255;
        private int bitCount;
        private int overrun;

        Bool(byte[] d, int off, int len) throws Invalid {
            this.d = d;
            this.pos = off;
            this.end = off + len;
            value = next() << 8 | next();
        }

        private int next() throws Invalid {
            if (pos < end) {
                return d[pos++] & 0xFF;
            }
            if (++overrun > 16) {
                throw new Invalid("VP8 partition truncated");
            }
            return 0;
        }

        int bit(int prob) throws Invalid {
            int split = 1 + ((range - 1) * prob >> 8);
            int big = split << 8;
            int ret;
            if (value >= big) {
                ret = 1;
                range -= split;
                value -= big;
            } else {
                ret = 0;
                range = split;
            }
            while (range < 128) {
                value <<= 1;
                range <<= 1;
                if (++bitCount == 8) {
                    bitCount = 0;
                    value |= next();
                }
            }
            return ret;
        }

        int lit(int n) throws Invalid {
            int v = 0;
            while (n-- > 0) {
                v = v << 1 | bit(128);
            }
            return v;
        }

        int signed(int n) throws Invalid {
            int v = lit(n);
            return lit(1) == 1 ? -v : v;
        }
    }

    private int mbw;
    private int mbh;
    private int width;
    private int height;
    private final byte[] probs = Vp8Tables.COEFF_PROBA.clone();
    private boolean simple;
    private int fLevel;
    private int sharpness;
    private boolean lfDelta;
    private final int[] refDelta = new int[4];
    private final int[] modeDelta = new int[4];
    private boolean segOn;
    private boolean segMap;
    private boolean segAbs;
    private final int[] segQuant = new int[4];
    private final int[] segFilter = new int[4];
    private final int[] segProb = {255, 255, 255};
    private boolean useSkip;
    private int skipProb;
    private final int[][] y1 = new int[4][2];
    private final int[][] y2 = new int[4][2];
    private final int[][] uv = new int[4][2];

    private int ystride;
    private int uvstride;
    private byte[] yp;
    private byte[] up;
    private byte[] vp;

    private int[] mbSegment;
    private boolean[] mbI4;
    private boolean[] mbInner;

    static Frame decode(byte[] d, int off, int len, int maxPx) throws Invalid {
        return new Vp8().run(d, off, len, maxPx);
    }

    private Frame run(byte[] d, int off, int len, int maxPx) throws Invalid {
        if (len < 10) {
            throw new Invalid("VP8 frame too short");
        }
        int tag = d[off] & 0xFF | (d[off + 1] & 0xFF) << 8 | (d[off + 2] & 0xFF) << 16;
        if ((tag & 1) != 0) {
            throw new Invalid("VP8 inter frame");
        }
        int firstSize = tag >> 5;
        if ((d[off + 3] & 0xFF) != 0x9D || (d[off + 4] & 0xFF) != 0x01 || (d[off + 5] & 0xFF) != 0x2A) {
            throw new Invalid("bad VP8 start code");
        }
        width = (d[off + 6] & 0xFF | (d[off + 7] & 0xFF) << 8) & 0x3FFF;
        height = (d[off + 8] & 0xFF | (d[off + 9] & 0xFF) << 8) & 0x3FFF;
        if (width == 0 || height == 0 || width > maxPx || height > maxPx) {
            throw new Invalid("bad VP8 size");
        }
        int partStart = off + 10;
        if (firstSize > len - 10) {
            throw new Invalid("VP8 first partition truncated");
        }
        Bool br = new Bool(d, partStart, firstSize);
        br.lit(1);
        br.lit(1);
        header(br);
        int numParts = 1 << br.lit(2);
        quant(br);
        br.lit(1);
        for (int t = 0; t < 4; t++) {
            for (int b = 0; b < 8; b++) {
                for (int c = 0; c < 3; c++) {
                    for (int p = 0; p < 11; p++) {
                        int i = ((t * 8 + b) * 3 + c) * 11 + p;
                        if (br.bit(Vp8Tables.COEFF_UPDATE[i] & 0xFF) == 1) {
                            probs[i] = (byte) br.lit(8);
                        }
                    }
                }
            }
        }
        useSkip = br.lit(1) == 1;
        if (useSkip) {
            skipProb = br.lit(8);
        }
        int sizes = partStart + firstSize;
        int dataStart = sizes + 3 * (numParts - 1);
        int endAll = off + len;
        if (dataStart > endAll) {
            throw new Invalid("VP8 partition table truncated");
        }
        Bool[] parts = new Bool[numParts];
        int at = dataStart;
        for (int i = 0; i < numParts; i++) {
            int size;
            if (i < numParts - 1) {
                int s = sizes + 3 * i;
                size = d[s] & 0xFF | (d[s + 1] & 0xFF) << 8 | (d[s + 2] & 0xFF) << 16;
            } else {
                size = endAll - at;
            }
            size = Math.max(0, Math.min(size, endAll - at));
            parts[i] = new Bool(d, at, size);
            at += size;
        }
        mbw = (width + 15) >> 4;
        mbh = (height + 15) >> 4;
        ystride = mbw * 16 + 1 + 4;
        uvstride = mbw * 8 + 1;
        yp = plane(ystride, mbh * 16);
        up = plane(uvstride, mbh * 8);
        vp = plane(uvstride, mbh * 8);
        mbSegment = new int[mbw * mbh];
        mbI4 = new boolean[mbw * mbh];
        mbInner = new boolean[mbw * mbh];
        macroblocks(br, parts);
        loopFilter();
        return new Frame(width, height, rgb());
    }

    private static byte[] plane(int stride, int rows) {
        byte[] p = new byte[stride * (rows + 1)];
        java.util.Arrays.fill(p, 0, stride, (byte) 127);
        for (int y = 0; y < rows; y++) {
            p[(y + 1) * stride] = (byte) 129;
        }
        return p;
    }

    private void header(Bool br) throws Invalid {
        segOn = br.lit(1) == 1;
        if (segOn) {
            segMap = br.lit(1) == 1;
            boolean update = br.lit(1) == 1;
            if (update) {
                segAbs = br.lit(1) == 1;
                for (int i = 0; i < 4; i++) {
                    segQuant[i] = br.lit(1) == 1 ? br.signed(7) : 0;
                }
                for (int i = 0; i < 4; i++) {
                    segFilter[i] = br.lit(1) == 1 ? br.signed(6) : 0;
                }
            }
            if (segMap) {
                for (int i = 0; i < 3; i++) {
                    segProb[i] = br.lit(1) == 1 ? br.lit(8) : 255;
                }
            }
        }
        simple = br.lit(1) == 1;
        fLevel = br.lit(6);
        sharpness = br.lit(3);
        lfDelta = br.lit(1) == 1;
        if (lfDelta && br.lit(1) == 1) {
            for (int i = 0; i < 4; i++) {
                if (br.lit(1) == 1) {
                    refDelta[i] = br.signed(6);
                }
            }
            for (int i = 0; i < 4; i++) {
                if (br.lit(1) == 1) {
                    modeDelta[i] = br.signed(6);
                }
            }
        }
    }

    private void quant(Bool br) throws Invalid {
        int base = br.lit(7);
        int dqy1dc = br.lit(1) == 1 ? br.signed(4) : 0;
        int dqy2dc = br.lit(1) == 1 ? br.signed(4) : 0;
        int dqy2ac = br.lit(1) == 1 ? br.signed(4) : 0;
        int dquvdc = br.lit(1) == 1 ? br.signed(4) : 0;
        int dquvac = br.lit(1) == 1 ? br.signed(4) : 0;
        for (int s = 0; s < 4; s++) {
            int q = base;
            if (segOn) {
                q = segQuant[s] + (segAbs ? 0 : base);
            }
            y1[s][0] = Vp8Tables.DC_Q[clip(q + dqy1dc, 127)];
            y1[s][1] = Vp8Tables.AC_Q[clip(q, 127)];
            y2[s][0] = Vp8Tables.DC_Q[clip(q + dqy2dc, 127)] * 2;
            y2[s][1] = Math.max(8, Vp8Tables.AC_Q[clip(q + dqy2ac, 127)] * 101581 >> 16);
            uv[s][0] = Vp8Tables.DC_Q[clip(q + dquvdc, 117)];
            uv[s][1] = Vp8Tables.AC_Q[clip(q + dquvac, 127)];
        }
    }

    private static int clip(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }

    private static int clip8(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    // ------------------------------------------------------------ macroblocks

    private final short[] coeffs = new short[384];
    private int[] topModes;
    private final int[] leftModes = new int[4];
    private int[] topY;
    private int[] topU;
    private int[] topV;
    private int[] topDc;
    private final int[] leftY = new int[4];
    private final int[] leftU = new int[2];
    private final int[] leftV = new int[2];
    private int leftDc;

    private void macroblocks(Bool br, Bool[] parts) throws Invalid {
        topModes = new int[mbw * 4];
        topY = new int[mbw * 4];
        topU = new int[mbw * 2];
        topV = new int[mbw * 2];
        topDc = new int[mbw];
        int[] imodes = new int[16];
        for (int my = 0; my < mbh; my++) {
            Bool tp = parts[my & parts.length - 1];
            java.util.Arrays.fill(leftModes, B_DC);
            java.util.Arrays.fill(leftY, 0);
            java.util.Arrays.fill(leftU, 0);
            java.util.Arrays.fill(leftV, 0);
            leftDc = 0;
            for (int mx = 0; mx < mbw; mx++) {
                int idx = my * mbw + mx;
                int segment = 0;
                if (segMap) {
                    segment = br.bit(segProb[0]) == 0 ? br.bit(segProb[1]) : br.bit(segProb[2]) + 2;
                }
                boolean skip = useSkip && br.bit(skipProb) == 1;
                boolean i4 = br.bit(145) == 0;
                int ymode = 0;
                if (!i4) {
                    ymode = br.bit(156) == 1 ? br.bit(128) == 1 ? B_TM : B_HE : br.bit(163) == 1 ? B_VE : B_DC;
                    java.util.Arrays.fill(topModes, mx * 4, mx * 4 + 4, ymode);
                    java.util.Arrays.fill(leftModes, ymode);
                } else {
                    for (int y = 0; y < 4; y++) {
                        int m = leftModes[y];
                        for (int x = 0; x < 4; x++) {
                            int p = (topModes[mx * 4 + x] * 10 + m) * 9;
                            int i = I4_TREE[br.bit(Vp8Tables.BMODE_PROBA[p] & 0xFF)];
                            while (i > 0) {
                                i = I4_TREE[2 * i + br.bit(Vp8Tables.BMODE_PROBA[p + i] & 0xFF)];
                            }
                            m = -i;
                            topModes[mx * 4 + x] = m;
                            imodes[y * 4 + x] = m;
                        }
                        leftModes[y] = m;
                    }
                }
                int uvmode = br.bit(142) == 0 ? B_DC : br.bit(114) == 0 ? B_VE : br.bit(183) == 1 ? B_TM : B_HE;
                mbSegment[idx] = segment;
                mbI4[idx] = i4;
                boolean coded = !skip && residuals(tp, mx, segment, i4);
                if (skip) {
                    java.util.Arrays.fill(topY, mx * 4, mx * 4 + 4, 0);
                    java.util.Arrays.fill(topU, mx * 2, mx * 2 + 2, 0);
                    java.util.Arrays.fill(topV, mx * 2, mx * 2 + 2, 0);
                    java.util.Arrays.fill(leftY, 0);
                    java.util.Arrays.fill(leftU, 0);
                    java.util.Arrays.fill(leftV, 0);
                    if (!i4) {
                        topDc[mx] = 0;
                        leftDc = 0;
                    }
                    java.util.Arrays.fill(coeffs, (short) 0);
                }
                mbInner[idx] = coded || i4;
                reconstruct(mx, my, i4, ymode, imodes, uvmode, coded);
            }
        }
    }

    /** Returns whether any coefficient is non-zero. */
    private boolean residuals(Bool br, int mx, int segment, boolean i4) throws Invalid {
        java.util.Arrays.fill(coeffs, (short) 0);
        boolean any = false;
        int first = 0;
        int type = 3;
        if (!i4) {
            int[] dc = new int[16];
            int ctx = topDc[mx] + leftDc;
            int nz = coeffs(br, 1, ctx, y2[segment], 0, dc);
            topDc[mx] = leftDc = nz > 0 ? 1 : 0;
            if (nz > 1) {
                wht(dc);
            } else {
                int dc0 = dc[0] + 3 >> 3;
                for (int i = 0; i < 16; i++) {
                    coeffs[i * 16] = (short) dc0;
                }
            }
            first = 1;
            type = 0;
        }
        int[] blk = new int[16];
        for (int y = 0; y < 4; y++) {
            int l = leftY[y];
            for (int x = 0; x < 4; x++) {
                java.util.Arrays.fill(blk, 0);
                int ctx = l + topY[mx * 4 + x];
                int nz = coeffs(br, type, ctx, y1[segment], first, blk);
                l = nz > first ? 1 : 0;
                topY[mx * 4 + x] = l;
                int base = (y * 4 + x) * 16;
                for (int i = first; i < 16; i++) {
                    coeffs[base + i] = (short) blk[i];
                }
                any |= nz > first || coeffs[base] != 0;
            }
            leftY[y] = l;
        }
        for (int ch = 0; ch < 2; ch++) {
            int[] top = ch == 0 ? topU : topV;
            int[] left = ch == 0 ? leftU : leftV;
            for (int y = 0; y < 2; y++) {
                int l = left[y];
                for (int x = 0; x < 2; x++) {
                    java.util.Arrays.fill(blk, 0);
                    int ctx = l + top[mx * 2 + x];
                    int nz = coeffs(br, 2, ctx, uv[segment], 0, blk);
                    l = nz > 0 ? 1 : 0;
                    top[mx * 2 + x] = l;
                    int base = 256 + ch * 64 + (y * 2 + x) * 16;
                    for (int i = 0; i < 16; i++) {
                        coeffs[base + i] = (short) blk[i];
                    }
                    any |= nz > 0;
                }
                left[y] = l;
            }
        }
        return any;
    }

    private int coeffs(Bool br, int type, int ctx, int[] dq, int first, int[] out) throws Invalid {
        int n = first;
        int c = ctx;
        while (n < 16) {
            int p = ((type * 8 + BANDS[n]) * 3 + c) * 11;
            if (br.bit(probs[p] & 0xFF) == 0) {
                return n;
            }
            while (br.bit(probs[p + 1] & 0xFF) == 0) {
                n++;
                if (n == 16) {
                    return 16;
                }
                p = ((type * 8 + BANDS[n]) * 3) * 11;
            }
            int v;
            if (br.bit(probs[p + 2] & 0xFF) == 0) {
                v = 1;
                c = 1;
            } else {
                v = large(br, p);
                c = 2;
            }
            out[ZIGZAG[n]] = (br.bit(128) == 1 ? -v : v) * dq[n > 0 ? 1 : 0];
            n++;
        }
        return 16;
    }

    private int large(Bool br, int p) throws Invalid {
        if (br.bit(probs[p + 3] & 0xFF) == 0) {
            return br.bit(probs[p + 4] & 0xFF) == 0 ? 2 : 3 + br.bit(probs[p + 5] & 0xFF);
        }
        if (br.bit(probs[p + 6] & 0xFF) == 0) {
            if (br.bit(probs[p + 7] & 0xFF) == 0) {
                return 5 + br.bit(159);
            }
            int v = 7 + 2 * br.bit(165);
            return v + br.bit(145);
        }
        int bit1 = br.bit(probs[p + 8] & 0xFF);
        int bit0 = br.bit(probs[p + 9 + bit1] & 0xFF);
        int cat = 2 * bit1 + bit0;
        int v = 0;
        for (int prob : CAT[cat]) {
            v += v + br.bit(prob);
        }
        return v + 3 + (8 << cat);
    }

    private void wht(int[] in) {
        int[] tmp = new int[16];
        for (int i = 0; i < 4; i++) {
            int a0 = in[i] + in[12 + i];
            int a1 = in[4 + i] + in[8 + i];
            int a2 = in[4 + i] - in[8 + i];
            int a3 = in[i] - in[12 + i];
            tmp[i] = a0 + a1;
            tmp[8 + i] = a0 - a1;
            tmp[4 + i] = a3 + a2;
            tmp[12 + i] = a3 - a2;
        }
        for (int i = 0; i < 4; i++) {
            int dc = tmp[i * 4] + 3;
            int a0 = dc + tmp[3 + i * 4];
            int a1 = tmp[1 + i * 4] + tmp[2 + i * 4];
            int a2 = tmp[1 + i * 4] - tmp[2 + i * 4];
            int a3 = dc - tmp[3 + i * 4];
            coeffs[(i * 4) * 16] = (short) (a0 + a1 >> 3);
            coeffs[(i * 4 + 1) * 16] = (short) (a3 + a2 >> 3);
            coeffs[(i * 4 + 2) * 16] = (short) (a0 - a1 >> 3);
            coeffs[(i * 4 + 3) * 16] = (short) (a3 - a2 >> 3);
        }
    }

    // ---------------------------------------------------------- reconstruction

    private static int mul1(int a) {
        return (a * 20091 >> 16) + a;
    }

    private static int mul2(int a) {
        return a * 35468 >> 16;
    }

    private void transform(int cbase, byte[] pl, int at, int stride) {
        int[] tmp = new int[16];
        for (int i = 0; i < 4; i++) {
            int in0 = coeffs[cbase + i];
            int in4 = coeffs[cbase + 4 + i];
            int in8 = coeffs[cbase + 8 + i];
            int in12 = coeffs[cbase + 12 + i];
            int a = in0 + in8;
            int b = in0 - in8;
            int c = mul2(in4) - mul1(in12);
            int d = mul1(in4) + mul2(in12);
            tmp[i * 4] = a + d;
            tmp[i * 4 + 1] = b + c;
            tmp[i * 4 + 2] = b - c;
            tmp[i * 4 + 3] = a - d;
        }
        for (int i = 0; i < 4; i++) {
            int dc = tmp[i] + 4;
            int a = dc + tmp[8 + i];
            int b = dc - tmp[8 + i];
            int c = mul2(tmp[4 + i]) - mul1(tmp[12 + i]);
            int d = mul1(tmp[4 + i]) + mul2(tmp[12 + i]);
            int row = at + i * stride;
            pl[row] = (byte) clip8((pl[row] & 0xFF) + (a + d >> 3));
            pl[row + 1] = (byte) clip8((pl[row + 1] & 0xFF) + (b + c >> 3));
            pl[row + 2] = (byte) clip8((pl[row + 2] & 0xFF) + (b - c >> 3));
            pl[row + 3] = (byte) clip8((pl[row + 3] & 0xFF) + (a - d >> 3));
        }
    }

    private static int at(int stride, int x, int y) {
        return (y + 1) * stride + x + 1;
    }

    private void reconstruct(int mx, int my, boolean i4, int ymode, int[] imodes, int uvmode, boolean coded)
            throws Invalid {
        int x0 = mx * 16;
        int y0 = my * 16;
        if (!i4) {
            predictBlock(yp, ystride, x0, y0, 16, ymode, mx, my);
            if (coded) {
                for (int b = 0; b < 16; b++) {
                    transform(b * 16, yp, at(ystride, x0 + (b & 3) * 4, y0 + (b >> 2) * 4), ystride);
                }
            }
        } else {
            for (int b = 0; b < 16; b++) {
                int bx = x0 + (b & 3) * 4;
                int by = y0 + (b >> 2) * 4;
                predict4(imodes[b], bx, by, b & 3, mx, my, mx == mbw - 1);
                if (coded) {
                    transform(b * 16, yp, at(ystride, bx, by), ystride);
                }
            }
        }
        for (int ch = 0; ch < 2; ch++) {
            byte[] pl = ch == 0 ? up : vp;
            predictBlock(pl, uvstride, mx * 8, my * 8, 8, uvmode, mx, my);
            if (coded) {
                for (int b = 0; b < 4; b++) {
                    transform(256 + ch * 64 + b * 16, pl, at(uvstride, mx * 8 + (b & 1) * 4, my * 8 + (b >> 1) * 4),
                            uvstride);
                }
            }
        }
    }

    private static void predictBlock(byte[] pl, int stride, int x0, int y0, int n, int mode, int mx, int my) {
        int base = at(stride, x0, y0);
        switch (mode) {
            case B_DC -> {
                int sum = 0;
                int dc;
                int shift = n == 16 ? 3 : 2;
                if (mx > 0 && my > 0) {
                    for (int i = 0; i < n; i++) {
                        sum += (pl[base - stride + i] & 0xFF) + (pl[base + i * stride - 1] & 0xFF);
                    }
                    dc = sum + n >> shift + 2;
                } else if (my > 0) {
                    for (int i = 0; i < n; i++) {
                        sum += pl[base - stride + i] & 0xFF;
                    }
                    dc = sum + (n >> 1) >> shift + 1;
                } else if (mx > 0) {
                    for (int i = 0; i < n; i++) {
                        sum += pl[base + i * stride - 1] & 0xFF;
                    }
                    dc = sum + (n >> 1) >> shift + 1;
                } else {
                    dc = 128;
                }
                for (int y = 0; y < n; y++) {
                    java.util.Arrays.fill(pl, base + y * stride, base + y * stride + n, (byte) dc);
                }
            }
            case B_TM -> {
                int tl = pl[base - stride - 1] & 0xFF;
                for (int y = 0; y < n; y++) {
                    int l = (pl[base + y * stride - 1] & 0xFF) - tl;
                    for (int x = 0; x < n; x++) {
                        pl[base + y * stride + x] = (byte) clip8(l + (pl[base - stride + x] & 0xFF));
                    }
                }
            }
            case B_VE -> {
                for (int y = 0; y < n; y++) {
                    System.arraycopy(pl, base - stride, pl, base + y * stride, n);
                }
            }
            default -> {
                for (int y = 0; y < n; y++) {
                    java.util.Arrays.fill(pl, base + y * stride, base + y * stride + n, pl[base + y * stride - 1]);
                }
            }
        }
    }

    private static int avg3(int a, int b, int c) {
        return a + 2 * b + c + 2 >> 2;
    }

    private static int avg2(int a, int b) {
        return a + b + 1 >> 1;
    }

    private void predict4(int mode, int bx, int by, int col, int mx, int my, boolean lastMb) {
        byte[] pl = yp;
        int s = ystride;
        int base = at(s, bx, by);
        int[] t = new int[8];
        for (int i = 0; i < 4; i++) {
            t[i] = pl[base - s + i] & 0xFF;
        }
        if (col < 3) {
            for (int i = 4; i < 8; i++) {
                t[i] = pl[base - s + i] & 0xFF;
            }
        } else if (my == 0) {
            java.util.Arrays.fill(t, 4, 8, 127);
        } else {
            int row = at(s, mx * 16, my * 16 - 1);
            for (int i = 0; i < 4; i++) {
                t[4 + i] = lastMb ? pl[row + 15] & 0xFF : pl[row + 16 + i] & 0xFF;
            }
        }
        int[] l = new int[4];
        for (int i = 0; i < 4; i++) {
            l[i] = pl[base + i * s - 1] & 0xFF;
        }
        int tl = pl[base - s - 1] & 0xFF;
        int[][] o = new int[4][4];
        int a = t[0];
        int b = t[1];
        int c = t[2];
        int d = t[3];
        int e = t[4];
        int f = t[5];
        int g = t[6];
        int h = t[7];
        int i0 = l[0];
        int j0 = l[1];
        int k0 = l[2];
        int l0 = l[3];
        switch (mode) {
            case B_DC -> {
                int dc = 4;
                for (int i = 0; i < 4; i++) {
                    dc += t[i] + l[i];
                }
                dc >>= 3;
                for (int y = 0; y < 4; y++) {
                    java.util.Arrays.fill(o[y], dc);
                }
            }
            case B_TM -> {
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        o[y][x] = clip8(t[x] + l[y] - tl);
                    }
                }
            }
            case B_VE -> {
                int[] v = {avg3(tl, a, b), avg3(a, b, c), avg3(b, c, d), avg3(c, d, e)};
                for (int y = 0; y < 4; y++) {
                    System.arraycopy(v, 0, o[y], 0, 4);
                }
            }
            case B_HE -> {
                int[] v = {avg3(tl, i0, j0), avg3(i0, j0, k0), avg3(j0, k0, l0), avg3(k0, l0, l0)};
                for (int y = 0; y < 4; y++) {
                    java.util.Arrays.fill(o[y], v[y]);
                }
            }
            case B_RD -> {
                o[3][0] = avg3(j0, k0, l0);
                o[3][1] = o[2][0] = avg3(i0, j0, k0);
                o[3][2] = o[2][1] = o[1][0] = avg3(tl, i0, j0);
                o[3][3] = o[2][2] = o[1][1] = o[0][0] = avg3(a, tl, i0);
                o[2][3] = o[1][2] = o[0][1] = avg3(b, a, tl);
                o[1][3] = o[0][2] = avg3(c, b, a);
                o[0][3] = avg3(d, c, b);
            }
            case B_VR -> {
                o[0][0] = o[2][1] = avg2(tl, a);
                o[0][1] = o[2][2] = avg2(a, b);
                o[0][2] = o[2][3] = avg2(b, c);
                o[0][3] = avg2(c, d);
                o[3][0] = avg3(k0, j0, i0);
                o[2][0] = avg3(j0, i0, tl);
                o[1][0] = o[3][1] = avg3(i0, tl, a);
                o[1][1] = o[3][2] = avg3(tl, a, b);
                o[1][2] = o[3][3] = avg3(a, b, c);
                o[1][3] = avg3(b, c, d);
            }
            case B_LD -> {
                o[0][0] = avg3(a, b, c);
                o[0][1] = o[1][0] = avg3(b, c, d);
                o[0][2] = o[1][1] = o[2][0] = avg3(c, d, e);
                o[0][3] = o[1][2] = o[2][1] = o[3][0] = avg3(d, e, f);
                o[1][3] = o[2][2] = o[3][1] = avg3(e, f, g);
                o[2][3] = o[3][2] = avg3(f, g, h);
                o[3][3] = avg3(g, h, h);
            }
            case B_VL -> {
                o[0][0] = avg2(a, b);
                o[0][1] = o[2][0] = avg2(b, c);
                o[0][2] = o[2][1] = avg2(c, d);
                o[0][3] = o[2][2] = avg2(d, e);
                o[1][0] = avg3(a, b, c);
                o[1][1] = o[3][0] = avg3(b, c, d);
                o[1][2] = o[3][1] = avg3(c, d, e);
                o[1][3] = o[3][2] = avg3(d, e, f);
                o[2][3] = avg3(e, f, g);
                o[3][3] = avg3(f, g, h);
            }
            case B_HD -> {
                o[0][0] = o[1][2] = avg2(i0, tl);
                o[1][0] = o[2][2] = avg2(j0, i0);
                o[2][0] = o[3][2] = avg2(k0, j0);
                o[3][0] = avg2(l0, k0);
                o[0][3] = avg3(a, b, c);
                o[0][2] = avg3(tl, a, b);
                o[0][1] = o[1][3] = avg3(i0, tl, a);
                o[1][1] = o[2][3] = avg3(j0, i0, tl);
                o[2][1] = o[3][3] = avg3(k0, j0, i0);
                o[3][1] = avg3(l0, k0, j0);
            }
            default -> {
                o[0][0] = avg2(i0, j0);
                o[0][2] = o[1][0] = avg2(j0, k0);
                o[1][2] = o[2][0] = avg2(k0, l0);
                o[0][1] = avg3(i0, j0, k0);
                o[0][3] = o[1][1] = avg3(j0, k0, l0);
                o[1][3] = o[2][1] = avg3(k0, l0, l0);
                o[2][2] = o[2][3] = o[3][0] = o[3][1] = o[3][2] = o[3][3] = l0;
            }
        }
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                pl[base + y * s + x] = (byte) o[y][x];
            }
        }
    }

    // ------------------------------------------------------------- loop filter

    private void loopFilter() {
        if (fLevel == 0) {
            return;
        }
        for (int my = 0; my < mbh; my++) {
            for (int mx = 0; mx < mbw; mx++) {
                int idx = my * mbw + mx;
                int level = fLevel;
                if (segOn) {
                    level = segFilter[mbSegment[idx]] + (segAbs ? 0 : fLevel);
                }
                if (lfDelta) {
                    level += refDelta[0];
                    if (mbI4[idx]) {
                        level += modeDelta[0];
                    }
                }
                level = clip(level, 63);
                if (level == 0) {
                    continue;
                }
                int il = level;
                if (sharpness > 0) {
                    il >>= sharpness > 4 ? 2 : 1;
                    il = Math.min(il, 9 - sharpness);
                }
                il = Math.max(il, 1);
                int limit = 2 * level + il;
                int hev = level >= 40 ? 2 : level >= 15 ? 1 : 0;
                boolean inner = mbInner[idx];
                int yb = at(ystride, mx * 16, my * 16);
                int ub = at(uvstride, mx * 8, my * 8);
                if (simple) {
                    if (mx > 0) {
                        simpleLoop(yp, yb, 1, ystride, 16, limit + 4);
                    }
                    if (inner) {
                        for (int k = 4; k < 16; k += 4) {
                            simpleLoop(yp, yb + k, 1, ystride, 16, limit);
                        }
                    }
                    if (my > 0) {
                        simpleLoop(yp, yb, ystride, 1, 16, limit + 4);
                    }
                    if (inner) {
                        for (int k = 4; k < 16; k += 4) {
                            simpleLoop(yp, yb + k * ystride, ystride, 1, 16, limit);
                        }
                    }
                } else {
                    if (mx > 0) {
                        loop26(yp, yb, 1, ystride, 16, limit + 4, il, hev);
                        loop26(up, ub, 1, uvstride, 8, limit + 4, il, hev);
                        loop26(vp, ub, 1, uvstride, 8, limit + 4, il, hev);
                    }
                    if (inner) {
                        for (int k = 4; k < 16; k += 4) {
                            loop24(yp, yb + k, 1, ystride, 16, limit, il, hev);
                        }
                        loop24(up, ub + 4, 1, uvstride, 8, limit, il, hev);
                        loop24(vp, ub + 4, 1, uvstride, 8, limit, il, hev);
                    }
                    if (my > 0) {
                        loop26(yp, yb, ystride, 1, 16, limit + 4, il, hev);
                        loop26(up, ub, uvstride, 1, 8, limit + 4, il, hev);
                        loop26(vp, ub, uvstride, 1, 8, limit + 4, il, hev);
                    }
                    if (inner) {
                        for (int k = 4; k < 16; k += 4) {
                            loop24(yp, yb + k * ystride, ystride, 1, 16, limit, il, hev);
                        }
                        loop24(up, ub + 4 * uvstride, uvstride, 1, 8, limit, il, hev);
                        loop24(vp, ub + 4 * uvstride, uvstride, 1, 8, limit, il, hev);
                    }
                }
            }
        }
    }

    private static int px(byte[] p, int i) {
        return p[i] & 0xFF;
    }

    private static int sclip1(int v) {
        return v < -128 ? -128 : Math.min(v, 127);
    }

    private static int sclip2(int v) {
        return v < -16 ? -16 : Math.min(v, 15);
    }

    private static boolean needs(byte[] p, int i, int step, int t) {
        return 4 * Math.abs(px(p, i - step) - px(p, i)) + Math.abs(px(p, i - 2 * step) - px(p, i + step)) <= t;
    }

    private static void simpleLoop(byte[] p, int i, int hstep, int vstep, int size, int thresh) {
        int t2 = 2 * thresh + 1;
        for (int k = 0; k < size; k++, i += vstep) {
            if (needs(p, i, hstep, t2)) {
                filter2(p, i, hstep);
            }
        }
    }

    private static boolean needs2(byte[] p, int i, int step, int t, int it) {
        if (!needs(p, i, step, t)) {
            return false;
        }
        int p3 = px(p, i - 4 * step);
        int p2 = px(p, i - 3 * step);
        int p1 = px(p, i - 2 * step);
        int p0 = px(p, i - step);
        int q0 = px(p, i);
        int q1 = px(p, i + step);
        int q2 = px(p, i + 2 * step);
        int q3 = px(p, i + 3 * step);
        return Math.abs(p3 - p2) <= it && Math.abs(p2 - p1) <= it && Math.abs(p1 - p0) <= it
                && Math.abs(q3 - q2) <= it && Math.abs(q2 - q1) <= it && Math.abs(q1 - q0) <= it;
    }

    private static boolean hev(byte[] p, int i, int step, int thresh) {
        return Math.abs(px(p, i - 2 * step) - px(p, i - step)) > thresh
                || Math.abs(px(p, i + step) - px(p, i)) > thresh;
    }

    private static void loop26(byte[] p, int i, int hstep, int vstep, int size, int thresh, int it, int ht) {
        int t2 = 2 * thresh + 1;
        for (int k = 0; k < size; k++, i += vstep) {
            if (needs2(p, i, hstep, t2, it)) {
                if (hev(p, i, hstep, ht)) {
                    filter2(p, i, hstep);
                } else {
                    filter6(p, i, hstep);
                }
            }
        }
    }

    private static void loop24(byte[] p, int i, int hstep, int vstep, int size, int thresh, int it, int ht) {
        int t2 = 2 * thresh + 1;
        for (int k = 0; k < size; k++, i += vstep) {
            if (needs2(p, i, hstep, t2, it)) {
                if (hev(p, i, hstep, ht)) {
                    filter2(p, i, hstep);
                } else {
                    filter4(p, i, hstep);
                }
            }
        }
    }

    private static void filter2(byte[] p, int i, int step) {
        int p1 = px(p, i - 2 * step);
        int p0 = px(p, i - step);
        int q0 = px(p, i);
        int q1 = px(p, i + step);
        int a = 3 * (q0 - p0) + sclip1(p1 - q1);
        int a1 = sclip2(a + 4 >> 3);
        int a2 = sclip2(a + 3 >> 3);
        p[i - step] = (byte) clip8(p0 + a2);
        p[i] = (byte) clip8(q0 - a1);
    }

    private static void filter4(byte[] p, int i, int step) {
        int p1 = px(p, i - 2 * step);
        int p0 = px(p, i - step);
        int q0 = px(p, i);
        int q1 = px(p, i + step);
        int a = 3 * (q0 - p0);
        int a1 = sclip2(a + 4 >> 3);
        int a2 = sclip2(a + 3 >> 3);
        int a3 = a1 + 1 >> 1;
        p[i - 2 * step] = (byte) clip8(p1 + a3);
        p[i - step] = (byte) clip8(p0 + a2);
        p[i] = (byte) clip8(q0 - a1);
        p[i + step] = (byte) clip8(q1 - a3);
    }

    private static void filter6(byte[] p, int i, int step) {
        int p2 = px(p, i - 3 * step);
        int p1 = px(p, i - 2 * step);
        int p0 = px(p, i - step);
        int q0 = px(p, i);
        int q1 = px(p, i + step);
        int q2 = px(p, i + 2 * step);
        int a = sclip1(3 * (q0 - p0) + sclip1(p1 - q1));
        int a1 = 27 * a + 63 >> 7;
        int a2 = 18 * a + 63 >> 7;
        int a3 = 9 * a + 63 >> 7;
        p[i - 3 * step] = (byte) clip8(p2 + a3);
        p[i - 2 * step] = (byte) clip8(p1 + a2);
        p[i - step] = (byte) clip8(p0 + a1);
        p[i] = (byte) clip8(q0 - a1);
        p[i + step] = (byte) clip8(q1 - a2);
        p[i + 2 * step] = (byte) clip8(q2 - a3);
    }

    // -------------------------------------------------------------- colour out

    private static int mulHi(int v, int c) {
        return v * c >> 8;
    }

    private static int c8(int v) {
        return (v & ~16383) == 0 ? v >> 6 : v < 0 ? 0 : 255;
    }

    private static int rgb(int y, int u, int v) {
        int yy = mulHi(y, 19077);
        int r = c8(yy + mulHi(v, 26149) - 14234);
        int g = c8(yy - mulHi(u, 6419) - mulHi(v, 13320) + 8708);
        int b = c8(yy + mulHi(u, 33050) - 17685);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private int[] rgb() {
        int[] out = new int[width * height];
        int cw = (width + 1) >> 1;
        for (int y = 0; y < height; y++) {
            int topRow;
            int curRow;
            boolean upper;
            if (y == 0) {
                topRow = 0;
                curRow = 0;
                upper = true;
            } else if ((y & 1) == 1) {
                topRow = y >> 1;
                curRow = topRow + 1;
                upper = true;
            } else {
                curRow = y >> 1;
                topRow = curRow - 1;
                upper = false;
            }
            if (curRow >= (height + 1) >> 1) {
                curRow = topRow = (height - 1) >> 1;
            }
            row(out, y, topRow, curRow, upper, cw);
        }
        return out;
    }

    private int uvAt(int row, int x) {
        int i = at(uvstride, x, row);
        return up[i] & 0xFF | (vp[i] & 0xFF) << 16;
    }

    private void row(int[] out, int y, int topRow, int curRow, boolean upper, int cw) {
        int yi = at(ystride, 0, y);
        int o = y * width;
        int tl = uvAt(topRow, 0);
        int l = uvAt(curRow, 0);
        int uv0 = upper ? 3 * tl + l + 0x00020002 >>> 2 : 3 * l + tl + 0x00020002 >>> 2;
        out[o] = rgb(yp[yi] & 0xFF, uv0 & 0xFF, uv0 >>> 16);
        int last = (width - 1) >> 1;
        for (int x = 1; x <= last; x++) {
            int t = uvAt(topRow, x);
            int c = uvAt(curRow, x);
            int avg = tl + t + l + c + 0x00080008;
            int diag12 = avg + 2 * (t + l) >>> 3;
            int diag03 = avg + 2 * (tl + c) >>> 3;
            int a;
            int b;
            if (upper) {
                a = diag12 + tl >>> 1;
                b = diag03 + t >>> 1;
            } else {
                a = diag03 + l >>> 1;
                b = diag12 + c >>> 1;
            }
            out[o + 2 * x - 1] = rgb(yp[yi + 2 * x - 1] & 0xFF, a & 0xFF, a >>> 16);
            if (2 * x < width) {
                out[o + 2 * x] = rgb(yp[yi + 2 * x] & 0xFF, b & 0xFF, b >>> 16);
            }
            tl = t;
            l = c;
        }
        if ((width & 1) == 0) {
            int e = upper ? 3 * tl + l + 0x00020002 >>> 2 : 3 * l + tl + 0x00020002 >>> 2;
            out[o + width - 1] = rgb(yp[yi + width - 1] & 0xFF, e & 0xFF, e >>> 16);
        }
    }
}
