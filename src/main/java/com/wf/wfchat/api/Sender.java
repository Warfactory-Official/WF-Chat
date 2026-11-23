package com.wf.wfchat.api;

/** Turns text typed into a channel into the line actually sent to the server. */
@FunctionalInterface
public interface Sender {
    /** Plain chat: typed text goes out unchanged. */
    Sender CHAT = (text, server) -> text;

    /**
     * @param text   what the player typed, never starting with '/' (commands bypass the sender)
     * @param server capabilities of the connected server
     * @return the line to send; a leading '/' sends it as a command
     */
    String wrap(String text, ServerInfo server);

    /** Sends {@code "/" + command + " " + text}. */
    static Sender command(String command) {
        String prefix = "/" + command + " ";
        return (text, server) -> prefix + text;
    }

    /** {@code withServer} when the server runs WF Chat, else {@code fallback}. */
    static Sender ifServerMod(Sender withServer, Sender fallback) {
        return (text, server) -> (server.hasServerMod() ? withServer : fallback).wrap(text, server);
    }
}
