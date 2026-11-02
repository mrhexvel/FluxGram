package org.telegram.messenger.plugins.journal;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.util.UUID;

public final class PluginRuntimeJournalStoreApi24Check {
    private static final String ARCHIVE = repeat("cd", 32);

    private PluginRuntimeJournalStoreApi24Check() {
    }

    public static String run(Context context) throws Exception {
        require(Build.VERSION.SDK_INT == 24, "Expected Android API 24");

        File parent = new File(
                context.getCacheDir(),
                "plugin-runtime-journal-api24-" + UUID.randomUUID());
        require(parent.mkdir(), "Unable to create journal fixture root");
        int cases = 0;

        try {
            File stable = childDirectory(parent, "stable");
            PluginRuntimeJournalStore store = open(stable, 100);
            PluginRuntimeJournalStore.LoadResult initial = store.load();
            require(initial.snapshot.events.isEmpty(), "Initial journal is not empty");
            PluginRuntimeJournalCollector collector = new PluginRuntimeJournalCollector(100, 1);
            require(collector.offer(input()) == PluginRuntimeJournalCollector.OfferResult.ACCEPTED,
                    "Unable to collect journal event");
            store.commit(collector.drain());
            store.close();
            collector.close();
            require(new File(stable, PluginRuntimeJournalAndroidFiles.STATE_FILE).isFile(),
                    "Journal state was not published");
            require(new File(stable, PluginRuntimeJournalAndroidFiles.LOCK_FILE).isFile(),
                    "Journal lock was not created");
            require(!new File(stable, PluginRuntimeJournalAndroidFiles.INTENT_FILE).exists(),
                    "Stable journal intent was not removed");
            cases++;

            PluginRuntimeJournalStore reopened = open(stable, 101);
            require(reopened.load().snapshot.events.size() == 1,
                    "Reopened journal lost persisted event");
            reopened.close();
            cases++;

            File corrupt = childDirectory(parent, "corrupt");
            write(new File(corrupt, PluginRuntimeJournalAndroidFiles.STATE_FILE),
                    new byte[] {1, 2, 3});
            PluginRuntimeJournalStore reset = open(corrupt, 200);
            require(reset.load().recovery
                            == PluginRuntimeJournalStore.Recovery.RESET_CORRUPT_STATE,
                    "Corrupt journal state was not reset");
            reset.close();
            cases++;

            File stateDirectory = childDirectory(parent, "state-directory");
            require(new File(stateDirectory, PluginRuntimeJournalAndroidFiles.STATE_FILE).mkdir(),
                    "Unable to create journal state directory");
            expectLoadFailure(stateDirectory, 300);
            cases++;

            File stateSymlink = childDirectory(parent, "state-symlink");
            File stateTarget = new File(parent, "state-target");
            write(stateTarget, new byte[] {1});
            Os.symlink(
                    stateTarget.getAbsolutePath(),
                    new File(stateSymlink, PluginRuntimeJournalAndroidFiles.STATE_FILE)
                            .getAbsolutePath());
            expectLoadFailure(stateSymlink, 400);
            cases++;

            File lockSymlink = childDirectory(parent, "lock-symlink");
            File lockTarget = new File(parent, "lock-target");
            write(lockTarget, new byte[] {1});
            Os.symlink(
                    lockTarget.getAbsolutePath(),
                    new File(lockSymlink, PluginRuntimeJournalAndroidFiles.LOCK_FILE)
                            .getAbsolutePath());
            expectFailure(() -> open(lockSymlink, 500));
            cases++;

            File realRoot = childDirectory(parent, "real-root");
            File rootSymlink = new File(parent, "root-symlink");
            Os.symlink(realRoot.getAbsolutePath(), rootSymlink.getAbsolutePath());
            expectFailure(() -> new PluginRuntimeJournalAndroidFiles(rootSymlink));
            cases++;

            File locked = childDirectory(parent, "locked");
            PluginRuntimeJournalStore first = open(locked, 600);
            expectOverlappingLock(() -> open(locked, 601));
            first.close();
            PluginRuntimeJournalStore second = open(locked, 601);
            second.close();
            cases++;
        } finally {
            deleteTree(parent);
        }

        return cases + " cases";
    }

    private static PluginRuntimeJournalStore open(File root, long processInstanceId)
            throws IOException {
        return new PluginRuntimeJournalStore(
                new PluginRuntimeJournalAndroidFiles(root),
                processInstanceId);
    }

    private static PluginRuntimeJournalInput input() {
        return new PluginRuntimeJournalInput(
                1,
                "example.plugin",
                ARCHIVE,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                null,
                0);
    }

    private static File childDirectory(File parent, String name) {
        File directory = new File(parent, name);
        require(directory.mkdir(), "Unable to create " + name);
        return directory;
    }

    private static void write(File file, byte[] value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void expectFailure(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected journal store failure");
        } catch (IOException expected) {
        }
    }

    private static void expectLoadFailure(File root, long processInstanceId) throws Exception {
        PluginRuntimeJournalStore store = open(root, processInstanceId);
        boolean failed = false;

        try {
            store.load();
        } catch (IOException expected) {
            failed = true;
        } finally {
            store.close();
        }

        require(failed, "Expected journal load failure");
    }

    private static void expectOverlappingLock(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected overlapping journal lock");
        } catch (OverlappingFileLockException expected) {
        }
    }

    private static void deleteTree(File file) throws Exception {
        StructStat stat = Os.lstat(file.getAbsolutePath());

        if (OsConstants.S_ISDIR(stat.st_mode)) {
            File[] children = file.listFiles();

            if (children == null) {
                throw new IOException("Unable to list journal fixture");
            }
            for (File child : children) {
                deleteTree(child);
            }
        }

        require(file.delete(), "Unable to delete journal fixture");
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);

        for (int i = 0; i < count; i++) {
            result.append(value);
        }

        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
