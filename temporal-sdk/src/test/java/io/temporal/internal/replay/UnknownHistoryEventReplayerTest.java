package io.temporal.internal.replay;

import static io.temporal.testing.WorkflowHistoryLoader.readHistoryFromResource;

import io.temporal.activity.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.*;
import java.time.Duration;
import org.junit.*;
import org.junit.rules.Timeout;

public class UnknownHistoryEventReplayerTest {

  public static final String TASK_QUEUE = "unknown-history-event";
  public static final String RES_CLEAN = "testUnknownHistoryEventClean.json";
  public static final String RES_MAY_IGNORE = "testUnknownHistoryEventMayIgnore.json";
  public static final String RES_MAY_NOT_IGNORE = "testUnknownHistoryEventMayNotIgnore.json";

  @Rule public Timeout testTimeout = Timeout.seconds(10);

  private TestWorkflowEnvironment testEnvironment;
  private Worker worker;

  @Before
  public void setUp() {
    testEnvironment = TestWorkflowEnvironment.newInstance();
    worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(MyWorkflowImpl.class);
    worker.registerActivitiesImplementations(new MyActivityImpl());
    testEnvironment.start();
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void testRun() {
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("plain-run").build();
    MyWorkflow stub = client.newWorkflowStub(MyWorkflow.class, options);
    stub.execute();
    WorkflowExecutionHistory history = client.fetchHistory("plain-run");
    System.out.println(history.toJson(true));
  }

  @Test
  public void testClean() throws Exception {
    WorkflowExecutionHistory history = readHistoryFromResource(RES_CLEAN);
    worker.replayWorkflowExecution(history);
  }

  @Test
  public void testMayIgnore() throws Exception {
    WorkflowExecutionHistory history = readHistoryFromResource(RES_MAY_IGNORE);
    worker.replayWorkflowExecution(history);
  }

  @Test(expected = RuntimeException.class)
  public void testMayNotIgnore() throws Exception {
    WorkflowExecutionHistory history = readHistoryFromResource(RES_MAY_NOT_IGNORE);
    worker.replayWorkflowExecution(history);
  }

  @WorkflowInterface
  public interface MyWorkflow {

    @WorkflowMethod
    void execute();
  }

  @ActivityInterface
  public interface MyActivity {

    @ActivityMethod
    void execute();
  }

  public static class MyWorkflowImpl implements MyWorkflow {

    @Override
    public void execute() {
      MyActivity activity =
          Workflow.newLocalActivityStub(
              MyActivity.class,
              LocalActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.execute();
    }
  }

  public static class MyActivityImpl implements MyActivity {

    @Override
    public void execute() {}
  }
}
