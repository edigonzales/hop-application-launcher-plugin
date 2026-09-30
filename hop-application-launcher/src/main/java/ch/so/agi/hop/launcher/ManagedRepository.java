package ch.so.agi.hop.launcher;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

/** Exclusive cross-process lease covers update, catalog validation and the entire execution. */
public final class ManagedRepository {
  private final LauncherSettings settings;

  public ManagedRepository(LauncherSettings settings) {
    this.settings = settings;
  }

  public static final class NetworkFailure extends IOException {
    public NetworkFailure(Exception cause) {
      super("Repository is unreachable: " + cause.getMessage(), cause);
    }
  }

  public Lease lock() throws Exception {
    Path checkout = settings.checkout().toAbsolutePath().normalize();
    if (checkout.getParent() == null)
      throw new IOException("Checkout must not be a filesystem root");
    Files.createDirectories(checkout.getParent());
    // Canonical parent prevents two lexical paths to the same checkout using different locks.
    checkout = checkout.getParent().toRealPath().resolve(checkout.getFileName());
    if (Files.isSymbolicLink(checkout))
      throw new IOException("Checkout must not be a symbolic link");
    FileChannel channel =
        FileChannel.open(
            checkout.resolveSibling(checkout.getFileName() + ".launcher.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) throw new IOException("Checkout is in use by another launcher");
      return new Lease(checkout, channel, lock);
    } catch (Exception e) {
      channel.close();
      throw new IOException("Checkout is in use or cannot be locked", e);
    }
  }

  public final class Lease implements AutoCloseable {
    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;

    private Lease(Path root, FileChannel channel, FileLock lock) {
      this.root = root;
      this.channel = channel;
      this.lock = lock;
    }

    public Path root() {
      return root;
    }

    public String update() throws Exception {
      validateSource();
      if (!Files.exists(root)) {
        Path staging = Files.createTempDirectory(root.getParent(), ".launcher-clone-");
        try {
          try (Git git =
              Git.cloneRepository()
                  .setURI(settings.repository())
                  .setBranch(settings.branch())
                  .setDirectory(staging.toFile())
                  .setTimeout(30)
                  .call()) {
            /* close before moving */
          }
          Files.move(staging, root);
        } catch (TransportException | org.eclipse.jgit.api.errors.InvalidRemoteException e) {
          throw new NetworkFailure(e);
        } finally {
          if (Files.exists(staging)) deleteStaging(staging);
        }
      }
      try (Git git = openValidated()) {
        org.eclipse.jgit.transport.FetchResult fetched;
        try {
          fetched = git.fetch().setRemote("origin").setTimeout(30).call();
        } catch (TransportException | org.eclipse.jgit.api.errors.InvalidRemoteException e) {
          throw new NetworkFailure(e);
        }
        var current = git.getRepository().resolve("HEAD");
        var remoteBranch = fetched.getAdvertisedRef("refs/heads/" + settings.branch());
        if (remoteBranch == null)
          throw new IOException("Remote branch does not exist: " + settings.branch());
        var target = remoteBranch.getObjectId();
        try (RevWalk walk = new RevWalk(git.getRepository())) {
          if (!walk.isMergedInto(walk.parseCommit(current), walk.parseCommit(target)))
            throw new IOException(
                "Local and remote history differ; refusing to overwrite local commits");
        }
        var merged =
            git.merge().include(target).setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call();
        if (!merged.getMergeStatus().isSuccessful())
          throw new IOException("Fast-forward failed: " + merged.getMergeStatus());
        return git.getRepository().resolve("HEAD").name();
      }
    }

    /** Validate even offline/running: never execute a stale form against a modified checkout. */
    public String revision() throws Exception {
      validateSource();
      try (Git git = openValidated()) {
        return git.getRepository().resolve("HEAD").name();
      }
    }

    private Git openValidated() throws Exception {
      if (!Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Not a managed Git checkout: " + root);
      Git git = Git.open(root.toFile());
      try {
        Repository repo = git.getRepository();
        if (!Objects.equals(
            repo.getConfig().getString("remote", "origin", "url"), settings.repository()))
          throw new IOException("Checkout origin differs from configured repository");
        if (!Objects.equals(repo.getFullBranch(), "refs/heads/" + settings.branch()))
          throw new IOException("Checkout branch differs from configured branch");
        if (!git.status().call().isClean())
          throw new IOException(
              "Checkout has local changes; automatic updates and execution are disabled");
        if (!repo.getRepositoryState().canCheckout())
          throw new IOException("Checkout has an unfinished Git operation");
        return git;
      } catch (Exception e) {
        git.close();
        throw e;
      }
    }

    @Override
    public void close() throws IOException {
      try {
        lock.release();
      } finally {
        channel.close();
      }
    }
  }

  private void validateSource() throws Exception {
    String source = settings.repository();
    if (source.isBlank() || !Repository.isValidRefName("refs/heads/" + settings.branch()))
      throw new IOException("Repository and valid branch are required");
    // Native paths (including Windows drive letters) and file URIs are useful for offline tests.
    if (source.startsWith("file:")) {
      Path.of(URI.create(source));
      return;
    }
    if (!source.startsWith("https:")) {
      Path local = Path.of(source);
      if (local.isAbsolute()) return;
    }
    URI uri = URI.create(source);
    if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
      throw new IOException(
          "Use public HTTPS or a local Git repository without embedded credentials");
  }

  private static void deleteStaging(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        p.toFile().setWritable(true);
        Files.deleteIfExists(p);
      }
    }
  }
}
