package com.matching.agent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Single-producer single-consumer lock-free ring buffer.
 * Pre-allocates TraceEvent slots to eliminate per-event allocation.
 * Lossy: drops events when full rather than blocking the producer.
 *
 * Uses VarHandle with release/acquire semantics instead of volatile,
 * which is cheaper on ARM (Apple Silicon) where volatile implies
 * a full memory fence.
 */
public class SpscRingBuffer {

    private static final VarHandle TAIL;
    private static final VarHandle HEAD;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            TAIL = l.findVarHandle(SpscRingBuffer.class, "tail", long.class);
            HEAD = l.findVarHandle(SpscRingBuffer.class, "head", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final TraceEvent[] slots;
    private final int mask;

    // --- Producer fields (engine thread) ---
    private long tail = 0;
    private long writeCursor = 0;   // producer-private; advances on claim(), published on publish()
    private long cachedHead = 0;

    // Cache-line padding (64 bytes) to prevent false sharing between producer and consumer fields
    @SuppressWarnings("unused")
    private long p1, p2, p3, p4, p5, p6, p7, p8;

    // --- Consumer fields (drain thread) ---
    private long head = 0;
    private long cachedTail = 0;

    public SpscRingBuffer(int capacityPow2) {
        int capacity = 1 << capacityPow2;
        this.mask = capacity - 1;
        this.slots = new TraceEvent[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new TraceEvent();
        }
    }

    /** Claim a pre-allocated slot for writing. Returns null if full (lossy drop). */
    public TraceEvent claim() {
        long wc = writeCursor;
        if (wc - cachedHead >= slots.length) {
            cachedHead = (long) HEAD.getAcquire(this); // acquire read (cross-thread)
            if (wc - cachedHead >= slots.length) {
                return null; // full — drop event
            }
        }
        writeCursor = wc + 1;
        return slots[(int) (wc & mask)];
    }

    /** Make all claimed slots visible to the consumer in a single release write. */
    public void publish() {
        TAIL.setRelease(this, writeCursor); // release write — publishes entire batch
    }

    /** Read next event for consumer. Returns null if empty. */
    public TraceEvent poll() {
        long h = (long) HEAD.get(this); // plain read (consumer-local)
        if (h >= cachedTail) {
            cachedTail = (long) TAIL.getAcquire(this); // acquire read (cross-thread)
            if (h >= cachedTail) {
                return null; // empty
            }
        }
        return slots[(int) (h & mask)];
    }

    /** Release the consumed event and advance head. */
    public void release(TraceEvent event) {
        event.reset();
        HEAD.setRelease(this, (long) HEAD.get(this) + 1); // release write
    }

    /** Check if buffer is empty (safe from any thread). */
    public boolean isEmpty() {
        return (long) HEAD.getAcquire(this) >= (long) TAIL.getAcquire(this);
    }
}
