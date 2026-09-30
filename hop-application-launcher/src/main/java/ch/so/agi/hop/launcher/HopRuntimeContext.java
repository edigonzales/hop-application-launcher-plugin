package ch.so.agi.hop.launcher;

import java.nio.file.Path;
import org.apache.hop.core.parameters.INamedParameterDefinitions;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.workflow.WorkflowMeta;

/** Deliberately independent of HopGui's current project and metadata provider. */
public final class HopRuntimeContext {
  public final Variables variables = new Variables();
  public final JsonMetadataProvider metadata;

  public HopRuntimeContext(Path root) throws Exception {
    variables.setVariable("PROJECT_HOME", root.toRealPath().toString());
    metadata = new JsonMetadataProvider();
    metadata.setVariables(variables);
    metadata.setBaseFolder(root.resolve("shared/hop/metadata").toString());
  }

  public INamedParameterDefinitions load(Path file) throws Exception {
    if (file.toString().endsWith(".hpl"))
      return new PipelineMeta(file.toString(), metadata, variables);
    WorkflowMeta workflow = new WorkflowMeta(variables, file.toString(), metadata);
    if (workflow.hasMissingPlugins())
      throw new IllegalArgumentException("Workflow requires missing action plugins: " + file);
    return workflow;
  }
}
