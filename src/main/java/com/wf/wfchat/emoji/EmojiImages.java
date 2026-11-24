package com.wf.wfchat.emoji;

import com.mojang.blaze3d.platform.NativeImage;
import com.wf.wfchat.WfChat;
import com.wf.wfchat.core.Anim;
import com.wf.wfchat.core.Apng;
import com.wf.wfchat.core.EmojiFile;
import com.wf.wfchat.core.webp.Invalid;
import com.wf.wfchat.core.webp.WebP;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.w3c.dom.NodeList;

/** Downloads, verifies and caches emoji images off-thread; uploads textures on the render thread. */
public final class EmojiImages {
    private static final EmojiImages INSTANCE = new EmojiImages();
    private static final int MAX_BYTES = 1 << 20;
    private static final int TARGET = 36;
    private static final int UPLOADS_PER_FRAME = 4;
    private static final int MIN_DELAY_MS = 20;

    private record Decoded(EmojiRef ref, NativeImage strip, int frames, int[] frameEndMs) {
    }

    private record Frames(int width, int height, List<int[]> abgr, int[] delayMs) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "wfchat-emoji");
        t.setDaemon(true);
        return t;
    });
    private final Queue<Decoded> ready = new ConcurrentLinkedQueue<>();

    private EmojiImages() {
    }

    public static EmojiImages get() {
        return INSTANCE;
    }

    ExecutorService executor() {
        return executor;
    }

    byte[] fetch(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "wfchat-emoji").GET().build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200 || res.body().length > MAX_BYTES) {
            throw new IOException("HTTP " + res.statusCode() + " " + url);
        }
        return res.body();
    }

    /** Starts loading if nobody has. Call from the render thread. */
    public void request(EmojiRef ref) {
        if (ref.state != EmojiRef.NONE) {
            return;
        }
        ref.state = EmojiRef.LOADING;
        executor.execute(() -> load(ref));
    }

    private void load(EmojiRef ref) {
        try {
            Path file = EmojiConfig.dir().resolve(ref.cacheKey);
            byte[] raw;
            boolean cached = Files.isRegularFile(file);
            if (cached) {
                raw = Files.readAllBytes(file);
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            } else if (ref.url != null) {
                raw = fetch(ref.url);
            } else {
                throw new IOException("server emoji not in cache");
            }
            EmojiFile.Verified v = EmojiFile.verifyContent(ref.name, raw, ref.limits);
            if (!cached) {
                Files.createDirectories(file.getParent());
                Files.write(file, v.data());
            }
            Frames frames = switch (v.kind()) {
                case GIF -> decodeGif(v);
                case WEBP -> frames(WebP.decode(v.data(), Math.max(v.width(), v.height())));
                case PNG -> v.frames() > 1 ? frames(Apng.decode(v.data())) : decodePng(v);
            };
            ready.add(strip(ref, frames));
        } catch (IOException | RuntimeException | InterruptedException | EmojiFile.Rejected | Invalid ex) {
            WfChat.LOGGER.debug("emoji {} failed: {}", ref.name, ex.toString());
            ref.state = EmojiRef.FAILED;
        }
    }

    private static Frames frames(Anim anim) {
        List<int[]> abgr = new ArrayList<>(anim.argb().size());
        int[] delays = new int[anim.argb().size()];
        for (int i = 0; i < delays.length; i++) {
            int[] argb = anim.argb().get(i);
            int[] out = new int[argb.length];
            for (int p = 0; p < argb.length; p++) {
                int c = argb[p];
                out[p] = c & 0xFF00FF00 | (c & 0xFF) << 16 | c >> 16 & 0xFF;
            }
            abgr.add(out);
            delays[i] = Math.max(MIN_DELAY_MS, anim.delayMs()[i]);
        }
        return new Frames(anim.width(), anim.height(), abgr, delays);
    }

    private static Frames decodePng(EmojiFile.Verified v) throws IOException {
        try (NativeImage image = NativeImage.read(new ByteArrayInputStream(v.data()))) {
            int w = image.getWidth();
            int h = image.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = Pixels.get(image, x, y);
                }
            }
            return new Frames(w, h, List.of(px), new int[]{MIN_DELAY_MS});
        }
    }

    /** Composites frames onto a persistent canvas, honouring offsets and disposal. */
    private static Frames decodeGif(EmojiFile.Verified v) throws IOException {
        int w = v.width();
        int h = v.height();
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(v.data()))) {
            reader.setInput(in, false, false);
            int n = reader.getNumImages(true);
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            List<int[]> out = new ArrayList<>(n);
            int[] delays = new int[n];
            for (int i = 0; i < n; i++) {
                BufferedImage frame = reader.read(i);
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i)
                        .getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode desc = child(root, "ImageDescriptor");
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                int left = desc == null ? 0 : Integer.parseInt(desc.getAttribute("imageLeftPosition"));
                int top = desc == null ? 0 : Integer.parseInt(desc.getAttribute("imageTopPosition"));
                String disposal = gce == null ? "none" : gce.getAttribute("disposalMethod");
                delays[i] = Math.max(MIN_DELAY_MS, (gce == null ? 0 : Integer.parseInt(gce.getAttribute("delayTime"))) * 10);
                int[] saved = disposal.equals("restoreToPrevious") ? canvas.getRGB(0, 0, w, h, null, 0, w) : null;
                var g = canvas.createGraphics();
                g.drawImage(frame, left, top, null);
                g.dispose();
                int[] argb = canvas.getRGB(0, 0, w, h, null, 0, w);
                int[] abgr = new int[argb.length];
                for (int p = 0; p < argb.length; p++) {
                    int c = argb[p];
                    abgr[p] = c & 0xFF00FF00 | (c & 0xFF) << 16 | c >> 16 & 0xFF;
                }
                out.add(abgr);
                if (saved != null) {
                    canvas.setRGB(0, 0, w, h, saved, 0, w);
                } else if (disposal.equals("restoreToBackgroundColor")) {
                    for (int y = top; y < Math.min(h, top + frame.getHeight()); y++) {
                        for (int x = left; x < Math.min(w, left + frame.getWidth()); x++) {
                            canvas.setRGB(x, y, 0);
                        }
                    }
                }
            }
            return new Frames(w, h, out, delays);
        } finally {
            reader.dispose();
        }
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        NodeList list = root.getElementsByTagName(name);
        return list.getLength() == 0 ? null : (IIOMetadataNode) list.item(0);
    }

    /** Box-filters every frame to a square of at most {@link #TARGET} px and stacks them vertically. */
    private static Decoded strip(EmojiRef ref, Frames f) {
        int size = Math.min(TARGET, Math.max(f.width, f.height));
        int n = f.abgr.size();
        NativeImage strip = new NativeImage(size, size * n, false);
        int[] ends = new int[n];
        int t = 0;
        for (int i = 0; i < n; i++) {
            scale(f.abgr.get(i), f.width, f.height, strip, size, i * size);
            t += f.delayMs[i];
            ends[i] = t;
        }
        return new Decoded(ref, strip, n, ends);
    }

    private static void scale(int[] src, int sw, int sh, NativeImage dst, int size, int yOffset) {
        for (int y = 0; y < size; y++) {
            int y0 = y * sh / size;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / size);
            for (int x = 0; x < size; x++) {
                int x0 = x * sw / size;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / size);
                long a = 0, b = 0, g = 0, r = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int p = src[sy * sw + sx];
                        int pa = p >>> 24;
                        a += pa;
                        b += (p >>> 16 & 0xFF) * pa;
                        g += (p >>> 8 & 0xFF) * pa;
                        r += (p & 0xFF) * pa;
                    }
                }
                int n = (y1 - y0) * (x1 - x0);
                int oa = (int) (a / n);
                Pixels.set(dst, x, yOffset + y, a == 0 ? 0
                        : oa << 24 | (int) (b / a) << 16 | (int) (g / a) << 8 | (int) (r / a));
            }
        }
    }

    /** Render thread, once per frame. */
    public void pump() {
        for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
            Decoded d = ready.poll();
            if (d == null) {
                return;
            }
            ResourceLocation rl = location(d.ref.cacheKey);
            Minecraft.getInstance().getTextureManager().register(rl, Textures.create(d.strip));
            d.ref.texture = rl;
            d.ref.frames = d.frames;
            d.ref.frameEndMs = d.frames > 1 ? d.frameEndMs : null;
            d.ref.state = EmojiRef.READY;
        }
    }

    private static ResourceLocation location(String file) {
        String path = "emoji/" + file.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        //? if <1.21 {
        /*return new ResourceLocation(WfChat.MOD_ID, path);
        *///?} else {
        return ResourceLocation.fromNamespaceAndPath(WfChat.MOD_ID, path);
        //?}
    }
}
