package ch.so.agi.hop.launcher;

import static ch.so.agi.hop.launcher.ApplicationDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CatalogLoaderTest {
  @TempDir Path temp;

  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  private Path catalog(String extra, String form) throws Exception {
    Path root = temp.resolve("repo");
    Files.createDirectories(root.resolve("shared/hop"));
    Files.writeString(
        root.resolve("shared/hop/applications.yaml"),
        "schemaVersion: 1\n"
            + "applications:\n"
            + "  - id: demo\n"
            + "    entrypoint: demo.hpl\n"
            + "    sidecar: demo.yaml\n"
            + extra);
    Files.writeString(
        root.resolve("demo.hpl"),
        "<pipeline><info><name>demo</name><parameters>"
            + "<parameter><name>INPUT</name><default_value/></parameter><parameter><name>OUTPUT</name><default_value/></parameter>"
            + "<parameter><name>CHOICE</name><default_value>csv</default_value></parameter>"
            + "<parameter><name>FLAG</name><default_value>Y</default_value></parameter>"
            + "<parameter><name>TEXT</name><default_value>Hello</default_value></parameter>"
            + "</parameters></info></pipeline>");
    Files.writeString(root.resolve("demo.yaml"), "schemaVersion: 1\ntitle: Demo\n" + form);
    return root;
  }

  private final String form =
      """
      outputDirectoryParameter: OUTPUT
      parameters:
        INPUT:
          type: file
          required: true
          extensions: [xml]
        OUTPUT:
          type: directory
          required: true
        CHOICE:
          type: choice
          values: [csv, json]
        FLAG:
          type: boolean
      """;

  @Test
  void loadsAllTypesAndPreservesHopDefaultsAndSidecarOrder() throws Exception {
    var app = new CatalogLoader().load(catalog("", form)).getFirst();
    assertEquals(
        List.of("INPUT", "OUTPUT", "CHOICE", "FLAG", "TEXT"),
        app.parameters().stream().map(Parameter::name).toList());
    assertEquals(
        List.of(Type.FILE, Type.DIRECTORY, Type.CHOICE, Type.BOOLEAN, Type.STRING),
        app.parameters().stream().map(Parameter::type).toList());
    assertEquals("", app.organization());
    assertEquals("Y", app.parameters().get(3).defaultValue());
    assertEquals("Hello", app.parameters().get(4).defaultValue());
  }

  @Test
  void derivesOrganizationFromNormalizedEntrypointRatherThanIdOrSidecar() throws Exception {
    Path root = catalog("", form);
    Files.createDirectories(root.resolve("staatskanzlei/wahlresultate"));
    Files.move(root.resolve("demo.hpl"), root.resolve("staatskanzlei/wahlresultate/demo.hpl"));
    Path manifest = root.resolve("shared/hop/applications.yaml");
    Files.writeString(
        manifest,
        Files.readString(manifest)
            .replace("entrypoint: demo.hpl", "entrypoint: ./staatskanzlei/wahlresultate/demo.hpl"));
    var app = new CatalogLoader().load(root).getFirst();
    assertEquals("demo", app.id());
    assertEquals("staatskanzlei", app.organization());
  }

  @Test
  void rejectsUnknownParametersTypesDefaultsAndDuplicateKeys() throws Exception {
    Path root = catalog("", form);
    Path sidecar = root.resolve("demo.yaml");
    String original = Files.readString(sidecar);
    for (String invalid :
        List.of(
            original.replace("INPUT:", "UNKNOWN:"),
            original.replace("type: file", "type: integer"),
            original.replace("[csv, json]", "[json]"),
            original + "title: Twice\n",
            original.replace("schemaVersion: 1", "schemaVersion: 2"))) {
      Files.writeString(sidecar, invalid);
      assertThrows(Exception.class, () -> new CatalogLoader().load(root));
    }
    Files.writeString(sidecar, original);
    Path hpl = root.resolve("demo.hpl");
    Files.writeString(
        hpl,
        Files.readString(hpl)
            .replace("<default_value>Y</default_value>", "<default_value>true</default_value>"));
    assertThrows(Exception.class, () -> new CatalogLoader().load(root));
  }

  @Test
  void rejectsDuplicateIdsMissingFilesAndEscapes() throws Exception {
    Path root = catalog("  - id: demo\n    entrypoint: demo.hpl\n    sidecar: demo.yaml\n", form);
    assertThrows(Exception.class, () -> new CatalogLoader().load(root));
    assertThrows(Exception.class, () -> CatalogLoader.inside(root, "missing.hpl"));
    Path outside = temp.resolve("outside.hpl");
    Files.writeString(outside, "test");
    assertThrows(Exception.class, () -> CatalogLoader.inside(root, "../outside.hpl"));
    assertThrows(Exception.class, () -> CatalogLoader.inside(root, outside.toString()));
  }

  @Test
  void validatesFilesFoldersChoicesAndDoesNotChangeInput() throws Exception {
    Path root = catalog("", form);
    var app = new CatalogLoader().load(root).getFirst();
    Path input = temp.resolve("Wählen ; input.xml");
    Files.writeString(input, "<anything/>");
    Map<String, String> values =
        new HashMap<>(Map.of("INPUT", input.toString(), "OUTPUT", temp.toString()));
    var result = ParameterValidator.validate(app, values, root);
    assertEquals(input.toRealPath().toString(), result.get("INPUT"));
    assertEquals("csv", result.get("CHOICE"));
    values.put("CHOICE", "bad");
    assertThrows(Exception.class, () -> ParameterValidator.validate(app, values, root));
    values.remove("CHOICE");
    values.put("FLAG", "true");
    assertThrows(Exception.class, () -> ParameterValidator.validate(app, values, root));
    values.remove("FLAG");
    values.put("OUTPUT", root.toString());
    assertThrows(Exception.class, () -> ParameterValidator.validate(app, values, root));
    values.put("OUTPUT", input.toString());
    assertThrows(Exception.class, () -> ParameterValidator.validate(app, values, root));
    values.put("OUTPUT", temp.toString());
    values.put("INPUT", temp.resolve("missing.xml").toString());
    assertThrows(Exception.class, () -> ParameterValidator.validate(app, values, root));
    assertEquals("<anything/>", Files.readString(input));
  }
}
