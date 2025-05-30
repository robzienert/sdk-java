package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivities;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class DetachedScopeTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  private static final CompletionClientActivitiesImpl completionClientActivitiesImpl =
      new CompletionClientActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestDetachedCancellationScope.class)
          .setActivityImplementations(activitiesImpl, completionClientActivitiesImpl)
          .build();

  @AfterClass
  public static void afterClass() throws Exception {
    completionClientActivitiesImpl.close();
  }

  @Test
  public void testDetachedScope() {
    completionClientActivitiesImpl.setCompletionClient(
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient());
    WorkflowStub client = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow1");
    client.start(testWorkflowRule.getTaskQueue());
    testWorkflowRule.sleep(Duration.ofSeconds(1)); // To let activityWithDelay start.
    client.cancel();
    try {
      client.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
    activitiesImpl.assertInvocations("activity1", "activity2", "activity3");
    completionClientActivitiesImpl.assertInvocations("activityWithDelay");
  }

  public static class TestDetachedCancellationScope implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      CompletionClientActivities completionClientActivities =
          Workflow.newActivityStub(
              CompletionClientActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));

      try {
        completionClientActivities.activityWithDelay(100000, true);
        fail("unreachable");
      } catch (ActivityFailure e) {
        assertTrue(e.getCause() instanceof CanceledFailure);
        Workflow.newDetachedCancellationScope(() -> assertEquals(1, testActivities.activity1(1)))
            .run();
      }
      try {
        Workflow.sleep(Duration.ofHours(1));
        fail("unreachable");
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(
                () -> assertEquals("a12", testActivities.activity2("a1", 2)))
            .run();
      }
      try {
        Workflow.newTimer(Duration.ofHours(1)).get();
        fail("unreachable");
      } catch (CanceledFailure e) {
        Workflow.newDetachedCancellationScope(
                () -> assertEquals("a123", testActivities.activity3("a1", 2, 3)))
            .run();
      }
      return "result";
    }
  }
}
