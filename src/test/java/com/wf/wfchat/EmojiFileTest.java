package com.wf.wfchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wf.wfchat.core.EmojiFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;

class EmojiFileTest {
    private static final EmojiFile.Limits L = EmojiFile.Limits.DEFAULT;

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 1, 0xFFFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] gif(int w, int h, int frames) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                img.setRGB(i % w, 0, 0xFF00FF00);
                writer.writeToSequence(new IIOImage(img, null, null), (ImageWriteParam) null);
            }
            writer.endWriteSequence();
        }
        writer.dispose();
        return out.toByteArray();
    }

    private static byte[] withChunk(byte[] png, String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int iend = png.length - 12;
        out.write(png, 0, iend);
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        out.write(data.length >>> 24);
        out.write(data.length >>> 16);
        out.write(data.length >>> 8);
        out.write(data.length);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        long v = crc.getValue();
        out.write((int) (v >>> 24));
        out.write((int) (v >>> 16));
        out.write((int) (v >>> 8));
        out.write((int) v);
        out.write(png, iend, 12);
        return out.toByteArray();
    }

    @Test
    void acceptsPng() throws Exception {
        EmojiFile.Verified v = EmojiFile.verify("Pepe.PNG", png(32, 32), L);
        assertThat(v.name()).isEqualTo("pepe");
        assertThat(v.width()).isEqualTo(32);
        assertThat(v.kind()).isEqualTo(EmojiFile.Kind.PNG);
        assertThat(EmojiFile.verify("pepe.png", v.data(), L).sha256()).isEqualTo(v.sha256());
    }

    @Test
    void stripsMetadata() throws Exception {
        byte[] raw = withChunk(png(32, 32), "tEXt", "Comment\0secret".getBytes(StandardCharsets.ISO_8859_1));
        EmojiFile.Verified v = EmojiFile.verify("pepe.png", raw, L);
        assertThat(v.strippedBytes()).isGreaterThan(0);
        assertThat(new String(v.data(), StandardCharsets.ISO_8859_1)).doesNotContain("secret");
    }

    @Test
    void acceptsGif() throws Exception {
        EmojiFile.Verified v = EmojiFile.verify("dance.gif", gif(16, 16, 4), L);
        assertThat(v.frames()).isEqualTo(4);
        assertThat(EmojiFile.verify("dance.gif", v.data(), L).sha256()).isEqualTo(v.sha256());
    }

    @Test
    void rejects() throws Exception {
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", png(256, 256), L)).isInstanceOf(EmojiFile.Rejected.class);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", png(4, 4), L)).isInstanceOf(EmojiFile.Rejected.class);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", png(32, 16), L)).hasMessageContaining("square");
        assertThatThrownBy(() -> EmojiFile.verify("pepe.gif", png(32, 32), L)).hasMessageContaining("claims");
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", "not an image".getBytes(), L)).isInstanceOf(EmojiFile.Rejected.class);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.webp", png(32, 32), L)).hasMessageContaining("extension");
        assertThatThrownBy(() -> EmojiFile.verify("x.png", png(32, 32), L)).hasMessageContaining("name");
        assertThatThrownBy(() -> EmojiFile.verify("pepe.gif", gif(16, 16, 5), new EmojiFile.Limits(131072, 8, 128, 4, true)))
                .hasMessageContaining("frames");
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", png(32, 32), new EmojiFile.Limits(50, 8, 128, 4, true)))
                .hasMessageContaining("limit");
    }

    @Test
    void rejectsCorruption() throws Exception {
        byte[] p = png(32, 32);
        byte[] bad = p.clone();
        bad[40] ^= 0x55;
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", bad, L)).isInstanceOf(EmojiFile.Rejected.class);
        byte[] trailing = java.util.Arrays.copyOf(p, p.length + 4);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", trailing, L)).hasMessageContaining("IEND");
        byte[] g = gif(16, 16, 2);
        byte[] gtrail = java.util.Arrays.copyOf(g, g.length + 1);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.gif", gtrail, L)).hasMessageContaining("trailer");
        assertThatThrownBy(() -> EmojiFile.verify("pepe.gif", java.util.Arrays.copyOf(g, g.length - 10), L))
                .isInstanceOf(EmojiFile.Rejected.class);
    }

    @Test
    void rejectsAnimatedPng() throws Exception {
        byte[] raw = withChunk(png(32, 32), "acTL", new byte[8]);
        assertThatThrownBy(() -> EmojiFile.verify("pepe.png", raw, L)).hasMessageContaining("animated");
    }
}
