package io.temporal.workflow;

import static io.temporal.testing.internal.SDKTestOptions.newWorkflowOptionsWithTimeouts;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflowWithCronScheduleImpl;
import java.time.Duration;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowWithCronScheduleTest {

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowWithCronScheduleImpl.class)
          .build();

  @Test
  public void testCronWorkflowWithIncrementSchedule() {
    // Min interval in cron is 1min. So we will not test it against real service in Jenkins.
    // Feel free to uncomment the line below and test in local.
    assumeFalse("skipping as test will timeout", SDKTestWorkflowRule.useExternalService);

    WorkflowStub client =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                "TestWorkflowWithCronSchedule",
                newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
                    .setWorkflowRunTimeout(Duration.ofHours(1))
                    // Slash is used to describe increments of n here so this cron executes every
                    // 6th hour.
                    .setCronSchedule("0 */6 * * *")
                    .build());
    testWorkflowRule.registerDelayedCallback(Duration.ofDays(1), client::cancel);
    client.start(testName.getMethodName());

    try {
      client.getResult(String.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }

    Map<Integer, String> lastCompletionResults =
        TestWorkflowWithCronScheduleImpl.lastCompletionResults.get(testName.getMethodName());
    assertEquals(4, lastCompletionResults.size());
    // Run 3 failed. So on run 4 we get the last completion result from run 2.
    assertEquals("run 2", lastCompletionResults.get(4));
    // The last failure ought to be the one from run 3
    assertTrue(TestWorkflowWithCronScheduleImpl.lastFail.isPresent());
    assertTrue(
        TestWorkflowWithCronScheduleImpl.lastFail.get().getMessage().contains("simulated error"));
  }
}
