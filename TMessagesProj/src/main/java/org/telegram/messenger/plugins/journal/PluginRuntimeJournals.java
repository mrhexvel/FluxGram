package org.telegram.messenger.plugins.journal;

import android.content.Context;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PluginRuntimeJournals {
    private static PluginRuntimeJournalCoordinator instance;

    private PluginRuntimeJournals() {
    }

    public static synchronized void initialize(Context context) {
        if (instance != null) {
            return;
        }
        if (context == null) {
            throw new IllegalArgumentException("Plugin runtime journal context is required");
        }

        Context applicationContext = context.getApplicationContext();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4),
                threadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> openStore(applicationContext, processInstanceId),
                PluginRuntimeJournals::newProcessInstanceId,
                executor);
        instance = coordinator;
        coordinator.start();
    }

    static synchronized PluginRuntimeJournalCoordinator get() {
        if (instance == null) {
            throw new IllegalStateException("Plugin runtime journal is not initialized");
        }

        return instance;
    }

    public static PluginRuntimeJournalCoordinator.RecordResult record(
            PluginRuntimeJournalInput input) {
        PluginRuntimeJournalCoordinator current;

        synchronized (PluginRuntimeJournals.class) {
            current = instance;
        }

        if (current == null) {
            return PluginRuntimeJournalCoordinator.RecordResult.UNAVAILABLE;
        }

        return current.record(input);
    }

    private static PluginRuntimeJournalStore openStore(
            Context context,
            long processInstanceId) throws IOException {
        File pluginRoot = new File(context.getNoBackupFilesDir(), "fluxgram_plugins");
        File journalRoot = new File(pluginRoot, "runtime_journal");

        if (!journalRoot.isDirectory() && !journalRoot.mkdirs()) {
            throw new IOException("Unable to create plugin runtime journal directory");
        }

        return new PluginRuntimeJournalStore(
                new PluginRuntimeJournalAndroidFiles(journalRoot),
                processInstanceId);
    }

    private static long newProcessInstanceId() {
        SecureRandom random = new SecureRandom();
        long value;

        do {
            value = random.nextLong() & Long.MAX_VALUE;
        } while (value == 0);

        return value;
    }

    private static ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, "plugin-runtime-journal");
            thread.setDaemon(true);
            return thread;
        };
    }
}
