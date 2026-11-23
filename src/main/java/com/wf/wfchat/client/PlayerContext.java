package com.wf.wfchat.client;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Sender of the player chat message currently being delivered by the vanilla chat listener. */
public record PlayerContext(UUID id, String name, @Nullable String body) {
}
