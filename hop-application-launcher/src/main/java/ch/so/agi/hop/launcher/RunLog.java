package ch.so.agi.hop.launcher;

import java.util.UUID;
import org.apache.hop.core.logging.*;

/** A stable parent for a run's engine logs; retains no engine or SWT objects. */
public final class RunLog implements IHasLogChannel, ILogParentProvided {
  private final SimpleLoggingObject parent;
  private final ILogChannel channel;
  private boolean complete, released;

  public RunLog() {
    parent = new SimpleLoggingObject("Application Launcher", LoggingObjectType.GENERAL, null);
    parent.setContainerObjectId(UUID.randomUUID().toString());
    channel = new LogChannel(parent, null, false, true);
    parent.setLogChannelId(channel.getLogChannelId());
  }

  public ILoggingObject parent() {
    return parent;
  }

  @Override
  public ILogChannel getLogChannel() {
    return channel;
  }

  @Override
  public IHasLogChannel getLogChannelProvider() {
    return this;
  }

  public String text() {
    return HopLogStore.getAppender().getBuffer(channel.getLogChannelId(), false).toString();
  }

  /** Called only after the final log has been persisted, including when the view was closed. */
  public synchronized void complete() {
    complete = true;
    discardIfUnused();
  }

  /** The UI no longer needs this run; never discard a still-running engine's log. */
  public synchronized void release() {
    released = true;
    discardIfUnused();
  }

  private void discardIfUnused() {
    if (complete && released) HopLogStore.discardLines(channel.getLogChannelId(), false);
  }
}
