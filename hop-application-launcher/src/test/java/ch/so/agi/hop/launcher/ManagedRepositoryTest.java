package ch.so.agi.hop.launcher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRepositoryTest {
  @TempDir Path temp;

  private Git source() throws Exception {
    Git git =
        Git.init().setInitialBranch("main").setDirectory(temp.resolve("source").toFile()).call();
    commit(git, "first");
    return git;
  }

  private void commit(Git git, String value) throws Exception {
    Files.writeString(git.getRepository().getWorkTree().toPath().resolve("data.txt"), value);
    git.add().addFilepattern(".").call();
    git.commit().setMessage(value).setAuthor("Test", "test@example.invalid").call();
  }

  @Test
  void multipleRepositoriesAndBranchesKeepIndependentCheckoutsAndOfflineRevisions()
      throws Exception {
    try (Git first = source();
        Git second =
            Git.init()
                .setInitialBranch("release")
                .setDirectory(temp.resolve("second-source").toFile())
                .call()) {
      commit(second, "second repository");
      var a =
          new LauncherSettings.RepositoryEntry(
              "a", "First", first.getRepository().getWorkTree().toString(), "main");
      var b =
          new LauncherSettings.RepositoryEntry(
              "b", "Second", second.getRepository().getWorkTree().toString(), "release");
      Path base = temp.resolve("repositories");
      var repoA = new ManagedRepository(a.location(base));
      var repoB = new ManagedRepository(b.location(base));
      String revisionA, revisionB;
      try (var leaseA = repoA.lock();
          var leaseB = repoB.lock()) {
        revisionA = leaseA.update();
        revisionB = leaseB.update();
        assertEquals("first", Files.readString(leaseA.root().resolve("data.txt")));
        assertEquals("second repository", Files.readString(leaseB.root().resolve("data.txt")));
        assertThrows(Exception.class, repoA::lock);
        assertThrows(Exception.class, repoB::lock);
      }
      commit(first, "updated first");
      try (var leaseA = repoA.lock();
          var leaseB = repoB.lock()) {
        assertNotEquals(revisionA, leaseA.update());
        assertEquals(revisionB, leaseB.revision());
      }
      second.close();
      Files.move(temp.resolve("second-source"), temp.resolve("unavailable-second"));
      try (var leaseB = repoB.lock()) {
        assertThrows(ManagedRepository.NetworkFailure.class, leaseB::update);
        assertEquals(revisionB, leaseB.revision());
      }
      // Changing a configured branch creates a different checkout and preserves the old one.
      first.branchCreate().setName("feature").call();
      var feature = new LauncherSettings.RepositoryEntry("a", "First", a.repository(), "feature");
      try (var lease = new ManagedRepository(feature.location(base)).lock()) {
        lease.update();
        assertNotEquals(a.location(base).checkout(), lease.root());
      }
      assertTrue(Files.isDirectory(a.location(base).checkout().resolve(".git")));
    }
  }

  @Test
  void cloneUpdateAndExclusiveLease() throws Exception {
    try (Git source = source()) {
      ManagedRepository repo =
          new ManagedRepository(
              new LauncherSettings.RepositoryLocation(
                  source.getRepository().getWorkTree().toString(),
                  "main",
                  temp.resolve("checkout")));
      String before;
      try (var lease = repo.lock()) {
        before = lease.update();
        assertEquals(before, lease.update());
        assertThrows(Exception.class, repo::lock);
      }
      commit(source, "second");
      try (var lease = repo.lock()) {
        assertNotEquals(before, lease.update());
        assertEquals("second", Files.readString(lease.root().resolve("data.txt")));
      }
    }
  }

  @Test
  void refusesDirtyCheckoutAndDivergenceWithoutChangingFiles() throws Exception {
    try (Git source = source()) {
      ManagedRepository repo =
          new ManagedRepository(
              new LauncherSettings.RepositoryLocation(
                  source.getRepository().getWorkTree().toString(),
                  "main",
                  temp.resolve("checkout")));
      try (var lease = repo.lock()) {
        lease.update();
        Files.writeString(lease.root().resolve("data.txt"), "mine");
        assertThrows(Exception.class, lease::update);
        assertThrows(Exception.class, lease::revision);
        assertEquals("mine", Files.readString(lease.root().resolve("data.txt")));
        try (Git local = Git.open(lease.root().toFile())) {
          commit(local, "local commit");
        }
        commit(source, "remote commit");
        assertThrows(Exception.class, lease::update);
        assertEquals("local commit", Files.readString(lease.root().resolve("data.txt")));
      }
    }
  }

  @Test
  void refusesForeignDirectoryWrongOriginAndBranch() throws Exception {
    try (Git source = source()) {
      Path checkout = temp.resolve("checkout");
      Files.createDirectory(checkout);
      Files.writeString(checkout.resolve("keep"), "keep");
      var settings =
          new LauncherSettings.RepositoryLocation(
              source.getRepository().getWorkTree().toString(), "main", checkout);
      try (var lease = new ManagedRepository(settings).lock()) {
        assertThrows(Exception.class, lease::update);
      }
      Files.delete(checkout.resolve("keep"));
      Files.delete(checkout);
      try (var lease = new ManagedRepository(settings).lock()) {
        lease.update();
      }
      try (var lease =
          new ManagedRepository(
                  new LauncherSettings.RepositoryLocation(settings.repository(), "other", checkout))
              .lock()) {
        assertThrows(Exception.class, lease::revision);
      }
      try (var lease =
          new ManagedRepository(
                  new LauncherSettings.RepositoryLocation(
                      temp.resolve("other").toString(), "main", checkout))
              .lock()) {
        assertThrows(Exception.class, lease::revision);
      }
    }
  }

  @Test
  void unavailableRemotePreservesOfflineRevision() throws Exception {
    try (Git source = source()) {
      var repo =
          new ManagedRepository(
              new LauncherSettings.RepositoryLocation(
                  source.getRepository().getWorkTree().toString(),
                  "main",
                  temp.resolve("checkout")));
      try (var lease = repo.lock()) {
        String before = lease.update();
        source.close();
        Files.move(temp.resolve("source"), temp.resolve("unavailable"));
        assertThrows(ManagedRepository.NetworkFailure.class, lease::update);
        assertEquals(before, lease.revision());
      }
    }
  }
}
