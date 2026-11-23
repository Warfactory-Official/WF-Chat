package com.wf.wfchat.api;

public interface ChatRegistry {
    /** Adds a permanent channel. Re-registering an id replaces it. */
    void channel(ChannelSpec spec);

    /** Runs before the built-in system/global/local classifiers. */
    void classifier(Classifier classifier);

    void channelCommand(ChannelCommand command);

    void vanity(VanityProvider provider);

    /**
     * Maps a shortcode to a font glyph. {@code font} is a font definition id
     * ({@code namespace:path}), {@code glyph} the character it defines.
     */
    void glyph(String name, String font, char glyph);
}
