package ch.so.agi.hop.launcher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherSettingsTest {
  @TempDir Path temp;

  @Test
  void suppliesExactlyOneDefaultAndRoundTripsSelection() throws Exception {
    var defaults = LauncherSettings.fromOptions(key -> null);
    assertEquals(1, defaults.repositories().size());
    assertEquals("Datenportal Themenrepo", defaults.activeRepository().name());
    assertEquals(LauncherSettings.DEFAULT_REPOSITORY, defaults.activeLocation().repository());
    assertEquals("main", defaults.activeLocation().branch());
    defaults.validate();
    var second =
        new LauncherSettings.RepositoryEntry("second", "Other", temp.toString(), "release");
    var settings =
        new LauncherSettings(List.of(defaults.activeRepository(), second), temp, "second");
    assertEquals(settings, LauncherSettings.fromOptions(settings.options()::get));
    assertEquals("second", settings.activeRepositoryId());
    assertEquals("second", settings.select("missing").activeRepositoryId());
  }

  @Test
  void startupUsesDefaultWhileSessionSelectionDoesNotChangeIt() {
    var zulu =
        new LauncherSettings.RepositoryEntry("z", "Zulu", "https://example.org/z.git", "main");
    var alpha =
        new LauncherSettings.RepositoryEntry("a", "alpha", "https://example.org/a.git", "main");
    var settings = new LauncherSettings(List.of(zulu, alpha), temp, "z");
    assertEquals(
        List.of(alpha, zulu), LauncherSettings.sortedRepositories(settings.repositories()));
    var switched = settings.select("a");
    assertEquals("a", switched.activeRepositoryId());
    assertEquals("z", switched.defaultRepositoryId());
    var restarted = LauncherSettings.fromOptions(switched.options()::get);
    assertEquals("z", restarted.activeRepositoryId());
    assertEquals("z", restarted.defaultRepositoryId());
    assertEquals("a", new LauncherSettings(List.of(alpha), temp, "z", "z").defaultRepositoryId());
    assertEquals(
        "a", new LauncherSettings(List.of(zulu, alpha), temp, "missing").defaultRepositoryId());
    assertEquals("", new LauncherSettings(List.of(), temp, "z").defaultRepositoryId());
  }

  @Test
  void previousSessionSelectionBecomesDefaultOnlyOnMigration() {
    var entries =
        List.of(
            new LauncherSettings.RepositoryEntry("z", "Zulu", "https://example.org/z.git", "main"),
            new LauncherSettings.RepositoryEntry(
                "a", "Alpha", "https://example.org/a.git", "main"));
    var options = new HashMap<>(new LauncherSettings(entries, temp, "a").options());
    options.remove(LauncherSettings.PREFIX + "defaultRepositoryId");
    options.put(LauncherSettings.PREFIX + "activeRepositoryId", "z");
    var migrated = LauncherSettings.fromOptions(options::get);
    assertEquals("z", migrated.defaultRepositoryId());
    assertEquals("z", migrated.activeRepositoryId());
    options.putAll(new LauncherSettings(entries, temp, "a").options());
    assertEquals("a", LauncherSettings.fromOptions(options::get).activeRepositoryId());
  }

  @Test
  void migratesLegacySettingsWithoutTouchingCheckoutAndNeverResurrectsRemovedEntries()
      throws Exception {
    Path old = temp.resolve("checkout");
    Files.createDirectory(old);
    Files.writeString(old.resolve("keep"), "local changes");
    Map<String, Object> options =
        new HashMap<>(
            Map.of(
                LauncherSettings.PREFIX + "repository", "https://example.org/team/jobs.git",
                LauncherSettings.PREFIX + "branch", "release",
                LauncherSettings.PREFIX + "checkout", old.toString()));
    var migrated = LauncherSettings.fromOptions(options::get);
    assertEquals("jobs", migrated.activeRepository().name());
    assertEquals("release", migrated.activeLocation().branch());
    assertEquals(temp.resolve("checkout-repositories"), migrated.checkoutBase());
    assertEquals("local changes", Files.readString(old.resolve("keep")));
    assertFalse(Files.exists(migrated.checkoutBase()));
    options.putAll(new LauncherSettings(List.of(), migrated.checkoutBase(), "default").options());
    var empty = LauncherSettings.fromOptions(options::get);
    assertTrue(empty.repositories().isEmpty());
    assertNull(empty.activeRepository());
    assertEquals("", empty.activeRepositoryId());
  }

  @Test
  void pathsAreStableForRenameAndDistinctForSourceBranchAndIdentity() {
    var first =
        new LauncherSettings.RepositoryEntry("first", "First", "https://example.org/a.git", "main");
    var renamed =
        new LauncherSettings.RepositoryEntry("first", "Renamed", first.repository(), "main");
    assertEquals(first.location(temp), renamed.location(temp));
    for (var other :
        List.of(
            new LauncherSettings.RepositoryEntry("second", "Second", first.repository(), "main"),
            new LauncherSettings.RepositoryEntry("first", "First", first.repository(), "release"),
            new LauncherSettings.RepositoryEntry(
                "first", "First", "https://example.org/b.git", "main"))) {
      assertNotEquals(first.location(temp).checkout(), other.location(temp).checkout());
      assertEquals(temp, other.location(temp).checkout().getParent());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LauncherSettings.RepositoryEntry("../escape", "Name", first.repository(), "main"));
  }

  @Test
  void validatesNamesSourcesBranchesAndDuplicates() throws Exception {
    var valid =
        new LauncherSettings.RepositoryEntry("one", "Name", temp.toUri().toString(), "main");
    new LauncherSettings(List.of(valid), temp, "one").validate();
    for (var invalid :
        List.of(
            new LauncherSettings.RepositoryEntry("one", " ", valid.repository(), "main"),
            new LauncherSettings.RepositoryEntry("one", "Name", "ssh://example.org/repo", "main"),
            new LauncherSettings.RepositoryEntry(
                "one", "Name", "https://user:secret@example.org/repo", "main"),
            new LauncherSettings.RepositoryEntry("one", "Name", valid.repository(), "bad..branch"),
            new LauncherSettings.RepositoryEntry("one", "Name", "", "main")))
      assertThrows(
          Exception.class, () -> new LauncherSettings(List.of(invalid), temp, "one").validate());
    var duplicate =
        new LauncherSettings.RepositoryEntry("two", "Other", valid.repository(), "main");
    assertThrows(
        IllegalArgumentException.class,
        () -> new LauncherSettings(List.of(valid, duplicate), temp, "one"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LauncherSettings(List.of(valid, valid), temp, "one"));
    assertThrows(
        IllegalArgumentException.class, () -> LauncherSettings.fromOptions(key -> "broken"));
  }
}
