import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCatalog;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCollector;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalInput;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalSnapshot;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalStore;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class PluginRuntimeJournalStoreCheck {
    private static final String ARCHIVE = repeat("cd", 32);

    public static void main(String[] args) throws Exception {
        initialLoadAndCommit();
        dropsOnlyBatch();
        crashRecovery();
        staleIntentDoesNotRollback();
        corruptionRecovery();
        ownerAndClose();
    }

    private static void initialLoadAndCommit() throws Exception {
        FakeFiles files = new FakeFiles();
        PluginRuntimeJournalStore store = new PluginRuntimeJournalStore(files, 100);
        PluginRuntimeJournalStore.LoadResult empty = store.load();
        require(empty.recovery == PluginRuntimeJournalStore.Recovery.CLEAN);
        require(empty.snapshot.events.isEmpty());
        require(empty.nextSequence == 1);

        PluginRuntimeJournalCollector collector = new PluginRuntimeJournalCollector(100, 1);
        require(collector.offer(input(1)) == PluginRuntimeJournalCollector.OfferResult.ACCEPTED);
        PluginRuntimeJournalCollector.Batch first = collector.drain();
        PluginRuntimeJournalStore.CommitResult committed = store.commit(first);
        require(committed.status == PluginRuntimeJournalStore.CommitStatus.COMMITTED);
        require(committed.snapshot.events.size() == 1);
        require(store.commit(first).status
                == PluginRuntimeJournalStore.CommitStatus.ALREADY_COMMITTED);

        PluginRuntimeJournalCollector conflictingCollector =
                new PluginRuntimeJournalCollector(100, 2);
        require(conflictingCollector.offer(input(99))
                == PluginRuntimeJournalCollector.OfferResult.ACCEPTED);
        expectIo(() -> store.commit(conflictingCollector.drain()));
        conflictingCollector.close();

        require(collector.offer(input(1)) == PluginRuntimeJournalCollector.OfferResult.ACCEPTED);
        PluginRuntimeJournalCollector.Batch second = collector.drain();
        require(store.commit(second).snapshot.events.size() == 2);
        expectIo(() -> store.commit(first));
        store.close();

        PluginRuntimeJournalStore reopened = new PluginRuntimeJournalStore(files, 101);
        PluginRuntimeJournalStore.LoadResult loaded = reopened.load();
        require(loaded.snapshot.events.size() == 2);
        require(loaded.nextSequence == 3);
        require(!loaded.sequenceExhausted);
        require(loaded.nextBatchId == 1);
        require(!loaded.batchExhausted);
        reopened.close();
        collector.close();
    }

    private static void dropsOnlyBatch() throws Exception {
        FakeFiles files = new FakeFiles();
        PluginRuntimeJournalStore store = new PluginRuntimeJournalStore(files, 200);
        store.load();
        PluginRuntimeJournalCollector collector = new PluginRuntimeJournalCollector(
                200,
                Long.MAX_VALUE);
        require(collector.offer(input(1)) == PluginRuntimeJournalCollector.OfferResult.ACCEPTED);
        store.commit(collector.drain());
        require(collector.offer(input(1))
                == PluginRuntimeJournalCollector.OfferResult.DROPPED_SEQUENCE_EXHAUSTED);
        PluginRuntimeJournalCollector.Batch drops = collector.drain();
        require(drops.events.isEmpty());
        require(drops.droppedEvents == 1);
        require(store.commit(drops).snapshot.droppedEvents == 1);
        require(store.commit(drops).status
                == PluginRuntimeJournalStore.CommitStatus.ALREADY_COMMITTED);
        store.close();
        collector.close();
    }

    private static void crashRecovery() throws Exception {
        PluginRuntimeJournalCollector collector = new PluginRuntimeJournalCollector(300, 1);
        collector.offer(input(1));
        PluginRuntimeJournalCollector.Batch batch = collector.drain();

        for (boolean failBefore : new boolean[] {true, false}) {
            for (int failure = 1; failure <= 8; failure++) {
                FakeFiles files = new FakeFiles();
                files.failAfter = failure;
                files.failBefore = failBefore;
                PluginRuntimeJournalStore store = new PluginRuntimeJournalStore(files, 300);
                store.load();
                expectIo(() -> store.commit(batch));
                store.close();
                files.failAfter = 0;
                files.operations = 0;

                PluginRuntimeJournalStore recovered = new PluginRuntimeJournalStore(files, 300);
                PluginRuntimeJournalStore.LoadResult result = recovered.load();
                int expectedEvents = failure == 1 || failBefore && failure == 2 ? 0 : 1;
                require(result.snapshot.events.size() == expectedEvents);

                if (expectedEvents == 1) {
                    require(recovered.commit(batch).status
                            == PluginRuntimeJournalStore.CommitStatus.ALREADY_COMMITTED);
                }

                recovered.close();
            }
        }

        collector.close();
    }

    private static void corruptionRecovery() throws Exception {
        FakeFiles corruptState = new FakeFiles();
        corruptState.values.put(PluginRuntimeJournalStore.Name.STATE, new byte[] {1, 2, 3});
        PluginRuntimeJournalStore reset = new PluginRuntimeJournalStore(corruptState, 400);
        PluginRuntimeJournalStore.LoadResult resetResult = reset.load();
        require(resetResult.recovery == PluginRuntimeJournalStore.Recovery.RESET_CORRUPT_STATE);
        require(resetResult.snapshot.events.isEmpty());
        reset.close();

        FakeFiles corruptIntent = new FakeFiles();
        PluginRuntimeJournalStore stable = new PluginRuntimeJournalStore(corruptIntent, 401);
        stable.load();
        PluginRuntimeJournalCollector collector = new PluginRuntimeJournalCollector(401, 1);
        collector.offer(input(1));
        stable.commit(collector.drain());
        stable.close();
        byte[] stableState = corruptIntent.values.get(PluginRuntimeJournalStore.Name.STATE).clone();
        corruptIntent.values.put(PluginRuntimeJournalStore.Name.INTENT, new byte[] {4, 5, 6});

        PluginRuntimeJournalStore ignored = new PluginRuntimeJournalStore(corruptIntent, 402);
        PluginRuntimeJournalStore.LoadResult ignoredResult = ignored.load();
        require(ignoredResult.recovery
                == PluginRuntimeJournalStore.Recovery.IGNORED_CORRUPT_INTENT);
        require(ignoredResult.snapshot.events.size() == 1);
        require(Arrays.equals(
                stableState,
                corruptIntent.values.get(PluginRuntimeJournalStore.Name.STATE)));
        ignored.close();
        collector.close();

        FakeFiles repeatedFailure = new FakeFiles();
        PluginRuntimeJournalStore repeated = new PluginRuntimeJournalStore(repeatedFailure, 403);
        repeated.load();
        repeatedFailure.failReads = true;
        expectIo(repeated::load);
        expectInvalidState(repeated::snapshot);
        repeated.close();
    }

    private static void staleIntentDoesNotRollback() throws Exception {
        FakeFiles files = new FakeFiles();
        PluginRuntimeJournalCollector firstCollector = new PluginRuntimeJournalCollector(350, 1);
        Thread.sleep(5);
        firstCollector.offer(input(1));
        PluginRuntimeJournalCollector.Batch first = firstCollector.drain();
        files.failAfter = 2;
        PluginRuntimeJournalStore failed = new PluginRuntimeJournalStore(files, 350);
        failed.load();
        expectIo(() -> failed.commit(first));
        failed.close();
        byte[] staleIntent = files.values.get(PluginRuntimeJournalStore.Name.INTENT).clone();
        files.failAfter = 0;
        files.operations = 0;

        PluginRuntimeJournalStore recovered = new PluginRuntimeJournalStore(files, 350);
        PluginRuntimeJournalStore.LoadResult firstLoad = recovered.load();
        require(firstLoad.snapshot.events.size() == 1);
        require(firstLoad.nextSequence == 2);
        require(firstLoad.nextBatchId == 2);
        PluginRuntimeJournalCollector secondCollector = new PluginRuntimeJournalCollector(
                350,
                firstLoad.nextSequence,
                firstLoad.nextBatchId,
                firstLoad.nextMonotonicTimeMillis);
        secondCollector.offer(input(2));
        recovered.commit(secondCollector.drain());
        recovered.close();
        files.values.put(PluginRuntimeJournalStore.Name.INTENT, staleIntent);

        PluginRuntimeJournalStore stable = new PluginRuntimeJournalStore(files, 350);
        PluginRuntimeJournalStore.LoadResult stableLoad = stable.load();
        require(stableLoad.recovery == PluginRuntimeJournalStore.Recovery.IGNORED_STALE_INTENT);
        require(stableLoad.snapshot.events.size() == 2);
        require(stableLoad.snapshot.events.get(1).monotonicTimeMillis
                >= stableLoad.snapshot.events.get(0).monotonicTimeMillis);
        stable.close();
        firstCollector.close();
        secondCollector.close();
    }

    private static void ownerAndClose() throws Exception {
        PluginRuntimeJournalStore store = new PluginRuntimeJournalStore(new FakeFiles(), 500);
        store.load();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                store.snapshot();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        other.start();
        other.join(5000);
        require(failure.get() instanceof IllegalStateException);
        store.close();
        store.close();
        expectInvalidState(store::snapshot);
    }

    private static PluginRuntimeJournalInput input(long runtimeInstanceId) {
        return new PluginRuntimeJournalInput(
                runtimeInstanceId,
                "example.plugin",
                ARCHIVE,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                null,
                0);
    }

    private static final class FakeFiles implements PluginRuntimeJournalStore.FileOperations {
        private final Map<PluginRuntimeJournalStore.Name, byte[]> values =
                new EnumMap<>(PluginRuntimeJournalStore.Name.class);
        private int failAfter;
        private int operations;
        private boolean failBefore;
        private boolean failReads;

        @Override
        public byte[] read(PluginRuntimeJournalStore.Name name, int maximumBytes) throws IOException {
            if (failReads) {
                throw new IOException("Injected fixture read failure");
            }

            byte[] value = values.get(name);

            if (value == null) {
                return null;
            }
            if (value.length > maximumBytes) {
                throw new IOException("Fixture value exceeds limit");
            }

            return value.clone();
        }

        @Override
        public void writeSynced(PluginRuntimeJournalStore.Name name, byte[] value) throws IOException {
            beforeMutation();
            values.put(name, value.clone());
            mutate();
        }

        @Override
        public void atomicReplace(
                PluginRuntimeJournalStore.Name source,
                PluginRuntimeJournalStore.Name target) throws IOException {
            beforeMutation();
            byte[] value = values.remove(source);

            if (value == null) {
                throw new IOException("Fixture source is missing");
            }

            values.put(target, value);
            mutate();
        }

        @Override
        public void delete(PluginRuntimeJournalStore.Name name) throws IOException {
            beforeMutation();
            values.remove(name);
            mutate();
        }

        @Override
        public void syncDirectory() throws IOException {
            beforeMutation();
            mutate();
        }

        private void mutate() throws IOException {
            operations++;

            if (!failBefore && failAfter > 0 && operations == failAfter) {
                throw new IOException("Injected fixture failure");
            }
        }

        private void beforeMutation() throws IOException {
            if (failBefore && failAfter > 0 && operations + 1 == failAfter) {
                operations++;
                throw new IOException("Injected fixture failure");
            }
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);

        for (int i = 0; i < count; i++) {
            result.append(value);
        }

        return result.toString();
    }

    private static void expectIo(CheckedRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void expectInvalidState(CheckedRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
