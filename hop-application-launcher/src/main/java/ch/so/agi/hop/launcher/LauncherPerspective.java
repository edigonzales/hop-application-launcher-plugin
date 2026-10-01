package ch.so.agi.hop.launcher;

import static ch.so.agi.hop.launcher.ApplicationDefinition.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.context.IGuiContextHandler;
import org.apache.hop.ui.hopgui.perspective.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.*;

// Hop initializes GUI plugin loaders before resolving perspective classes via getClass().
@GuiPlugin(name = "i18n::Launcher.Name", description = "i18n::Launcher.Description")
@HopPerspectivePlugin(
    id = "application-launcher",
    name = "i18n::Launcher.Name",
    description = "i18n::Launcher.Description",
    image = "launcher.svg",
    documentationUrl = "https://edigonzales.github.io/hop-application-launcher-plugin/")
public final class LauncherPerspective implements IHopPerspective {
  private HopGui hopGui;
  private Display display;
  private Composite control, form;
  private ScrolledComposite scroll;
  private ApplicationTree apps;
  private Composite updateBanner;
  private Label updateMessage;
  private ProgressBar updateProgress;
  private Label description, status, revision;
  private Combo repositorySelection;
  private LauncherLogPanel log;
  private SashForm vertical;
  private Link logFile;
  private String savedLog = "";
  private Button refresh, settingsButton, start, cancel, open, offline;
  private final Map<String, Supplier<String>> fields = new LinkedHashMap<>();
  private java.util.List<ApplicationDefinition> definitions = java.util.List.of();
  private ApplicationDefinition selected;
  private LauncherSettings settings;
  private java.util.List<LauncherSettings.RepositoryEntry> displayedRepositories =
      java.util.List.of();
  private LauncherController controller;
  private boolean opened, busy;
  private String output = "";
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "hop-application-launcher");
            t.setDaemon(true);
            return t;
          });

  public static String message(String key) {
    return BaseMessages.getString(LauncherPerspective.class, "Launcher." + key);
  }

  @Override
  public String getId() {
    return "application-launcher";
  }

  @Override
  public void activate() {
    hopGui.setActivePerspective(this);
  }

  @Override
  public boolean isActive() {
    return hopGui != null && hopGui.isActivePerspective(this);
  }

  @Override
  public Control getControl() {
    return control;
  }

  @Override
  public java.util.List<IGuiContextHandler> getContextHandlers() {
    return java.util.List.of();
  }

  @Override
  public void perspectiveActivated() {
    if (!opened) {
      opened = true;
      refresh(false);
    }
  }

  @Override
  public void initialize(HopGui gui, Composite parent) {
    hopGui = gui;
    display = parent.getDisplay();
    settings = LauncherSettings.load();
    controller = settings.activeRepository() == null ? null : new LauncherController(settings);
    control = new Composite(parent, SWT.NONE);
    control.setLayout(new GridLayout(1, false));
    FormData fd = new FormData();
    fd.left = new FormAttachment(0);
    fd.right = new FormAttachment(100);
    fd.top = new FormAttachment(0);
    fd.bottom = new FormAttachment(100);
    control.setLayoutData(fd);
    PropsUi.setLook(control);
    Composite toolbar = new Composite(control, SWT.NONE);
    toolbar.setLayout(new RowLayout());
    settingsButton = button(toolbar, "Settings", () -> settingsDialog());
    refresh = button(toolbar, "Refresh", () -> refresh(false));
    offline = button(toolbar, "Offline", () -> refresh(true));
    offline.setEnabled(false);
    updateBanner = new Composite(control, SWT.NONE);
    updateBanner.setLayout(new GridLayout(2, false));
    GridData bannerLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
    bannerLayout.exclude = true;
    updateBanner.setLayoutData(bannerLayout);
    updateBanner.setVisible(false);
    updateProgress = new ProgressBar(updateBanner, SWT.INDETERMINATE | SWT.HORIZONTAL);
    updateProgress.setData("launcher.updateProgress", true);
    GridData progressLayout = new GridData(SWT.LEFT, SWT.CENTER, false, false);
    progressLayout.widthHint = 90;
    updateProgress.setLayoutData(progressLayout);
    updateMessage = new Label(updateBanner, SWT.WRAP);
    updateMessage.setData("launcher.updateMessage", true);
    updateMessage.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    // Reserve the row's height without letting a long repository name widen the perspective.
    Composite repositoryArea = new Composite(control, SWT.NONE);
    repositoryArea.setLayout(new FormLayout());
    GridData repositoryAreaData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    repositoryAreaData.widthHint = 0;
    repositoryArea.setLayoutData(repositoryAreaData);
    Composite repositoryRow = new Composite(repositoryArea, SWT.NONE);
    GridLayout repositoryLayout = new GridLayout(2, false);
    repositoryLayout.marginWidth = 0;
    repositoryLayout.marginHeight = 0;
    repositoryRow.setLayout(repositoryLayout);
    FormData repositoryBounds = new FormData();
    repositoryBounds.left = new FormAttachment(0);
    repositoryBounds.top = new FormAttachment(0);
    repositoryBounds.width = 0;
    repositoryRow.setLayoutData(repositoryBounds);
    new Label(repositoryRow, SWT.NONE).setText(message("RepositorySelection"));
    repositorySelection = new Combo(repositoryRow, SWT.DROP_DOWN | SWT.READ_ONLY);
    repositorySelection.setData("launcher.repositories", true);
    GridData repositorySelectionData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    repositorySelectionData.widthHint = 0;
    repositorySelection.setLayoutData(repositorySelectionData);
    repositorySelection.addListener(
        SWT.Selection,
        e -> {
          int index = repositorySelection.getSelectionIndex();
          if (busy || index < 0) return;
          var replacement = settings.select(displayedRepositories.get(index).id());
          if (replacement.equals(settings)) return;
          try {
            applySettings(replacement);
          } catch (Exception ex) {
            populateRepositories();
            showError(ex);
          }
        });
    revision = new Label(control, SWT.WRAP);
    revision.setData("launcher.revision", true);
    revision.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    vertical = new SashForm(control, SWT.VERTICAL);
    vertical.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Composite applicationArea = new Composite(vertical, SWT.NONE);
    applicationArea.setLayout(new GridLayout(1, false));
    SashForm panels = new SashForm(applicationArea, SWT.HORIZONTAL);
    panels.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    apps = new ApplicationTree(panels, this::selectApplication);
    Control tree = apps.getControl();
    Listener alignRepository =
        e -> {
          if (tree.isDisposed() || repositoryArea.isDisposed()) return;
          var bounds = display.map(tree.getParent(), repositoryArea, tree.getBounds());
          repositoryBounds.left = new FormAttachment(0, bounds.x);
          repositoryBounds.width = bounds.width;
          // Only lay out this row: the tree and its sash must retain their own sizing.
          repositoryArea.layout(true, true);
        };
    tree.addListener(SWT.Resize, alignRepository);
    tree.addListener(SWT.Move, alignRepository);
    repositoryArea.addListener(SWT.Resize, alignRepository);
    Composite detail = new Composite(panels, SWT.NONE);
    detail.setLayout(new GridLayout(1, false));
    description = new Label(detail, SWT.WRAP);
    description.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    scroll = new ScrolledComposite(detail, SWT.V_SCROLL | SWT.H_SCROLL);
    scroll.setExpandHorizontal(true);
    scroll.setExpandVertical(true);
    scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    form = new Composite(scroll, SWT.NONE);
    form.setLayout(new GridLayout(3, false));
    scroll.setContent(form);
    Composite actions = new Composite(detail, SWT.NONE);
    actions.setLayout(new RowLayout());
    start = button(actions, "Start", this::start);
    cancel =
        button(
            actions,
            "Cancel",
            () -> {
              controller.cancel();
              status.setText(message("Cancelling"));
            });
    open =
        button(
            actions,
            "OpenOutput",
            () -> {
              if (!output.isEmpty() && !Program.launch(output))
                showError(new IllegalStateException(message("CannotOpen")));
            });
    start.setEnabled(false);
    cancel.setEnabled(false);
    open.setEnabled(false);
    panels.setWeights(30, 70);
    status = new Label(applicationArea, SWT.WRAP);
    status.setData("launcher.status", true);
    status.setText(message("Ready"));
    status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    logFile = new Link(applicationArea, SWT.NONE);
    logFile.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    logFile.setData("launcher.logFile", true);
    logFile.addListener(
        SWT.Selection,
        e -> {
          if (!savedLog.isEmpty() && !Program.launch(savedLog))
            showError(new IllegalStateException(message("CannotOpenLog")));
        });
    log = new LauncherLogPanel(vertical);
    vertical.setWeights(65, 35);
    vertical.setMaximizedControl(applicationArea);
    selectApplication(null, "");
    populateRepositories();
    busy(false, false);
    control.addListener(
        SWT.Dispose,
        e -> {
          if (controller != null) controller.cancel();
          worker.shutdown();
        });
  }

  private Button button(Composite parent, String key, Runnable action) {
    Button button = new Button(parent, SWT.PUSH);
    button.setText(message(key));
    button.setData("launcher.action", key);
    button.addListener(SWT.Selection, e -> action.run());
    return button;
  }

  private void ui(Runnable action) {
    if (!display.isDisposed()) {
      try {
        display.asyncExec(
            () -> {
              if (!control.isDisposed()) action.run();
            });
      } catch (SWTException e) {
        if (e.code != SWT.ERROR_DEVICE_DISPOSED) throw e;
      }
    }
  }

  private void busy(boolean value, boolean running) {
    busy = value;
    apps.setEnabled(!value);
    form.setEnabled(!value);
    settingsButton.setEnabled(!value);
    refresh.setEnabled(!value && controller != null);
    repositorySelection.setEnabled(!value && controller != null);
    offline.setEnabled(false);
    start.setEnabled(!value && selected != null);
    cancel.setEnabled(running);
    open.setEnabled(!value && !output.isEmpty());
    open.setToolTipText(output.isEmpty() ? null : output);
  }

  private void refresh(boolean useOffline) {
    if (busy) return;
    if (controller == null) {
      busy(false, false);
      status.setText(message("NoRepositories"));
      control.layout(true, true);
      return;
    }
    final LauncherController target = controller;
    busy(true, false);
    showUpdate(message(useOffline ? "LoadingOffline" : "Updating"), true);
    status.setText(message(useOffline ? "LoadingOffline" : "Updating"));
    log.attach(null);
    savedLog = "";
    logFile.setText("");
    // A failed update must not leave an executable form from an older revision.
    definitions = java.util.List.of();
    apps.clearForRefresh();
    revision.setText("");
    worker.submit(
        () -> {
          try {
            var catalog = useOffline ? target.useOffline() : target.refresh();
            ui(
                () -> {
                  definitions = catalog.applications();
                  apps.populate(definitions);
                  revision.setText(
                      (catalog.offline() ? message("OfflineState") : message("Revision"))
                          + " "
                          + catalog.revision());
                  showUpdate("", false);
                  busy(false, false);
                  status.setText(message(definitions.isEmpty() ? "Empty" : "Ready"));
                  control.layout(true, true);
                });
          } catch (Exception | LinkageError e) {
            ui(
                () -> {
                  busy(false, false);
                  showUpdate(message(useOffline ? "OfflineLoadFailed" : "UpdateFailed"), false);
                  showError(e);
                  offline.setEnabled(e instanceof ManagedRepository.NetworkFailure);
                });
          }
        });
  }

  private void showUpdate(String text, boolean updating) {
    updateMessage.setText(text);
    updateProgress.setVisible(updating);
    ((GridData) updateProgress.getLayoutData()).exclude = !updating;
    updateBanner.setVisible(!text.isEmpty());
    ((GridData) updateBanner.getLayoutData()).exclude = text.isEmpty();
    control.layout(true, true);
  }

  private void selectApplication(ApplicationDefinition application, String organization) {
    for (Control child : form.getChildren()) child.dispose();
    fields.clear();
    output = "";
    open.setEnabled(false);
    selected = application;
    open.setToolTipText(null);
    boolean hasOutput = selected != null && !selected.outputDirectoryParameter().isEmpty();
    open.setVisible(hasOutput);
    RowData outputLayout = new RowData();
    outputLayout.exclude = !hasOutput;
    open.setLayoutData(outputLayout);
    open.getParent().layout(true, true);
    description.setText(
        selected != null
            ? selected.title() + "\n" + selected.description()
            : organization.isEmpty() ? "" : organization + "\n" + message("SelectApplication"));
    if (selected != null)
      for (Parameter p : selected.parameters()) {
        Label label = new Label(form, SWT.NONE);
        label.setText(p.label() + (p.required() ? " *" : ""));
        label.setToolTipText(p.description());
        if (p.type() == Type.BOOLEAN) {
          Button field = new Button(form, SWT.CHECK);
          field.setSelection("Y".equals(p.defaultValue()));
          field.setData("launcher.parameter", p.name());
          field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
          fields.put(p.name(), () -> field.getSelection() ? "Y" : "N");
        } else if (p.type() == Type.CHOICE) {
          Combo field = new Combo(form, SWT.DROP_DOWN | SWT.READ_ONLY);
          field.setItems(p.values().toArray(String[]::new));
          field.setText(p.defaultValue());
          field.setData("launcher.parameter", p.name());
          field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
          fields.put(p.name(), field::getText);
        } else {
          Text field = new Text(form, SWT.BORDER);
          field.setText(p.defaultValue());
          field.setToolTipText(p.description());
          field.setData("launcher.parameter", p.name());
          field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
          fields.put(p.name(), field::getText);
          if (p.type() == Type.FILE || p.type() == Type.DIRECTORY) {
            button(
                form,
                "Browse",
                () -> {
                  String path;
                  if (p.type() == Type.FILE) {
                    FileDialog dialog = new FileDialog(control.getShell(), SWT.OPEN);
                    dialog.setText(p.label());
                    if (!p.extensions().isEmpty())
                      dialog.setFilterExtensions(
                          new String[] {
                            String.join(";", p.extensions().stream().map(x -> "*." + x).toList()),
                            "*"
                          });
                    path = dialog.open();
                  } else {
                    DirectoryDialog dialog = new DirectoryDialog(control.getShell());
                    dialog.setText(p.label());
                    path = dialog.open();
                  }
                  if (path != null) field.setText(path);
                });
          } else new Label(form, SWT.NONE);
        }
      }
    form.layout(true, true);
    scroll.setMinSize(form.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    start.setEnabled(!busy && selected != null);
    control.layout(true, true);
  }

  private void start() {
    if (busy || selected == null) return;
    String id = selected.id();
    Map<String, String> values = new LinkedHashMap<>();
    fields.forEach((name, value) -> values.put(name, value.get()));
    final LauncherController target = controller;
    log.attach(target.prepareRun());
    vertical.setMaximizedControl(null);
    busy(true, true);
    status.setText(message("Running"));
    savedLog = "";
    logFile.setText("");
    output = "";
    worker.submit(
        () -> {
          try {
            var result = target.run(id, values, LauncherSettings.stateDirectory().resolve("runs"));
            ui(
                () -> {
                  output = result.outputDirectory();
                  busy(false, false);
                  status.setText(message(result.status()));
                  savedLog = result.log().toString();
                  logFile.setText("<a>" + message("SavedLog") + "</a>");
                  logFile.setToolTipText(savedLog);
                  log.finish();
                  control.layout(true, true);
                });
          } catch (Exception | LinkageError e) {
            ui(
                () -> {
                  busy(false, false);
                  showError(e);
                });
          }
        });
  }

  private void showError(Throwable e) {
    status.setText(message("FAILED"));
    vertical.setMaximizedControl(null);
    log.error(e);
  }

  private void populateRepositories() {
    displayedRepositories = LauncherSettings.sortedRepositories(settings.repositories());
    repositorySelection.setItems(
        displayedRepositories.stream()
            .map(LauncherSettings.RepositoryEntry::label)
            .toArray(String[]::new));
    var active = settings.activeRepository();
    if (active != null) repositorySelection.select(displayedRepositories.indexOf(active));
    repositorySelection.setToolTipText(active == null ? null : active.repository());
  }

  private void applySettings(LauncherSettings replacement) {
    boolean changed = !replacement.equals(settings);
    settings = replacement;
    controller = settings.activeRepository() == null ? null : new LauncherController(settings);
    populateRepositories();
    if (changed) apps.reset();
    definitions = java.util.List.of();
    revision.setText("");
    log.attach(null);
    savedLog = "";
    logFile.setText("");
    logFile.setToolTipText(null);
    showUpdate("", false);
    refresh(false);
  }

  private void settingsDialog() {
    new LauncherSettingsDialog(
            control.getShell(),
            settings,
            replacement -> {
              replacement.save();
              applySettings(replacement);
            })
        .open();
  }
}
