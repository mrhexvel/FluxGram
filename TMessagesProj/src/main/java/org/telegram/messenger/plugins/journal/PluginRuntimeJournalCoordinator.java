package org.telegram.messenger.plugins.journal;

import java.io.IOException;
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

    private State state = State.NEW;
    private long processInstanceId;
    private PluginRuntimeJournalStore store;
    private PluginRuntimeJournalCollector collector;
    private boolean workerScheduled;
    private boolean closeRequested;
    private long loadFailures;
    private long closeFailures;
    private long rejectedTasks;

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
            if (state != State.NEW && state != State.UNAVAILABLE) {
                return;
            }
            if (closeRequested) {
                return;
            }

            state = State.STARTING;
            scheduleLocked(this::bootstrap);
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

    @Override
    public void close() {
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }

            closeRequested = true;
            state = State.CLOSING;

            if (!workerScheduled) {
                scheduleLocked(this::closeWorker);
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
                workerScheduled = false;

                if (closeRequested) {
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
                } else if (closeRequested) {
                    state = State.CLOSING;
                    scheduleLocked(this::closeWorker);
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
            state = State.CLOSED;
        }

        executor.shutdown();
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
