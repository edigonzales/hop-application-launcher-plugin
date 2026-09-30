package ch.so.agi.hop.launcher;

import static ch.so.agi.hop.launcher.ApplicationDefinition.*;

import java.nio.file.*;
import java.util.*;

public final class ParameterValidator {
  private ParameterValidator() {}

  public static Map<String, String> validate(
      ApplicationDefinition app, Map<String, String> supplied, Path root) throws Exception {
    Map<String, String> result = new LinkedHashMap<>();
    for (Parameter p : app.parameters()) {
      String value = supplied.getOrDefault(p.name(), p.defaultValue());
      if (p.required() && value.isBlank())
        throw new IllegalArgumentException(p.label() + ": value is required");
      if (p.type() == Type.BOOLEAN && !Set.of("Y", "N").contains(value))
        throw new IllegalArgumentException(p.label() + ": expected Y or N");
      if (p.type() == Type.CHOICE && !value.isEmpty() && !p.values().contains(value))
        throw new IllegalArgumentException(p.label() + ": invalid choice");
      if (!value.isBlank() && (p.type() == Type.FILE || p.type() == Type.DIRECTORY)) {
        Path path = Path.of(value).toRealPath();
        if (path.startsWith(root.toRealPath()))
          throw new IllegalArgumentException(
              p.label() + ": input/output must be outside the managed checkout");
        if (p.type() == Type.FILE) {
          if (!Files.isRegularFile(path))
            throw new IllegalArgumentException(p.label() + ": not a file");
          try (var input = Files.newInputStream(path)) {
            input.read();
          }
          if (!p.extensions().isEmpty()
              && p.extensions().stream()
                  .noneMatch(
                      ext ->
                          path.getFileName()
                              .toString()
                              .toLowerCase(Locale.ROOT)
                              .endsWith("." + ext.toLowerCase(Locale.ROOT))))
            throw new IllegalArgumentException(p.label() + ": unsupported file extension");
        } else if (!Files.isDirectory(path))
          throw new IllegalArgumentException(p.label() + ": not a directory");
        if (p.name().equals(app.outputDirectoryParameter()) && !Files.isWritable(path))
          throw new IllegalArgumentException(p.label() + ": directory is not writable");
        value = path.toString();
      }
      result.put(p.name(), value);
    }
    if (!result.keySet().containsAll(supplied.keySet()))
      throw new IllegalArgumentException("Unknown supplied parameter");
    return Collections.unmodifiableMap(result);
  }
}
