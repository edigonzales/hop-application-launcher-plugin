package ch.so.agi.hop.launcher;

import static ch.so.agi.hop.launcher.LauncherPerspective.message;

import org.apache.hop.core.variables.Variables;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.gui.GuiResource;
import org.apache.hop.ui.core.widget.StyledTextVar;
import org.apache.hop.ui.hopgui.file.pipeline.HopGuiLogBrowser;
import org.apache.hop.ui.hopgui.file.shared.TextZoom;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Thin controls around Hop's log rendering, filtering and polling implementation. */
final class LauncherLogPanel {
  private final Composite content;
  private final StyledTextVar text;
  private final Button pause;
  private final Text filter;
  private final Button highlight, matchCase, exclude;
  private final Font baseFont;
  private final TextZoom zoom;
  private final HopGuiLogBrowser browser;
  private RunLog session;

  LauncherLogPanel(Composite parent) {
    CTabFolder tabs = new CTabFolder(parent, SWT.BORDER);
    PropsUi.setLook(tabs);
    CTabItem tab = new CTabItem(tabs, SWT.NONE);
    tab.setText(message("Log"));
    content = new Composite(tabs, SWT.NONE);
    content.setLayout(new GridLayout(1, false));
    tab.setControl(content);
    tabs.setSelection(tab);
    Composite toolbar = new Composite(content, SWT.NONE);
    toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    RowLayout row = new RowLayout();
    row.center = true;
    toolbar.setLayout(row);
    action(toolbar, "ClearLog", SWT.PUSH, this::clear);
    action(toolbar, "CopyLog", SWT.PUSH, this::copy);
    pause = action(toolbar, "PauseLog", SWT.CHECK, this::pauseChanged);
    action(toolbar, "LargerLog", SWT.PUSH, () -> changeFont(1));
    action(toolbar, "SmallerLog", SWT.PUSH, () -> changeFont(-1));
    action(toolbar, "ResetLogFont", SWT.PUSH, () -> changeFont(0));

    Composite filters = new Composite(content, SWT.NONE);
    filters.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    filters.setLayout(new GridLayout(5, false));
    new Label(filters, SWT.NONE).setText(message("FilterLog"));
    filter = new Text(filters, SWT.BORDER | SWT.SEARCH);
    filter.setData("launcher.log.filter", true);
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    highlight = action(filters, "HighlightLog", SWT.CHECK, this::filterChanged);
    matchCase = action(filters, "MatchCaseLog", SWT.CHECK, this::filterChanged);
    exclude = action(filters, "ExcludeLog", SWT.CHECK, this::filterChanged);

    text =
        new StyledTextVar(
            new Variables(),
            content,
            SWT.READ_ONLY | SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL,
            false,
            false);
    text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    text.getTextWidget().setData("launcher.log", true);
    PropsUi.setLook(text);
    baseFont = GuiResource.getInstance().getFontFixed();
    zoom = new TextZoom(text, baseFont);
    zoom.resetFont();
    browser = new HopGuiLogBrowser(text, () -> session);
    browser.installLogSniffer();
    filter.addModifyListener(e -> filterChanged());
    text.addDisposeListener(
        e -> {
          if (session != null) session.release();
          Font current = text.getFont();
          if (current != baseFont) current.dispose();
        });
  }

  private Button action(Composite parent, String key, int style, Runnable action) {
    Button button = new Button(parent, style);
    button.setText(message(key));
    button.setData("launcher.action", key);
    button.addListener(SWT.Selection, e -> action.run());
    return button;
  }

  void attach(RunLog replacement) {
    if (session != null) session.release();
    session = replacement;
    pause.setSelection(false);
    browser.setPaused(false);
    // Attach before starting the worker so resetLogPosition cannot skip messages from this run.
    browser.resetLogChannels();
    browser.resetLogPosition();
    text.setText("");
    filter.setText("");
    highlight.setSelection(false);
    matchCase.setSelection(false);
    exclude.setSelection(false);
    filterChanged();
  }

  void finish() {
    // Even sub-second runs must show their final lines without waiting for the polling timer.
    pause.setSelection(false);
    browser.setPaused(false);
    browser.refreshFilteredView();
  }

  void error(Throwable error) {
    if (session == null) attach(new RunLog());
    session.getLogChannel().logError(error.toString(), error);
    session.complete();
    finish();
  }

  private void clear() {
    browser.resetLogPosition();
    text.setText("");
  }

  private void copy() {
    String selection = text.getSelectionText();
    GuiResource.getInstance().toClipboard(selection.isEmpty() ? text.getText() : selection);
  }

  private void pauseChanged() {
    browser.setPaused(pause.getSelection());
    if (!pause.getSelection()) browser.refreshFilteredView();
  }

  private void filterChanged() {
    browser.setFilter(
        filter.getText(),
        highlight.getSelection(),
        matchCase.getSelection(),
        exclude.getSelection());
    browser.refreshFilteredView();
  }

  private void changeFont(int direction) {
    Font old = text.getFont();
    if (direction > 0) zoom.increaseFont();
    else if (direction < 0) zoom.decreaseFont();
    else zoom.resetFont();
    if (old != baseFont && old != text.getFont()) old.dispose();
  }
}
