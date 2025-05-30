package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityWorkflowTimeUpdateTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  private static final long INITIAL_TEST_SERVER_TIME_MILLIS = 1000;
  private static final long ACTIVITY_SLEEPING_TIME = 1000;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setInitialTimeMillis(INITIAL_TEST_SERVER_TIME_MILLIS)
          .setWorkflowTypes(LocalActivityWorkflowTimeUpdateWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testLocalActivityWorkflowTimeUpdate() {
    long serverTimestampOnStart =
        testWorkflowRule.isUseExternalService()
            ? System.currentTimeMillis()
            : INITIAL_TEST_SERVER_TIME_MILLIS;
    TestWorkflow workflowStub = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    long timestampDuringExecution = workflowStub.execute();
    // Test with the test server that uses completely artificial timestamp is a better test.
    // Test with the real server can't really test this part properly without simulating different
    // times set on the worker and server hosts.
    assertTrue(
        "An actual value: " + timestampDuringExecution,
        timestampDuringExecution >= serverTimestampOnStart + ACTIVITY_SLEEPING_TIME
            && timestampDuringExecution < serverTimestampOnStart + TimeUnit.SECONDS.toMillis(10));

    testWorkflowRule.invalidateWorkflowCache();
    long timestampDuringTheReplay = workflowStub.query();

    assertEquals(
        "Replay should preserve the timestamps",
        timestampDuringExecution,
        timestampDuringTheReplay);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @QueryMethod
    long query();

    @WorkflowMethod
    long execute();
  }

  public static class LocalActivityWorkflowTimeUpdateWorkflowImpl implements TestWorkflow {
    private long workflowTaskTimeMs;

    @Override
    public long query() {
      return workflowTaskTimeMs;
    }

    @Override
    public long execute() {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());
      localActivities.sleepActivity(ACTIVITY_SLEEPING_TIME, 1);
      workflowTaskTimeMs = Workflow.currentTimeMillis();
      return workflowTaskTimeMs;
    }
  }
}
