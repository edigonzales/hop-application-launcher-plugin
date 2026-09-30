package ch.so.agi.hop.launcher;

import java.nio.file.Path;
import java.util.*;

/** UI-independent orchestration, also exercised from an installed plugin's actual classloader. */
public final class LauncherController {
  public record Catalog(
      String revision, List<ApplicationDefinition> applications, boolean offline) {}

  private final LauncherSettings settings;
  private String validatedRevision;
  private volatile ExecutionService execution;

  public LauncherController(LauncherSettings settings) {
    this.settings = settings;
  }

  public Catalog refresh() throws Exception {
    try (var lease = new ManagedRepository(settings).lock()) {
      String revision = lease.update();
      var apps = new CatalogLoader().load(lease.root());
      validatedRevision = revision;
      return new Catalog(revision, apps, false);
    }
  }

  /** Explicit user action, never an automatic fallback from a failed update. */
  public Catalog useOffline() throws Exception {
    try (var lease = new ManagedRepository(settings).lock()) {
      String revision = lease.revision();
      // A persisted checkout can be validated on startup while offline.
      var apps = new CatalogLoader().load(lease.root());
      validatedRevision = revision;
      return new Catalog(revision, apps, true);
    }
  }

  public ExecutionService.Outcome run(String application, Map<String, String> parameters, Path logs)
      throws Exception {
    try (var lease = new ManagedRepository(settings).lock()) {
      String revision = lease.revision();
      if (!Objects.equals(revision, validatedRevision))
        throw new IllegalStateException(
            "Checkout changed: refresh the application form before starting");
      var app =
          new CatalogLoader()
              .load(lease.root()).stream()
                  .filter(a -> a.id().equals(application))
                  .findFirst()
                  .orElseThrow(
                      () -> new IllegalArgumentException("Application not found: " + application));
      return execution.run(lease.root(), revision, app, parameters, logs);
    } finally {
      execution = null;
    }
  }

  /** Called on the UI thread before queuing the worker so even an immediate cancel is retained. */
  public void prepareRun() {
    if (execution != null) throw new IllegalStateException("A run is already active");
    execution = new ExecutionService();
  }

  public void cancel() {
    var current = execution;
    if (current != null) current.cancel();
  }
}
