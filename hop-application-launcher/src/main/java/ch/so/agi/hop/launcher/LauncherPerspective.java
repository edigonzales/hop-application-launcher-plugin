package ch.so.agi.hop.launcher;

import static ch.so.agi.hop.launcher.ApplicationDefinition.*;

import java.nio.file.Files;
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
  private Composite control, form;
  private ScrolledComposite scroll;
  private org.eclipse.swt.widgets.List apps;
  private Label description, status, revision;
  private Text log;
  private Button refresh, settingsButton, start, cancel, open, offline;
  private final Map<String, Supplier<String>> fields = new LinkedHashMap<>();
  private java.util.List<ApplicationDefinition> definitions = java.util.List.of();
  private ApplicationDefinition selected;
  private LauncherSettings settings;
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
    settings = LauncherSettings.load();
    controller = new LauncherController(settings);
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
    revision = new Label(control, SWT.WRAP);
    revision.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    SashForm panels = new SashForm(control, SWT.HORIZONTAL);
    panels.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    apps = new org.eclipse.swt.widgets.List(panels, SWT.BORDER | SWT.V_SCROLL);
    apps.setData("launcher.apps", true);
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
    status = new Label(control, SWT.WRAP);
    status.setData("launcher.status", true);
    status.setText(message("Ready"));
    status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    ExpandBar details = new ExpandBar(control, SWT.NONE);
    details.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    log = new Text(details, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    ExpandItem item = new ExpandItem(details, SWT.NONE);
    item.setText(message("Log"));
    item.setControl(log);
    item.setHeight(180);
    details.addListener(
        SWT.Expand,
        e ->
            control
                .getDisplay()
                .asyncExec(
                    () -> {
                      if (!control.isDisposed()) control.layout(true, true);
                    }));
    details.addListener(
        SWT.Collapse,
        e ->
            control
                .getDisplay()
                .asyncExec(
                    () -> {
                      if (!control.isDisposed()) control.layout(true, true);
                    }));
    apps.addListener(SWT.Selection, e -> selectApplication(apps.getSelectionIndex()));
    control.addListener(
        SWT.Dispose,
        e -> {
          controller.cancel();
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
    Display display = control.getDisplay();
    if (!display.isDisposed())
      display.asyncExec(
          () -> {
            if (!control.isDisposed()) action.run();
          });
  }

  private void busy(boolean value, boolean running) {
    busy = value;
    apps.setEnabled(!value);
    form.setEnabled(!value);
    settingsButton.setEnabled(!value);
    refresh.setEnabled(!value);
    offline.setEnabled(false);
    start.setEnabled(!value && selected != null);
    cancel.setEnabled(running);
    open.setEnabled(!value && !output.isEmpty());
  }

  private void refresh(boolean useOffline) {
    if (busy) return;
    busy(true, false);
    status.setText(message("Updating"));
    log.setText("");
    // A failed update must not leave an executable form from an older revision.
    definitions = java.util.List.of();
    apps.removeAll();
    selectApplication(-1);
    worker.submit(
        () -> {
          try {
            var catalog = useOffline ? controller.useOffline() : controller.refresh();
            ui(
                () -> {
                  definitions = catalog.applications();
                  apps.setItems(
                      definitions.stream()
                          .map(ApplicationDefinition::title)
                          .toArray(String[]::new));
                  revision.setText(
                      (catalog.offline() ? message("OfflineState") : message("Revision"))
                          + " "
                          + catalog.revision());
                  if (!definitions.isEmpty()) {
                    apps.select(0);
                    selectApplication(0);
                  }
                  busy(false, false);
                  status.setText(message(definitions.isEmpty() ? "Empty" : "Ready"));
                  control.layout(true, true);
                });
          } catch (Exception | LinkageError e) {
            ui(
                () -> {
                  busy(false, false);
                  showError(e);
                  offline.setEnabled(e instanceof ManagedRepository.NetworkFailure);
                });
          }
        });
  }

  private void selectApplication(int index) {
    for (Control child : form.getChildren()) child.dispose();
    fields.clear();
    output = "";
    open.setEnabled(false);
    selected = index < 0 ? null : definitions.get(index);
    description.setText(selected == null ? "" : selected.title() + "\n" + selected.description());
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
    controller.prepareRun();
    busy(true, true);
    status.setText(message("Running"));
    log.setText("");
    output = "";
    worker.submit(
        () -> {
          try {
            var result =
                controller.run(id, values, LauncherSettings.stateDirectory().resolve("runs"));
            String text = Files.readString(result.log());
            ui(
                () -> {
                  output = result.outputDirectory();
                  busy(false, false);
                  status.setText(message(result.status()));
                  log.setText(result.report() + "\n\n" + text);
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
    log.setText(e.getMessage() == null ? e.toString() : e.getMessage());
  }

  private void settingsDialog() {
    Shell dialog =
        new Shell(control.getShell(), SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    dialog.setText(message("Settings"));
    dialog.setLayout(new GridLayout(2, false));
    Text repository = setting(dialog, "Repository", settings.repository());
    Text branch = setting(dialog, "Branch", settings.branch());
    Text checkout = setting(dialog, "Checkout", settings.checkout().toString());
    button(
        dialog,
        "Save",
        () -> {
          try {
            var replacement =
                new LauncherSettings(
                    repository.getText().trim(),
                    branch.getText().trim(),
                    java.nio.file.Path.of(checkout.getText().trim()).toAbsolutePath());
            if (replacement.repository().isEmpty()
                || replacement.branch().isEmpty()
                || checkout.getText().isBlank())
              throw new IllegalArgumentException(message("SettingsRequired"));
            replacement.save();
            settings = replacement;
            controller = new LauncherController(settings);
            dialog.dispose();
            refresh(false);
          } catch (Exception | LinkageError e) {
            showError(e);
          }
        });
    button(dialog, "Close", dialog::dispose);
    dialog.pack();
    dialog.setSize(Math.max(650, dialog.getSize().x), dialog.getSize().y);
    dialog.open();
  }

  private Text setting(Composite parent, String key, String value) {
    Label label = new Label(parent, SWT.NONE);
    label.setText(message(key));
    Text field = new Text(parent, SWT.BORDER);
    field.setText(value);
    field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return field;
  }
}
