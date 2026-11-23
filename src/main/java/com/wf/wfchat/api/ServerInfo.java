package com.wf.wfchat.api;

public interface ServerInfo {
    /** True when the connected server runs WF Chat (detected from its command tree). */
    boolean hasServerMod();
}
