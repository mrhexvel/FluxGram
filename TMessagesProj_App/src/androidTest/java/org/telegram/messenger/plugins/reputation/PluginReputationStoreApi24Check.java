package org.telegram.messenger.plugins.reputation;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public final class PluginReputationStoreApi24Check {
    private static final String AUTHORITY_KEY_ID =
            "p256:0000000000000000000000000000000000000000000000000000000000000000";

    private PluginReputationStoreApi24Check() {
    }

    public static String run(Context context) throws Exception {
        require(Build.VERSION.SDK_INT == 24, "Expected Android API 24");

        File parent = new File(
                context.getCacheDir(),
                "plugin-reputation-api24-" + UUID.randomUUID());
        require(parent.mkdir(), "Unable to create reputation fixture root");

        int cases = 0;

        try {
            File stable = childDirectory(parent, "stable");
            PluginReputationStore store = new PluginReputationStore(stable, AUTHORITY_KEY_ID);
            PluginReputationStore.Snapshot initial = store.load(100);
            cases++;

            require(initial.generation() == 0, "Initial generation changed");
            require(initial.cache.size() == 0, "Initial cache is not empty");
            require(filesEqual(
                    new File(stable, PluginReputationStore.BASELINE_FILE),
                    new File(stable, PluginReputationStore.CURRENT_FILE)),
                    "Stable snapshots differ");
            require(new File(stable, PluginReputationStore.LOCK_FILE).isFile(),
                    "Store lock was not created");
            require(!new File(stable, PluginReputationStore.TRANSACTION_FILE).exists(),
                    "Stable transaction was not removed");

            PluginReputationStore reopened = new PluginReputationStore(stable, AUTHORITY_KEY_ID);
            require(reopened.load(100).generation() == 0, "Store lock was not released");
            cases++;

            File current = new File(stable, PluginReputationStore.CURRENT_FILE);
            require(current.delete(), "Unable to remove current mirror");
            require(reopened.load(100).generation() == 0, "Missing current changed generation");
            require(filesEqual(
                    new File(stable, PluginReputationStore.BASELINE_FILE),
                    current),
                    "Missing current was not restored");
            cases++;

            write(current, new byte[] {1, 2, 3});
            require(reopened.load(100).generation() == 0, "Corrupt current changed generation");
            require(filesEqual(
                    new File(stable, PluginReputationStore.BASELINE_FILE),
                    current),
                    "Corrupt current was not restored");
            cases++;

            File ambiguous = childDirectory(parent, "ambiguous");
            write(new File(ambiguous, "unknown"), new byte[] {1});
            expectFailure(() -> new PluginReputationStore(ambiguous, AUTHORITY_KEY_ID).load(100));
            cases++;

            File directoryBaseline = childDirectory(parent, "directory-baseline");
            require(new File(directoryBaseline, PluginReputationStore.BASELINE_FILE).mkdir(),
                    "Unable to create baseline directory");
            expectFailure(() -> new PluginReputationStore(
                    directoryBaseline,
                    AUTHORITY_KEY_ID).load(100));
            cases++;

            File lockSymlink = childDirectory(parent, "lock-symlink");
            File lockTarget = new File(parent, "lock-target");
            write(lockTarget, new byte[] {1});
            Os.symlink(
                    lockTarget.getAbsolutePath(),
                    new File(lockSymlink, PluginReputationStore.LOCK_FILE).getAbsolutePath());
            expectFailure(() -> new PluginReputationStore(lockSymlink, AUTHORITY_KEY_ID).load(100));
            cases++;

            File realRoot = childDirectory(parent, "real-root");
            File rootSymlink = new File(parent, "root-symlink");
            Os.symlink(realRoot.getAbsolutePath(), rootSymlink.getAbsolutePath());
            expectFailure(() -> new PluginReputationStore(rootSymlink, AUTHORITY_KEY_ID));
            cases++;
        } finally {
            deleteTree(parent);
        }

        return cases + " cases";
    }

    private static File childDirectory(File parent, String name) {
        File directory = new File(parent, name);
        require(directory.mkdir(), "Unable to create " + name);
        return directory;
    }

    private static boolean filesEqual(File first, File second) throws IOException {
        return Arrays.equals(read(first), read(second));
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            return output.toByteArray();
        }
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
            throw new AssertionError("Expected reputation store failure");
        } catch (IOException expected) {
        }
    }

    private static void deleteTree(File file) throws Exception {
        StructStat stat = Os.lstat(file.getAbsolutePath());

        if (OsConstants.S_ISDIR(stat.st_mode)) {
            File[] children = file.listFiles();

            if (children == null) {
                throw new IOException("Unable to list reputation fixture");
            }
            for (File child : children) {
                deleteTree(child);
            }
        }

        require(file.delete(), "Unable to delete reputation fixture");
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
