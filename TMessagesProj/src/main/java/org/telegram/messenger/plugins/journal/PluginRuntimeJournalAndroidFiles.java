package org.telegram.messenger.plugins.journal;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public final class PluginRuntimeJournalAndroidFiles
        implements PluginRuntimeJournalStore.FileOperations {
    private static final String STATE_FILE = "state";
    private static final String STATE_TEMPORARY_FILE = "state.tmp";
    private static final String INTENT_FILE = "intent";
    private static final String INTENT_TEMPORARY_FILE = "intent.tmp";
    private static final String LOCK_FILE = "lock";
    private static final Map<String, ReentrantLock> PROCESS_LOCKS = new HashMap<>();

    private final File root;
    private final ReentrantLock processLock;

    private FileOutputStream lockStream;
    private FileLock fileLock;
    private boolean acquired;

    public PluginRuntimeJournalAndroidFiles(File root) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("Plugin runtime journal root is required");
        }

        validateRoot(root);
        this.root = root;
        String canonicalRoot = root.getCanonicalPath();

        synchronized (PROCESS_LOCKS) {
            ReentrantLock existing = PROCESS_LOCKS.get(canonicalRoot);

            if (existing == null) {
                existing = new ReentrantLock();
                PROCESS_LOCKS.put(canonicalRoot, existing);
            }

            processLock = existing;
        }
    }

    @Override
    public void acquire() throws IOException {
        if (acquired) {
            throw new IllegalStateException("Plugin runtime journal files are already acquired");
        }

        processLock.lock();
        FileDescriptor descriptor = null;
        FileOutputStream stream = null;

        try {
            validateRoot(root);
            descriptor = Os.open(
                    file(LOCK_FILE).getAbsolutePath(),
                    OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_NOFOLLOW,
                    0600);
            StructStat stat = Os.fstat(descriptor);

            if (!OsConstants.S_ISREG(stat.st_mode)) {
                throw new IOException("Invalid plugin runtime journal lock file type");
            }

            stream = new FileOutputStream(descriptor);
            descriptor = null;
            FileChannel channel = stream.getChannel();
            FileLock lock = channel.lock();
            lockStream = stream;
            fileLock = lock;
            acquired = true;
        } catch (ErrnoException error) {
            IOException failure = new IOException("Unable to lock plugin runtime journal", error);
            cleanupAcquireFailure(descriptor, stream, failure);
            throw failure;
        } catch (IOException | RuntimeException error) {
            cleanupAcquireFailure(descriptor, stream, error);
            throw error;
        }
    }

    @Override
    public byte[] read(PluginRuntimeJournalStore.Name name, int maximumBytes) throws IOException {
        checkAcquired();
        FileDescriptor descriptor = null;

        try {
            descriptor = Os.open(
                    file(name).getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW,
                    0);
            StructStat stat = Os.fstat(descriptor);

            if (!OsConstants.S_ISREG(stat.st_mode)
                    || stat.st_size < 0
                    || stat.st_size > maximumBytes) {
                throw new IOException("Invalid plugin runtime journal file size or type");
            }

            try (FileInputStream input = new FileInputStream(descriptor);
                 ByteArrayOutputStream output = new ByteArrayOutputStream((int) stat.st_size)) {
                descriptor = null;
                byte[] buffer = new byte[8192];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);

                    if (output.size() > maximumBytes) {
                        throw new IOException("Plugin runtime journal file exceeds size limit");
                    }
                }

                return output.toByteArray();
            }
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) {
                return null;
            }

            throw new IOException("Unable to read plugin runtime journal file", error);
        } finally {
            closeDescriptor(descriptor);
        }
    }

    @Override
    public void writeSynced(PluginRuntimeJournalStore.Name name, byte[] value) throws IOException {
        checkAcquired();

        if (value == null) {
            throw new IllegalArgumentException("Plugin runtime journal file value is required");
        }

        FileDescriptor descriptor = null;

        try {
            descriptor = Os.open(
                    file(name).getAbsolutePath(),
                    OsConstants.O_WRONLY
                            | OsConstants.O_CREAT
                            | OsConstants.O_TRUNC
                            | OsConstants.O_NOFOLLOW,
                    0600);
            StructStat stat = Os.fstat(descriptor);

            if (!OsConstants.S_ISREG(stat.st_mode)) {
                throw new IOException("Invalid plugin runtime journal file type");
            }

            try (FileOutputStream output = new FileOutputStream(descriptor)) {
                descriptor = null;
                output.write(value);
                output.flush();
                output.getFD().sync();
            }
        } catch (ErrnoException error) {
            throw new IOException("Unable to write plugin runtime journal file", error);
        } finally {
            closeDescriptor(descriptor);
        }
    }

    @Override
    public void atomicReplace(
            PluginRuntimeJournalStore.Name source,
            PluginRuntimeJournalStore.Name target) throws IOException {
        checkAcquired();

        try {
            Os.rename(file(source).getAbsolutePath(), file(target).getAbsolutePath());
        } catch (ErrnoException error) {
            throw new IOException("Unable to publish plugin runtime journal file", error);
        }
    }

    @Override
    public void delete(PluginRuntimeJournalStore.Name name) throws IOException {
        checkAcquired();
        File target = file(name);

        try {
            StructStat stat = Os.lstat(target.getAbsolutePath());

            if (!OsConstants.S_ISREG(stat.st_mode)) {
                throw new IOException("Invalid plugin runtime journal file type");
            }

            Os.remove(target.getAbsolutePath());
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.ENOENT) {
                throw new IOException("Unable to remove plugin runtime journal file", error);
            }
        }
    }

    @Override
    public void syncDirectory() throws IOException {
        checkAcquired();
        FileDescriptor descriptor = null;

        try {
            descriptor = Os.open(root.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            StructStat stat = Os.fstat(descriptor);

            if (!OsConstants.S_ISDIR(stat.st_mode)) {
                throw new IOException("Invalid plugin runtime journal directory type");
            }

            Os.fsync(descriptor);
        } catch (ErrnoException error) {
            throw new IOException("Unable to sync plugin runtime journal directory", error);
        } finally {
            closeDescriptor(descriptor);
        }
    }

    @Override
    public void close() throws IOException {
        if (!acquired) {
            return;
        }

        IOException failure = null;

        if (fileLock != null) {
            try {
                fileLock.release();
                fileLock = null;
            } catch (IOException error) {
                failure = error;
            }
        }
        if (fileLock == null && lockStream != null) {
            try {
                lockStream.close();
                lockStream = null;
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                }
            }
        }

        if (fileLock == null && lockStream == null) {
            acquired = false;
            processLock.unlock();
        }

        if (failure != null) {
            throw failure;
        }
    }

    private void checkAcquired() {
        if (!acquired
                || fileLock == null
                || !fileLock.isValid()
                || lockStream == null
                || !processLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Plugin runtime journal files are not acquired");
        }
    }

    private void cleanupAcquireFailure(
            FileDescriptor descriptor,
            FileOutputStream stream,
            Throwable failure) {
        try {
            closeDescriptor(descriptor);
        } catch (IOException error) {
            failure.addSuppressed(error);
        }
        try {
            closeStream(stream);
        } catch (IOException error) {
            failure.addSuppressed(error);
        } finally {
            processLock.unlock();
        }
    }

    private File file(PluginRuntimeJournalStore.Name name) throws IOException {
        if (name == null) {
            throw new IOException("Invalid plugin runtime journal file name");
        }

        switch (name) {
            case STATE:
                return file(STATE_FILE);
            case STATE_TEMPORARY:
                return file(STATE_TEMPORARY_FILE);
            case INTENT:
                return file(INTENT_FILE);
            case INTENT_TEMPORARY:
                return file(INTENT_TEMPORARY_FILE);
            default:
                throw new IOException("Invalid plugin runtime journal file name");
        }
    }

    private File file(String name) throws IOException {
        if (!STATE_FILE.equals(name)
                && !STATE_TEMPORARY_FILE.equals(name)
                && !INTENT_FILE.equals(name)
                && !INTENT_TEMPORARY_FILE.equals(name)
                && !LOCK_FILE.equals(name)) {
            throw new IOException("Invalid plugin runtime journal file name");
        }

        return new File(root, name);
    }

    private static void validateRoot(File root) throws IOException {
        try {
            StructStat stat = Os.lstat(root.getAbsolutePath());

            if (!OsConstants.S_ISDIR(stat.st_mode)) {
                throw new IOException("Plugin runtime journal root is not a directory");
            }
        } catch (ErrnoException error) {
            throw new IOException("Unable to validate plugin runtime journal root", error);
        }
    }

    private static void closeDescriptor(FileDescriptor descriptor) throws IOException {
        if (descriptor == null) {
            return;
        }

        try {
            Os.close(descriptor);
        } catch (ErrnoException error) {
            throw new IOException("Unable to close plugin runtime journal file", error);
        }
    }

    private static void closeStream(FileOutputStream stream) throws IOException {
        if (stream != null) {
            stream.close();
        }
    }
}
