package com.wf.wfchat.api;

import com.wf.wfchat.core.Registry;

public final class WfChatApi {
    private WfChatApi() {
    }

    /** Safe from any mod's init, on either side. Registration order = classifier priority. */
    public static void register(WfChatPlugin plugin) {
        Registry.get().register(plugin);
    }
}
