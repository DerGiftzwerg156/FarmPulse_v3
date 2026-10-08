package de.farmpulse.rpsim.desktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Single instance of the desktop mode: an exclusive lock on a file in the data folder. The operating system releases
 * it with the process, so a killed FarmPulse never blocks the next start.
 */
final class InstanceLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * The lock, or {@code null} when another FarmPulse holds it (also another lock of this JVM).
     *
     * @throws IOException when the lock file cannot be created at all
     */
    static InstanceLock tryAcquire(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return new InstanceLock(channel, lock);
            }
        } catch (OverlappingFileLockException e) {
            // held by this JVM
        } catch (IOException e) {
            closeQuietly(channel);
            throw e;
        }
        closeQuietly(channel);
        return null;
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException e) {
            // released with the channel anyway
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
    }
}
