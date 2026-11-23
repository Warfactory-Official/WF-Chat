package com.wf.wfchat.client;

import com.wf.wfchat.api.IncomingMessage;
import com.wf.wfchat.core.Compat;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

/** Local = sender is us, or a tracked player standing in a chunk this client has loaded. */
final class Nearby {
    private Nearby() {
    }

    static boolean test(IncomingMessage m) {
        UUID id = m.senderId();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (id == null || level == null || mc.player == null) {
            return false;
        }
        if (mc.player.getUUID().equals(id)) {
            return true;
        }
        Player p = level.getPlayerByUUID(id);
        return p != null && level.getChunkSource().hasChunk(Compat.cx(p.chunkPosition()), Compat.cz(p.chunkPosition()));
    }
}
