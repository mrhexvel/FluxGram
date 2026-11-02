package org.telegram.messenger.plugins.reputation;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public final class PluginReputationCoordinator implements AutoCloseable {
    public enum Stage {
        CACHE,
        REFRESH
    }

    public enum Outcome {
        CACHE_FRESH,
        CACHE_STALE,
        REFRESH_ACCEPTED,
        REFRESH_UNCHANGED,
        CONFIGURATION_DISABLED,
        OVERLOADED,
        STORAGE_UNAVAILABLE,
        NETWORK_TIMEOUT,
        NETWORK_UNAVAILABLE,
        REMOTE_EVIDENCE_REJECTED,
        CLOSED
    }

    public static final class Result {
        public final PluginReputationQuery query;
        public final PluginReputationCache.LookupResult lookup;
        public final Stage stage;
        public final Outcome outcome;
        public final boolean terminal;

        Result(
                PluginReputationQuery query,
                PluginReputationCache.LookupResult lookup,
                Stage stage,
                Outcome outcome,
                boolean terminal) {
            this.query = query;
            this.lookup = lookup;
            this.stage = stage;
            this.outcome = outcome;
            this.terminal = terminal;
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    public interface Request {
        void cancel();
        boolean isCancelled();
    }

    interface Clock {
        long nowMillis();
    }

    interface StoreClient {
        StoreState load(long nowMillis) throws IOException;
        StoreUpdate update(
                StoreState expected,
                byte[] response,
                byte[] envelope,
                long nowMillis) throws IOException;
    }

    interface NetworkClient {
        NetworkResult execute(PluginReputationQuery query, long nowMillis) throws IOException;
    }

    static final class StoreState {
        final PluginReputationCache cache;
        final Object token;

        StoreState(PluginReputationCache cache, Object token) {
            if (cache == null || token == null) {
                throw new IllegalArgumentException("Invalid plugin reputation store state");
            }
            this.cache = cache;
            this.token = token;
        }
    }

    static final class StoreUpdate {
        final PluginReputationCache.UpdateStatus status;
        final StoreState state;

        StoreUpdate(PluginReputationCache.UpdateStatus status, StoreState state) {
            if (status == null || state == null) {
                throw new IllegalArgumentException("Invalid plugin reputation store update");
            }
            this.status = status;
            this.state = state;
        }
    }

    static final class NetworkResult {
        final byte[] response;
        final byte[] envelope;

        NetworkResult(byte[] response, byte[] envelope) {
            if (response == null || envelope == null) {
                throw new IllegalArgumentException("Invalid plugin reputation network result");
            }
            this.response = response.clone();
            this.envelope = envelope.clone();
        }
    }

    public static final int MAX_OUTSTANDING_IDENTITIES = 32;
    public static final int MAX_TOTAL_REQUESTS = 64;
    public static final int MAX_REQUESTS_PER_IDENTITY = 8;
    public static final int MAX_CONCURRENT_NETWORK = 2;

    private final Object publicLock = new Object();
    private final String authorityKeyId;
    private final StoreClient store;
    private final NetworkClient network;
    private final Executor stateExecutor;
    private final Executor networkExecutor;
    private final Clock clock;
    private final boolean enabled;
    private final Map<PluginReputationQuery, Pending> pending = new HashMap<>();
    private final ArrayDeque<Pending> networkQueue = new ArrayDeque<>();
    private final Set<RequestImpl> activeRequests = new HashSet<>();

    private StoreState state;
    private boolean closed;
    private int acceptedRequests;
    private int activeNetwork;

    PluginReputationCoordinator(
            String authorityKeyId,
            StoreClient store,
            NetworkClient network,
            Executor stateExecutor,
            Executor networkExecutor,
            Clock clock) {
        if (!PluginReputationResponse.isKeyId(authorityKeyId)
                || store == null
                || network == null
                || stateExecutor == null
                || networkExecutor == null
                || clock == null) {
            throw new IllegalArgumentException("Invalid plugin reputation coordinator input");
        }

        this.authorityKeyId = authorityKeyId;
        this.store = store;
        this.network = network;
        this.stateExecutor = stateExecutor;
        this.networkExecutor = networkExecutor;
        this.clock = clock;
        enabled = true;
    }

    private PluginReputationCoordinator() {
        authorityKeyId = null;
        store = null;
        network = null;
        stateExecutor = null;
        networkExecutor = null;
        clock = System::currentTimeMillis;
        enabled = false;
    }

    public static PluginReputationCoordinator disabled() {
        return new PluginReputationCoordinator();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Request lookup(
            PluginReputationQuery query,
            Executor callbackExecutor,
            Callback callback) {
        if (query == null || callbackExecutor == null || callback == null) {
            throw new IllegalArgumentException("Invalid plugin reputation lookup");
        }

        RequestImpl request = new RequestImpl(query, callbackExecutor, callback);

        if (!enabled) {
            request.deliver(result(
                    query,
                    PluginReputationCache.unavailable(),
                    Stage.CACHE,
                    Outcome.CONFIGURATION_DISABLED,
                    true));
            return request;
        }

        synchronized (publicLock) {
            if (closed) {
                request.deliver(result(
                        query,
                        PluginReputationCache.unavailable(),
                        Stage.CACHE,
                        Outcome.CLOSED,
                        true));
                return request;
            }
            if (acceptedRequests >= MAX_TOTAL_REQUESTS) {
                request.deliver(result(
                        query,
                        PluginReputationCache.unavailable(),
                        Stage.CACHE,
                        Outcome.OVERLOADED,
                        true));
                return request;
            }
            acceptedRequests++;
            request.reserved = true;
            activeRequests.add(request);
        }

        try {
            stateExecutor.execute(() -> begin(request));
        } catch (RejectedExecutionException error) {
            request.deliver(result(
                    query,
                    PluginReputationCache.unavailable(),
                    Stage.CACHE,
                    Outcome.CLOSED,
                    true));
        }
        return request;
    }

    private void begin(RequestImpl request) {
        if (request.isCancelled()) {
            return;
        }
        if (isClosed()) {
            request.deliver(result(
                    request.query,
                    PluginReputationCache.unavailable(),
                    Stage.CACHE,
                    Outcome.CLOSED,
                    true));
            return;
        }

        if (state == null) {
            try {
                state = store.load(now());
            } catch (IOException | RuntimeException error) {
                state = null;
            }
        }
        if (state == null) {
            request.deliver(result(
                    request.query,
                    PluginReputationCache.unavailable(),
                    Stage.CACHE,
                    Outcome.STORAGE_UNAVAILABLE,
                    true));
            return;
        }

        long nowMillis = now();
        PluginReputationCache.LookupResult cached = lookup(state, request.query, nowMillis);

        if (cached.authenticated && cached.fresh) {
            request.deliver(result(
                    request.query,
                    cached,
                    Stage.CACHE,
                    Outcome.CACHE_FRESH,
                    true));
            return;
        }

        Pending existing = pending.get(request.query);
        Pending target = existing;
        boolean created = false;
        boolean overloaded = false;

        if (existing != null) {
            removeCancelled(existing);
            if (existing.waiters.size() >= MAX_REQUESTS_PER_IDENTITY) {
                overloaded = true;
            } else {
                existing.waiters.add(request);
            }
        } else if (pending.size() >= MAX_OUTSTANDING_IDENTITIES) {
            overloaded = true;
        } else {
            target = new Pending(request.query);
            target.waiters.add(request);
            pending.put(request.query, target);
            created = true;
        }

        request.deliver(result(
                request.query,
                cached,
                Stage.CACHE,
                Outcome.CACHE_STALE,
                false));

        if (overloaded) {
            request.deliver(result(
                    request.query,
                    cached,
                    Stage.REFRESH,
                    Outcome.OVERLOADED,
                    true));
            return;
        }
        if (request.isCancelled() || isClosed()) {
            if (created && target != null && removeCancelled(target)) {
                pending.remove(target.query);
            }
            return;
        }

        if (created) {
            networkQueue.addLast(target);
            startNetwork();
        }
    }

    private void startNetwork() {
        while (activeNetwork < MAX_CONCURRENT_NETWORK && !networkQueue.isEmpty()) {
            Pending next = networkQueue.removeFirst();

            if (removeCancelled(next)) {
                pending.remove(next.query);
                continue;
            }

            next.started = true;
            activeNetwork++;
            long nowMillis = now();

            try {
                networkExecutor.execute(() -> executeNetwork(next, nowMillis));
            } catch (RejectedExecutionException error) {
                finishNetwork(next, null, error);
            }
        }
    }

    private void executeNetwork(Pending request, long nowMillis) {
        NetworkResult result = null;
        Throwable failure = null;

        if (!isClosed()) {
            try {
                result = network.execute(request.query, nowMillis);
            } catch (IOException | RuntimeException error) {
                failure = error;
            }
        }

        NetworkResult completed = result;
        Throwable completedFailure = failure;

        try {
            stateExecutor.execute(() -> finishNetwork(request, completed, completedFailure));
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void finishNetwork(Pending request, NetworkResult response, Throwable failure) {
        if (request.started) {
            activeNetwork--;
        }
        pending.remove(request.query);

        PluginReputationCache.LookupResult lookup = currentLookup(request.query);
        Outcome outcome;

        if (isClosed()) {
            outcome = Outcome.CLOSED;
        } else if (failure != null) {
            outcome = failure instanceof SocketTimeoutException
                    ? Outcome.NETWORK_TIMEOUT
                    : Outcome.NETWORK_UNAVAILABLE;
        } else {
            StoreUpdate update = persist(response);

            if (update == null) {
                lookup = currentLookup(request.query);
                outcome = Outcome.STORAGE_UNAVAILABLE;
            } else {
                state = update.state;
                lookup = currentLookup(request.query);
                outcome = map(update.status);
            }
        }

        for (RequestImpl waiter : request.waiters) {
            if (!waiter.isCancelled()) {
                waiter.deliver(result(
                        request.query,
                        lookup,
                        Stage.REFRESH,
                        outcome,
                        true));
            }
        }
        startNetwork();
    }

    private StoreUpdate persist(NetworkResult response) {
        if (response == null || state == null) {
            return null;
        }

        long nowMillis = now();

        try {
            return store.update(state, response.response, response.envelope, nowMillis);
        } catch (IOException firstError) {
            try {
                state = store.load(nowMillis);
                return store.update(state, response.response, response.envelope, nowMillis);
            } catch (IOException | RuntimeException secondError) {
                return null;
            }
        } catch (RuntimeException error) {
            return null;
        }
    }

    private PluginReputationCache.LookupResult currentLookup(PluginReputationQuery query) {
        if (state == null) {
            return PluginReputationCache.unavailable();
        }
        return lookup(state, query, now());
    }

    private PluginReputationCache.LookupResult lookup(
            StoreState source,
            PluginReputationQuery query,
            long nowMillis) {
        try {
            return source.cache.lookup(
                    query.archiveSha256,
                    query.developerKeyId,
                    authorityKeyId,
                    nowMillis);
        } catch (RuntimeException error) {
            return PluginReputationCache.unavailable();
        }
    }

    private Outcome map(PluginReputationCache.UpdateStatus status) {
        if (status == PluginReputationCache.UpdateStatus.ACCEPTED) {
            return Outcome.REFRESH_ACCEPTED;
        }
        if (status == PluginReputationCache.UpdateStatus.UNCHANGED) {
            return Outcome.REFRESH_UNCHANGED;
        }
        if (status == PluginReputationCache.UpdateStatus.REJECTED_CAPACITY) {
            return Outcome.STORAGE_UNAVAILABLE;
        }
        return Outcome.REMOTE_EVIDENCE_REJECTED;
    }

    private boolean removeCancelled(Pending request) {
        for (int i = request.waiters.size() - 1; i >= 0; i--) {
            if (request.waiters.get(i).isCancelled()) {
                request.waiters.remove(i);
            }
        }
        return request.waiters.isEmpty();
    }

    private long now() {
        return Math.max(0, clock.nowMillis());
    }

    private boolean isClosed() {
        synchronized (publicLock) {
            return closed;
        }
    }

    private static Result result(
            PluginReputationQuery query,
            PluginReputationCache.LookupResult lookup,
            Stage stage,
            Outcome outcome,
            boolean terminal) {
        return new Result(query, lookup, stage, outcome, terminal);
    }

    @Override
    public void close() {
        ArrayList<RequestImpl> requests;

        synchronized (publicLock) {
            if (closed) {
                return;
            }
            closed = true;
            requests = new ArrayList<>(activeRequests);
        }

        for (RequestImpl request : requests) {
            request.cancel();
        }

        if (!enabled) {
            return;
        }

        try {
            stateExecutor.execute(() -> {
                for (Pending request : pending.values()) {
                    removeCancelled(request);
                }
                pending.clear();
                networkQueue.clear();
                if (networkExecutor instanceof ExecutorService) {
                    ((ExecutorService) networkExecutor).shutdownNow();
                }
                if (stateExecutor instanceof ExecutorService) {
                    ((ExecutorService) stateExecutor).shutdown();
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private final class RequestImpl implements Request {
        private final PluginReputationQuery query;
        private final Executor executor;
        private final Callback callback;
        private final ArrayDeque<Result> callbacks = new ArrayDeque<>();

        private boolean cancelled;
        private boolean callbackRunning;
        private boolean terminalQueued;
        private boolean reserved;

        private RequestImpl(
                PluginReputationQuery query,
                Executor executor,
                Callback callback) {
            this.query = query;
            this.executor = executor;
            this.callback = callback;
        }

        private void deliver(Result result) {
            synchronized (this) {
                if (cancelled || terminalQueued) {
                    releaseIfNeeded();
                    return;
                }
                callbacks.addLast(result);
                terminalQueued = result.terminal;
                if (callbackRunning) {
                    return;
                }
                callbackRunning = true;
            }
            scheduleNext();
        }

        private void scheduleNext() {
            try {
                executor.execute(() -> {
                    Result next;

                    synchronized (RequestImpl.this) {
                        next = callbacks.pollFirst();
                    }
                    if (next != null && !isCancelled()) {
                        try {
                            callback.onResult(next);
                        } catch (Throwable ignored) {
                        }
                    }

                    boolean again;

                    synchronized (RequestImpl.this) {
                        again = !callbacks.isEmpty() && !cancelled;
                        if (!again) {
                            callbackRunning = false;
                        }
                    }
                    if (again) {
                        scheduleNext();
                    } else if (next != null && next.terminal) {
                        releaseIfNeeded();
                    }
                });
            } catch (RejectedExecutionException error) {
                synchronized (this) {
                    callbacks.clear();
                    callbackRunning = false;
                    cancelled = true;
                }
                releaseIfNeeded();
            }
        }

        private void releaseIfNeeded() {
            synchronized (publicLock) {
                if (reserved) {
                    reserved = false;
                    acceptedRequests--;
                    activeRequests.remove(this);
                }
            }
        }

        @Override
        public void cancel() {
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                callbacks.clear();
            }
            releaseIfNeeded();
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }
    }

    private final class Pending {
        private final PluginReputationQuery query;
        private final ArrayList<RequestImpl> waiters = new ArrayList<>();
        private boolean started;

        private Pending(PluginReputationQuery query) {
            this.query = query;
        }
    }
}
