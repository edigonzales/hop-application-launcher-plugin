package ch.so.agi.hop.launcher;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hop.core.Result;
import org.apache.hop.core.parameters.INamedParameters;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engine.*;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.config.WorkflowRunConfiguration;
import org.apache.hop.workflow.engine.*;

/** Synchronous service executed on a worker; cancellation may be requested by the SWT thread. */
public final class ExecutionService {
  public record Outcome(String status, Path report, Path log, String outputDirectory) {}

  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final RunLog runLog = new RunLog();
  private volatile IPipelineEngine<PipelineMeta> pipeline;
  private volatile IWorkflowEngine<WorkflowMeta> workflow;

  public RunLog log() {
    return runLog;
  }

  public void cancel() {
    cancelled.set(true);
  }

  public Outcome run(
      Path root, String revision, ApplicationDefinition app, Map<String, String> values, Path logs)
      throws Exception {
    Instant start = Instant.now();
    Path folder = logs.resolve(start.toString().replace(':', '-') + "-" + UUID.randomUUID());
    Files.createDirectories(folder);
    Path report = folder.resolve("run.properties"), log = folder.resolve("hop.log");
    String status = "FAILED", output = "", error = "";
    // Engines may reset their stop flag during initialization. Reassert cancellation from a
    // worker until execution ends; never stop transforms on the SWT event thread.
    ScheduledExecutorService cancellation =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "launcher-cancellation");
              t.setDaemon(true);
              return t;
            });
    cancellation.scheduleWithFixedDelay(
        () -> {
          if (cancelled.get()) {
            var p = pipeline;
            if (p != null && p.isRunning()) p.stopAll();
            var w = workflow;
            if (w != null && w.isActive()) w.stopExecution();
          }
        },
        0,
        50,
        TimeUnit.MILLISECONDS);
    try {
      runLog.getLogChannel().logBasic("Starting " + app.id());
      var validated = ParameterValidator.validate(app, values, root);
      output = validated.getOrDefault(app.outputDirectoryParameter(), "");
      HopRuntimeContext context = new HopRuntimeContext(root);
      var meta = context.load(app.entrypoint());
      Result result;
      if (meta instanceof PipelineMeta pm) {
        var config =
            context.metadata.getSerializer(PipelineRunConfiguration.class).load("launcher-local");
        if (config == null
            || config.getEngineRunConfiguration() == null
            || !"Local".equals(config.getEngineRunConfiguration().getEnginePluginId()))
          throw new IllegalArgumentException(
              "launcher-local must be a local pipeline configuration");
        pipeline =
            PipelineEngineFactory.createPipelineEngine(
                context.variables, "launcher-local", context.metadata, pm);
        pipeline.setParent(runLog.parent());
        setParameters(pipeline, validated);
        if (!cancelled.get()) {
          pipeline.prepareExecution();
          if (cancelled.get()) pipeline.stopAll();
          else {
            pipeline.startThreads();
            pipeline.waitUntilFinished();
          }
        }
        result = pipeline.getResult();
      } else {
        var config =
            context.metadata.getSerializer(WorkflowRunConfiguration.class).load("launcher-local");
        if (config == null
            || config.getEngineRunConfiguration() == null
            || !"Local".equals(config.getEngineRunConfiguration().getEnginePluginId()))
          throw new IllegalArgumentException(
              "launcher-local must be a local workflow configuration");
        workflow =
            WorkflowEngineFactory.createWorkflowEngine(
                context.variables,
                "launcher-local",
                context.metadata,
                (WorkflowMeta) meta,
                runLog.parent());
        setParameters(workflow, validated);
        result = cancelled.get() ? new Result() : workflow.startExecution();
      }
      status =
          cancelled.get()
              ? "CANCELLED"
              : result.getNrErrors() == 0 && (pipeline != null || result.getResult())
                  ? "SUCCESS"
                  : "FAILED";
    } catch (Exception | LinkageError e) {
      error = e.toString();
      runLog.getLogChannel().logError(error, e);
      if (cancelled.get()) status = "CANCELLED";

    } finally {
      cancellation.shutdownNow();
      runLog.getLogChannel().logBasic(app.id() + ": " + status);
      try {
        Files.writeString(log, runLog.text());
        Properties props = new Properties();
        props.setProperty("application", app.id());
        props.setProperty("revision", revision);
        props.setProperty("start", start.toString());
        props.setProperty("end", Instant.now().toString());
        props.setProperty("status", status);
        props.setProperty("error", error);
        try (var writer = Files.newBufferedWriter(report)) {
          props.store(writer, "Hop Application Launcher");
        }
      } finally {
        try {
          if (pipeline != null) pipeline.cleanup();
        } finally {
          pipeline = null;
          workflow = null;
          runLog.complete();
        }
      }
    }
    return new Outcome(status, report, log, output);
  }

  private void setParameters(INamedParameters target, Map<String, String> values) throws Exception {
    for (var entry : values.entrySet()) target.setParameterValue(entry.getKey(), entry.getValue());
    target.activateParameters((org.apache.hop.core.variables.IVariables) target);
  }
}
