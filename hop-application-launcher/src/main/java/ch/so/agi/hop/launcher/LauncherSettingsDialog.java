package ch.so.agi.hop.launcher;

import static ch.so.agi.hop.launcher.LauncherPerspective.message;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Edits a private draft; closing the shell never changes configuration or checkouts. */
final class LauncherSettingsDialog {
  private final Shell shell;
  private final Table table;
  private final Text name, repository, branch, checkout;
  private final Button remove;
  private final Combo defaultRepository;
  private String defaultRepositoryId;
  private final ArrayList<LauncherSettings.RepositoryEntry> entries;
  private int selected = -1;
  private boolean loading;

  LauncherSettingsDialog(Shell parent, LauncherSettings settings, Consumer<LauncherSettings> save) {
    entries = new ArrayList<>(settings.repositories());
    defaultRepositoryId = settings.defaultRepositoryId();
    shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    shell.setData("launcher.settings", true);
    shell.setText(message("Settings"));
    shell.setLayout(new GridLayout(2, false));
    table =
        new Table(
            shell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    table.setData("launcher.repositoryList", true);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    GridData rows = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    rows.heightHint = 140;
    table.setLayoutData(rows);
    String[] columns = {"RepositoryName", "Repository", "Branch"};
    int[] widths = {180, 360, 120};
    for (int i = 0; i < columns.length; i++) {
      TableColumn column = new TableColumn(table, SWT.NONE);
      column.setText(message(columns[i]));
      column.setWidth(widths[i]);
    }
    Composite actions = new Composite(shell, SWT.NONE);
    actions.setLayout(new RowLayout());
    actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    button(actions, "AddRepository", this::addRepository);
    remove =
        button(
            actions,
            "RemoveRepository",
            () -> {
              if (selected < 0) return;
              entries.remove(selected);
              populate(Math.min(selected, entries.size() - 1));
            });
    name = field("RepositoryName", "");
    repository = field("Repository", "");
    branch = field("Branch", "");
    for (Text field : new Text[] {name, repository, branch})
      field.addListener(SWT.Modify, e -> updateDraft());
    new Label(shell, SWT.NONE).setText(message("DefaultRepository"));
    defaultRepository = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);
    defaultRepository.setData("launcher.defaultRepository", true);
    defaultRepository.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    defaultRepository.addListener(
        SWT.Selection,
        e -> {
          int index = defaultRepository.getSelectionIndex();
          if (index >= 0)
            defaultRepositoryId = LauncherSettings.sortedRepositories(entries).get(index).id();
        });
    checkout = field("Checkout", settings.checkoutBase().toString());
    Label error = new Label(shell, SWT.WRAP);
    error.setData("launcher.settingsError", true);
    error.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    for (Text field : new Text[] {name, repository, branch, checkout})
      field.addListener(
          SWT.Modify,
          e -> {
            if (!error.getText().isEmpty()) {
              error.setText("");
              shell.layout(true, true);
            }
          });
    button(
        shell,
        "Save",
        () -> {
          try {
            if (checkout.getText().isBlank())
              throw new IllegalArgumentException(message("SettingsRequired"));
            var replacement =
                new LauncherSettings(
                    entries,
                    Path.of(checkout.getText().trim()),
                    defaultRepositoryId,
                    settings.activeRepositoryId());
            replacement.validate();
            save.accept(replacement);
            shell.dispose();
          } catch (Exception ex) {
            error.setText(message("SettingsInvalid") + " " + ex.getMessage());
            shell.layout(true, true);
          }
        });
    button(shell, "Close", shell::dispose);
    table.addListener(SWT.Selection, e -> select(table.getSelectionIndex()));
    populate(entries.isEmpty() ? -1 : 0);
  }

  private void addRepository() {
    entries.add(new LauncherSettings.RepositoryEntry(UUID.randomUUID().toString(), "", "", "main"));
    populate(entries.size() - 1);
    name.setFocus();
  }

  void open() {
    shell.pack();
    shell.setSize(Math.max(740, shell.getSize().x), shell.getSize().y);
    shell.open();
  }

  private void updateDraft() {
    if (loading || selected < 0) return;
    var entry =
        new LauncherSettings.RepositoryEntry(
            entries.get(selected).id(), name.getText(), repository.getText(), branch.getText());
    entries.set(selected, entry);
    table
        .getItem(selected)
        .setText(new String[] {entry.name(), entry.repository(), entry.branch()});
    populateDefaults();
  }

  private void populate(int index) {
    table.removeAll();
    for (var entry : entries)
      new TableItem(table, SWT.NONE)
          .setText(new String[] {entry.name(), entry.repository(), entry.branch()});
    if (index >= 0) table.setSelection(index);
    select(index);
    populateDefaults();
  }

  private void populateDefaults() {
    var sorted = LauncherSettings.sortedRepositories(entries);
    defaultRepository.setItems(
        sorted.stream().map(LauncherSettings.RepositoryEntry::label).toArray(String[]::new));
    int index = -1;
    for (int i = 0; i < sorted.size(); i++)
      if (sorted.get(i).id().equals(defaultRepositoryId)) index = i;
    if (index < 0 && !sorted.isEmpty()) index = 0;
    defaultRepositoryId = index < 0 ? "" : sorted.get(index).id();
    if (index >= 0) defaultRepository.select(index);
    defaultRepository.setEnabled(index >= 0);
  }

  private void select(int index) {
    selected = index;
    loading = true;
    try {
      var entry = index < 0 ? null : entries.get(index);
      name.setText(entry == null ? "" : entry.name());
      repository.setText(entry == null ? "" : entry.repository());
      branch.setText(entry == null ? "" : entry.branch());
      for (Text field : new Text[] {name, repository, branch}) field.setEnabled(entry != null);
      remove.setEnabled(entry != null);
    } finally {
      loading = false;
    }
  }

  private Text field(String key, String value) {
    new Label(shell, SWT.NONE).setText(message(key));
    Text field = new Text(shell, SWT.BORDER);
    field.setData("launcher.setting", key);
    field.setText(value);
    field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return field;
  }

  private Button button(Composite parent, String key, Runnable action) {
    Button button = new Button(parent, SWT.PUSH);
    button.setText(message(key));
    button.setData("launcher.action", key);
    button.addListener(SWT.Selection, e -> action.run());
    return button;
  }
}
