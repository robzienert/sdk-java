package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetVersionWorkflowReplaceGetVersionIdTest extends BaseVersionTest {

  private static final Logger log =
      LoggerFactory.getLogger(GetVersionWorkflowReplaceGetVersionIdTest.class);
  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowReplaceGetVersionId.class)
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionWorkflowReplaceGetVersionId() {
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    NoArgsWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    workflowStub.execute();
    assertTrue(hasReplayed);
  }

  public static class TestGetVersionWorkflowReplaceGetVersionId implements NoArgsWorkflow {

    @Override
    public void execute() {
      log.info("TestGetVersionWorkflow3Impl this=" + this.hashCode());
      // Test adding a version check in replay code.
      if (!WorkflowUnsafe.isReplaying()) {
        // The first version of the code
        int changeFoo1 = Workflow.getVersion("changeFoo0", Workflow.DEFAULT_VERSION, 2);
        if (changeFoo1 != 2) {
          throw new IllegalStateException("Unexpected version: " + changeFoo1);
        }
        int changeFoo2 = Workflow.getVersion("changeFoo1", Workflow.DEFAULT_VERSION, 111);
        if (changeFoo2 != 111) {
          throw new IllegalStateException("Unexpected version: " + changeFoo2);
        }
      } else {
        hasReplayed = true;
        // The updated code
        int changeBar = Workflow.getVersion("changeBar", Workflow.DEFAULT_VERSION, 1);
        if (changeBar != Workflow.DEFAULT_VERSION) {
          throw new IllegalStateException("Unexpected version: " + changeBar);
        }
        int changeFoo = Workflow.getVersion("changeFoo1", Workflow.DEFAULT_VERSION, 123);
        if (changeFoo != 111) {
          throw new IllegalStateException("Unexpected version: " + changeFoo);
        }
      }
      Workflow.sleep(1000); // forces new workflow task
    }
  }
}
