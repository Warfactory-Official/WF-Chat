package com.wf.wfchat.core.webp;

/** Malformed or unsupported WebP data. */
public final class Invalid extends Exception {
    Invalid(String message) {
        super(message);
    }
}
