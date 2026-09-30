package ch.so.agi.hop.launcher;

import java.nio.file.Path;
import java.util.List;

public record ApplicationDefinition(
    String id,
    String organization,
    Path entrypoint,
    Path sidecar,
    String title,
    String description,
    String outputDirectoryParameter,
    List<Parameter> parameters) {
  public enum Type {
    STRING,
    FILE,
    DIRECTORY,
    CHOICE,
    BOOLEAN
  }

  public record Parameter(
      String name,
      String label,
      String description,
      Type type,
      boolean required,
      List<String> extensions,
      List<String> values,
      String defaultValue) {}
}
