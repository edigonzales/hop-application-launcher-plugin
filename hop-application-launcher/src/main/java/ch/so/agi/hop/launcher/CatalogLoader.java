package ch.so.agi.hop.launcher;

import static ch.so.agi.hop.launcher.ApplicationDefinition.*;

import java.nio.file.*;
import java.util.*;
import org.apache.hop.core.parameters.INamedParameterDefinitions;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/** Version-one YAML contract. YAML is parsed as data only, never instantiated as Java objects. */
public final class CatalogLoader {
  public List<ApplicationDefinition> load(Path checkout) throws Exception {
    Path root = checkout.toRealPath();
    Path manifest = inside(root, "shared/hop/applications.yaml");
    Map<String, Object> catalog = yaml(manifest);
    List<ApplicationDefinition> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    HopRuntimeContext context = new HopRuntimeContext(root);
    for (Object item : list(catalog.get("applications"), "applications")) {
      Map<String, Object> app = map(item, manifest.toString());
      String id = text(app, "id", true);
      if (!ids.add(id)) throw invalid(manifest, "Duplicate application ID: " + id);
      Path relativeEntry = Path.of(text(app, "entrypoint", true)).normalize();
      Path entry = inside(root, relativeEntry.toString());
      String organization =
          relativeEntry.getNameCount() > 1 ? relativeEntry.getName(0).toString() : "";
      if (!(entry.toString().endsWith(".hpl") || entry.toString().endsWith(".hwf")))
        throw invalid(manifest, "Entrypoint must be .hpl or .hwf: " + entry);
      Path sidecar = inside(root, text(app, "sidecar", true));
      try {
        Map<String, Object> form = yaml(sidecar);
        INamedParameterDefinitions definitions = context.load(entry);
        Set<String> remaining = new TreeSet<>(Arrays.asList(definitions.listParameters()));
        List<Parameter> parameters = new ArrayList<>();
        Map<String, Object> fields = map(form.getOrDefault("parameters", Map.of()), "parameters");
        for (var field : fields.entrySet()) {
          String name = field.getKey();
          if (!remaining.remove(name)) throw invalid(sidecar, "Unknown Hop parameter: " + name);
          parameters.add(parameter(name, map(field.getValue(), name), definitions));
        }
        for (String name : remaining) parameters.add(parameter(name, Map.of(), definitions));
        String output = text(form, "outputDirectoryParameter", false);
        if (!output.isEmpty()
            && parameters.stream()
                .noneMatch(p -> p.name().equals(output) && p.type() == Type.DIRECTORY))
          throw invalid(sidecar, "outputDirectoryParameter must reference a directory parameter");
        result.add(
            new ApplicationDefinition(
                id,
                organization,
                entry,
                sidecar,
                text(form, "title", true),
                text(form, "description", false),
                output,
                List.copyOf(parameters)));
      } catch (Exception e) {
        throw invalid(sidecar, e.getMessage(), e);
      }
    }
    return List.copyOf(result);
  }

  private Parameter parameter(
      String name, Map<String, Object> field, INamedParameterDefinitions hop) throws Exception {
    Type type =
        Type.valueOf(field.getOrDefault("type", "string").toString().toUpperCase(Locale.ROOT));
    String value = Objects.toString(hop.getParameterDefault(name), "");
    List<String> values = strings(field.getOrDefault("values", List.of()), "values");
    List<String> extensions = strings(field.getOrDefault("extensions", List.of()), "extensions");
    if (type == Type.CHOICE
        && (values.isEmpty()
            || new HashSet<>(values).size() != values.size()
            || (!value.isEmpty() && !values.contains(value))))
      throw new IllegalArgumentException(name + ": choice values/default are invalid");
    if (type == Type.BOOLEAN) {
      if (value.isEmpty()) value = "N";
      if (!Set.of("Y", "N").contains(value))
        throw new IllegalArgumentException(name + ": boolean default must be Y or N");
    }
    Object required = field.getOrDefault("required", false);
    if (!(required instanceof Boolean))
      throw new IllegalArgumentException(name + ": required must be boolean");
    for (String ext : extensions)
      if (!ext.matches("[A-Za-z0-9]+"))
        throw new IllegalArgumentException(
            name + ": extensions must be suffixes without dots or wildcards");
    String label = text(field, "label", false);
    String description =
        field.containsKey("description")
            ? text(field, "description", false)
            : Objects.toString(hop.getParameterDescription(name), "");
    return new Parameter(
        name,
        label.isEmpty() ? name : label,
        description,
        type,
        (Boolean) required,
        extensions,
        values,
        value);
  }

  public static Path inside(Path root, String relative) throws Exception {
    Path path = Path.of(relative);
    if (path.isAbsolute()) throw invalid(root, "Expected relative path: " + relative);
    Path resolved = root.resolve(path).normalize().toRealPath();
    if (!resolved.startsWith(root.toRealPath()) || !Files.isRegularFile(resolved))
      throw invalid(root, "File outside checkout or not a regular file: " + relative);
    return resolved;
  }

  private Map<String, Object> yaml(Path file) throws Exception {
    try (var input = Files.newInputStream(file)) {
      var settings =
          LoadSettings.builder()
              .setLabel(file.toString())
              .setAllowDuplicateKeys(false)
              .setMaxAliasesForCollections(0)
              .setCodePointLimit(1_000_000)
              .build();
      Map<String, Object> value =
          map(new Load(settings).loadFromInputStream(input), file.toString());
      if (!(value.get("schemaVersion") instanceof Number n)
          || n.intValue() != 1
          || n.doubleValue() != 1) throw invalid(file, "Unsupported schemaVersion (expected 1)");
      return value;
    } catch (Exception e) {
      throw invalid(file, e.getMessage(), e);
    }
  }

  private static Map<String, Object> map(Object value, String label) {
    if (!(value instanceof Map<?, ?> raw))
      throw new IllegalArgumentException(label + ": expected mapping");
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach(
        (k, v) -> {
          if (!(k instanceof String s))
            throw new IllegalArgumentException(label + ": expected string keys");
          result.put(s, v);
        });
    return result;
  }

  private static List<?> list(Object value, String label) {
    if (!(value instanceof List<?> result))
      throw new IllegalArgumentException(label + ": expected list");
    return result;
  }

  private static List<String> strings(Object value, String label) {
    return list(value, label).stream()
        .map(
            v -> {
              if (!(v instanceof String s) || s.isBlank())
                throw new IllegalArgumentException(label + ": expected nonempty strings");
              return s;
            })
        .toList();
  }

  private static String text(Map<String, Object> map, String key, boolean required) {
    Object raw = map.getOrDefault(key, "");
    if (!(raw instanceof String s) || (required && s.isBlank()))
      throw new IllegalArgumentException(
          key + ": expected " + (required ? "nonempty " : "") + "string");
    return s;
  }

  private static IllegalArgumentException invalid(Path file, String message) {
    return invalid(file, message, null);
  }

  private static IllegalArgumentException invalid(Path file, String message, Exception cause) {
    return new IllegalArgumentException(file + ": " + message, cause);
  }
}
