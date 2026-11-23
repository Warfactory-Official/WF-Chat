package com.wf.wfchat.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wf.wfchat.WfChat;
import com.wf.wfchat.core.GlyphTable;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Reads {@code assets/<ns>/wfchat/glyphs.json} from every pack:
 * {@code {"glyphs": {"name": {"font": "ns:fontid", "char": ""}}}}; the font itself is an ordinary
 * font definition in the same pack.
 */
public class GlyphResources implements ResourceManagerReloadListener {
    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        GlyphTable.clearResources();
        for (String namespace : manager.getNamespaces()) {
            for (Resource resource : manager.getResourceStack(location(namespace, "wfchat/glyphs.json"))) {
                try (Reader reader = resource.openAsReader()) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("glyphs").entrySet()) {
                        JsonObject g = e.getValue().getAsJsonObject();
                        String ch = g.get("char").getAsString();
                        if (ch.length() != 1) {
                            throw new IllegalArgumentException("glyph '" + e.getKey() + "' char must be one UTF-16 unit");
                        }
                        GlyphTable.putResource(e.getKey(), g.get("font").getAsString(), ch.charAt(0));
                    }
                } catch (IOException | RuntimeException ex) {
                    WfChat.LOGGER.warn("{}:wfchat/glyphs.json unreadable: {}", namespace, ex.toString());
                }
            }
        }
    }

    private static ResourceLocation location(String namespace, String path) {
        //? if <1.21 {
        /*return new ResourceLocation(namespace, path);
        *///?} else {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
        //?}
    }
}
