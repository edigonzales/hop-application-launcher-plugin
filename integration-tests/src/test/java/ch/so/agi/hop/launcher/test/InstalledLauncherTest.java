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
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.ui.hopgui.HopGuiEnvironment;
import org.apache.hop.ui.hopgui.perspective.*;
import org.eclipse.swt.SWT;
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
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertEquals(3, findApps(content).getItemCount());
      fill(content, "INPUT_XML", input.toString());
      fill(content, "OUTPUT_DIR", output.toString());
      click(content, "Start");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertCsv(output, input);
      Files.writeString(output.resolve("hello-world.csv"), "THIS MUST BE REPLACED\n");
      click(content, "Start");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertCsv(output, input);
      // Selecting a workflow uses the same form, with parameters forwarded to its child pipeline.
      var list = findApps(content);
      list.select(1);
      list.notifyListeners(SWT.Selection, new Event());
      fill(content, "INPUT_XML", input.toString());
      fill(content, "OUTPUT_DIR", output.toString());
      Files.delete(output.resolve("hello-world.csv"));
      click(content, "Start");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertCsv(output, input);
      Image screenshot =
          new Image(display, shell.getClientArea().width, shell.getClientArea().height);
      GC gc = new GC(shell);
      try {
        gc.copyArea(screenshot, 0, 0);
        ImageLoader images = new ImageLoader();
        images.data = new ImageData[] {screenshot.getImageData()};
        images.save(work.resolve("launcher.png").toString(), SWT.IMAGE_PNG);
      } finally {
        gc.dispose();
        screenshot.dispose();
      }
      // Cancellation is a distinct outcome and keeps refresh disabled until the worker releases its
      // lease.
      list.select(2);
      list.notifyListeners(SWT.Selection, new Event());
      fill(content, "INPUT_XML", input.toString());
      fill(content, "OUTPUT_DIR", output.toString());
      click(content, "Start");
      assertFalse(findButton(content, "Refresh").getEnabled());
      click(content, "Cancel");
      waitUi(display, () -> findButton(content, "Start").getEnabled(), Duration.ofSeconds(30));
      assertTrue(
          allReports().stream().anyMatch(p -> "CANCELLED".equals(p.getProperty("status"))),
          "Cancellation must be recorded");
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
    Object settings =
        s.getConstructor(String.class, String.class, Path.class)
            .newInstance(source.toString(), "main", checkout);
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
                "<transform><name>Delay</name><type>Delay</type><copies>1</copies><timeout>5</timeout><scaletime>seconds</scaletime></transform></pipeline>");
    Files.writeString(source.resolve("demo/delay.hpl"), xml);
    Files.writeString(
        source.resolve("shared/hop/applications.yaml"),
        "  - id: demo.delay\n"
            + "    entrypoint: demo/delay.hpl\n"
            + "    sidecar: demo/hello-world.launcher.yaml\n",
        StandardOpenOption.APPEND);
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

  private static org.eclipse.swt.widgets.List findApps(Composite root) {
    return (org.eclipse.swt.widgets.List)
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

  private static void git(Path directory, String... args) throws Exception {
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
  }

  private static void commit(Path directory) throws Exception {
    git(directory, "add", ".");
    git(directory, "commit", "-m", "Disposable launcher fixture");
  }
}
