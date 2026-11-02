package org.telegram.messenger.plugins;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.plugins.packages.PluginManifest;
import org.telegram.messenger.plugins.packages.PluginPackageStore;
import org.telegram.messenger.plugins.packages.PluginPackageValidator;
import org.telegram.messenger.plugins.reputation.PluginReputationCoordinator;
import org.telegram.messenger.plugins.reputation.PluginReputationCoordinators;
import org.telegram.messenger.plugins.reputation.PluginReputationInstallPolicy;
import org.telegram.messenger.plugins.reputation.PluginReputationPolicy;
import org.telegram.messenger.plugins.reputation.PluginReputationQuery;
import org.telegram.messenger.plugins.reputation.PluginReputationStartupPump;
import org.telegram.messenger.plugins.risk.PluginRiskDiff;
import org.telegram.messenger.plugins.risk.PluginRiskPolicy;
import org.telegram.messenger.plugins.risk.PluginRiskReport;
import org.telegram.messenger.plugins.signing.PluginSignerJournal;
import org.telegram.messenger.plugins.signing.PluginSignerIdentity;
import org.telegram.messenger.plugins.signing.PluginSignerReview;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ExternalPlugins {
    private static final long STARTUP_REPUTATION_TIMEOUT_MS = 12_000;
    private static final ChaquopyPluginPythonRiskScanner RISK_SCANNER =
            new ChaquopyPluginPythonRiskScanner();
    private static final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "FluxGramInstaller"));
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Set<String> removing = ConcurrentHashMap.newKeySet();
    private static PluginPackageStore store;
    private static boolean initialized;
    private static volatile String startupError;
    private static volatile Map<String, PluginPackageStore.Pending> pending =
            Collections.emptyMap();
    private static volatile Map<String, IncompatiblePlugin> incompatible =
            Collections.emptyMap();

    public static PluginPackageStore.Pending getPending(String id) {
        return pending.get(id);
    }

    public interface Callback<T> {
        void complete(T value, Exception error);
    }

    public static final class ReputationBlockedException extends IOException {
        public final List<String> reasons;

        ReputationBlockedException(List<String> reasons) {
            super("Plugin package is blocked by reputation");
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    public static final class DebugResult {
        public final String operation;
        public final String pluginId;
        public final String version;
        public final String runtimeState;
        public final long generation;
        public final PluginUnloadReport unloadReport;

        DebugResult(String operation, String pluginId, String version,
                String runtimeState, long generation, PluginUnloadReport unloadReport) {
            this.operation = operation;
            this.pluginId = pluginId;
            this.version = version;
            this.runtimeState = runtimeState;
            this.generation = generation;
            this.unloadReport = unloadReport;
        }

        public String toJson(Exception error) {
            try {
                JSONObject result = new JSONObject();
                result.put("success", error == null);
                result.put("operation", operation);
                result.put("plugin_id", pluginId == null ? JSONObject.NULL : pluginId);
                result.put("version", version == null ? JSONObject.NULL : version);
                result.put("runtime_state",
                        runtimeState == null ? JSONObject.NULL : runtimeState);
                result.put("generation", generation);

                if (unloadReport != null) {
                    result.put("unload_report", new JSONObject(unloadReport.toJson()));
                }

                if (error != null) {
                    result.put("error", error.getClass().getSimpleName());
                    result.put("message", boundedMessage(error));
                }

                return result.toString();
            } catch (JSONException jsonError) {
                throw new IllegalStateException("Cannot encode plugin debug result", jsonError);
            }
        }

        private static String boundedMessage(Exception error) {
            String message = error.getMessage();

            if (message == null || message.isEmpty()) {
                message = error.getClass().getSimpleName();
            }

            return message.length() <= 240 ? message : message.substring(0, 240);
        }
    }

    public static final class IncompatiblePlugin implements Plugin {
        public enum State {
            INCOMPATIBLE,
            CHECKING,
            BLOCKED
        }

        private final PluginPackageStore.Installed installed;
        private final File iconFile;
        private final State state;
        private final List<String> reputationReasons;

        IncompatiblePlugin(PluginPackageStore.Installed installed) {
            this(installed, State.INCOMPATIBLE, Collections.emptyList());
        }

        IncompatiblePlugin(
                PluginPackageStore.Installed installed,
                State state,
                List<String> reputationReasons) {
            this.installed = installed;
            this.state = state;
            this.reputationReasons = Collections.unmodifiableList(
                    new ArrayList<>(reputationReasons));

            File resolvedIcon = null;

            if (installed.manifest.icon != null) {
                File file = new File(installed.directory, installed.manifest.icon);

                try {
                    if (file.getCanonicalPath().startsWith(
                            installed.directory.getCanonicalPath() + File.separator)) {
                        resolvedIcon = file;
                    }
                } catch (IOException error) {
                    FileLog.e(error);
                }
            }

            iconFile = resolvedIcon;
        }

        public PluginManifest getManifest() {
            return installed.manifest;
        }

        public State getState() {
            return state;
        }

        public List<String> getReputationReasons() {
            return reputationReasons;
        }

        @Override
        public String getId() {
            return installed.manifest.id;
        }

        @Override
        public String getName() {
            return installed.manifest.name;
        }

        @Override
        public String getDescription() {
            return installed.manifest.description;
        }

        @Override
        public String getVersion() {
            return installed.manifest.version;
        }

        @Override
        public File getIconFile() {
            return iconFile;
        }

        @Override
        public void beforeSend(BeforeSendEvent event) {}
    }

    public static final class Review implements AutoCloseable {
        public final Bitmap icon;
        public final File archive;
        public final PluginPackageValidator.Result result;
        public final String installedVersion;
        public final PluginRiskReport previousRiskReport;
        public final PluginRiskDiff riskDiff;
        public final PluginRiskPolicy.Result riskPolicy;
        public final PluginSignerReview signerReview;

        Review(
                File archive,
                PluginPackageValidator.Result result,
                PluginPackageStore.Installed installed) throws IOException {
            if (installed != null && installed.signerStateError != null) {
                throw new IOException(installed.signerStateError);
            }

            this.icon = result.iconBytes == null ? null
                    : PluginIcons.decode(result.iconBytes, result.manifest.icon);
            this.archive = archive;
            this.result = result;
            this.installedVersion = installed == null ? null : installed.manifest.version;
            this.previousRiskReport = installed == null ? null : installed.riskReport;
            this.riskDiff = previousRiskReport == null ? null
                    : PluginRiskDiff.between(previousRiskReport, result.riskReport);
            this.riskPolicy = installed == null
                    ? PluginRiskPolicy.evaluateInstall(result.riskReport)
                    : PluginRiskPolicy.evaluateUpdate(previousRiskReport, result.riskReport);
            this.signerReview = PluginSignerReview.prepare(
                    result.manifest.id,
                    result.sha256,
                    installed == null ? null : installed.signerState,
                    result.signatureVerification,
                    result.rotationProof);
        }

        @Override
        public void close() {
            archive.delete();
        }
    }

    public static synchronized void initialize(Context context) {
        if (initialized) {
            return;
        }

        initialized = true;

        Context app = context.getApplicationContext();

        worker.execute(
                () -> {
                    try {
                        store =
                                new PluginPackageStore(
                                        new File(app.getFilesDir(), "plugins/installed"),
                                        app.getCacheDir(),
                                        RISK_SCANNER);

                        Map<String, PluginPackageStore.Pending> operations = store.pending();
                        Set<String> recoveryFailed = ConcurrentHashMap.newKeySet();
                        recordPendingErrors(store.pendingErrors(), recoveryFailed);
                        PluginManager manager = PluginManager.getInstance();

                        if (manager.isRecoveryBlockingImports()) {
                            pending = Collections.unmodifiableMap(operations);
                            loadMetadataOnly(manager);
                            changed();

                            return;
                        }

                        ArrayList<PluginPackageStore.Pending> reputationPending = new ArrayList<>();

                        for (PluginPackageStore.Pending operation : operations.values()) {
                            if (!operation.delete && !operation.rollback && !operation.migrated) {
                                reputationPending.add(operation);
                                continue;
                            }

                            try {
                                recoverPendingOperation(manager, operation);
                            } catch (Exception | LinkageError error) {
                                PluginCrashRecovery.handled(error);
                                recoveryFailed.add(operation.id);
                                FileLog.e("FluxGram pending plugin recovery failed: "
                                        + operation.id, error);
                            }
                        }

                        recoverPendingReputation(
                                manager,
                                reputationPending,
                                recoveryFailed,
                                () -> loadInstalledWithReputation(manager, recoveryFailed));
                    } catch (Exception error) {
                        startupError = error.toString();
                        FileLog.e(error);
                    }

                    changed();
                });
    }

    private static void recordPendingErrors(
            Map<String, String> errors, Set<String> recoveryFailed) {
        for (Map.Entry<String, String> error : errors.entrySet()) {
            recoveryFailed.add(error.getKey());
            FileLog.e("FluxGram invalid pending plugin operation: "
                    + error.getKey() + ": " + error.getValue());
        }
    }

    private static void recoverPendingOperation(
            PluginManager manager, PluginPackageStore.Pending operation) throws Exception {
        if (operation.delete) {
            PluginStorage.clearPlugin(operation.id);

            if (!PluginSettings.clearForPluginNow(operation.id)
                    || !manager.clearPersistedEnabledStateNow(operation.id)) {
                throw new IOException("Cannot clear deleted plugin state");
            }

            store.applyPendingDelete(operation.id);
            return;
        }

        if (operation.rollback) {
            store.rollbackPending(operation.id);

            if (!manager.setPersistedEnabledState(
                    operation.id,
                    operation.enabledBeforeUpdate)) {
                throw new IOException("Cannot restore plugin enabled state");
            }

            store.finishPendingRollback(operation.id);
            return;
        }

        if (operation.migrated) {
            if (!manager.setPersistedEnabledState(
                    operation.id,
                    operation.enableAfterUpdate)) {
                throw new IOException("Cannot save plugin enabled state");
            }

            store.finishPendingUpdate(operation.id);
            return;
        }

        throw new IOException("Pending plugin reputation decision is required");
    }

    private static void recoverPendingReputation(
            PluginManager manager,
            ArrayList<PluginPackageStore.Pending> operations,
            Set<String> recoveryFailed,
            Runnable complete) {
        PluginReputationStartupPump pump = new PluginReputationStartupPump(
                (query, callback) -> PluginReputationCoordinators.get().lookup(
                        query, worker, callback),
                (task, delayMillis) -> {
                    ScheduledFuture<?> future = worker.schedule(
                            task, delayMillis, TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                },
                STARTUP_REPUTATION_TIMEOUT_MS,
                () -> {
                    try {
                        pending = Collections.unmodifiableMap(store.pending());
                        recordPendingErrors(store.pendingErrors(), recoveryFailed);
                    } catch (Exception error) {
                        startupError = error.toString();
                        FileLog.e(error);
                    }

                    complete.run();
                });

        for (PluginPackageStore.Pending operation : operations) {
            pump.add(new PluginReputationStartupPump.Task() {
                private PluginPackageStore.Installed update;
                private PluginReputationQuery query;

                @Override
                public PluginReputationQuery prepare() throws Exception {
                    update = store.activatePendingUpdate(operation.id);
                    query = reputationQuery(update);
                    return query;
                }

                @Override
                public void complete(PluginReputationCoordinator.Result result) throws Exception {
                    requireCurrentIdentity(operation.id, query);

                    if (result != null
                            && PluginReputationInstallPolicy.evaluate(result)
                            == PluginReputationInstallPolicy.Decision.BLOCK) {
                        rollbackPending(manager, operation);
                        return;
                    }

                    finishPendingUpdate(manager, operation, update);
                }

                @Override
                public void failed(Exception error) {
                    PluginCrashRecovery.handled(error);
                    recoveryFailed.add(operation.id);
                    FileLog.e("FluxGram pending plugin recovery failed: "
                            + operation.id, error);
                }
            });
        }

        pump.start();
    }

    private static void finishPendingUpdate(
            PluginManager manager,
            PluginPackageStore.Pending operation,
            PluginPackageStore.Installed update) throws Exception {

        if (!update.manifest.isCompatible()) {
            rollbackPending(manager, operation);
            return;
        }

        try {
            PluginCrashRecovery.runExternal(
                    operation.id,
                    () -> PythonPluginAdapter.migrateStorage(update));
        } catch (Exception | LinkageError error) {
            PluginCrashRecovery.handled(error);
            FileLog.e(error);
            rollbackPending(manager, operation);
            return;
        }

        store.markPendingMigrated(operation.id);

        if (!manager.setPersistedEnabledState(
                operation.id,
                operation.enableAfterUpdate)) {
            throw new IOException("Cannot save plugin enabled state");
        }

        store.finishPendingUpdate(operation.id);
    }

    private static void rollbackPending(
            PluginManager manager,
            PluginPackageStore.Pending operation) throws IOException {
        store.rollbackPending(operation.id);

        if (!manager.setPersistedEnabledState(
                operation.id,
                operation.enabledBeforeUpdate)) {
            throw new IOException("Cannot restore plugin enabled state");
        }

        store.finishPendingRollback(operation.id);
    }

    private static void loadInstalledWithReputation(
            PluginManager manager,
            Set<String> recoveryFailed) {
        try {
            ArrayList<PluginPackageStore.Installed> installedPackages = store.list();
            LinkedHashMap<String, IncompatiblePlugin> unavailable = new LinkedHashMap<>();
            PluginReputationStartupPump pump = new PluginReputationStartupPump(
                    (query, callback) -> PluginReputationCoordinators.get().lookup(
                            query, worker, callback),
                    (task, delayMillis) -> {
                        ScheduledFuture<?> future = worker.schedule(
                                task, delayMillis, TimeUnit.MILLISECONDS);
                        return () -> future.cancel(false);
                    },
                    STARTUP_REPUTATION_TIMEOUT_MS,
                    ExternalPlugins::changed);

            for (PluginPackageStore.Installed installed : installedPackages) {
                if (!installed.manifest.isCompatible() || installed.signerStateError != null) {
                    unavailable.put(installed.manifest.id, new IncompatiblePlugin(installed));
                    manager.clearPersistedEnabledState(installed.manifest.id);
                    continue;
                }

                PluginReputationQuery query = reputationQuery(installed);

                if (query == null) {
                    registerInstalled(manager, installed, recoveryFailed);
                    continue;
                }

                unavailable.put(
                        installed.manifest.id,
                        new IncompatiblePlugin(
                                installed,
                                IncompatiblePlugin.State.CHECKING,
                                Collections.emptyList()));
                pump.add(installedReputationTask(
                        manager, installed, query, recoveryFailed));
            }

            incompatible = Collections.unmodifiableMap(unavailable);
            changed();
            pump.start();
        } catch (Exception error) {
            startupError = error.toString();
            FileLog.e(error);
            changed();
        }
    }

    private static PluginReputationStartupPump.Task installedReputationTask(
            PluginManager manager,
            PluginPackageStore.Installed installed,
            PluginReputationQuery query,
            Set<String> recoveryFailed) {
        return new PluginReputationStartupPump.Task() {
            private boolean authenticatedBlocked;

            @Override
            public PluginReputationQuery prepare() {
                return query;
            }

            @Override
            public void complete(PluginReputationCoordinator.Result result) throws Exception {
                PluginPackageStore.Installed current = requireCurrentIdentity(
                        installed.manifest.id, query);
                PluginReputationInstallPolicy.Decision decision = result == null
                        ? PluginReputationInstallPolicy.Decision.ALLOW
                        : PluginReputationInstallPolicy.evaluate(result);

                if (decision == PluginReputationInstallPolicy.Decision.BLOCK) {
                    authenticatedBlocked = true;
                    replaceUnavailable(new IncompatiblePlugin(
                            current,
                            IncompatiblePlugin.State.BLOCKED,
                            result.lookup.reasons));

                    if (!manager.clearPersistedEnabledStateNow(installed.manifest.id)) {
                        throw new IOException("Cannot clear blocked plugin enabled state");
                    }
                } else {
                    removeUnavailable(installed.manifest.id);
                    registerInstalled(manager, current, recoveryFailed);
                }

                changed();
            }

            @Override
            public void failed(Exception error) {
                if (authenticatedBlocked) {
                    PluginCrashRecovery.handled(error);
                    recoveryFailed.add(installed.manifest.id);
                    FileLog.e("FluxGram blocked plugin state update failed: "
                            + installed.manifest.id, error);
                    changed();
                    return;
                }

                try {
                    PluginPackageStore.Installed current = requireCurrentIdentity(
                            installed.manifest.id, query);
                    removeUnavailable(installed.manifest.id);
                    registerInstalled(manager, current, recoveryFailed);
                } catch (Exception | LinkageError activationError) {
                    PluginCrashRecovery.handled(activationError);
                    recoveryFailed.add(installed.manifest.id);
                    FileLog.e("FluxGram plugin startup failed: "
                            + installed.manifest.id, activationError);
                }

                changed();
            }
        };
    }

    private static void registerInstalled(
            PluginManager manager,
            PluginPackageStore.Installed installed,
            Set<String> recoveryFailed) {
        PythonPluginAdapter adapter = new PythonPluginAdapter(installed);

        if (recoveryFailed.contains(installed.manifest.id)) {
            adapter.setPreflightError(new IOException("Pending plugin recovery failed"));
            manager.registerBlockedUntilRestart(adapter);
            return;
        }

        try {
            PluginCrashRecovery.runExternal(
                    installed.manifest.id,
                    () -> PythonPluginAdapter.migrateStorage(installed));
        } catch (Exception | LinkageError error) {
            PluginCrashRecovery.handled(error);
            FileLog.e(error);
            disableAfterMigrationFailure(manager, installed.manifest.id);
            adapter.setPreflightError(error);
        }

        manager.register(adapter, false);
    }

    private static PluginReputationQuery reputationQuery(
            PluginPackageStore.Installed installed) {
        if (installed.signerState != null) {
            String keyId = installed.signerState.identity.kind
                    == PluginSignerIdentity.Kind.SIGNED
                    ? installed.signerState.identity.keyId : null;
            return new PluginReputationQuery(installed.signerState.archiveSha256, keyId);
        }
        if (installed.riskReport != null) {
            return new PluginReputationQuery(installed.riskReport.archiveSha256, null);
        }

        return null;
    }

    private static PluginPackageStore.Installed requireCurrentIdentity(
            String id,
            PluginReputationQuery query) throws IOException {
        PluginPackageStore.Installed current = store.get(id);
        PluginReputationQuery currentQuery = reputationQuery(current);

        if (query == null ? currentQuery != null : !query.equals(currentQuery)) {
            throw new IOException("Installed plugin identity changed during reputation check");
        }

        return current;
    }

    private static void replaceUnavailable(IncompatiblePlugin plugin) {
        LinkedHashMap<String, IncompatiblePlugin> updated = new LinkedHashMap<>(incompatible);
        updated.put(plugin.getId(), plugin);
        incompatible = Collections.unmodifiableMap(updated);
    }

    private static void removeUnavailable(String id) {
        LinkedHashMap<String, IncompatiblePlugin> updated = new LinkedHashMap<>(incompatible);
        updated.remove(id);
        incompatible = Collections.unmodifiableMap(updated);
    }

    public static String getStartupError() {
        return startupError;
    }

    public static ArrayList<Plugin> getIncompatiblePlugins() {
        return new ArrayList<>(incompatible.values());
    }

    public static Plugin getIncompatiblePlugin(String id) {
        return incompatible.get(id);
    }

    public static boolean isExternal(Plugin plugin) {
        return plugin instanceof IncompatiblePlugin
                || (plugin instanceof PythonPluginAdapter
                && ((PythonPluginAdapter) plugin).isExternal());
    }

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public static void changed() {
        AndroidUtilities.runOnUIThread(
                () -> {
                    for (Runnable listener : listeners) {
                        listener.run();
                    }
                });
    }

    public static void review(Context context, Uri uri, Callback<Review> callback) {
        Context app = context.getApplicationContext();

        worker.execute(
                () -> {
                    File snapshot = null;
                    Review review = null;
                    Exception failure = null;

                    try {
                        snapshot =
                                File.createTempFile("fgplugin-picked-", ".zip", app.getCacheDir());

                        try (InputStream in = app.getContentResolver().openInputStream(uri);
                                OutputStream out = new FileOutputStream(snapshot)) {
                            if (in == null) {
                                throw new IOException("Cannot open selected document");
                            }

                            PluginPackageStore.copy(
                                    in, out, PluginPackageValidator.MAX_ARCHIVE_BYTES);
                        }

                        if (store == null || startupError != null) {
                            throw new IOException("Plugin storage unavailable: " + startupError);
                        }

                        PluginPackageValidator.Result result = PluginPackageValidator.inspect(
                                snapshot, app.getCacheDir(), RISK_SCANNER);
                        review = new Review(snapshot, result, store.find(result.manifest.id));
                    } catch (Exception error) {
                        failure = error;

                        if (snapshot != null) {
                            snapshot.delete();
                        }
                    }

                    Review value = review;
                    Exception error = failure;

                    AndroidUtilities.runOnUIThread(() -> callback.complete(value, error));
                });
    }

    public static void install(
            Review review,
            boolean enable,
            String expectedVersion,
            boolean confirmed,
            Callback<String> callback) {
        worker.execute(() -> beginInstall(
                review,
                enable,
                expectedVersion,
                confirmed,
                callback));
    }

    private static void beginInstall(
            Review review,
            boolean enable,
            String expectedVersion,
            boolean confirmed,
            Callback<String> callback) {
        String id = null;

        try {
            if (store == null || startupError != null) {
                throw new IOException("Plugin storage unavailable: " + startupError);
            }

            id = review.result.manifest.id;

            if (review.riskPolicy.decision == PluginRiskPolicy.Decision.BLOCK) {
                throw new IOException("Plugin package is blocked by static risk analysis");
            }
            if (review.riskPolicy.decision == PluginRiskPolicy.Decision.CONFIRM
                    && !confirmed) {
                throw new IOException("Plugin risk changes require confirmation");
            }

            PluginSignerJournal approvedSigner = review.signerReview.approve(confirmed);
            String developerKeyId = review.result.signatureVerification.isVerified()
                    ? review.result.signatureVerification.keyId
                    : null;
            PluginReputationQuery query = new PluginReputationQuery(
                    review.result.sha256,
                    developerKeyId);
            String pluginId = id;

            PluginReputationCoordinators.get().lookup(query, worker, result -> {
                PluginReputationInstallPolicy.Decision decision =
                        PluginReputationInstallPolicy.evaluate(result);

                if (decision == PluginReputationInstallPolicy.Decision.WAIT) {
                    return;
                }
                if (decision == PluginReputationInstallPolicy.Decision.BLOCK) {
                    completeInstall(
                            review,
                            pluginId,
                            new ReputationBlockedException(result.lookup.reasons),
                            callback);
                    return;
                }

                installApproved(
                        review,
                        enable,
                        expectedVersion,
                        approvedSigner,
                        pluginId,
                        callback);
            });
        } catch (Exception error) {
            completeInstall(review, id, error, callback);
        }
    }

    private static void installApproved(
            Review review,
            boolean enable,
            String expectedVersion,
            PluginSignerJournal approvedSigner,
            String id,
            Callback<String> callback) {
        Exception failure = null;

        try {
            PluginManager manager = PluginManager.getInstance();

            if (manager.isRecoveryBlockingImports()) {
                throw new IOException("Restart FluxGram before installing or updating plugins");
            }

            if (expectedVersion != null) {
                boolean enabledBefore = manager.isEnabled(id);
                store.scheduleUpdate(
                        review.archive,
                        review.result.sha256,
                        id,
                        expectedVersion,
                        enable,
                        enabledBefore,
                        approvedSigner);
                manager.blockUntilRestart(id);
                pending = Collections.unmodifiableMap(store.pending());
            } else {
                String installedId = id;
                PluginPackageStore.Installed installed =
                        store.install(
                                review.archive,
                                review.result.sha256,
                                approvedSigner,
                                () -> PluginStorage.clearPlugin(installedId));
                PythonPluginAdapter adapter = new PythonPluginAdapter(installed);
                boolean migrated = true;

                try {
                    PluginCrashRecovery.runExternal(
                            installed.manifest.id,
                            () -> PythonPluginAdapter.migrateStorage(installed));
                } catch (Exception | LinkageError error) {
                    PluginCrashRecovery.handled(error);
                    migrated = false;
                    disableAfterMigrationFailure(manager, id);
                    adapter.setPreflightError(error);
                }

                manager.register(adapter, false);

                if (enable && migrated) {
                    manager.setEnabled(id, true);
                }
            }
        } catch (Exception error) {
            failure = error;
        }

        completeInstall(review, id, failure, callback);
    }

    private static void completeInstall(
            Review review,
            String id,
            Exception failure,
            Callback<String> callback) {
        review.close();

        AndroidUtilities.runOnUIThread(
                () -> {
                    changed();
                    callback.complete(id, failure);
                });
    }

    public static void developerInstallOrReload(File archive, String expectedHash,
            boolean reload, boolean enable, Callback<DebugResult> callback) {
        if (!PluginDeveloperBridge.isAvailable()) {
            callback.complete(null, new IOException("Plugin developer access is unavailable"));
            return;
        }

        worker.execute(() -> {
            DebugResult result = null;
            Exception failure = null;
            String id = null;
            String version = null;
            PluginUnloadReport unloadReport = null;

            try {
                if (store == null || startupError != null) {
                    throw new IOException("Plugin storage unavailable: " + startupError);
                }

                PluginManager manager = PluginManager.getInstance();

                if (manager.isRecoveryBlockingImports()) {
                    throw new IOException("Restart FluxGram before using plugin debug reload");
                }

                PluginPackageValidator.Result checked =
                        PluginPackageValidator.inspect(
                                archive, archive.getParentFile(), RISK_SCANNER);

                if (!checked.sha256.equals(expectedHash)) {
                    throw new IOException("Uploaded plugin hash does not match the request");
                }

                id = checked.manifest.id;
                version = checked.manifest.version;

                if (!reload) {
                    PluginSignerReview signerReview = PluginSignerReview.prepare(
                            id,
                            checked.sha256,
                            null,
                            checked.signatureVerification,
                            checked.rotationProof);
                    PluginPackageStore.Installed installed = store.install(
                            archive,
                            expectedHash,
                            signerReview.approve(true),
                            null);
                    PythonPluginAdapter adapter = new PythonPluginAdapter(installed);
                    Exception migrationFailure = migrateForDebug(installed);

                    if (migrationFailure != null) {
                        disableAfterMigrationFailure(manager, id);
                        adapter.setPreflightError(migrationFailure);
                    }

                    manager.register(adapter, false);

                    if (enable && migrationFailure == null) {
                        manager.setEnabled(id, true);
                    }

                    String runtimeState = adapter.awaitDebugRuntime(10_000);

                    if (migrationFailure != null) {
                        throw migrationFailure;
                    }

                    result = new DebugResult(
                            "install", id, version, runtimeState,
                            adapter.getGeneration(), null);
                } else {
                    Plugin plugin = manager.getPlugin(id);

                    if (!(plugin instanceof PythonPluginAdapter)
                            || !((PythonPluginAdapter) plugin).isExternal()) {
                        throw new IOException("Debug reload requires an installed external plugin");
                    }

                    PythonPluginAdapter previous = (PythonPluginAdapter) plugin;
                    boolean enabledBefore = manager.isEnabled(id);
                    PluginPackageStore.Installed current = store.get(id);
                    PluginSignerReview signerReview = PluginSignerReview.prepare(
                            id,
                            checked.sha256,
                            current.signerState,
                            checked.signatureVerification,
                            checked.rotationProof);
                    store.scheduleDebugReload(
                            archive,
                            expectedHash,
                            id,
                            previous.getVersion(),
                            enable,
                            signerReview.approve(true));
                    pending = Collections.unmodifiableMap(store.pending());

                    try {
                        previous.stopForRemoval();
                        unloadReport = previous.getLastUnloadReport();

                        if (unloadReport == null || !unloadReport.successful) {
                            throw new IOException("Plugin cleanup report is incomplete");
                        }
                    } catch (Exception cleanupError) {
                        try {
                            store.cancelPendingUpdate(id);
                            pending = Collections.unmodifiableMap(store.pending());
                        } catch (Exception cancelError) {
                            cleanupError.addSuppressed(cancelError);
                        }

                        throw cleanupError;
                    }

                    if (!manager.unregisterStopped(id, previous)) {
                        throw new IOException("Stopped plugin registration changed during reload");
                    }

                    PluginPackageStore.Installed replacement = store.activatePendingUpdate(id);

                    Exception migrationError = migrateForDebug(replacement);

                    if (migrationError != null) {
                        store.rollbackPending(id);

                        if (!manager.setPersistedEnabledState(id, enabledBefore)) {
                            throw new IOException(
                                    "Cannot restore plugin enabled state", migrationError);
                        }

                        store.finishPendingRollback(id);
                        pending = Collections.unmodifiableMap(store.pending());
                        PythonPluginAdapter restored = new PythonPluginAdapter(store.get(id));
                        manager.register(restored, false);
                        restored.awaitDebugRuntime(10_000);
                        throw new IOException("Debug reload migration failed", migrationError);
                    }

                    store.markPendingMigrated(id);

                    if (!manager.setPersistedEnabledState(id, enable)) {
                        throw new IOException("Cannot save plugin enabled state");
                    }

                    store.finishPendingUpdate(id);
                    pending = Collections.unmodifiableMap(store.pending());
                    PythonPluginAdapter adapter = new PythonPluginAdapter(store.get(id));
                    manager.register(adapter, false);
                    String runtimeState = adapter.awaitDebugRuntime(10_000);
                    result = new DebugResult(
                            "reload", id, version, runtimeState,
                            adapter.getGeneration(), unloadReport);
                }
            } catch (Exception | LinkageError error) {
                if (id != null) {
                    restoreDebugRegistration(id, error);
                }

                failure = error instanceof Exception
                        ? (Exception) error : new IOException("Plugin debug operation failed", error);
            } finally {
                archive.delete();
            }

            if (result == null) {
                result = new DebugResult(
                        reload ? "reload" : "install", id, version,
                        null, -1, unloadReport);
            }

            DebugResult value = result;
            Exception error = failure;
            AndroidUtilities.runOnUIThread(() -> {
                changed();
                callback.complete(value, error);
            });
        });
    }

    private static Exception migrateForDebug(PluginPackageStore.Installed installed) {
        try {
            PluginCrashRecovery.runExternal(
                    installed.manifest.id,
                    () -> PythonPluginAdapter.migrateStorage(installed));
            return null;
        } catch (Exception | Error error) {
            PluginCrashRecovery.handled(error);
            return error instanceof Exception
                    ? (Exception) error : new IOException("Plugin migration failed", error);
        }
    }

    private static void restoreDebugRegistration(String id, Throwable failure) {
        PluginManager manager = PluginManager.getInstance();

        if (manager.getPlugin(id) != null || store == null) {
            return;
        }

        try {
            PluginPackageStore.Installed installed = store.get(id);
            PythonPluginAdapter adapter = new PythonPluginAdapter(installed);
            manager.register(adapter, false);
            adapter.awaitDebugRuntime(10_000);
        } catch (Exception | LinkageError restoreError) {
            failure.addSuppressed(restoreError);
        }
    }

    public static void export(Context context, String id, Callback<File> callback) {
        File cacheDirectory = context.getExternalCacheDir();

        worker.execute(() -> {
            File result = null;
            Exception failure = null;

            try {
                if (store == null || startupError != null) {
                    throw new IOException("Plugin storage unavailable: " + startupError);
                }

                if (cacheDirectory == null) {
                    throw new IOException("Shared file storage is unavailable");
                }

                result = store.export(id, new File(cacheDirectory, "plugin-shares"));
            } catch (Exception error) {
                failure = error;
            }

            File file = result;
            Exception error = failure;
            AndroidUtilities.runOnUIThread(() -> callback.complete(file, error));
        });
    }

    private static void disableAfterMigrationFailure(PluginManager manager, String id) {
        manager.clearPersistedEnabledState(id);
    }

    private static void loadMetadataOnly(PluginManager manager) throws IOException {
        LinkedHashMap<String, IncompatiblePlugin> unavailable = new LinkedHashMap<>();

        for (PluginPackageStore.Installed installed : store.list()) {
            unavailable.put(installed.manifest.id, new IncompatiblePlugin(
                    installed,
                    installed.manifest.isCompatible()
                            ? IncompatiblePlugin.State.CHECKING
                            : IncompatiblePlugin.State.INCOMPATIBLE,
                    Collections.emptyList()));
        }

        incompatible = Collections.unmodifiableMap(unavailable);
    }

    public static boolean isRemoving(String id) {
        return removing.contains(id);
    }

    public static void delete(String id, Callback<String> callback) {
        if (!removing.add(id)) {
            AndroidUtilities.runOnUIThread(() -> callback.complete(
                    id, new IOException("Plugin removal is already in progress")));
            return;
        }

        changed();
        worker.execute(
                () -> {
                    Exception failure = null;

                    try {
                        if (store == null || startupError != null) {
                            throw new IOException("Plugin storage unavailable: " + startupError);
                        }

                        PluginManager manager = PluginManager.getInstance();
                        Plugin plugin = manager.getPlugin(id);
                        IncompatiblePlugin incompatiblePlugin = incompatible.get(id);
                        boolean runningExternal = plugin instanceof PythonPluginAdapter
                                && ((PythonPluginAdapter) plugin).isExternal();

                        if (!runningExternal && incompatiblePlugin == null) {
                            throw new IOException("Only installed external plugins can be removed");
                        }

                        if (getPending(id) != null) {
                            throw new IOException("Plugin already has a pending operation");
                        }

                        if (runningExternal) {
                            manager.blockUntilRestart(id);
                            changed();
                            ((PythonPluginAdapter) plugin).stopForRemoval();

                            CountDownLatch uiCleanup = new CountDownLatch(1);
                            AndroidUtilities.runOnUIThread(uiCleanup::countDown);

                            if (!uiCleanup.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("Plugin UI cleanup has not finished. Try deleting it again shortly.");
                            }
                        }

                        store.scheduleDelete(id);
                        pending = Collections.unmodifiableMap(store.pending());
                        PluginStorage.clearPlugin(id);

                        if (!PluginSettings.clearForPluginNow(id)
                                || !manager.clearPersistedEnabledStateNow(id)) {
                            throw new IOException("Cannot clear deleted plugin state");
                        }

                        store.applyPendingDelete(id);

                        if (runningExternal) {
                            manager.unregister(id);
                        } else {
                            LinkedHashMap<String, IncompatiblePlugin> remaining =
                                    new LinkedHashMap<>(incompatible);
                            remaining.remove(id);
                            incompatible = Collections.unmodifiableMap(remaining);
                        }

                        pending = Collections.unmodifiableMap(store.pending());
                    } catch (Exception error) {
                        failure = error;
                    }

                    removing.remove(id);
                    Exception error = failure;

                    AndroidUtilities.runOnUIThread(
                            () -> {
                                changed();
                                callback.complete(id, error);
                            });
                });
    }
}
