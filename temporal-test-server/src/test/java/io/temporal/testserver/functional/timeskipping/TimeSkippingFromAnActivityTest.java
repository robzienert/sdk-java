package io.temporal.testserver.functional.timeskipping;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.TestServiceStubs;
import io.temporal.serviceclient.TestServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testserver.TestServer;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import org.junit.*;

public class TimeSkippingFromAnActivityTest {
  private static final String TASK_QUEUE = "task-queue";

  public static class SleepingActivityImpl implements SleepingActivity {

    @Override
    public void sleep() {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static class TestWorkflowImpl implements TestWorkflows.PrimitiveWorkflow {
    @Override
    public void execute() {
      SleepingActivity activity =
          Workflow.newActivityStub(
              SleepingActivity.class, SDKTestOptions.newActivityOptionsForTaskQueue(TASK_QUEUE));
      Async.procedure(activity::sleep);
    }
  }

  private TestServer.InProcessTestServer server;
  private WorkflowServiceStubs workflowServiceStubs;
  private TestServiceStubs testServiceStubs;
  private WorkflowClient workflowClient;
  private WorkerFactory wf;

  @Before
  public void setUp() {
    this.server = TestServer.createServer(false, 0);
    this.workflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setChannel(server.getChannel()).build());
    this.testServiceStubs =
        TestServiceStubs.newServiceStubs(
            TestServiceStubsOptions.newBuilder()
                .setChannel(workflowServiceStubs.getRawChannel())
                .validateAndBuildWithDefaults());

    this.workflowClient = WorkflowClient.newInstance(workflowServiceStubs);
    this.wf = WorkerFactory.newInstance(workflowClient);
    Worker worker = wf.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker.registerActivitiesImplementations(new SleepingActivityImpl());
    this.wf.start();
  }

  @After
  public void tearDown() {
    this.wf.shutdownNow();
    this.testServiceStubs.shutdownNow();
    this.workflowServiceStubs.shutdownNow();
    this.server.close();
  }

  @Test
  public void testAbandonActivity() {
    WorkflowClient.newInstance(workflowServiceStubs)
        .newWorkflowStub(
            TestWorkflows.PrimitiveWorkflow.class,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(TASK_QUEUE))
        .execute();
    // time skipping is locked here. Workflow is done, but the activity is not
  }
}
