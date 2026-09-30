package ch.so.agi.hop.launcher;

import java.util.*;
import java.util.function.BiConsumer;
import org.apache.hop.ui.core.gui.GuiResource;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Groups only catalog applications and retains selection by identity across asynchronous refreshes.
 */
final class ApplicationTree {
  private final Tree tree;
  private final BiConsumer<ApplicationDefinition, String> selection;
  private final Map<String, Boolean> expanded = new HashMap<>();
  private String selectedId, selectedOrganization;

  ApplicationTree(Composite parent, BiConsumer<ApplicationDefinition, String> selection) {
    this.selection = selection;
    tree = new Tree(parent, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
    tree.setData("launcher.apps", true);
    tree.addListener(
        SWT.Selection,
        e -> {
          if (tree.getSelectionCount() > 0) {
            rememberSelection();
            notifySelection();
          }
        });
  }

  void setEnabled(boolean enabled) {
    tree.setEnabled(enabled);
  }

  /** Keep the last good state even if an update fails and the tree is temporarily empty. */
  void clearForRefresh() {
    if (tree.getItemCount() > 0) {
      for (TreeItem group : tree.getItems())
        expanded.put((String) group.getData("launcher.organization"), group.getExpanded());
      rememberSelection();
    }
    tree.removeAll();
    notifySelection();
  }

  void reset() {
    tree.removeAll();
    expanded.clear();
    selectedId = null;
    selectedOrganization = null;
    notifySelection();
  }

  void populate(List<ApplicationDefinition> applications) {
    Map<String, List<ApplicationDefinition>> groups =
        new TreeMap<>(
            Comparator.comparing(ApplicationTree::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.naturalOrder()));
    for (var app : applications)
      groups.computeIfAbsent(app.organization(), key -> new ArrayList<>()).add(app);
    TreeItem first = null, restored = null, target;
    tree.setRedraw(false);
    try {
      tree.removeAll();
      for (var entry : groups.entrySet()) {
        String organization = entry.getKey();
        TreeItem group = new TreeItem(tree, SWT.NONE);
        group.setText(label(organization));
        group.setData("launcher.organization", organization);
        group.setImage(GuiResource.getInstance().getImageFolder());
        if (selectedId == null && organization.equals(selectedOrganization)) restored = group;
        for (var app : entry.getValue()) {
          TreeItem leaf = new TreeItem(group, SWT.NONE);
          leaf.setText(app.title());
          leaf.setData(app);
          leaf.setData("launcher.applicationId", app.id());
          leaf.setImage(
              app.entrypoint().toString().endsWith(".hpl")
                  ? GuiResource.getInstance().getImagePipeline()
                  : GuiResource.getInstance().getImageWorkflow());
          if (first == null) first = leaf;
          if (app.id().equals(selectedId)) restored = leaf;
        }
        group.setExpanded(expanded.getOrDefault(organization, true));
      }
      target = restored != null ? restored : first;
      if (target != null) {
        tree.setSelection(target);
        rememberSelection();
        // setSelection expands ancestors on Cocoa. Restore their state after selecting the leaf.
        for (TreeItem group : tree.getItems())
          group.setExpanded(
              expanded.getOrDefault((String) group.getData("launcher.organization"), true));
      }
      expanded.keySet().retainAll(groups.keySet());
    } finally {
      tree.setRedraw(true);
    }
    if (target == null) {
      selectedId = null;
      selectedOrganization = null;
    }
    notifySelection(target);
  }

  private void rememberSelection() {
    TreeItem[] current = tree.getSelection();
    // Cocoa can clear the native selection when its parent is collapsed. Keep the form's identity.
    if (current.length > 0) {
      selectedId = (String) current[0].getData("launcher.applicationId");
      selectedOrganization = (String) current[0].getData("launcher.organization");
    }
  }

  private void notifySelection() {
    TreeItem[] items = tree.getSelection();
    notifySelection(items.length == 0 ? null : items[0]);
  }

  private void notifySelection(TreeItem item) {
    // The form retains a selected leaf even when a collapsed group hides its native highlight.
    tree.setData(
        "launcher.selectedApplicationId",
        item == null ? null : item.getData("launcher.applicationId"));
    if (item == null) selection.accept(null, "");
    else if (item.getData() instanceof ApplicationDefinition app) selection.accept(app, "");
    else selection.accept(null, item.getText());
  }

  private static String label(String organization) {
    return organization.isEmpty()
        ? LauncherPerspective.message("GeneralOrganization")
        : organization;
  }
}
