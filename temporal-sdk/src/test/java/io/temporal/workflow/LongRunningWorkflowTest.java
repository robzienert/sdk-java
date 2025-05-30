package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.activity.ActivityOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class LongRunningWorkflowTest {
  private final SleepActivitySleepingLongerThanLongPollTimeoutImpl sleepActivities =
      new SleepActivitySleepingLongerThanLongPollTimeoutImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(LongRunningWorkflowImpl.class)
          .setActivityImplementations(sleepActivities)
          .setTestTimeoutSeconds(
              WorkflowServiceStubsOptions.DEFAULT_POLL_RPC_TIMEOUT
                  .plus(Duration.ofSeconds(20))
                  .toMillis())
          .build();

  /**
   * Workflow execution and block on blocking stub takes longer than long poll timeout. Check that
   * we don't throw any exceptions and retry the long poll until the result is available.
   */
  @Test
  public void testAwaitingForWorkflowResultLongerThanLongPollTimeout() {
    TestWorkflows.TestWorkflowReturnString longRunningWorkflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);

    assertEquals("ok", longRunningWorkflow.execute());
  }

  public static class LongRunningWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {
    private final ActivityOptions activityOptions =
        ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build();

    private final TestActivities.NoArgsReturnsStringActivity activities =
        Workflow.newActivityStub(TestActivities.NoArgsReturnsStringActivity.class, activityOptions);

    @Override
    public String execute() {
      return activities.execute();
    }
  }

  public static class SleepActivitySleepingLongerThanLongPollTimeoutImpl
      implements TestActivities.NoArgsReturnsStringActivity {
    @Override
    public String execute() {
      try {
        Thread.sleep(
            WorkflowServiceStubsOptions.DEFAULT_POLL_RPC_TIMEOUT
                .plus(Duration.ofSeconds(10))
                .toMillis());
      } catch (InterruptedException e) {
        fail("unexpected interrupted exception");
        Thread.currentThread().interrupt();
        return "not ok";
      }
      return "ok";
    }
  }
}
