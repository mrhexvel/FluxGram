package org.telegram.messenger.plugins.reputation;

import android.content.Context;
import android.os.Process;

import org.telegram.messenger.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class PluginReputationCoordinators {
    private static PluginReputationCoordinator instance;

    private PluginReputationCoordinators() {
    }

    public static synchronized void initialize(Context context) {
        if (instance != null) {
            return;
        }
        if (context == null) {
            throw new IllegalArgumentException("Plugin reputation context is required");
        }

        PluginReputationEndpoint endpoint = PluginReputationEndpoint.create(
                BuildConfig.FLUXGRAM_PLUGIN_REPUTATION_API_BASE_URL,
                BuildConfig.FLUXGRAM_PLUGIN_REPUTATION_AUTHORITY_KEY_ID);

        if (!endpoint.isEnabled()) {
            instance = PluginReputationCoordinator.disabled();
            return;
        }

        Context applicationContext = context.getApplicationContext();
        ExecutorService stateExecutor = Executors.newSingleThreadExecutor(
                threadFactory("plugin-reputation-state"));
        ExecutorService networkExecutor = Executors.newFixedThreadPool(
                PluginReputationCoordinator.MAX_CONCURRENT_NETWORK,
                threadFactory("plugin-reputation-network"));
        ProductionStore store = new ProductionStore(
                applicationContext,
                endpoint.authorityKeyId);
        PluginReputationHttpTransport transport = new PluginReputationHttpTransport(endpoint);

        instance = new PluginReputationCoordinator(
                endpoint.authorityKeyId,
                store,
                (query, nowMillis) -> {
                    PluginReputationHttpTransport.Result result = transport.execute(query, nowMillis);
                    return new PluginReputationCoordinator.NetworkResult(
                            result.response(),
                            result.envelope());
                },
                stateExecutor,
                networkExecutor,
                System::currentTimeMillis);
    }

    public static synchronized PluginReputationCoordinator get() {
        if (instance == null) {
            throw new IllegalStateException("Plugin reputation coordinator is not initialized");
        }
        return instance;
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();

        return runnable -> {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class ProductionStore
            implements PluginReputationCoordinator.StoreClient {
        private final Context context;
        private final String authorityKeyId;

        private PluginReputationStore store;

        private ProductionStore(Context context, String authorityKeyId) {
            this.context = context;
            this.authorityKeyId = authorityKeyId;
        }

        @Override
        public PluginReputationCoordinator.StoreState load(long nowMillis) throws IOException {
            PluginReputationStore.Snapshot snapshot = store().load(nowMillis);
            return new PluginReputationCoordinator.StoreState(snapshot.cache, snapshot);
        }

        @Override
        public PluginReputationCoordinator.StoreUpdate update(
                PluginReputationCoordinator.StoreState expected,
                byte[] response,
                byte[] envelope,
                long nowMillis) throws IOException {
            PluginReputationStore.UpdateResult result = store().update(
                    (PluginReputationStore.Snapshot) expected.token,
                    response,
                    envelope,
                    nowMillis);
            PluginReputationCoordinator.StoreState updated =
                    new PluginReputationCoordinator.StoreState(
                            result.snapshot.cache,
                            result.snapshot);
            return new PluginReputationCoordinator.StoreUpdate(result.status, updated);
        }

        private PluginReputationStore store() throws IOException {
            if (store != null) {
                return store;
            }

            File pluginRoot = new File(context.getNoBackupFilesDir(), "fluxgram_plugins");
            File reputationRoot = new File(pluginRoot, "reputation");

            if (!reputationRoot.isDirectory() && !reputationRoot.mkdirs()) {
                throw new IOException("Unable to create plugin reputation directory");
            }

            store = new PluginReputationStore(reputationRoot, authorityKeyId);
            return store;
        }
    }
}
