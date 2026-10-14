package com.api.decouper.samples.support;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Test-only local process coordination around resetting a shared schema; not a component lock. */
final class TestDatabaseLease implements AutoCloseable {
  private static final ConcurrentHashMap<String, ReentrantLock> GUARDS = new ConcurrentHashMap<>();
  private final ReentrantLock guard;
  private final FileChannel channel;
  private final FileLock fileLock;
  private boolean closed;

  private TestDatabaseLease(ReentrantLock guard, FileChannel channel, FileLock fileLock) {
    this.guard = guard;
    this.channel = channel;
    this.fileLock = fileLock;
  }

  static TestDatabaseLease acquire(String jdbcUrl) throws IOException {
    URI uri = URI.create(jdbcUrl.substring(5));
    String identity =
        uri.getHost().toLowerCase(Locale.ROOT)
            + ":"
            + (uri.getPort() < 0 ? 3306 : uri.getPort())
            + uri.getPath();
    String key;
    try {
      key =
          String.format(
              "%064x",
              new BigInteger(
                  1,
                  MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(StandardCharsets.UTF_8))));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
    ReentrantLock guard = GUARDS.computeIfAbsent(key, ignored -> new ReentrantLock());
    try {
      guard.lockInterruptibly();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for shared test database", ex);
    }
    FileChannel channel = null;
    try {
      Path path =
          Paths.get(System.getProperty("java.io.tmpdir"), "api-decouper-test-" + key + ".lock");
      channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      return new TestDatabaseLease(guard, channel, channel.lock());
    } catch (IOException | RuntimeException ex) {
      if (channel != null) {
        try {
          channel.close();
        } catch (IOException cleanup) {
          ex.addSuppressed(cleanup);
        }
      }
      guard.unlock();
      throw ex;
    }
  }

  public void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      fileLock.release();
    } finally {
      try {
        channel.close();
      } finally {
        guard.unlock();
      }
    }
    // Keep the empty lock file: deleting it could let a waiting process lock a different inode.
  }
}
