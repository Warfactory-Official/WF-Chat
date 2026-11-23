package com.wf.wfchat.api;

@FunctionalInterface
public interface Classifier {
    void classify(IncomingMessage message, ChannelSink sink);
}
