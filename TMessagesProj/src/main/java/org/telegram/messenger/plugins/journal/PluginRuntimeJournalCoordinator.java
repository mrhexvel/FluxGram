package org.telegram.messenger.plugins.journal;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public final class PluginRuntimeJournalCoordinator implements AutoCloseable {
    public enum State {
        NEW,
        STARTING,
        RUNNING,
        UNAVAILABLE,
        CLOSING,
        CLOSED
    }

    public enum RecordResult {
        ACCEPTED,
        DROPPED_BUSY,
        DROPPED_FULL,
        DROPPED_SEQUENCE_EXHAUSTED,
        QUEUED,
        UNAVAILABLE,
        CLOSED
    }

    public interface StoreFactory {
        PluginRuntimeJournalStore open(long processInstanceId) throws IOException;
    }

    public interface ProcessInstanceIdFactory {
        long create();
    }

    private final Object lock = new Object();
    private final StoreFactory storeFactory;
    private final ProcessInstanceIdFactory processInstanceIdFactory;
    private final ExecutorService executor;
    private final ArrayDeque<PluginRuntimeJournalInput> pendingStart = new ArrayDeque<>();

    private volatile State state = State.NEW;
    private long processInstanceId;
    private PluginRuntimeJournalStore store;
    private volatile PluginRuntimeJournalCollector collector;
    private PluginRuntimeJournalCollector.Batch inFlight;
    private boolean workerScheduled;
    private boolean flushRequested;
    private boolean bootstrapCleanupPending;
    private volatile boolean closeRequested;
    private long loadFailures;
    private long closeFailures;
    private long rejectedTasks;
    private long commitFailures;
    private long bootstrapRetryDelayMillis = 50;

    private static final int MAX_PENDING_START = 64;

    public PluginRuntimeJournalCoordinator(
            StoreFactory storeFactory,
            ProcessInstanceIdFactory processInstanceIdFactory,
            ExecutorService executor) {
        if (storeFactory == null || processInstanceIdFactory == null || executor == null) {
            throw new IllegalArgumentException("Invalid plugin runtime journal coordinator");
        }

        this.storeFactory = storeFactory;
        this.processInstanceIdFactory = processInstanceIdFactory;
        this.executor = executor;
    }

    public void start() {
        synchronized (lock) {
            startLocked();
        }
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public long loadFailures() {
        synchronized (lock) {
            return loadFailures;
        }
    }

    public long closeFailures() {
        synchronized (lock) {
            return closeFailures;
        }
    }

    public long rejectedTasks() {
        synchronized (lock) {
            return rejectedTasks;
        }
    }

    public long commitFailures() {
        synchronized (lock) {
            return commitFailures;
        }
    }

    public RecordResult record(PluginRuntimeJournalInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Plugin runtime journal input is required");
        }

        synchronized (lock) {
            if (state == State.CLOSING || state == State.CLOSED || closeRequested) {
                return RecordResult.CLOSED;
            }
            if (state != State.RUNNING || collector == null) {
                if (pendingStart.size() >= MAX_PENDING_START) {
                    return RecordResult.UNAVAILABLE;
                }

                pendingStart.addLast(input);
                startLocked();
                return RecordResult.QUEUED;
            }

            PluginRuntimeJournalCollector.OfferResult result = collector.offer(input);
            flushRequested = true;

            if (!workerScheduled) {
                scheduleLocked(this::flushWorker);
            }

            switch (result) {
                case ACCEPTED:
                    return RecordResult.ACCEPTED;
                case DROPPED_BUSY:
                    return RecordResult.DROPPED_BUSY;
                case DROPPED_FULL:
                    return RecordResult.DROPPED_FULL;
                case DROPPED_SEQUENCE_EXHAUSTED:
                    return RecordResult.DROPPED_SEQUENCE_EXHAUSTED;
                default:
                    return RecordResult.CLOSED;
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }

            closeRequested = true;
            state = State.CLOSING;

            if (!workerScheduled) {
                if (!pendingStart.isEmpty() && store == null) {
                    scheduleLocked(this::bootstrap);
                } else if (inFlight != null || flushRequested) {
                    flushRequested = true;
                    scheduleLocked(this::flushWorker);
                } else {
                    scheduleLocked(this::closeWorker);
                }
            }
        }
    }

    private void bootstrap() {
        PluginRuntimeJournalStore opened = null;

        try {
            long currentProcessInstanceId;

            synchronized (lock) {
                if (processInstanceId == 0) {
                    processInstanceId = processInstanceIdFactory.create();

                    if (processInstanceId <= 0) {
                        throw new IllegalStateException("Invalid plugin runtime journal process identity");
                    }
                }

                currentProcessInstanceId = processInstanceId;
            }

            opened = storeFactory.open(currentProcessInstanceId);
            PluginRuntimeJournalStore.LoadResult loaded = opened.load();

            if (loaded.sequenceExhausted || loaded.batchExhausted) {
                throw new IOException("Plugin runtime journal sequence is exhausted");
            }

            PluginRuntimeJournalCollector created = new PluginRuntimeJournalCollector(
                    currentProcessInstanceId,
                    loaded.nextSequence,
                    loaded.nextBatchId,
                    loaded.nextMonotonicTimeMillis);

            synchronized (lock) {
                store = opened;
                collector = created;
                opened = null;
                bootstrapRetryDelayMillis = 50;
                boolean queued = false;

                while (!pendingStart.isEmpty()) {
                    created.offer(pendingStart.removeFirst());
                    queued = true;
                }

                workerScheduled = false;

                if (queued) {
                    flushRequested = true;
                    state = closeRequested ? State.CLOSING : State.RUNNING;
                    scheduleLocked(this::flushWorker);
                } else if (closeRequested) {
                    state = State.CLOSING;
                    scheduleLocked(this::closeWorker);
                } else {
                    state = State.RUNNING;
                }
            }
        } catch (Throwable error) {
            closeOpened(opened);

            synchronized (lock) {
                loadFailures = saturatingIncrement(loadFailures);
                workerScheduled = false;

                if (store != null) {
                    state = State.CLOSING;
                    scheduleLocked(this::closeWorker);
                } else if (closeRequested && !pendingStart.isEmpty()) {
                    state = State.CLOSING;
                } else if (closeRequested) {
                    state = State.CLOSING;
                    scheduleLocked(this::closeWorker);
                } else if (!pendingStart.isEmpty()) {
                    state = State.STARTING;
                    scheduleLocked(this::retryBootstrap);
                } else {
                    state = State.UNAVAILABLE;
                }
            }
        }
    }

    private void closeOpened(PluginRuntimeJournalStore opened) {
        if (opened == null) {
            return;
        }

        try {
            opened.close();
        } catch (IOException error) {
            synchronized (lock) {
                store = opened;
                bootstrapCleanupPending = true;
                closeFailures = saturatingIncrement(closeFailures);
            }
        }
    }

    private void closeWorker() {
        PluginRuntimeJournalCollector currentCollector;
        PluginRuntimeJournalStore currentStore;

        synchronized (lock) {
            currentCollector = collector;
            currentStore = store;
        }

        if (currentCollector != null) {
            currentCollector.close();
        }

        try {
            if (currentStore != null) {
                currentStore.close();
            }
        } catch (IOException error) {
            synchronized (lock) {
                closeFailures = saturatingIncrement(closeFailures);
                workerScheduled = false;
                state = State.CLOSING;
            }
            return;
        }

        synchronized (lock) {
            collector = null;
            store = null;
            workerScheduled = false;

            if (bootstrapCleanupPending) {
                bootstrapCleanupPending = false;

                if (closeRequested) {
                    if (!pendingStart.isEmpty()) {
                        state = State.CLOSING;
                        return;
                    }
                } else {
                    if (pendingStart.isEmpty()) {
                        state = State.UNAVAILABLE;
                    } else {
                        state = State.STARTING;
                        scheduleLocked(this::retryBootstrap);
                    }
                    return;
                }
            }

            state = State.CLOSED;
        }

        executor.shutdown();
    }

    private void flushWorker() {
        int committedBatches = 0;
        int closingFailures = 0;
        long retryDelayMillis = 50;

        synchronized (lock) {
            flushRequested = false;
        }

        while (committedBatches < 4 || closeRequested) {
            if (inFlight == null) {
                PluginRuntimeJournalCollector currentCollector = collector;

                if (currentCollector == null) {
                    break;
                }

                inFlight = currentCollector.drain();

                if (inFlight.isEmpty()) {
                    inFlight = null;
                    break;
                }
            }

            try {
                store.commit(inFlight);
                inFlight = null;
                retryDelayMillis = 50;
                committedBatches++;
            } catch (IOException error) {
                synchronized (lock) {
                    commitFailures = saturatingIncrement(commitFailures);
                }

                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException interrupted) {
                    continue;
                }

                retryDelayMillis = Math.min(1000, retryDelayMillis * 2);

                if (closeRequested && ++closingFailures >= 2) {
                    synchronized (lock) {
                        flushRequested = true;
                        workerScheduled = false;
                        state = State.CLOSING;
                    }
                    return;
                }
            }

            synchronized (lock) {
                if (!closeRequested && committedBatches >= 4) {
                    flushRequested = true;
                    break;
                }
            }
        }

        synchronized (lock) {
            workerScheduled = false;

            if (closeRequested) {
                state = State.CLOSING;
                scheduleLocked(this::closeWorker);
            } else if (flushRequested) {
                flushRequested = true;
                scheduleLocked(this::flushWorker);
            }
        }
    }

    private void startLocked() {
        if (state != State.NEW && state != State.UNAVAILABLE) {
            return;
        }
        if (closeRequested) {
            return;
        }

        state = State.STARTING;
        scheduleLocked(this::bootstrap);
    }

    private void retryBootstrap() {
        long delay;

        synchronized (lock) {
            delay = bootstrapRetryDelayMillis;
            bootstrapRetryDelayMillis = Math.min(1000, bootstrapRetryDelayMillis * 2);
        }

        try {
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {
        }

        synchronized (lock) {
            if (closeRequested && pendingStart.isEmpty()) {
                workerScheduled = false;
                state = State.CLOSING;
                scheduleLocked(this::closeWorker);
                return;
            }
        }

        bootstrap();
    }

    private void scheduleLocked(Runnable operation) {
        if (workerScheduled) {
            return;
        }

        workerScheduled = true;

        try {
            executor.execute(() -> {
                try {
                    operation.run();
                } catch (Throwable error) {
                    synchronized (lock) {
                        workerScheduled = false;
                        loadFailures = saturatingIncrement(loadFailures);
                        state = closeRequested ? State.CLOSING : State.UNAVAILABLE;
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            workerScheduled = false;
            rejectedTasks = saturatingIncrement(rejectedTasks);
            state = closeRequested ? State.CLOSING : State.UNAVAILABLE;
        }
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }
}
