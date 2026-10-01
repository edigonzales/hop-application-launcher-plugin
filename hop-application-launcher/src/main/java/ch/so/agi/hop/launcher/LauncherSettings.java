package ch.so.agi.hop.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Collator;
import java.util.*;
import java.util.function.Function;
import org.apache.hop.core.Const;
import org.apache.hop.core.config.HopConfig;

/** Local bootstrap configuration: it must be available before cloning the catalog. */
public record LauncherSettings(
    List<RepositoryEntry> repositories,
    Path checkoutBase,
    String defaultRepositoryId,
    String activeRepositoryId) {
  static final String PREFIX = "applicationLauncher.";
  static final String DEFAULT_REPOSITORY = "https://github.com/sogis/datenportal-themenrepo.git";

  public record RepositoryEntry(String id, String name, String repository, String branch) {
    public RepositoryEntry {
      if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}"))
        throw new IllegalArgumentException("Invalid repository ID");
      name = Objects.requireNonNull(name).trim();
      repository = Objects.requireNonNull(repository).trim();
      branch = Objects.requireNonNull(branch).trim();
    }

    public String label() {
      return name + " (" + branch + ")";
    }

    public RepositoryLocation location(Path base) {
      try {
        String digest =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest((repository + "\n" + branch).getBytes(StandardCharsets.UTF_8)));
        return new RepositoryLocation(repository, branch, base.resolve(id + "-" + digest));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Immutable execution target; controllers never consult mutable UI selection. */
  public record RepositoryLocation(String repository, String branch, Path checkout) {}

  public LauncherSettings {
    repositories = List.copyOf(repositories);
    checkoutBase = Objects.requireNonNull(checkoutBase).toAbsolutePath().normalize();
    if (checkoutBase.getParent() == null)
      throw new IllegalArgumentException("Checkout base must not be a filesystem root");
    Set<String> ids = new HashSet<>();
    Set<List<String>> sources = new HashSet<>();
    for (var entry : repositories) {
      if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate repository ID");
      if (!sources.add(List.of(entry.repository(), entry.branch())))
        throw new IllegalArgumentException(
            "Duplicate repository and branch: " + entry.repository());
    }
    String requestedDefault = defaultRepositoryId;
    if (repositories.stream().noneMatch(r -> r.id().equals(requestedDefault)))
      defaultRepositoryId =
          repositories.isEmpty() ? "" : sortedRepositories(repositories).getFirst().id();
    String requested = activeRepositoryId;
    if (repositories.stream().noneMatch(r -> r.id().equals(requested)))
      activeRepositoryId = defaultRepositoryId;
  }

  public LauncherSettings(
      List<RepositoryEntry> repositories, Path checkoutBase, String defaultRepositoryId) {
    this(repositories, checkoutBase, defaultRepositoryId, defaultRepositoryId);
  }

  static List<RepositoryEntry> sortedRepositories(List<RepositoryEntry> repositories) {
    Collator collator = Collator.getInstance();
    collator.setStrength(Collator.SECONDARY);
    return repositories.stream()
        .sorted(
            Comparator.comparing(RepositoryEntry::name, collator)
                .thenComparing(RepositoryEntry::branch, collator)
                .thenComparing(RepositoryEntry::id))
        .toList();
  }

  public RepositoryEntry activeRepository() {
    return repositories.stream()
        .filter(r -> r.id().equals(activeRepositoryId))
        .findFirst()
        .orElse(null);
  }

  public RepositoryLocation activeLocation() {
    return Objects.requireNonNull(activeRepository(), "No repository configured")
        .location(checkoutBase);
  }

  public LauncherSettings select(String id) {
    return new LauncherSettings(repositories, checkoutBase, defaultRepositoryId, id);
  }

  public void validate() throws Exception {
    for (var entry : repositories) {
      if (entry.name().isBlank()) throw new IllegalArgumentException("Repository name is required");
      ManagedRepository.validateSource(entry.repository(), entry.branch());
    }
  }

  public static Path stateDirectory() {
    return Path.of(Const.HOP_CONFIG_FOLDER).resolve("application-launcher");
  }

  public static LauncherSettings load() {
    boolean migrate =
        HopConfig.readOption(PREFIX + "repositories") == null
            || HopConfig.readOption(PREFIX + "defaultRepositoryId") == null;
    var settings = fromOptions(HopConfig::readOption);
    if (migrate) settings.save();
    return settings;
  }

  // Kept independent of Hop's singleton so migrations and serialization can be tested in isolation.
  static LauncherSettings fromOptions(Function<String, Object> read) {
    Path base = Path.of(System.getProperty("user.home"), ".hop", "repositories");
    Object stored = read.apply(PREFIX + "repositories");
    if (stored == null) {
      String url = option(read, "repository", DEFAULT_REPOSITORY);
      String branch = option(read, "branch", "main");
      String previous = option(read, "checkout", "");
      if (!previous.isBlank()) {
        Path old = Path.of(previous).toAbsolutePath().normalize();
        if (old.getParent() == null) throw new IllegalArgumentException("Invalid legacy checkout");
        base = old.resolveSibling(old.getFileName() + "-repositories");
      }
      String name = url.replace('\\', '/').replaceAll("/+$", "");
      name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
      if (url.equals(DEFAULT_REPOSITORY)) name = "Datenportal Themenrepo";
      return new LauncherSettings(
          List.of(new RepositoryEntry("default", name, url, branch)), base, "default");
    }
    if (!(stored instanceof List<?> list))
      throw new IllegalArgumentException("Invalid repository list in Hop configuration");
    List<RepositoryEntry> entries = new ArrayList<>();
    for (Object value : list) {
      if (!(value instanceof Map<?, ?> entry))
        throw new IllegalArgumentException("Invalid repository entry in Hop configuration");
      entries.add(
          new RepositoryEntry(
              (String) entry.get("id"), (String) entry.get("name"),
              (String) entry.get("repository"), (String) entry.get("branch")));
    }
    String startupId =
        read.apply(PREFIX + "defaultRepositoryId") == null
            ? option(read, "activeRepositoryId", "")
            : option(read, "defaultRepositoryId", "");
    return new LauncherSettings(
        entries, Path.of(option(read, "checkoutBase", base.toString())), startupId);
  }

  private static String option(Function<String, Object> read, String key, String fallback) {
    Object value = read.apply(PREFIX + key);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  Map<String, Object> options() {
    return Map.of(
        PREFIX + "repositories",
            repositories.stream()
                .map(
                    r ->
                        Map.of(
                            "id",
                            r.id(),
                            "name",
                            r.name(),
                            "repository",
                            r.repository(),
                            "branch",
                            r.branch()))
                .toList(),
        PREFIX + "checkoutBase", checkoutBase.toString(),
        PREFIX + "defaultRepositoryId", defaultRepositoryId);
  }

  public void save() {
    HopConfig.saveOptions(options());
  }
}
