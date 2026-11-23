package com.wf.wfchat.core;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.world.level.ChunkPos;

public final class Compat {
    private Compat() {
    }

    public static int cx(ChunkPos p) {
        //? if >=26.1 {
        /*return p.x();
        *///?} else {
        return p.x;
        //?}
    }

    public static int cz(ChunkPos p) {
        //? if >=26.1 {
        /*return p.z();
        *///?} else {
        return p.z;
        //?}
    }

    public static UUID id(GameProfile p) {
        //? if >=26.1 {
        /*return p.id();
        *///?} else {
        return p.getId();
        //?}
    }

    public static String name(GameProfile p) {
        //? if >=26.1 {
        /*return p.name();
        *///?} else {
        return p.getName();
        //?}
    }
}
