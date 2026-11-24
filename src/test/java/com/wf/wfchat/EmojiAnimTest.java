package com.wf.wfchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wf.wfchat.core.Anim;
import com.wf.wfchat.core.Apng;
import com.wf.wfchat.core.EmojiFile;
import com.wf.wfchat.core.webp.WebP;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class EmojiAnimTest {
    private static final EmojiFile.Limits LOOSE = new EmojiFile.Limits(1 << 20, 8, 128, 128, false);

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = EmojiAnimTest.class.getResourceAsStream("/emoji/" + name)) {
            return in.readAllBytes();
        }
    }

    private static String rgbaHash(Anim anim) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int[] frame : anim.argb()) {
            byte[] b = new byte[frame.length * 4];
            for (int i = 0; i < frame.length; i++) {
                int c = frame[i];
                boolean clear = c >>> 24 == 0;
                b[i * 4] = (byte) (clear ? 0 : c >> 16);
                b[i * 4 + 1] = (byte) (clear ? 0 : c >> 8);
                b[i * 4 + 2] = (byte) (clear ? 0 : c);
                b[i * 4 + 3] = (byte) (c >>> 24);
            }
            md.update(b);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Hashes are of libwebp / PIL decoded RGBA frames, RGB zeroed under alpha 0. */
    @ParameterizedTest
    @CsvFileSource(resources = "/emoji/frames.sha256", delimiter = ' ')
    void decodesBitExact(String file, String sha256) throws Exception {
        EmojiFile.Verified v = EmojiFile.verify(file.replace("_", ""), resource(file), LOOSE);
        Anim anim = file.endsWith(".webp") ? WebP.decode(v.data(), 128) : Apng.decode(v.data());
        assertThat(anim.argb()).hasSize(v.frames());
        assertThat(rgbaHash(anim)).isEqualTo(sha256);
    }

    @Test
    void frameCountsAndKinds() throws Exception {
        assertThat(EmojiFile.verify("a1.webp", resource("anim_lossy.webp"), LOOSE).frames()).isEqualTo(8);
        assertThat(EmojiFile.verify("a2.webp", resource("pil_mixed.webp"), LOOSE).frames()).isEqualTo(14);
        assertThat(EmojiFile.verify("a3.webp", resource("lossy.webp"), LOOSE).frames()).isEqualTo(1);
        EmojiFile.Verified apng = EmojiFile.verify("a4.png", resource("apng_rgba.png"), LOOSE);
        assertThat(apng.frames()).isEqualTo(10);
        assertThat(apng.kind()).isEqualTo(EmojiFile.Kind.PNG);
        assertThat(EmojiFile.verifyContent("a5", resource("lossy.webp"), LOOSE).kind()).isEqualTo(EmojiFile.Kind.WEBP);
    }

    @Test
    void webpLimits() throws Exception {
        byte[] raw = resource("lossless.webp");
        assertThatThrownBy(() -> EmojiFile.verify("big.webp", raw, new EmojiFile.Limits(1 << 20, 8, 32, 128, true)))
                .hasMessageContaining("px");
        assertThatThrownBy(() -> EmojiFile.verify("big.webp", raw, new EmojiFile.Limits(100, 8, 128, 128, true)))
                .hasMessageContaining("bytes");
        assertThatThrownBy(() -> EmojiFile.verify("odd.webp", resource("lossy_odd.webp"), EmojiFile.Limits.DEFAULT))
                .hasMessageContaining("square");
        assertThatThrownBy(() -> EmojiFile.verify("anim.webp", resource("anim_lossy.webp"),
                new EmojiFile.Limits(1 << 20, 8, 128, 4, true))).hasMessageContaining("frames");
        assertThatThrownBy(() -> EmojiFile.verify("anim.png", resource("apng_rgba.png"),
                new EmojiFile.Limits(1 << 20, 8, 128, 4, false))).hasMessageContaining("frame");
        assertThatThrownBy(() -> EmojiFile.verify("x.webp", raw, LOOSE)).hasMessageContaining("name");
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", raw, LOOSE)).hasMessageContaining("claims");
    }

    private static byte[] withChunk(byte[] webp, String id, byte[] data, boolean exifFlag) {
        byte[] out = Arrays.copyOf(webp, webp.length + 8 + data.length + (data.length & 1));
        int at = webp.length;
        for (int i = 0; i < 4; i++) {
            out[at + i] = (byte) id.charAt(i);
        }
        out[at + 4] = (byte) data.length;
        out[at + 5] = (byte) (data.length >> 8);
        System.arraycopy(data, 0, out, at + 8, data.length);
        int riff = out.length - 8;
        out[4] = (byte) riff;
        out[5] = (byte) (riff >> 8);
        out[6] = (byte) (riff >> 16);
        out[7] = (byte) (riff >> 24);
        if (exifFlag) {
            out[20] |= 0x08;
        }
        return out;
    }

    @Test
    void webpMetadataStripped() throws Exception {
        byte[] base = resource("lossy_alpha.webp");
        byte[] raw = withChunk(withChunk(base, "EXIF", "camera: secret".getBytes(StandardCharsets.UTF_8), true), "XMPP",
                new byte[]{1, 2, 3}, false);
        EmojiFile.Verified v = EmojiFile.verify("meta.webp", raw, LOOSE);
        EmojiFile.Verified plain = EmojiFile.verify("meta.webp", base, LOOSE);
        assertThat(v.strippedBytes()).isGreaterThan(0);
        assertThat(v.sha256()).isEqualTo(plain.sha256());
        assertThat(new String(v.data(), StandardCharsets.ISO_8859_1)).doesNotContain("EXIF", "secret");
    }

    @Test
    void webpStructureRejected() throws Exception {
        byte[] base = resource("lossy.webp");
        byte[] trailing = Arrays.copyOf(base, base.length + 4);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.webp", trailing, LOOSE)).hasMessageContaining("RIFF");
        byte[] truncated = Arrays.copyOf(base, base.length - 40);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.webp", truncated, LOOSE)).isInstanceOf(EmojiFile.Rejected.class);
        byte[] noData = withChunk(Arrays.copyOf(base, 12), "ZZZZ", new byte[4], false);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.webp", noData, LOOSE)).isInstanceOf(EmojiFile.Rejected.class);
    }

    @Test
    void corruptedInputOnlyEverRejected() throws Exception {
        Random rnd = new Random(7);
        for (String name : List.of("lossless.webp", "lossy_alpha.webp", "pil_mixed.webp", "lossless_alpha.webp")) {
            byte[] raw = resource(name);
            for (int i = 0; i < 300; i++) {
                byte[] m = raw.clone();
                for (int k = 1 + rnd.nextInt(3); k > 0; k--) {
                    m[12 + rnd.nextInt(m.length - 12)] = (byte) rnd.nextInt(256);
                }
                if (i % 4 == 0) {
                    m = Arrays.copyOf(m, 12 + rnd.nextInt(m.length - 12));
                }
                try {
                    EmojiFile.verify("fuzz.webp", m, LOOSE);
                } catch (EmojiFile.Rejected expected) {
                    // rejected is the only allowed failure
                }
            }
        }
    }

    @Test
    void apngSequenceChecked() throws Exception {
        byte[] raw = resource("apng_rgba.png");
        int fcTl = indexOf(raw, "fcTL");
        byte[] bad = raw.clone();
        bad[fcTl + 7] = 9;
        refreshCrc(bad, fcTl - 4);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", bad, LOOSE)).hasMessageContaining("fcTL");
    }

    private static int indexOf(byte[] d, String type) {
        outer:
        for (int i = 0; i < d.length - 4; i++) {
            for (int k = 0; k < 4; k++) {
                if (d[i + k] != type.charAt(k)) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalStateException(type);
    }

    private static void refreshCrc(byte[] d, int chunkStart) {
        int len = (d[chunkStart] & 0xFF) << 24 | (d[chunkStart + 1] & 0xFF) << 16 | (d[chunkStart + 2] & 0xFF) << 8
                | d[chunkStart + 3] & 0xFF;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(d, chunkStart + 4, 4 + len);
        long v = crc.getValue();
        int at = chunkStart + 8 + len;
        d[at] = (byte) (v >> 24);
        d[at + 1] = (byte) (v >> 16);
        d[at + 2] = (byte) (v >> 8);
        d[at + 3] = (byte) v;
    }
}
