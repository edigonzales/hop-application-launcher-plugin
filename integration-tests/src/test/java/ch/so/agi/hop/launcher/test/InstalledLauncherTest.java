package ch.so.agi.hop.launcher.test;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.config.HopConfig;
import org.apache.hop.core.gui.plugin.GuiPluginType;
import org.apache.hop.core.logging.*;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.ui.core.gui.GuiResource;
import org.apache.hop.ui.hopgui.HopGuiEnvironment;
import org.apache.hop.ui.hopgui.perspective.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.*;
import org.junit.jupiter.api.*;

/** No compile-time dependency on the launcher: only the installed ZIP may supply its classes. */
class InstalledLauncherTest {
  private ClassLoader loader;
  private Path work, source, checkout, input, output;

  @Test
  void installedPerspectiveClonesRendersAndRunsPipelineAndWorkflow() throws Exception {
    Assumptions.assumeTrue(
        System.getProperty("launcher.plugins") != null,
        "Run scripts/run-e2e.py for installed tests");
    work = Path.of("target/installed-e2e").toAbsolutePath();
    Files.createDirectories(work);
    Path config = Files.createTempDirectory(work, "config-");
    Files.writeString(config.resolve("hop-config.json"), "{}");
    System.setProperty("HOP_CONFIG_FOLDER", config.toString());
    System.setProperty("HOP_AUDIT_FOLDER", work.resolve("audit").toString());
    System.setProperty(Const.HOP_PLUGIN_BASE_FOLDERS, System.getProperty("launcher.plugins"));
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("ch.so.agi.hop.launcher.LauncherPerspective"));
    HopEnvironment.init();
    HopGuiEnvironment.init(
        List.of(GuiPluginType.getInstance(), HopPerspectivePluginType.getInstance()));
    var registry = PluginRegistry.getInstance();
    var plugin = registry.findPluginWithId(HopPerspectivePluginType.class, "application-launcher");
    assertNotNull(plugin, "Perspective must be discovered from the installed JAR index");
    // Match HopGui.loadPerspectives(): getClass must work BEFORE getClassLoader.
    // Requesting the loader first would hide a missing GUI-plugin registration.
    Class<IHopPerspective> perspectiveType = registry.getClass(plugin, IHopPerspective.class);
    loader = registry.getClassLoader(plugin);
    assertSame(loader, perspectiveType.getClassLoader());
    Path session = Files.createTempDirectory(work, "session-");
    source = session.resolve("source");
    checkout = session.resolve("checkout");
    output = session.resolve("Ausgabe ä");
    Files.createDirectories(output);
    copyTree(Path.of(System.getProperty("launcher.examples")), source);
    addLongRunningExample();
    addOrganizationExamples();
    git(source, "init", "-b", "main");
    commit(source);
    input = work.resolve("Eingabe ä ; test.xml");
    Files.writeString(input, "<demo/>\n");
    HopConfig.saveOptions(
        Map.of(
            "applicationLauncher.repository",
            source.toString(),
            "applicationLauncher.branch",
            "main",
            "applicationLauncher.checkout",
            checkout.toString()));
    Display display = new Display();
    Shell shell = new Shell(display);
    shell.setText("Application Launcher — installed E2E");
    shell.setLayout(new FormLayout());
    shell.setSize(1100, 720);
    try {
      var perspective = (IHopPerspective) perspectiveType.getConstructor().newInstance();
      perspective.initialize(null, shell);
      shell.open();
      perspective.perspectiveActivated();
      Composite content = (Composite) perspective.getControl();
      assertUpdating(content, "Applications are being updated …");
      screenshot(shell, "launcher-updating.png");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      verifyInitialTree(content);
      selectApp(content, "demo.hello-world");
      assertTrue(findButton(content, "OpenOutput").getVisible());
      assertFalse(findButton(content, "OpenOutput").getEnabled());
      fill(content, "INPUT_XML", input.toString());
      fill(content, "OUTPUT_DIR", output.toString());
      click(content, "Start");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertCsv(output, input);
      assertTrue(logText(content).getText().contains("Write CSV"));
      assertTrue(logText(content).getText().contains("demo.hello-world: SUCCESS"));
      assertTrue(findButton(content, "OpenOutput").getEnabled());
      assertEquals(
          output.toRealPath().toString(), findButton(content, "OpenOutput").getToolTipText());
      fill(content, "OUTPUT_DIR", output.resolve("not-the-last-run").toString());
      assertEquals(
          output.toRealPath().toString(), findButton(content, "OpenOutput").getToolTipText());
      fill(content, "OUTPUT_DIR", output.toString());
      Files.writeString(output.resolve("hello-world.csv"), "THIS MUST BE REPLACED\n");
      click(content, "Start");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertCsv(output, input);
      assertEquals(1, logText(content).getText().split("Starting demo.hello-world", -1).length - 1);
      // Selecting a workflow uses the same form, with parameters forwarded to its child pipeline.
      selectApp(content, "demo.workflow");
      fill(content, "INPUT_XML", input.toString());
      fill(content, "OUTPUT_DIR", output.toString());
      Files.delete(output.resolve("hello-world.csv"));
      click(content, "Start");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertCsv(output, input);
      assertTrue(
          logText(content).getText().contains("Write CSV"), "Workflow children must be visible");
      assertFalse(logText(content).getText().contains("Starting demo.hello-world"));
      screenshot(shell, "launcher.png");
      // Cancellation is a distinct outcome and keeps refresh disabled until the worker releases its
      // lease.
      selectApp(content, "demo.delay");
      fill(content, "INPUT_XML", input.toString());
      fill(content, "OUTPUT_DIR", output.toString());
      click(content, "Start");
      assertFalse(findButton(content, "Refresh").getEnabled());
      assertFalse(findApps(content).getEnabled());
      assertFalse(marked(content, "launcher.repositories").getEnabled());
      click(content, "Cancel");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertTrue(
          allReports().stream().anyMatch(p -> "CANCELLED".equals(p.getProperty("status"))),
          "Cancellation must be recorded");
      verifyLogAndOutputControls(display, perspective, content);
      verifyTreeAndRefresh(display, content);
      verifyRepositories(display, content);
      // Closing the view during execution cancels, but keeps logs until persistence finishes.
      selectApp(content, "demo.delay");
      fill(content, "INPUT_XML", input.toString());
      fill(content, "OUTPUT_DIR", output.toString());
      click(content, "Start");
      waitUi(
          display,
          () -> logText(content).getText().contains("Starting demo.delay"),
          Duration.ofSeconds(4));
      String closingChannel = currentLog(perspective).getLogChannelId();
      content.dispose();
      waitUi(
          display,
          () -> !LoggingRegistry.getInstance().getMap().containsKey(closingChannel),
          Duration.ofSeconds(30));
      waitUi(
          display,
          () ->
              Thread.getAllStackTraces().keySet().stream()
                  .noneMatch(
                      t ->
                          t.isAlive()
                              && (t.getName().equals("log sniffer Timer")
                                  || t.getName().equals("hop-application-launcher"))),
          Duration.ofSeconds(10));
      // Preserve active Hop project state; launcher does not switch projects or set global
      // PROJECT_HOME.
      assertNull(System.getProperty("PROJECT_HOME"));
    } finally {
      shell.dispose();
      while (display.readAndDispatch()) {}
      display.dispose();
    }
    verifyServiceFailuresAndGitLease();
    if (System.getProperty("launcher.topic") != null) verifyPilot();
    System.out.println("Installed launcher artifacts: " + work);
  }

  private List<Properties> allReports() throws Exception {
    List<Properties> reports = new ArrayList<>();
    try (var paths =
        Files.walk(Path.of(Const.HOP_CONFIG_FOLDER).resolve("application-launcher/runs"))) {
      for (Path p :
          paths.filter(p -> p.getFileName().toString().equals("run.properties")).toList()) {
        Properties props = new Properties();
        try (var r = Files.newBufferedReader(p)) {
          props.load(r);
        }
        reports.add(props);
      }
    }
    return reports;
  }

  private void verifyServiceFailuresAndGitLease() throws Exception {
    Object controller = controller(source, checkout);
    call(controller, "refresh");
    call(controller, "prepareRun");
    Object outcome =
        call(
            controller,
            "run",
            new Class<?>[] {String.class, Map.class, Path.class},
            "demo.workflow",
            Map.of("INPUT_XML", input.toString(), "OUTPUT_DIR", output.toString()),
            work.resolve("service-runs"));
    assertEquals("SUCCESS", call(outcome, "status"));
    assertTrue(
        Files.readString((Path) call(outcome, "log")).contains("Write CSV"),
        "Workflow log must include its child pipeline");
    assertCsv(output, input);
    // A directory in place of the output file makes Text File Output fail deterministically on
    // every OS.
    Files.delete(output.resolve("hello-world.csv"));
    Files.createDirectory(output.resolve("hello-world.csv"));
    Files.writeString(output.resolve("hello-world.csv/keep"), "keep");
    call(controller, "prepareRun");
    outcome =
        call(
            controller,
            "run",
            new Class<?>[] {String.class, Map.class, Path.class},
            "demo.hello-world",
            Map.of("INPUT_XML", input.toString(), "OUTPUT_DIR", output.toString()),
            work.resolve("service-runs"));
    assertEquals("FAILED", call(outcome, "status"));
    Files.delete(output.resolve("hello-world.csv/keep"));
    Files.delete(output.resolve("hello-world.csv"));
    // An engine failure must remain a failure, even though a file may have been partially created.
    call(controller, "prepareRun");
    assertEquals(
        "FAILED",
        call(
            call(
                controller,
                "run",
                new Class<?>[] {String.class, Map.class, Path.class},
                "demo.hello-world",
                Map.of(
                    "INPUT_XML",
                    input.resolveSibling("missing.xml").toString(),
                    "OUTPUT_DIR",
                    output.toString()),
                work.resolve("service-runs")),
            "status"));
    // During a long execution, another controller cannot update the checkout.
    call(controller, "prepareRun");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Object> running =
          executor.submit(
              () ->
                  call(
                      controller,
                      "run",
                      new Class<?>[] {String.class, Map.class, Path.class},
                      "demo.delay",
                      Map.of("INPUT_XML", input.toString(), "OUTPUT_DIR", output.toString()),
                      work.resolve("service-runs")));
      Thread.sleep(800);
      Object other = controller(source, checkout);
      assertThrows(InvocationTargetException.class, () -> call(other, "refresh"));
      call(controller, "cancel");
      assertEquals("CANCELLED", call(running.get(30, TimeUnit.SECONDS), "status"));
    } finally {
      executor.shutdownNow();
    }
  }

  private void verifyPilot() throws Exception {
    Path repo = Path.of(System.getProperty("launcher.topic"));
    Path pilot = Files.createTempDirectory(work, "pilot-source-");
    copyTree(repo.resolve("shared/hop"), pilot.resolve("shared/hop"));
    copyTree(
        repo.resolve("staatskanzlei/wahlresultate"), pilot.resolve("staatskanzlei/wahlresultate"));
    git(pilot, "init", "-b", "main");
    commit(pilot);
    Object controller = controller(pilot, pilot.resolveSibling(pilot.getFileName() + "-checkout"));
    call(controller, "refresh");
    call(controller, "prepareRun");
    Path realInput = Path.of(System.getProperty("launcher.input")).toRealPath();
    Path out = work.resolve("pilot-output");
    Files.createDirectories(out);
    Object result =
        call(
            controller,
            "run",
            new Class<?>[] {String.class, Map.class, Path.class},
            "staatskanzlei.wahlresultate",
            Map.of("INPUT_XML", realInput.toString(), "OUTPUT_DIR", out.toString()),
            work.resolve("pilot-runs"));
    assertEquals("SUCCESS", call(result, "status"));
    assertCsv(out, realInput);
    System.out.println("Pilot output: " + out.resolve("hello-world.csv"));
  }

  private Object controller(Path source, Path checkout) throws Exception {
    Class<?> s = loader.loadClass("ch.so.agi.hop.launcher.LauncherSettings");
    Object entry =
        loader
            .loadClass("ch.so.agi.hop.launcher.LauncherSettings$RepositoryEntry")
            .getConstructor(String.class, String.class, String.class, String.class)
            .newInstance("test", "Test", source.toString(), "main");
    Object settings =
        s.getConstructor(List.class, Path.class, String.class)
            .newInstance(List.of(entry), checkout, "test");
    return loader
        .loadClass("ch.so.agi.hop.launcher.LauncherController")
        .getConstructor(s)
        .newInstance(settings);
  }

  private Object call(Object object, String name) throws Exception {
    return call(object, name, new Class<?>[0]);
  }

  private Object call(Object object, String name, Class<?>[] signature, Object... values)
      throws Exception {
    return object.getClass().getMethod(name, signature).invoke(object, values);
  }

  private void addLongRunningExample() throws Exception {
    Path file = source.resolve("demo/hello-world.hpl");
    String xml =
        Files.readString(file)
            .replace("<to>Hello World</to>", "<to>Delay</to>")
            .replace(
                "</order>",
                "<hop><from>Delay</from><to>Hello World</to><enabled>Y</enabled></hop></order>")
            .replace(
                "</pipeline>",
                "<transform><name>Delay</name><type>Delay</type><copies>1</copies><timeout>10</timeout><scaletime>seconds</scaletime></transform></pipeline>");
    Files.writeString(source.resolve("demo/delay.hpl"), xml);
    Files.writeString(
        source.resolve("demo/no-output.launcher.yaml"),
        Files.readString(source.resolve("demo/hello-world.launcher.yaml"))
            .replace("outputDirectoryParameter: OUTPUT_DIR\n", ""));
    Files.writeString(
        source.resolve("shared/hop/applications.yaml"),
        "  - id: demo.delay\n"
            + "    entrypoint: demo/delay.hpl\n"
            + "    sidecar: demo/hello-world.launcher.yaml\n"
            + "  - id: demo.no-output\n"
            + "    entrypoint: demo/hello-world.hpl\n"
            + "    sidecar: demo/no-output.launcher.yaml\n",
        StandardOpenOption.APPEND);
  }

  private void verifyLogAndOutputControls(
      Display display, IHopPerspective perspective, Composite content) throws Exception {
    selectApp(content, "demo.no-output");
    assertFalse(findButton(content, "OpenOutput").getVisible());
    assertTrue(
        ((org.eclipse.swt.layout.RowData) findButton(content, "OpenOutput").getLayoutData())
            .exclude);
    fill(content, "INPUT_XML", input.toString());
    fill(content, "OUTPUT_DIR", output.toString());
    click(content, "Start");
    waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
    assertFalse(findButton(content, "OpenOutput").getVisible());
    assertFalse(findButton(content, "OpenOutput").getEnabled());

    selectApp(content, "demo.delay");
    assertTrue(findButton(content, "OpenOutput").getVisible());
    assertFalse(findButton(content, "OpenOutput").getEnabled());
    fill(content, "INPUT_XML", input.toString());
    fill(content, "OUTPUT_DIR", output.toString());
    click(content, "Start");
    waitUi(
        display,
        () -> logText(content).getText().contains("Starting demo.delay"),
        Duration.ofSeconds(4));
    assertFalse(findButton(content, "Start").getEnabled(), "Log must appear before the run ends");
    ILogChannel runChannel = currentLog(perspective);
    new LogChannel("Unrelated execution").logBasic("FOREIGN_LOG_SENTINEL");
    runChannel.logBasic("BEFORE_CLEAR_SENTINEL");
    waitUi(
        display,
        () -> logText(content).getText().contains("BEFORE_CLEAR_SENTINEL"),
        Duration.ofSeconds(3));
    assertFalse(logText(content).getText().contains("FOREIGN_LOG_SENTINEL"));
    Button pause = findButton(content, "PauseLog");
    pause.setSelection(true);
    click(content, "PauseLog");
    String paused = logText(content).getText();
    runChannel.logBasic("AFTER_PAUSE_SENTINEL");
    pump(display, 1100);
    assertEquals(paused, logText(content).getText());
    pause.setSelection(false);
    click(content, "PauseLog");
    assertTrue(logText(content).getText().contains("AFTER_PAUSE_SENTINEL"));
    Text filter =
        (Text)
            controls(content).stream()
                .filter(c -> Boolean.TRUE.equals(c.getData("launcher.log.filter")))
                .findFirst()
                .orElseThrow();
    filter.setText("AFTER_PAUSE_SENTINEL");
    assertTrue(logText(content).getText().contains("AFTER_PAUSE_SENTINEL"));
    assertFalse(logText(content).getText().contains("BEFORE_CLEAR_SENTINEL"));
    findButton(content, "HighlightLog").setSelection(true);
    click(content, "HighlightLog");
    assertTrue(logText(content).getText().contains("BEFORE_CLEAR_SENTINEL"));
    findButton(content, "HighlightLog").setSelection(false);
    click(content, "HighlightLog");
    findButton(content, "ExcludeLog").setSelection(true);
    click(content, "ExcludeLog");
    assertFalse(logText(content).getText().contains("AFTER_PAUSE_SENTINEL"));
    assertTrue(logText(content).getText().contains("BEFORE_CLEAR_SENTINEL"));
    findButton(content, "ExcludeLog").setSelection(false);
    click(content, "ExcludeLog");
    filter.setText("after_pause_sentinel");
    findButton(content, "MatchCaseLog").setSelection(true);
    click(content, "MatchCaseLog");
    assertTrue(logText(content).getText().isBlank());
    findButton(content, "MatchCaseLog").setSelection(false);
    click(content, "MatchCaseLog");
    assertTrue(logText(content).getText().contains("AFTER_PAUSE_SENTINEL"));
    filter.setText("");
    Font baseFont = logText(content).getFont();
    Font systemFont = display.getSystemFont();
    Font defaultFont = GuiResource.getInstance().getFontDefault();
    int baseHeight = baseFont.getFontData()[0].getHeight();
    click(content, "LargerLog");
    Font largerFont = logText(content).getFont();
    assertEquals(baseHeight + 1, largerFont.getFontData()[0].getHeight());
    assertFalse(systemFont.isDisposed(), "Zoom must preserve the display's shared font");
    assertFalse(defaultFont.isDisposed(), "Zoom must preserve Hop's shared default font");
    click(content, "SmallerLog");
    Font smallerFont = logText(content).getFont();
    assertEquals(baseHeight, smallerFont.getFontData()[0].getHeight());
    assertTrue(largerFont.isDisposed(), "Replaced zoom fonts must be released");
    click(content, "ResetLogFont");
    assertSame(baseFont, logText(content).getFont());
    assertTrue(smallerFont.isDisposed(), "Reset must release the zoom font");
    assertFalse(baseFont.isDisposed(), "Reset must preserve Hop's shared fixed font");
    click(content, "ClearLog");
    assertTrue(logText(content).getText().isBlank());
    runChannel.logError("AFTER_CLEAR_ERROR");
    waitUi(
        display,
        () -> logText(content).getText().contains("AFTER_CLEAR_ERROR"),
        Duration.ofSeconds(3));
    assertFalse(logText(content).getText().contains("BEFORE_CLEAR_SENTINEL"));
    assertTrue(logText(content).getStyleRanges().length > 0, "Native error highlighting");
    // Hiding and reactivating the perspective must not cancel or restart its engine.
    content.setVisible(false);
    perspective.perspectiveActivated();
    pump(display, 100);
    content.setVisible(true);
    waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
    assertTrue(logText(content).getText().contains("demo.delay: SUCCESS"));
    assertTrue(findButton(content, "OpenOutput").getEnabled());
    Path saved =
        Path.of(
            controls(content).stream()
                .filter(c -> Boolean.TRUE.equals(c.getData("launcher.logFile")))
                .findFirst()
                .orElseThrow()
                .getToolTipText());
    String diskLog = Files.readString(saved);
    assertTrue(diskLog.contains("BEFORE_CLEAR_SENTINEL"));
    assertTrue(diskLog.contains("AFTER_PAUSE_SENTINEL"));
    assertTrue(diskLog.contains("AFTER_CLEAR_ERROR"));
    assertFalse(diskLog.contains("FOREIGN_LOG_SENTINEL"));

    // Validation error before creating an engine is rendered and leaves the output button disabled.
    fill(content, "INPUT_XML", input.resolveSibling("missing.xml").toString());
    click(content, "Start");
    waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
    assertTrue(logText(content).getText().contains("missing.xml"));
    assertFalse(findButton(content, "OpenOutput").getEnabled());
    assertFalse(logText(content).getText().contains("AFTER_CLEAR_ERROR"));

    // Engine failure still exposes the validated output directory.
    selectApp(content, "demo.hello-world");
    fill(content, "INPUT_XML", input.toString());
    fill(content, "OUTPUT_DIR", output.toString());
    Files.deleteIfExists(output.resolve("hello-world.csv"));
    Files.createDirectories(output.resolve("hello-world.csv"));
    Files.writeString(output.resolve("hello-world.csv/keep"), "keep");
    click(content, "Start");
    waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
    assertTrue(logText(content).getText().contains("demo.hello-world: FAILED"));
    assertTrue(findButton(content, "OpenOutput").getEnabled());
    Files.delete(output.resolve("hello-world.csv/keep"));
    Files.delete(output.resolve("hello-world.csv"));
    click(content, "Refresh");
    assertFalse(findButton(content, "OpenOutput").getVisible());
    waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
    assertFalse(findButton(content, "OpenOutput").getEnabled());
  }

  private void screenshot(Shell shell, String filename) {
    shell.update();
    Image screenshot =
        new Image(shell.getDisplay(), shell.getClientArea().width, shell.getClientArea().height);
    GC gc = new GC(shell);
    try {
      gc.copyArea(screenshot, 0, 0);
      ImageLoader images = new ImageLoader();
      images.data = new ImageData[] {screenshot.getImageData()};
      images.save(work.resolve(filename).toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      screenshot.dispose();
    }
  }

  private void addOrganizationExamples() throws Exception {
    Files.createDirectories(source.resolve("alpha"));
    Files.createDirectories(source.resolve("zeta"));
    Files.copy(source.resolve("demo/hello-world.hpl"), source.resolve("alpha/example.hpl"));
    Files.copy(source.resolve("demo/hello-world.hpl"), source.resolve("root.hpl"));
    Files.copy(source.resolve("demo/hello-world.hwf"), source.resolve("zeta/example.hwf"));
    Files.copy(source.resolve("demo/hello-world.hpl"), source.resolve("alpha/unlisted.hpl"));
    Files.writeString(
        source.resolve("shared/hop/applications.yaml"),
        """
          - id: demo.root
            entrypoint: root.hpl
            sidecar: demo/hello-world.launcher.yaml
          - id: demo.zeta
            entrypoint: zeta/example.hwf
            sidecar: demo/workflow.launcher.yaml
          - id: demo.alpha
            entrypoint: alpha/example.hpl
            sidecar: demo/hello-world.launcher.yaml
        """,
        StandardOpenOption.APPEND);
  }

  private static void selectApp(Composite content, String id) {
    Tree tree = findApps(content);
    TreeItem item =
        Arrays.stream(tree.getItems())
            .flatMap(g -> Arrays.stream(g.getItems()))
            .filter(i -> id.equals(i.getData("launcher.applicationId")))
            .findFirst()
            .orElseThrow();
    selectItem(tree, item);
  }

  private static void selectItem(Tree tree, TreeItem item) {
    tree.setSelection(item);
    Event event = new Event();
    event.item = item;
    tree.notifyListeners(SWT.Selection, event);
  }

  private static String selectedId(Composite content) {
    return (String) findApps(content).getData("launcher.selectedApplicationId");
  }

  private static TreeItem organization(Composite content, String key) {
    return Arrays.stream(findApps(content).getItems())
        .filter(i -> key.equals(i.getData("launcher.organization")))
        .findFirst()
        .orElseThrow();
  }

  private static Control marked(Composite content, String key) {
    return controls(content).stream()
        .filter(c -> Boolean.TRUE.equals(c.getData(key)))
        .findFirst()
        .orElseThrow();
  }

  private static void assertUpdating(Composite content, String text) {
    assertTrue(marked(content, "launcher.updateProgress").isVisible());
    assertEquals(text, ((Label) marked(content, "launcher.updateMessage")).getText());
    assertFalse(findApps(content).getEnabled());
    assertFalse(findButton(content, "Start").getEnabled());
  }

  private static void awaitRefresh(Display display, Composite content) throws Exception {
    waitUi(display, () -> findButton(content, "Refresh").getEnabled(), Duration.ofSeconds(30));
    assertFalse(marked(content, "launcher.updateProgress").isVisible());
  }

  private static void refreshSuccessfully(Display display, Composite content) throws Exception {
    click(content, "Refresh");
    assertUpdating(content, "Applications are being updated …");
    awaitRefresh(display, content);
    assertFalse(marked(content, "launcher.updateMessage").isVisible());
  }

  private static void verifyInitialTree(Composite content) {
    Tree tree = findApps(content);
    assertEquals(
        List.of("alpha", "demo", "General", "zeta"),
        Arrays.stream(tree.getItems()).map(TreeItem::getText).toList());
    assertEquals(7, Arrays.stream(tree.getItems()).mapToInt(TreeItem::getItemCount).sum());
    assertEquals("demo.alpha", selectedId(content));
    for (TreeItem group : tree.getItems()) {
      assertTrue(group.getExpanded());
      assertNotNull(group.getImage());
      for (TreeItem app : group.getItems()) assertNotNull(app.getImage());
    }
    TreeItem demo = organization(content, "demo");
    assertEquals(
        List.of("demo.hello-world", "demo.workflow", "demo.delay", "demo.no-output"),
        Arrays.stream(demo.getItems()).map(i -> i.getData("launcher.applicationId")).toList());
    assertNotSame(demo.getItem(0).getImage(), demo.getItem(1).getImage());
    assertFalse(marked(content, "launcher.updateMessage").isVisible());
  }

  private void verifyTreeAndRefresh(Display display, Composite content) throws Exception {
    Path manifest = source.resolve("shared/hop/applications.yaml");
    String original = Files.readString(manifest);
    selectApp(content, "demo.workflow");
    TreeItem oldOrganization = organization(content, "demo");
    organization(content, "alpha").setExpanded(false);
    organization(content, "demo").setExpanded(false);
    // GTK may move the native highlight to the collapsed parent without a user selection event.
    // Emulate that on every platform: refresh must retain the application shown in the form.
    findApps(content).setSelection(organization(content, "demo"));
    assertEquals("demo.workflow", selectedId(content));
    String workflow =
        "  - id: demo.workflow\n"
            + "    entrypoint: demo/hello-world.hwf\n"
            + "    sidecar: demo/workflow.launcher.yaml\n";
    Files.writeString(manifest, original.replace(workflow, "") + workflow);
    commit(source);
    refreshSuccessfully(display, content);
    assertEquals("demo.workflow", selectedId(content));
    assertTrue(findButton(content, "Start").getEnabled());
    assertTrue(
        controls(content).stream()
            .anyMatch(
                c -> c instanceof Label l && l.getText().startsWith("Hello World workflow\n")));
    assertFalse(organization(content, "alpha").getExpanded());
    assertFalse(organization(content, "demo").getExpanded());
    // Late GTK events from the disposed pre-refresh tree must not select a new organization.
    Event staleSelection = new Event();
    staleSelection.item = oldOrganization;
    findApps(content).notifyListeners(SWT.Selection, staleSelection);
    assertEquals("demo.workflow", selectedId(content));
    assertEquals(
        "demo.workflow",
        organization(content, "demo").getItem(3).getData("launcher.applicationId"));

    selectItem(findApps(content), organization(content, "demo"));
    assertFalse(findButton(content, "Start").getEnabled());
    assertFalse(findButton(content, "OpenOutput").getVisible());
    assertTrue(
        controls(content).stream()
            .anyMatch(
                c -> c instanceof Label l && l.getText().equals("demo\nSelect an application")));
    refreshSuccessfully(display, content);
    assertNull(selectedId(content));
    assertEquals("demo", findApps(content).getSelection()[0].getData("launcher.organization"));
    assertFalse(findButton(content, "Start").getEnabled());

    selectApp(content, "demo.workflow");
    Files.writeString(manifest, original.replace(workflow, ""));
    commit(source);
    refreshSuccessfully(display, content);
    assertEquals("demo.alpha", selectedId(content));

    Files.writeString(manifest, "schemaVersion: 1\napplications: []\n");
    commit(source);
    refreshSuccessfully(display, content);
    assertEquals(0, findApps(content).getItemCount());
    assertFalse(findButton(content, "Start").getEnabled());
    assertFalse(findButton(content, "OpenOutput").getVisible());

    Files.writeString(manifest, original);
    commit(source);
    refreshSuccessfully(display, content);
    selectApp(content, "demo.workflow");
    organization(content, "zeta").setExpanded(false);
    Path unavailable = source.resolveSibling("offline-source");
    Files.move(source, unavailable);
    try {
      click(content, "Refresh");
      assertUpdating(content, "Applications are being updated …");
      awaitRefresh(display, content);
      assertTrue(marked(content, "launcher.updateMessage").isVisible());
      assertEquals(
          "Applications could not be updated. See the log for details.",
          ((Label) marked(content, "launcher.updateMessage")).getText());
      assertFalse(logText(content).getText().isBlank());
      assertFalse(findButton(content, "Start").getEnabled());
      assertTrue(findButton(content, "Offline").getEnabled());
      click(content, "Offline");
      assertUpdating(content, "Loading local applications …");
      awaitRefresh(display, content);
      assertFalse(marked(content, "launcher.updateMessage").isVisible());
      assertEquals("demo.workflow", selectedId(content));
      assertFalse(organization(content, "zeta").getExpanded());
      assertTrue(findButton(content, "Start").getEnabled());
    } finally {
      Files.move(unavailable, source);
    }
    refreshSuccessfully(display, content);
  }

  private void assertStartupRepository(Display display, String expected) throws Exception {
    HopConfig.getInstance().readFromFile();
    Shell startup = new Shell(display);
    startup.setLayout(new FormLayout());
    Font baseFont = GuiResource.getInstance().getFontFixed();
    Font zoomFont = null;
    try {
      var perspective =
          (IHopPerspective)
              loader
                  .loadClass("ch.so.agi.hop.launcher.LauncherPerspective")
                  .getConstructor()
                  .newInstance();
      perspective.initialize(null, startup);
      assertEquals(
          expected,
          ((Combo) marked((Composite) perspective.getControl(), "launcher.repositories"))
              .getText());
      Composite content = (Composite) perspective.getControl();
      click(content, "LargerLog");
      zoomFont = logText(content).getFont();
    } finally {
      startup.dispose();
    }
    assertFalse(baseFont.isDisposed(), "Closing a view must preserve Hop's shared fixed font");
    assertTrue(zoomFont.isDisposed(), "Closing a view must release its zoom font");
    assertFalse(display.getSystemFont().isDisposed());
    assertFalse(GuiResource.getInstance().getFontDefault().isDisposed());
  }

  private Shell openSettings(Composite content) {
    click(content, "Settings");
    return Arrays.stream(content.getDisplay().getShells())
        .filter(s -> Boolean.TRUE.equals(s.getData("launcher.settings")))
        .findFirst()
        .orElseThrow();
  }

  private static Text setting(Composite dialog, String key) {
    return (Text)
        controls(dialog).stream()
            .filter(c -> key.equals(c.getData("launcher.setting")))
            .findFirst()
            .orElseThrow();
  }

  private void chooseRepository(Display display, Composite content, int index) throws Exception {
    Combo combo = (Combo) marked(content, "launcher.repositories");
    var entries = (List<?>) HopConfig.readOption("applicationLauncher.repositories");
    var entry = (Map<?, ?>) entries.get(index);
    combo.select(combo.indexOf(entry.get("name") + " (" + entry.get("branch") + ")"));
    combo.notifyListeners(SWT.Selection, new Event());
    assertUpdating(content, "Applications are being updated …");
    assertFalse(combo.getEnabled());
    assertEquals("", ((Label) marked(content, "launcher.revision")).getText());
    assertEquals(0, findApps(content).getItemCount());
    assertTrue(logText(content).getText().isBlank());
    awaitRefresh(display, content);
  }

  private void verifyRepositories(Display display, Composite content) throws Exception {
    Combo combo = (Combo) marked(content, "launcher.repositories");
    assertEquals(1, combo.getItemCount());
    Object originalEntries = HopConfig.readOption("applicationLauncher.repositories");
    String originalActive =
        HopConfig.readOptionString("applicationLauncher.defaultRepositoryId", "");
    Path base = Path.of(HopConfig.readOptionString("applicationLauncher.checkoutBase", ""));
    assertEquals(checkout.resolveSibling("checkout-repositories"), base);
    assertFalse(Files.exists(checkout), "Migration must not create or alter the legacy checkout");

    Shell dialog = openSettings(content);
    setting(dialog, "RepositoryName").setText("Discarded rename");
    click(dialog, "AddRepository");
    click(dialog, "Close");
    assertEquals(originalEntries, HopConfig.readOption("applicationLauncher.repositories"));
    assertEquals(1, combo.getItemCount());

    Path second = source.resolveSibling("second-source");
    copyTree(Path.of(System.getProperty("launcher.examples")), second);
    Path sidecar = second.resolve("demo/hello-world.launcher.yaml");
    Files.writeString(
        sidecar,
        Files.readString(sidecar).replace("title: Hello World", "title: Second repository"));
    git(second, "init", "-b", "release");
    commit(second);
    dialog = openSettings(content);
    click(dialog, "AddRepository");
    assertEquals("main", setting(dialog, "Branch").getText());
    click(dialog, "Save");
    assertFalse(dialog.isDisposed());
    assertFalse(((Label) marked(dialog, "launcher.settingsError")).getText().isBlank());
    setting(dialog, "RepositoryName").setText("Second");
    setting(dialog, "Repository").setText(second.toString());
    setting(dialog, "Branch").setText("release");
    screenshot(dialog, "launcher-repository-settings.png");
    click(dialog, "Save");
    assertTrue(dialog.isDisposed());
    awaitRefresh(display, content);
    assertEquals(2, combo.getItemCount());
    assertArrayEquals(new String[] {"Second (release)", "source (main)"}, combo.getItems());
    String firstRevision = ((Label) marked(content, "launcher.revision")).getText();
    selectApp(content, "demo.hello-world");
    fill(content, "INPUT_XML", "MUST_NOT_SURVIVE_REPOSITORY_SWITCH");
    chooseRepository(display, content, 1);
    assertEquals("demo.hello-world", selectedId(content));
    assertTrue(
        controls(content).stream()
            .anyMatch(c -> c instanceof Label l && l.getText().startsWith("Second repository\n")));
    assertTrue(
        controls(content).stream()
            .noneMatch(c -> c instanceof Text t && t.getText().contains("MUST_NOT_SURVIVE")));
    String secondRevision = ((Label) marked(content, "launcher.revision")).getText();
    assertNotEquals(firstRevision, secondRevision);
    String secondId =
        (String)
            ((Map<?, ?>)
                    ((List<?>) HopConfig.readOption("applicationLauncher.repositories")).get(1))
                .get("id");
    assertEquals(
        originalActive, HopConfig.readOptionString("applicationLauncher.defaultRepositoryId", ""));
    HopConfig.getInstance().readFromFile(); // Exercise the on-disk format used on the next startup.
    Object reloaded =
        loader.loadClass("ch.so.agi.hop.launcher.LauncherSettings").getMethod("load").invoke(null);
    assertEquals(originalActive, call(reloaded, "activeRepositoryId"));
    assertStartupRepository(display, "source (main)");
    reloaded = call(reloaded, "select", new Class<?>[] {String.class}, secondId);
    Path secondCheckout = (Path) call(call(reloaded, "activeLocation"), "checkout");
    assertEquals("release", git(secondCheckout, "branch", "--show-current").trim());
    fill(content, "INPUT_XML", input.toString());
    fill(content, "OUTPUT_DIR", output.toString());
    click(content, "Start");
    assertFalse(combo.getEnabled());
    waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
    assertCsv(output, input);
    screenshot(content.getShell(), "launcher-multiple-repositories.png");

    // Closing a draft default selection does not change startup behavior.
    dialog = openSettings(content);
    Combo defaultChoice = (Combo) marked(dialog, "launcher.defaultRepository");
    defaultChoice.select(defaultChoice.indexOf("Second (release)"));
    defaultChoice.notifyListeners(SWT.Selection, new Event());
    click(dialog, "Close");
    assertStartupRepository(display, "source (main)");

    // Renaming keeps the same checkout; explicitly choose this repository as the startup default.
    dialog = openSettings(content);
    Table table = (Table) marked(dialog, "launcher.repositoryList");
    table.setSelection(1);
    table.notifyListeners(SWT.Selection, new Event());
    setting(dialog, "RepositoryName").setText("Renamed second");
    defaultChoice = (Combo) marked(dialog, "launcher.defaultRepository");
    defaultChoice.select(defaultChoice.indexOf("Renamed second (release)"));
    defaultChoice.notifyListeners(SWT.Selection, new Event());
    screenshot(dialog, "launcher-default-repository-settings.png");
    click(dialog, "Save");
    awaitRefresh(display, content);
    assertEquals("Renamed second (release)", combo.getText());
    reloaded =
        loader.loadClass("ch.so.agi.hop.launcher.LauncherSettings").getMethod("load").invoke(null);
    assertEquals(secondCheckout, call(call(reloaded, "activeLocation"), "checkout"));
    assertEquals(secondId, call(reloaded, "defaultRepositoryId"));
    assertStartupRepository(display, "Renamed second (release)");

    // Network failure after a switch cannot expose the previous repository's form or revision.
    chooseRepository(display, content, 0);
    assertStartupRepository(display, "Renamed second (release)");
    Path unavailable = second.resolveSibling("second-unavailable");
    Files.move(second, unavailable);
    try {
      chooseRepository(display, content, 1);
      assertFalse(findButton(content, "Start").getEnabled());
      assertEquals("", ((Label) marked(content, "launcher.revision")).getText());
      assertTrue(findButton(content, "Offline").getEnabled());
      click(content, "Offline");
      awaitRefresh(display, content);
      assertTrue(findButton(content, "Start").getEnabled());
      assertTrue(
          ((Label) marked(content, "launcher.revision"))
              .getText()
              .endsWith(secondRevision.substring(secondRevision.indexOf(':') + 1).trim()));
    } finally {
      Files.move(unavailable, second);
    }
    dialog = openSettings(content);
    table = (Table) marked(dialog, "launcher.repositoryList");
    table.setSelection(1);
    table.notifyListeners(SWT.Selection, new Event());
    click(dialog, "RemoveRepository");
    click(dialog, "Save");
    awaitRefresh(display, content);
    assertEquals(1, combo.getItemCount());
    assertEquals(
        originalActive, HopConfig.readOptionString("applicationLauncher.defaultRepositoryId", ""));
    assertTrue(Files.isDirectory(secondCheckout.resolve(".git")));
    assertStartupRepository(display, "source (main)");
    dialog = openSettings(content);
    click(dialog, "RemoveRepository");
    assertFalse(marked(dialog, "launcher.defaultRepository").getEnabled());
    click(dialog, "Save");
    assertEquals(0, combo.getItemCount());
    assertFalse(combo.getEnabled());
    for (String action : List.of("Refresh", "Offline", "Start"))
      assertFalse(findButton(content, action).getEnabled());
    assertEquals(0, findApps(content).getItemCount());
    HopConfig.getInstance().readFromFile();
    reloaded =
        loader.loadClass("ch.so.agi.hop.launcher.LauncherSettings").getMethod("load").invoke(null);
    assertEquals(List.of(), call(reloaded, "repositories"));
    assertTrue(((Label) marked(content, "launcher.status")).getText().contains("No repositories"));

    // Re-add the original source through the UI, leaving later lifecycle tests a runnable catalog.
    dialog = openSettings(content);
    click(dialog, "AddRepository");
    setting(dialog, "RepositoryName").setText("Original");
    setting(dialog, "Repository").setText(source.toString());
    click(dialog, "Save");
    awaitRefresh(display, content);
    assertTrue(findButton(content, "Start").getEnabled());
  }

  private static StyledText logText(Composite content) {
    return (StyledText)
        controls(content).stream()
            .filter(c -> Boolean.TRUE.equals(c.getData("launcher.log")))
            .findFirst()
            .orElseThrow();
  }

  private static ILogChannel currentLog(Object perspective) throws Exception {
    Field panelField = perspective.getClass().getDeclaredField("log");
    panelField.setAccessible(true);
    Object panel = panelField.get(perspective);
    Field sessionField = panel.getClass().getDeclaredField("session");
    sessionField.setAccessible(true);
    return ((IHasLogChannel) sessionField.get(panel)).getLogChannel();
  }

  private static void pump(Display display, long millis) throws Exception {
    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    waitUi(display, () -> System.nanoTime() >= until, Duration.ofMillis(millis + 1000));
  }

  private static void assertCsv(Path folder, Path input) throws Exception {
    var lines = Files.readAllLines(folder.resolve("hello-world.csv"));
    assertEquals(2, lines.size());
    assertEquals("message;input_file", lines.get(0));
    String expected = input.toRealPath().toString();
    if (expected.contains(";") || expected.contains("\""))
      expected = "\"" + expected.replace("\"", "\"\"") + "\"";
    assertEquals("Hello World;" + expected, lines.get(1));
  }

  private static void waitUi(Display d, BooleanSupplier done, Duration timeout) throws Exception {
    long until = System.nanoTime() + timeout.toNanos();
    while (!done.getAsBoolean()) {
      if (System.nanoTime() > until) fail("UI operation did not complete");
      if (!d.readAndDispatch()) Thread.sleep(10);
    }
    while (d.readAndDispatch()) {}
  }

  private static List<Control> controls(Composite root) {
    List<Control> result = new ArrayList<>();
    for (Control c : root.getChildren()) {
      result.add(c);
      if (c instanceof Composite nested) result.addAll(controls(nested));
    }
    return result;
  }

  private static Button findButton(Composite root, String key) {
    return (Button)
        controls(root).stream()
            .filter(c -> key.equals(c.getData("launcher.action")))
            .findFirst()
            .orElseThrow();
  }

  private static Tree findApps(Composite root) {
    return (Tree)
        controls(root).stream()
            .filter(c -> Boolean.TRUE.equals(c.getData("launcher.apps")))
            .findFirst()
            .orElseThrow();
  }

  private static void fill(Composite root, String parameter, String value) {
    ((Text)
            controls(root).stream()
                .filter(c -> parameter.equals(c.getData("launcher.parameter")))
                .findFirst()
                .orElseThrow())
        .setText(value);
  }

  private static void click(Composite root, String key) {
    findButton(root, key).notifyListeners(SWT.Selection, new Event());
  }

  private static void copyTree(Path from, Path to) throws Exception {
    try (var paths = Files.walk(from)) {
      for (Path p : paths.toList()) {
        Path target = to.resolve(from.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(target);
        else Files.copy(p, target);
      }
    }
  }

  private static String git(Path directory, String... args) throws Exception {
    List<String> cmd =
        new ArrayList<>(
            List.of(
                "git",
                "-c",
                "user.name=Launcher test",
                "-c",
                "user.email=launcher@example.invalid",
                "-c",
                "commit.gpgsign=false"));
    cmd.addAll(Arrays.asList(args));
    Process p =
        new ProcessBuilder(cmd).directory(directory.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    assertEquals(0, p.waitFor(), out);
    return out;
  }

  private static void commit(Path directory) throws Exception {
    git(directory, "add", ".");
    git(directory, "commit", "-m", "Disposable launcher fixture");
  }
}
