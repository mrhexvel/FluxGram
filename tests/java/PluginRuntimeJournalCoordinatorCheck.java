import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCatalog;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCoordinator;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalExport;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalInput;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class PluginRuntimeJournalCoordinatorCheck {
    private static final String ARCHIVE = repeat("cd", 32);

    public static void main(String[] args) throws Exception {
        backgroundBootstrapAndClose();
        prestartRecordPersists();
        commitRetryKeepsBatch();
        loadRetryKeepsProcessIdentity();
        closeRetryKeepsOwnerThread();
        unavailableExportDoesNotOpenStore();
        rejectedExportIsNotQueued();
        readOnlyExportUsesWorker();
        exportQueueIsBounded();
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
        require(coordinator.record(input())
                == PluginRuntimeJournalCoordinator.RecordResult.ACCEPTED);
        awaitStateFile(opened.get(0));
        require(opened.get(0).writeThread == opened.get(0).ownerThread);
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
        require(opened.get(0).closeThread == opened.get(0).ownerThread);
        require(executor.isShutdown());
    }

    private static void loadRetryKeepsProcessIdentity() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<Long> processIds = new ArrayList<>();
        List<FakeFiles> opened = new ArrayList<>();
        int[] attempts = {0};
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> {
                    processIds.add(processInstanceId);
                    FakeFiles files = new FakeFiles();
                    files.failRead = attempts[0]++ == 0;
                    opened.add(files);
                    return new PluginRuntimeJournalStore(files, processInstanceId);
                },
                () -> 88,
                executor);

        require(coordinator.record(input())
                == PluginRuntimeJournalCoordinator.RecordResult.QUEUED);
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        awaitStateFile(opened.get(1));
        require(processIds.size() == 2);
        require(processIds.get(0).equals(processIds.get(1)));
        require(coordinator.loadFailures() == 1);
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
    }

    private static void prestartRecordPersists() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        FakeFiles files = new FakeFiles();
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> new PluginRuntimeJournalStore(files, processInstanceId),
                () -> 78,
                executor);

        require(coordinator.record(input())
                == PluginRuntimeJournalCoordinator.RecordResult.QUEUED);
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        awaitStateFile(files);
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
    }

    private static void commitRetryKeepsBatch() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        FakeFiles files = new FakeFiles();
        files.failWrite = true;
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> new PluginRuntimeJournalStore(files, processInstanceId),
                () -> 79,
                executor);

        coordinator.start();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        require(coordinator.record(input())
                == PluginRuntimeJournalCoordinator.RecordResult.ACCEPTED);
        awaitStateFile(files);
        require(coordinator.commitFailures() == 1);
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

    private static void readOnlyExportUsesWorker() throws Exception {
        Thread caller = Thread.currentThread();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        FakeFiles files = new FakeFiles();
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> new PluginRuntimeJournalStore(files, processInstanceId),
                () -> 101,
                executor);

        coordinator.start();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        require(coordinator.record(input())
                == PluginRuntimeJournalCoordinator.RecordResult.ACCEPTED);
        awaitStateFile(files);
        int writesBefore = files.writeCount;
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<PluginRuntimeJournalCoordinator.ExportStatus> status =
                new AtomicReference<>();
        AtomicReference<PluginRuntimeJournalExport> exported = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        require(coordinator.export((result, value) -> {
            status.set(result);
            exported.set(value);
            callbackThread.set(Thread.currentThread());
            completed.countDown();
        }) == PluginRuntimeJournalCoordinator.ExportRequestResult.QUEUED);
        require(completed.await(5, TimeUnit.SECONDS));
        require(status.get() == PluginRuntimeJournalCoordinator.ExportStatus.EXPORTED);
        require(exported.get() != null);
        require(callbackThread.get() == files.ownerThread);
        require(callbackThread.get() != caller);
        require(files.writeCount == writesBefore);
        String json = new String(
                exported.get().bytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        require(json.contains("\"kind\":\"runtime_generation_running\""));
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
    }

    private static void unavailableExportDoesNotOpenStore() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        int[] opens = {0};
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> {
                    opens[0]++;
                    return new PluginRuntimeJournalStore(new FakeFiles(), processInstanceId);
                },
                () -> 103,
                executor);

        require(coordinator.export((status, export) -> {
        }) == PluginRuntimeJournalCoordinator.ExportRequestResult.UNAVAILABLE);
        require(opens[0] == 0);
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
    }

    private static void rejectedExportIsNotQueued() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> new PluginRuntimeJournalStore(
                        new FakeFiles(),
                        processInstanceId),
                () -> 104,
                executor);

        coordinator.start();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        executor.shutdown();
        require(executor.awaitTermination(5, TimeUnit.SECONDS));
        int[] callbacks = {0};
        require(coordinator.export((status, export) -> callbacks[0]++)
                == PluginRuntimeJournalCoordinator.ExportRequestResult.UNAVAILABLE);
        require(callbacks[0] == 0);
    }

    private static void exportQueueIsBounded() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(8);
        PluginRuntimeJournalCoordinator coordinator = new PluginRuntimeJournalCoordinator(
                processInstanceId -> new PluginRuntimeJournalStore(
                        new FakeFiles(),
                        processInstanceId),
                () -> 102,
                executor);

        coordinator.start();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.RUNNING);
        executor.execute(() -> {
            try {
                releaseWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });

        for (int i = 0; i < 8; i++) {
            require(coordinator.export((status, export) -> completed.countDown())
                    == PluginRuntimeJournalCoordinator.ExportRequestResult.QUEUED);
        }

        require(coordinator.export((status, export) -> {
        }) == PluginRuntimeJournalCoordinator.ExportRequestResult.OVERLOADED);
        releaseWorker.countDown();
        require(completed.await(5, TimeUnit.SECONDS));
        coordinator.close();
        awaitState(coordinator, PluginRuntimeJournalCoordinator.State.CLOSED);
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

    private static void awaitStateFile(FakeFiles files) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;

        while (System.currentTimeMillis() < deadline) {
            synchronized (files.values) {
                if (files.values.containsKey(PluginRuntimeJournalStore.Name.STATE)) {
                    return;
                }
            }

            Thread.sleep(5);
        }

        throw new AssertionError("Timed out waiting for persisted journal event");
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

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);

        for (int i = 0; i < count; i++) {
            result.append(value);
        }

        return result.toString();
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
        private Thread writeThread;
        private boolean failRead;
        private boolean failWrite;
        private boolean failClose;
        private int closeAttempts;
        private int writeCount;

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

            synchronized (values) {
                byte[] value = values.get(name);
                return value == null ? null : value.clone();
            }
        }

        @Override
        public void writeSynced(
                PluginRuntimeJournalStore.Name name,
                byte[] value) throws IOException {
            require(Thread.currentThread() == ownerThread);
            writeThread = Thread.currentThread();
            writeCount++;

            if (failWrite) {
                failWrite = false;
                throw new IOException("Injected journal write failure");
            }

            synchronized (values) {
                values.put(name, value.clone());
            }
        }

        @Override
        public void atomicReplace(
                PluginRuntimeJournalStore.Name source,
                PluginRuntimeJournalStore.Name target) {
            require(Thread.currentThread() == ownerThread);

            synchronized (values) {
                values.put(target, values.remove(source));
            }
        }

        @Override
        public void delete(PluginRuntimeJournalStore.Name name) {
            require(Thread.currentThread() == ownerThread);

            synchronized (values) {
                values.remove(name);
            }
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
