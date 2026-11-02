import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCoordinator;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PluginRuntimeJournalCoordinatorCheck {
    public static void main(String[] args) throws Exception {
        backgroundBootstrapAndClose();
        loadRetryKeepsProcessIdentity();
        closeRetryKeepsOwnerThread();
    }

    private static void backgroundBootstrapAndClose() throws Exception {
        Thread caller = Thread.currentThread();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<Long> processIds = new ArrayList<>();
        List<FakeFiles> opened = new ArrayList<>();
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> {
                    processIds.add(processInstanceId);
                    FakeFiles files = new FakeFiles();
                    opened.add(files);
                    return new PluginRuntimeJournalStore(files, processInstanceId);
                },
                () -> 77,
                executor);

        coordinator.start();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        require(processIds.size() == 1 && processIds.get(0) == 77);
        require(opened.get(0).ownerThread != caller);
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
        require(opened.get(0).closeThread == opened.get(0).ownerThread);
        require(executor.isShutdown());
    }

    private static void loadRetryKeepsProcessIdentity() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<Long> processIds = new ArrayList<>();
        int[] attempts = {0};
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> {
                    processIds.add(processInstanceId);
                    FakeFiles files = new FakeFiles();
                    files.failRead = attempts[0]++ == 0;
                    return new PluginRuntimeJournalStore(files, processInstanceId);
                },
                () -> 88,
                executor);

        coordinator.start();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.UNAVAILABLE);
        require(coordinator.loadFailures() == 1);
        coordinator.start();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        require(processIds.size() == 2);
        require(processIds.get(0).equals(processIds.get(1)));
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
    }

    private static void closeRetryKeepsOwnerThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        FakeFiles files = new FakeFiles();
        files.failClose = true;
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> new PluginRuntimeJournalStore(files, processInstanceId),
                () -> 99,
                executor);

        coordinator.start();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        coordinator.close();
        awaitCloseFailures(coordinator, 1);
        require(coordinator.state() == PluginRuntimeJournalCoordinator.State.CLOSING);
        require(coordinator.closeFailures() == 1);
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
        require(files.closeAttempts == 2);
        require(files.closeThread == files.ownerThread);
    }

    private static void awaitState(
            PluginRuntimeJournalCoordinator coordinator,
            PluginRuntimeJournalCoordinator.State expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;

        while (System.currentTimeMillis() < deadline) {
            if (coordinator.state() == expected) {
                return;
            }

            Thread.sleep(5);
        }

        throw new AssertionError("Timed out waiting for " + expected
                + ", actual " + coordinator.state());
    }

    private static void awaitCloseFailures(
            PluginRuntimeJournalCoordinator coordinator,
            long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;

        while (System.currentTimeMillis() < deadline) {
            if (coordinator.closeFailures() == expected) {
                return;
            }

            Thread.sleep(5);
        }

        throw new AssertionError("Timed out waiting for journal close failure");
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("Plugin runtime journal coordinator check failed");
        }
    }

    private static final class FakeFiles implements PluginRuntimeJournalStore.FileOperations {
        private final Map<PluginRuntimeJournalStore.Name, byte[]> values =
                new EnumMap<>(PluginRuntimeJournalStore.Name.class);

        private Thread ownerThread;
        private Thread closeThread;
        private boolean failRead;
        private boolean failClose;
        private int closeAttempts;

        @Override
        public void acquire() {
            ownerThread = Thread.currentThread();
        }

        @Override
        public byte[] read(PluginRuntimeJournalStore.Name name, int maximumBytes) throws IOException {
            require(Thread.currentThread() == ownerThread);

            if (failRead) {
                failRead = false;
                throw new IOException("Injected journal load failure");
            }

            byte[] value = values.get(name);
            return value == null ? null : value.clone();
        }

        @Override
        public void writeSynced(PluginRuntimeJournalStore.Name name, byte[] value) {
            require(Thread.currentThread() == ownerThread);
            values.put(name, value.clone());
        }

        @Override
        public void atomicReplace(
                PluginRuntimeJournalStore.Name source,
                PluginRuntimeJournalStore.Name target) {
            require(Thread.currentThread() == ownerThread);
            values.put(target, values.remove(source));
        }

        @Override
        public void delete(PluginRuntimeJournalStore.Name name) {
            require(Thread.currentThread() == ownerThread);
            values.remove(name);
        }

        @Override
        public void syncDirectory() {
            require(Thread.currentThread() == ownerThread);
        }

        @Override
        public void close() throws IOException {
            require(Thread.currentThread() == ownerThread);
            closeThread = Thread.currentThread();
            closeAttempts++;

            if (failClose) {
                failClose = false;
                throw new IOException("Injected journal close failure");
            }
        }
    }
}
