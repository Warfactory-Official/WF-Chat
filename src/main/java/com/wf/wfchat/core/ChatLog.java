package com.wf.wfchat.core;

/**
 * Single ring of every received message. A message carries all channels it belongs to, so a view over
 * several channels lists it once. Not thread safe: render thread only.
 */
public final class ChatLog<M> {
    public static final class Entry<M> {
        public final long seq;
        public final long tick;
        public final String[] channels;
        public final M payload;

        Entry(long seq, long tick, String[] channels, M payload) {
            this.seq = seq;
            this.tick = tick;
            this.channels = channels;
            this.payload = payload;
        }

        public boolean in(String channel) {
            for (String c : channels) {
                if (c.equals(channel)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Entry<M>[] ring;
    private long next;

    @SuppressWarnings("unchecked")
    public ChatLog(int capacity) {
        ring = (Entry<M>[]) new Entry[capacity];
    }

    /** @param channels ordered most specific first; the first one selected in a view names the origin */
    public Entry<M> append(String[] channels, long tick, M payload) {
        Entry<M> e = new Entry<>(next, tick, channels, payload);
        ring[(int) (next % ring.length)] = e;
        next++;
        return e;
    }

    public long nextSeq() {
        return next;
    }

    public long firstSeq() {
        return Math.max(0, next - ring.length);
    }

    public Entry<M> get(long seq) {
        if (seq < firstSeq() || seq >= next) {
            return null;
        }
        return ring[(int) (seq % ring.length)];
    }

    public void clear() {
        java.util.Arrays.fill(ring, null);
        next = 0;
    }

    public int capacity() {
        return ring.length;
    }
}
