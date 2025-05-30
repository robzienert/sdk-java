package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionInSignalOnReplayTest extends BaseVersionTest {
  public static boolean hasReplayedSignal;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionInSignal.class)
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionInSignal() {
    TestWorkflows.TestSignaledWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestSignaledWorkflow.class);
    WorkflowExecution start = WorkflowClient.start(workflow::execute);

    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    workflow.signal("done");
    String result = workflowStub.getResult(String.class);
    assertTrue(hasReplayedSignal);
    assertEquals("[done]", result);
  }

  @Test
  public void testGetVersionInSignalReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testGetVersionInSignalHistory.json", TestGetVersionInSignal.class);
  }

  /** The following test covers the scenario where getVersion call is performed inside a signal */
  public static class TestGetVersionInSignal implements TestWorkflows.TestSignaledWorkflow {

    private final List<String> signalled = new ArrayList<>();

    @Override
    public String execute() {
      Workflow.sleep(5_000);
      return signalled.toString();
    }

    @Override
    public void signal(String arg) {
      hasReplayedSignal = WorkflowUnsafe.isReplaying();
      Workflow.getVersion("some-id", 1, 2);
      signalled.add(arg);
    }
  }
}
