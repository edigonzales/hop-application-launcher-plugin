package ch.so.agi.hop.launcher;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.logging.*;
import org.junit.jupiter.api.*;

class RunLogTest {
  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void releaseDuringExecutionRetainsParentAndChildLogsUntilPersistenceCompletes() {
    RunLog run = new RunLog();
    var subject = new SimpleLoggingObject("child", LoggingObjectType.PIPELINE, run.parent());
    ILogChannel child = new LogChannel(subject, run.parent(), false, true);
    run.getLogChannel().logBasic("root message");
    child.logBasic("child message");
    run.release();
    assertTrue(run.text().contains("root message"));
    assertTrue(run.text().contains("child message"));
    assertNotNull(LoggingRegistry.getInstance().getLoggingObject(child.getLogChannelId()));
    run.complete();
    assertNull(LoggingRegistry.getInstance().getLoggingObject(child.getLogChannelId()));
    assertNull(
        LoggingRegistry.getInstance().getLoggingObject(run.getLogChannel().getLogChannelId()));
  }

  @Test
  void completedRunStaysVisibleAndNeverIncludesOtherRuns() {
    RunLog first = new RunLog(), second = new RunLog();
    try {
      first.getLogChannel().logBasic("first only");
      second.getLogChannel().logBasic("second only");
      first.complete();
      assertTrue(first.text().contains("first only"));
      assertFalse(first.text().contains("second only"));
      assertFalse(second.text().contains("first only"));
    } finally {
      first.complete();
      second.complete();
      first.release();
      second.release();
    }
  }
}
