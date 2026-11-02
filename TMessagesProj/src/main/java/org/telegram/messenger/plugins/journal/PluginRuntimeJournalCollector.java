package org.telegram.messenger.plugins.journal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class PluginRuntimeJournalCollector implements AutoCloseable {
    public static final int MAX_PENDING_EVENTS = 256;
    public static final int MAX_BATCH_EVENTS = 64;

    public enum OfferResult {
        ACCEPTED,
        DROPPED_BUSY,
        DROPPED_FULL,
        DROPPED_SEQUENCE_EXHAUSTED,
        REJECTED_CLOSED
    }

    public static final class Batch {
        public final long processInstanceId;
        public final long batchId;
        public final List<PluginRuntimeJournalEvent> events;
        public final long droppedEvents;

        private Batch(
                long processInstanceId,
                long batchId,
                List<PluginRuntimeJournalEvent> events,
                long droppedEvents) {
            this.processInstanceId = processInstanceId;
            this.batchId = batchId;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
            this.droppedEvents = droppedEvents;
        }

        public boolean isEmpty() {
            return events.isEmpty() && droppedEvents == 0;
        }
    }

    private final long processInstanceId;
    private final long startedNanos;
    private final long initialMonotonicTimeMillis;
    private final ReentrantLock queueLock = new ReentrantLock();
    private final ReentrantLock drainLock = new ReentrantLock();
    private final ArrayDeque<PluginRuntimeJournalEvent> pending = new ArrayDeque<>();
    private final AtomicLong droppedEvents = new AtomicLong();

    private long nextSequence;
    private long nextBatchId;
    private long lastMonotonicTimeMillis;
    private boolean sequenceExhausted;
    private boolean batchExhausted;
    private boolean closed;
    private Thread consumerThread;

    public PluginRuntimeJournalCollector(long processInstanceId, long nextSequence) {
        this(processInstanceId, nextSequence, 1, 0);
    }

    public PluginRuntimeJournalCollector(
            long processInstanceId,
            long nextSequence,
            long nextBatchId) {
        this(processInstanceId, nextSequence, nextBatchId, 0);
    }

    public PluginRuntimeJournalCollector(
            long processInstanceId,
            long nextSequence,
            long nextBatchId,
            long initialMonotonicTimeMillis) {
        if (processInstanceId <= 0
                || nextSequence <= 0
                || nextBatchId <= 0
                || initialMonotonicTimeMillis < 0) {
            throw new IllegalArgumentException("Invalid plugin runtime journal collector");
        }

        this.processInstanceId = processInstanceId;
        this.nextSequence = nextSequence;
        this.nextBatchId = nextBatchId;
        this.initialMonotonicTimeMillis = initialMonotonicTimeMillis;
        lastMonotonicTimeMillis = initialMonotonicTimeMillis;
        startedNanos = System.nanoTime();
    }

    public OfferResult offer(PluginRuntimeJournalInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Plugin runtime journal input is required");
        }
        if (!queueLock.tryLock()) {
            increment(droppedEvents);
            return OfferResult.DROPPED_BUSY;
        }

        try {
            if (closed) {
                return OfferResult.REJECTED_CLOSED;
            }
            if (pending.size() == MAX_PENDING_EVENTS) {
                increment(droppedEvents);
                return OfferResult.DROPPED_FULL;
            }
            if (sequenceExhausted) {
                increment(droppedEvents);
                return OfferResult.DROPPED_SEQUENCE_EXHAUSTED;
            }

            long elapsedNanos = System.nanoTime() - startedNanos;
            long elapsedMillis = elapsedNanos < 0 ? 0 : elapsedNanos / 1_000_000L;
            long monotonicTimeMillis = Long.MAX_VALUE - initialMonotonicTimeMillis < elapsedMillis
                    ? Long.MAX_VALUE
                    : initialMonotonicTimeMillis + elapsedMillis;

            if (monotonicTimeMillis < lastMonotonicTimeMillis) {
                monotonicTimeMillis = lastMonotonicTimeMillis;
            }

            pending.addLast(input.event(nextSequence, monotonicTimeMillis, processInstanceId));
            lastMonotonicTimeMillis = monotonicTimeMillis;

            if (nextSequence == Long.MAX_VALUE) {
                sequenceExhausted = true;
            } else {
                nextSequence++;
            }

            return OfferResult.ACCEPTED;
        } finally {
            queueLock.unlock();
        }
    }

    public Batch drain() {
        if (!drainLock.tryLock()) {
            throw new IllegalStateException("Plugin runtime journal drain is already active");
        }

        try {
            if (consumerThread == null) {
                consumerThread = Thread.currentThread();
            } else if (consumerThread != Thread.currentThread()) {
                throw new IllegalStateException("Plugin runtime journal consumer changed");
            }

            queueLock.lock();

            try {
                if (closed) {
                    return new Batch(processInstanceId, 0, Collections.emptyList(), 0);
                }

                int count = Math.min(MAX_BATCH_EVENTS, pending.size());
                long dropped = droppedEvents.get();

                if (count == 0 && dropped == 0) {
                    return new Batch(processInstanceId, 0, Collections.emptyList(), 0);
                }
                if (batchExhausted) {
                    throw new IllegalStateException("Plugin runtime journal batch sequence exhausted");
                }

                ArrayList<PluginRuntimeJournalEvent> events = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    events.add(pending.removeFirst());
                }

                long batchId = nextBatchId;

                if (nextBatchId == Long.MAX_VALUE) {
                    batchExhausted = true;
                } else {
                    nextBatchId++;
                }

                return new Batch(processInstanceId, batchId, events, droppedEvents.getAndSet(0));
            } finally {
                queueLock.unlock();
            }
        } finally {
            drainLock.unlock();
        }
    }

    public int pendingCount() {
        queueLock.lock();

        try {
            return pending.size();
        } finally {
            queueLock.unlock();
        }
    }

    public boolean isClosed() {
        queueLock.lock();

        try {
            return closed;
        } finally {
            queueLock.unlock();
        }
    }

    @Override
    public void close() {
        queueLock.lock();

        try {
            closed = true;
            pending.clear();
            droppedEvents.set(0);
            consumerThread = null;
        } finally {
            queueLock.unlock();
        }
    }

    private static void increment(AtomicLong value) {
        value.getAndUpdate(current -> current == Long.MAX_VALUE ? current : current + 1);
    }
}
