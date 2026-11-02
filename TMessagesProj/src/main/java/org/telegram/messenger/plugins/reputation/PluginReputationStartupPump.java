package org.telegram.messenger.plugins.reputation;

import java.util.ArrayDeque;

public final class PluginReputationStartupPump {
    public interface Lookup {
        PluginReputationCoordinator.Request start(
                PluginReputationQuery query,
                PluginReputationCoordinator.Callback callback);
    }

    public interface Scheduler {
        Deadline schedule(Runnable task, long delayMillis);
    }

    public interface Deadline {
        void cancel();
    }

    public interface Task {
        PluginReputationQuery prepare() throws Exception;
        void complete(PluginReputationCoordinator.Result result) throws Exception;
        void failed(Exception error);
    }

    private final Lookup lookup;
    private final Scheduler scheduler;
    private final long timeoutMillis;
    private final Runnable drained;
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private Task active;
    private PluginReputationCoordinator.Request activeRequest;
    private Deadline activeDeadline;
    private long sequence;
    private boolean started;

    public PluginReputationStartupPump(
            Lookup lookup,
            Scheduler scheduler,
            long timeoutMillis,
            Runnable drained) {
        if (lookup == null || scheduler == null || timeoutMillis <= 0 || drained == null) {
            throw new IllegalArgumentException("Startup reputation pump dependencies are required");
        }

        this.lookup = lookup;
        this.scheduler = scheduler;
        this.timeoutMillis = timeoutMillis;
        this.drained = drained;
    }

    public void add(Task task) {
        if (started || task == null) {
            throw new IllegalStateException("Startup reputation tasks are already running");
        }

        tasks.add(task);
    }

    public void start() {
        if (started) {
            throw new IllegalStateException("Startup reputation pump already started");
        }

        started = true;
        advance();
    }

    private void advance() {
        active = tasks.poll();

        if (active == null) {
            drained.run();
            return;
        }

        Task task = active;
        PluginReputationQuery query;

        try {
            query = task.prepare();
        } catch (Exception error) {
            fail(task, error);
            return;
        }

        if (query == null) {
            finish(task, null);
            return;
        }

        long requestSequence = ++sequence;

        try {
            PluginReputationCoordinator.Request request = lookup.start(query, result -> {
                if (active != task
                        || requestSequence != sequence
                        || result == null
                        || !query.equals(result.query)
                        || !result.terminal) {
                    return;
                }

                finish(task, result);
            });

            if (request == null) {
                throw new IllegalStateException("Startup reputation lookup returned no request");
            }
            if (active != task || requestSequence != sequence) {
                request.cancel();
                return;
            }

            activeRequest = request;
            activeDeadline = scheduler.schedule(
                    () -> timeout(task, requestSequence, request),
                    timeoutMillis);
        } catch (Exception error) {
            if (active == task && activeRequest != null) {
                activeRequest.cancel();
            }
            finish(task, null);
        }
    }

    private void timeout(
            Task task,
            long requestSequence,
            PluginReputationCoordinator.Request request) {
        if (active != task || requestSequence != sequence || activeRequest != request) {
            return;
        }

        activeDeadline = null;
        activeRequest = null;
        request.cancel();
        finish(task, null);
    }

    private void finish(Task task, PluginReputationCoordinator.Result result) {
        if (active != task) {
            return;
        }

        clearActiveLookup();
        active = null;

        try {
            task.complete(result);
        } catch (Exception error) {
            task.failed(error);
        }

        advance();
    }

    private void fail(Task task, Exception error) {
        if (active != task) {
            return;
        }

        clearActiveLookup();
        active = null;
        task.failed(error);
        advance();
    }

    private void clearActiveLookup() {
        if (activeDeadline != null) {
            activeDeadline.cancel();
            activeDeadline = null;
        }

        activeRequest = null;
    }
}
