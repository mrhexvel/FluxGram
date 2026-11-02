package org.telegram.messenger.plugins.reputation;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public final class PluginReputationCoordinatorCheck {
    private static final String AUTHORITY = "p256:" + repeat('a', 64);
    private static final PluginReputationQuery QUERY =
            new PluginReputationQuery(repeat('1', 64), null);

    public static void main(String[] args) {
        disabledDoesNotAllocate();
        cacheThenPersistedRefresh();
        identicalRequestsCoalesce();
        networkConcurrencyIsBounded();
        failureAndCancellationStayFailClosed();
        callbackOrderIsSerialized();
        reentrantLookupAndCloseDoNotStartDuplicateNetwork();
        closeSuppressesQueuedCallbacks();
        closeSuppressesQueuedNetwork();
        storeLoadRetriesAndCallbackErrorsReleaseSlots();
        publicLimitsAreExactAndCancellationReleasesCapacity();
        installPolicyWaitsAndBlocksOnlyAuthenticatedEvidence();
        startupPumpWaitsAndProcessesSequentially();
    }

    private static void disabledDoesNotAllocate() {
        List<PluginReputationCoordinator.Result> results = new ArrayList<>();
        PluginReputationCoordinator coordinator = PluginReputationCoordinator.disabled();

        coordinator.lookup(QUERY, Runnable::run, results::add);

        require(results.size() == 1);
        require(results.get(0).outcome
                == PluginReputationCoordinator.Outcome.CONFIGURATION_DISABLED);
        require(results.get(0).terminal);
    }

    private static void cacheThenPersistedRefresh() {
        FakeStore store = new FakeStore();
        FakeNetwork network = new FakeNetwork();
        List<PluginReputationCoordinator.Result> results = new ArrayList<>();
        PluginReputationCoordinator coordinator = coordinator(
                store,
                network,
                Runnable::run,
                Runnable::run);

        coordinator.lookup(QUERY, Runnable::run, results::add);

        require(results.size() == 2);
        require(results.get(0).stage == PluginReputationCoordinator.Stage.CACHE);
        require(!results.get(0).terminal);
        require(results.get(1).stage == PluginReputationCoordinator.Stage.REFRESH);
        require(results.get(1).outcome
                == PluginReputationCoordinator.Outcome.REFRESH_ACCEPTED);
        require(results.get(1).terminal);
        require(store.updates == 1);
        require(network.calls == 1);
    }

    private static void identicalRequestsCoalesce() {
        FakeStore store = new FakeStore();
        FakeNetwork network = new FakeNetwork();
        ManualExecutor networkExecutor = new ManualExecutor();
        List<PluginReputationCoordinator.Result> first = new ArrayList<>();
        List<PluginReputationCoordinator.Result> second = new ArrayList<>();
        PluginReputationCoordinator coordinator = coordinator(
                store,
                network,
                Runnable::run,
                networkExecutor);

        coordinator.lookup(QUERY, Runnable::run, first::add);
        coordinator.lookup(QUERY, Runnable::run, second::add);

        require(networkExecutor.size() == 1);
        require(first.size() == 1 && second.size() == 1);
        networkExecutor.runNext();
        require(network.calls == 1);
        require(first.size() == 2 && second.size() == 2);
    }

    private static void networkConcurrencyIsBounded() {
        FakeStore store = new FakeStore();
        FakeNetwork network = new FakeNetwork();
        ManualExecutor networkExecutor = new ManualExecutor();
        PluginReputationCoordinator coordinator = coordinator(
                store,
                network,
                Runnable::run,
                networkExecutor);

        coordinator.lookup(query('1'), Runnable::run, result -> { });
        coordinator.lookup(query('2'), Runnable::run, result -> { });
        coordinator.lookup(query('3'), Runnable::run, result -> { });

        require(networkExecutor.size() == 2);
        networkExecutor.runNext();
        require(networkExecutor.size() == 2);
        networkExecutor.runNext();
        networkExecutor.runNext();
        require(network.calls == 3);
    }

    private static void failureAndCancellationStayFailClosed() {
        FakeStore store = new FakeStore();
        FakeNetwork network = new FakeNetwork();
        network.failure = new SocketTimeoutException("timeout");
        List<PluginReputationCoordinator.Result> results = new ArrayList<>();
        PluginReputationCoordinator coordinator = coordinator(
                store,
                network,
                Runnable::run,
                Runnable::run);

        coordinator.lookup(QUERY, Runnable::run, results::add);
        require(results.get(1).outcome
                == PluginReputationCoordinator.Outcome.NETWORK_TIMEOUT);
        require(results.get(1).lookup.decision
                == PluginReputationPolicy.Decision.NO_ASSERTION);

        ManualExecutor networkExecutor = new ManualExecutor();
        List<PluginReputationCoordinator.Result> cancelled = new ArrayList<>();
        PluginReputationCoordinator delayed = coordinator(
                new FakeStore(),
                new FakeNetwork(),
                Runnable::run,
                networkExecutor);
        PluginReputationCoordinator.Request request = delayed.lookup(
                QUERY,
                Runnable::run,
                cancelled::add);
        request.cancel();
        networkExecutor.runNext();
        require(cancelled.size() == 1);
    }

    private static void callbackOrderIsSerialized() {
        FakeStore store = new FakeStore();
        FakeNetwork network = new FakeNetwork();
        ManualExecutor callbacks = new ManualExecutor();
        List<PluginReputationCoordinator.Stage> stages = new ArrayList<>();
        PluginReputationCoordinator coordinator = coordinator(
                store,
                network,
                Runnable::run,
                Runnable::run);

        coordinator.lookup(QUERY, callbacks, result -> stages.add(result.stage));

        require(callbacks.size() == 1);
        callbacks.runNext();
        require(callbacks.size() == 1);
        callbacks.runNext();
        require(stages.size() == 2);
        require(stages.get(0) == PluginReputationCoordinator.Stage.CACHE);
        require(stages.get(1) == PluginReputationCoordinator.Stage.REFRESH);
    }

    private static void reentrantLookupAndCloseDoNotStartDuplicateNetwork() {
        FakeStore store = new FakeStore();
        FakeNetwork network = new FakeNetwork();
        List<PluginReputationCoordinator.Result> nested = new ArrayList<>();
        PluginReputationCoordinator coordinator = coordinator(
                store,
                network,
                Runnable::run,
                Runnable::run);
        boolean[] repeated = {false};

        coordinator.lookup(QUERY, Runnable::run, result -> {
            if (result.stage == PluginReputationCoordinator.Stage.CACHE && !repeated[0]) {
                repeated[0] = true;
                coordinator.lookup(QUERY, Runnable::run, nested::add);
            }
        });

        require(network.calls == 1);
        require(nested.size() == 2);

        FakeNetwork closedNetwork = new FakeNetwork();
        PluginReputationCoordinator closing = coordinator(
                new FakeStore(),
                closedNetwork,
                Runnable::run,
                Runnable::run);
        closing.lookup(QUERY, Runnable::run, result -> {
            if (result.stage == PluginReputationCoordinator.Stage.CACHE) {
                closing.close();
            }
        });
        require(closedNetwork.calls == 0);
    }

    private static void closeSuppressesQueuedCallbacks() {
        ManualExecutor callbacks = new ManualExecutor();
        List<PluginReputationCoordinator.Result> results = new ArrayList<>();
        PluginReputationCoordinator coordinator = coordinator(
                new FakeStore(),
                new FakeNetwork(),
                Runnable::run,
                Runnable::run);

        coordinator.lookup(QUERY, callbacks, results::add);
        coordinator.close();

        while (callbacks.size() > 0) {
            callbacks.runNext();
        }
        require(results.isEmpty());
    }

    private static void closeSuppressesQueuedNetwork() {
        ManualExecutor networkExecutor = new ManualExecutor();
        FakeNetwork network = new FakeNetwork();
        PluginReputationCoordinator coordinator = coordinator(
                new FakeStore(),
                network,
                Runnable::run,
                networkExecutor);

        coordinator.lookup(QUERY, Runnable::run, result -> { });
        coordinator.close();
        networkExecutor.runNext();

        require(network.calls == 0);
    }

    private static void storeLoadRetriesAndCallbackErrorsReleaseSlots() {
        FakeStore store = new FakeStore();
        store.failedLoads = 1;
        PluginReputationCoordinator coordinator = coordinator(
                store,
                new FakeNetwork(),
                Runnable::run,
                Runnable::run);
        List<PluginReputationCoordinator.Result> failed = new ArrayList<>();
        List<PluginReputationCoordinator.Result> retried = new ArrayList<>();

        coordinator.lookup(QUERY, Runnable::run, failed::add);
        coordinator.lookup(QUERY, Runnable::run, retried::add);

        require(failed.size() == 1);
        require(failed.get(0).outcome
                == PluginReputationCoordinator.Outcome.STORAGE_UNAVAILABLE);
        require(retried.size() == 2);
        require(store.loads == 2);

        for (int i = 0; i < PluginReputationCoordinator.MAX_TOTAL_REQUESTS + 1; i++) {
            coordinator.lookup(query((char) ('a' + i % 6)), Runnable::run, result -> {
                throw new AssertionError();
            });
        }

        List<PluginReputationCoordinator.Result> afterErrors = new ArrayList<>();
        coordinator.lookup(query('f'), Runnable::run, afterErrors::add);
        require(afterErrors.size() == 2);
        require(afterErrors.get(1).outcome
                != PluginReputationCoordinator.Outcome.OVERLOADED);
    }

    private static void publicLimitsAreExactAndCancellationReleasesCapacity() {
        ManualExecutor networkExecutor = new ManualExecutor();
        PluginReputationCoordinator coordinator = coordinator(
                new FakeStore(),
                new FakeNetwork(),
                Runnable::run,
                networkExecutor);
        ArrayList<PluginReputationCoordinator.Request> requests = new ArrayList<>();

        for (int identity = 0; identity < PluginReputationCoordinator.MAX_OUTSTANDING_IDENTITIES;
                identity++) {
            PluginReputationQuery query = query(identity);

            for (int waiter = 0; waiter < 2; waiter++) {
                requests.add(coordinator.lookup(query, Runnable::run, result -> { }));
            }
        }

        List<PluginReputationCoordinator.Result> overloaded = new ArrayList<>();
        coordinator.lookup(query(100), Runnable::run, overloaded::add);
        require(overloaded.size() == 1);
        require(overloaded.get(0).outcome
                == PluginReputationCoordinator.Outcome.OVERLOADED);

        requests.get(0).cancel();
        List<PluginReputationCoordinator.Result> accepted = new ArrayList<>();
        coordinator.lookup(query(0), Runnable::run, accepted::add);
        require(accepted.size() == 1);
        require(accepted.get(0).outcome
                == PluginReputationCoordinator.Outcome.CACHE_STALE);

        ManualExecutor waiterNetwork = new ManualExecutor();
        PluginReputationCoordinator waiterCoordinator = coordinator(
                new FakeStore(),
                new FakeNetwork(),
                Runnable::run,
                waiterNetwork);

        for (int i = 0; i < PluginReputationCoordinator.MAX_REQUESTS_PER_IDENTITY; i++) {
            waiterCoordinator.lookup(QUERY, Runnable::run, result -> { });
        }

        List<PluginReputationCoordinator.Result> waiterOverload = new ArrayList<>();
        waiterCoordinator.lookup(QUERY, Runnable::run, waiterOverload::add);
        require(waiterOverload.size() == 2);
        require(waiterOverload.get(1).outcome
                == PluginReputationCoordinator.Outcome.OVERLOADED);
    }

    private static void installPolicyWaitsAndBlocksOnlyAuthenticatedEvidence() {
        PluginReputationCache.LookupResult authenticatedBlock =
                new PluginReputationCache.LookupResult(
                        PluginReputationPolicy.Decision.BLOCK,
                        true,
                        false,
                        true,
                        false,
                        java.util.Collections.singletonList(
                                PluginReputationPolicy.BLOCK_ARCHIVE));
        PluginReputationCoordinator.Result cached = new PluginReputationCoordinator.Result(
                QUERY,
                authenticatedBlock,
                PluginReputationCoordinator.Stage.CACHE,
                PluginReputationCoordinator.Outcome.CACHE_STALE,
                false);
        PluginReputationCoordinator.Result terminalBlock = new PluginReputationCoordinator.Result(
                QUERY,
                authenticatedBlock,
                PluginReputationCoordinator.Stage.REFRESH,
                PluginReputationCoordinator.Outcome.NETWORK_TIMEOUT,
                true);
        PluginReputationCoordinator.Result unavailable = new PluginReputationCoordinator.Result(
                QUERY,
                PluginReputationCache.unavailable(),
                PluginReputationCoordinator.Stage.REFRESH,
                PluginReputationCoordinator.Outcome.NETWORK_UNAVAILABLE,
                true);
        PluginReputationCoordinator.Result unauthenticatedBlock =
                new PluginReputationCoordinator.Result(
                        QUERY,
                        new PluginReputationCache.LookupResult(
                                PluginReputationPolicy.Decision.BLOCK,
                                false,
                                false,
                                false,
                                false,
                                java.util.Collections.singletonList(
                                        PluginReputationPolicy.RECORD_UNAVAILABLE)),
                        PluginReputationCoordinator.Stage.REFRESH,
                        PluginReputationCoordinator.Outcome.REMOTE_EVIDENCE_REJECTED,
                        true);

        require(PluginReputationInstallPolicy.evaluate(cached)
                == PluginReputationInstallPolicy.Decision.WAIT);
        require(PluginReputationInstallPolicy.evaluate(terminalBlock)
                == PluginReputationInstallPolicy.Decision.BLOCK);
        require(PluginReputationInstallPolicy.evaluate(unavailable)
                == PluginReputationInstallPolicy.Decision.ALLOW);
        require(PluginReputationInstallPolicy.evaluate(unauthenticatedBlock)
                == PluginReputationInstallPolicy.Decision.ALLOW);
    }

    private static void startupPumpWaitsAndProcessesSequentially() {
        ArrayDeque<PluginReputationCoordinator.Callback> callbacks = new ArrayDeque<>();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        int[] started = {0};
        int[] completed = {0};
        int[] drained = {0};
        PluginReputationStartupPump pump = new PluginReputationStartupPump(
                (query, callback) -> {
                    started[0]++;
                    callbacks.addLast(callback);
                    require(callbacks.size() == 1);
                    return new FakeRequest();
                },
                scheduler,
                100,
                () -> drained[0]++);

        for (int index = 0; index < 40; index++) {
            PluginReputationQuery query = query(index + 1);
            pump.add(new PluginReputationStartupPump.Task() {
                @Override
                public PluginReputationQuery prepare() {
                    return query;
                }

                @Override
                public void complete(PluginReputationCoordinator.Result result) {
                    require(result.terminal);
                    completed[0]++;
                }

                @Override
                public void failed(Exception error) {
                    throw new AssertionError(error);
                }
            });
        }

        pump.start();
        require(started[0] == 1);
        PluginReputationCoordinator.Callback first = callbacks.peekFirst();
        first.onResult(startupResult(query(1), false));
        require(started[0] == 1);
        require(completed[0] == 0);
        require(scheduler.activeCount() == 1);

        while (!callbacks.isEmpty()) {
            PluginReputationCoordinator.Callback callback = callbacks.removeFirst();
            callback.onResult(startupResult(query(completed[0] + 1), true));
        }

        require(started[0] == 40);
        require(completed[0] == 40);
        require(drained[0] == 1);

        int[] operationalAllow = {0};
        PluginReputationStartupPump failedLookup = new PluginReputationStartupPump(
                (query, callback) -> {
                    throw new IllegalStateException("unavailable");
                },
                new ManualStartupScheduler(),
                100,
                () -> { });
        failedLookup.add(new PluginReputationStartupPump.Task() {
            @Override
            public PluginReputationQuery prepare() {
                return QUERY;
            }

            @Override
            public void complete(PluginReputationCoordinator.Result result) {
                require(result == null);
                operationalAllow[0]++;
            }

            @Override
            public void failed(Exception error) {
                throw new AssertionError(error);
            }
        });
        failedLookup.start();
        require(operationalAllow[0] == 1);

        startupPumpTimeoutIsBounded();
        startupPumpAppliesTerminalDecisionOnce();
    }

    private static void startupPumpTimeoutIsBounded() {
        ArrayDeque<PluginReputationCoordinator.Callback> callbacks = new ArrayDeque<>();
        ArrayDeque<FakeRequest> requests = new ArrayDeque<>();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        ArrayList<PluginReputationCoordinator.Result> completed = new ArrayList<>();
        PluginReputationStartupPump pump = new PluginReputationStartupPump(
                (query, callback) -> {
                    FakeRequest request = new FakeRequest();
                    callbacks.addLast(callback);
                    requests.addLast(request);
                    return request;
                },
                scheduler,
                100,
                () -> { });

        for (int index = 0; index < 2; index++) {
            PluginReputationQuery query = query(index + 10);
            pump.add(recordingTask(query, completed));
        }

        pump.start();
        PluginReputationCoordinator.Callback late = callbacks.removeFirst();
        FakeRequest timedOut = requests.removeFirst();
        scheduler.runNext();
        require(timedOut.cancelled);
        require(completed.size() == 1 && completed.get(0) == null);
        require(callbacks.size() == 1);

        late.onResult(startupResult(query(10), true));
        require(completed.size() == 1);

        PluginReputationCoordinator.Callback terminal = callbacks.removeFirst();
        terminal.onResult(startupResult(query(11), true));
        require(completed.size() == 2 && completed.get(1) != null);
        require(scheduler.activeCount() == 0);
        scheduler.runAll();
        require(completed.size() == 2);
    }

    private static void startupPumpAppliesTerminalDecisionOnce() {
        ArrayDeque<PluginReputationCoordinator.Callback> callbacks = new ArrayDeque<>();
        ManualStartupScheduler scheduler = new ManualStartupScheduler();
        int[] blocked = {0};
        int[] allowed = {0};
        PluginReputationStartupPump pump = new PluginReputationStartupPump(
                (query, callback) -> {
                    callbacks.addLast(callback);
                    return new FakeRequest();
                },
                scheduler,
                100,
                () -> { });

        for (int index = 0; index < 2; index++) {
            PluginReputationQuery query = query(index + 20);
            pump.add(new PluginReputationStartupPump.Task() {
                @Override
                public PluginReputationQuery prepare() {
                    return query;
                }

                @Override
                public void complete(PluginReputationCoordinator.Result result) {
                    if (PluginReputationInstallPolicy.evaluate(result)
                            == PluginReputationInstallPolicy.Decision.BLOCK) {
                        blocked[0]++;
                    } else {
                        allowed[0]++;
                    }
                }

                @Override
                public void failed(Exception error) {
                    throw new AssertionError(error);
                }
            });
        }

        PluginReputationCache.LookupResult block = new PluginReputationCache.LookupResult(
                PluginReputationPolicy.Decision.BLOCK,
                true,
                false,
                true,
                false,
                java.util.Collections.singletonList(PluginReputationPolicy.BLOCK_ARCHIVE));
        pump.start();
        PluginReputationCoordinator.Callback first = callbacks.removeFirst();
        PluginReputationCoordinator.Result blockResult = new PluginReputationCoordinator.Result(
                query(20),
                block,
                PluginReputationCoordinator.Stage.REFRESH,
                PluginReputationCoordinator.Outcome.REFRESH_UNCHANGED,
                true);
        first.onResult(blockResult);
        first.onResult(blockResult);
        callbacks.removeFirst().onResult(startupResult(query(21), true));

        require(blocked[0] == 1);
        require(allowed[0] == 1);
        require(scheduler.activeCount() == 0);
    }

    private static PluginReputationStartupPump.Task recordingTask(
            PluginReputationQuery query,
            List<PluginReputationCoordinator.Result> completed) {
        return new PluginReputationStartupPump.Task() {
            @Override
            public PluginReputationQuery prepare() {
                return query;
            }

            @Override
            public void complete(PluginReputationCoordinator.Result result) {
                completed.add(result);
            }

            @Override
            public void failed(Exception error) {
                throw new AssertionError(error);
            }
        };
    }

    private static PluginReputationCoordinator.Result startupResult(
            PluginReputationQuery query,
            boolean terminal) {
        return new PluginReputationCoordinator.Result(
                query,
                PluginReputationCache.unavailable(),
                terminal
                        ? PluginReputationCoordinator.Stage.REFRESH
                        : PluginReputationCoordinator.Stage.CACHE,
                terminal
                        ? PluginReputationCoordinator.Outcome.NETWORK_UNAVAILABLE
                        : PluginReputationCoordinator.Outcome.CACHE_STALE,
                terminal);
    }

    private static PluginReputationCoordinator coordinator(
            FakeStore store,
            FakeNetwork network,
            Executor stateExecutor,
            Executor networkExecutor) {
        return new PluginReputationCoordinator(
                AUTHORITY,
                store,
                network,
                stateExecutor,
                networkExecutor,
                () -> 100);
    }

    private static PluginReputationQuery query(char value) {
        return new PluginReputationQuery(repeat(value, 64), null);
    }

    private static PluginReputationQuery query(int value) {
        return new PluginReputationQuery(String.format("%064x", value), null);
    }

    private static final class FakeStore
            implements PluginReputationCoordinator.StoreClient {
        private PluginReputationCoordinator.StoreState state = state();
        private PluginReputationCache.UpdateStatus status =
                PluginReputationCache.UpdateStatus.ACCEPTED;
        private int failedLoads;
        private int loads;
        private int updates;

        @Override
        public PluginReputationCoordinator.StoreState load(long nowMillis) throws IOException {
            loads++;
            if (failedLoads > 0) {
                failedLoads--;
                throw new IOException("temporary");
            }
            return state;
        }

        @Override
        public PluginReputationCoordinator.StoreUpdate update(
                PluginReputationCoordinator.StoreState expected,
                byte[] response,
                byte[] envelope,
                long nowMillis) {
            updates++;
            state = state();
            return new PluginReputationCoordinator.StoreUpdate(status, state);
        }

        private static PluginReputationCoordinator.StoreState state() {
            return new PluginReputationCoordinator.StoreState(
                    PluginReputationCache.empty(),
                    new Object());
        }
    }

    private static final class FakeNetwork
            implements PluginReputationCoordinator.NetworkClient {
        private IOException failure;
        private int calls;

        @Override
        public PluginReputationCoordinator.NetworkResult execute(
                PluginReputationQuery query,
                long nowMillis) throws IOException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new PluginReputationCoordinator.NetworkResult(
                    new byte[]{1},
                    new byte[]{2});
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            Runnable task = tasks.pollFirst();
            require(task != null);
            task.run();
        }
    }

    private static final class FakeRequest implements PluginReputationCoordinator.Request {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class ManualStartupScheduler
            implements PluginReputationStartupPump.Scheduler {
        private final ArrayDeque<ScheduledTask> tasks = new ArrayDeque<>();

        @Override
        public PluginReputationStartupPump.Deadline schedule(
                Runnable task,
                long delayMillis) {
            require(delayMillis == 100);
            ScheduledTask scheduled = new ScheduledTask(task);
            tasks.addLast(scheduled);
            return () -> scheduled.cancelled = true;
        }

        private int activeCount() {
            int count = 0;

            for (ScheduledTask task : tasks) {
                if (!task.cancelled) {
                    count++;
                }
            }

            return count;
        }

        private void runNext() {
            ScheduledTask task;

            do {
                task = tasks.pollFirst();
                require(task != null);
            } while (task.cancelled);

            task.runnable.run();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                ScheduledTask task = tasks.pollFirst();

                if (!task.cancelled) {
                    task.runnable.run();
                }
            }
        }
    }

    private static final class ScheduledTask {
        private final Runnable runnable;
        private boolean cancelled;

        private ScheduledTask(Runnable runnable) {
            this.runnable = runnable;
        }
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }
}
