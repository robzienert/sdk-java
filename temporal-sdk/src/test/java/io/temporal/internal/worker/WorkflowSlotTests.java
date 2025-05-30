package io.temporal.internal.worker;

import static org.junit.Assert.assertEquals;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.testUtils.CountingSlotSupplier;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowSlotTests {
  private final int MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 100;
  private final int MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 1000;
  private final int MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 10000;
  private final int MAX_CONCURRENT_NEXUS_EXECUTION_SIZE = 2000;
  private final CountingSlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE);
  private final CountingSlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE);
  private final CountingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
  private final CountingSlotSupplier<NexusSlotInfo> nexusSlotSupplier =
      new CountingSlotSupplier<>(MAX_CONCURRENT_NEXUS_EXECUTION_SIZE);
  private final TestStatsReporter reporter = new TestStatsReporter();
  static CountDownLatch blockLatch = new CountDownLatch(1);
  static CountDownLatch runningLatch = new CountDownLatch(1);
  static boolean didFail = false;

  Scope metricsScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(1));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setWorkerTuner(
                      new CompositeTuner(
                          workflowTaskSlotSupplier,
                          activityTaskSlotSupplier,
                          localActivitySlotSupplier,
                          nexusSlotSupplier))
                  .build())
          .setMetricsScope(metricsScope)
          .setActivityImplementations(new TestActivityImpl())
          .setWorkflowTypes(SleepingWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setDoNotStart(true)
          .build();

  @Before
  public void setup() {
    reporter.flush();
    blockLatch = new CountDownLatch(1);
    runningLatch = new CountDownLatch(1);
    localActivitySlotSupplier.usedCount.set(0);
    didFail = false;
  }

  @After
  public void tearDown() {
    testWorkflowRule.getTestEnvironment().close();
    assertEquals(
        workflowTaskSlotSupplier.reservedCount.get(), workflowTaskSlotSupplier.releasedCount.get());
    assertEquals(
        activityTaskSlotSupplier.reservedCount.get(), activityTaskSlotSupplier.releasedCount.get());
    assertEquals(
        localActivitySlotSupplier.reservedCount.get(),
        localActivitySlotSupplier.releasedCount.get());
    assertEquals(nexusSlotSupplier.reservedCount.get(), nexusSlotSupplier.releasedCount.get());
  }

  // Arguments are the number of used slots by type
  private void assertWorkerSlotCount(int worker, int activity, int localActivity, int nexus) {
    try {
      // There can be a delay in metrics emission, another option if this
      // is too flaky is to poll the metrics.
      Thread.sleep(100);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        getWorkerTags("WorkflowWorker"),
        MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE - worker);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        getWorkerTags("ActivityWorker"),
        MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE - activity);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE,
        getWorkerTags("LocalActivityWorker"),
        MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE - localActivity);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("WorkflowWorker"), worker);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("ActivityWorker"), activity);
    reporter.assertGauge(
        MetricsType.WORKER_TASK_SLOTS_USED, getWorkerTags("LocalActivityWorker"), localActivity);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String action);

    @SignalMethod
    void unblock();
  }

  public static class SleepingWorkflowImpl implements TestWorkflow {
    boolean unblocked = false;

    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(4).build())
                .validateAndBuildWithDefaults());

    private final TestActivity localActivity =
        Workflow.newLocalActivityStub(
            TestActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(4)
                        .setInitialInterval(Duration.ofMillis(1))
                        .build())
                .validateAndBuildWithDefaults());

    private final TestNexusServices.TestNexusService1 nexusService =
        Workflow.newNexusServiceStub(
            TestNexusServices.TestNexusService1.class,
            NexusServiceOptions.newBuilder()
                .setOperationOptions(
                    NexusOperationOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                        .build())
                .build());

    @Override
    public String workflow(String action) {
      Workflow.await(() -> unblocked);
      if (action.equals("fail") && !didFail) {
        didFail = true;
        throw new RuntimeException("fail on purpose");
      } else if (action.equals("local-activity")) {
        localActivity.activity("test");
      } else if (action.equals("local-activity-fail")) {
        localActivity.activity("fail");
      } else if (action.equals("activity")) {
        activity.activity("test");
      } else if (action.equals("nexus")) {
        nexusService.operation("test");
      }
      return "ok";
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    String activity(String input);
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      runningLatch.countDown();
      try {
        ActivityExecutionContext executionContext = Activity.getExecutionContext();
        if (input.equals("fail") && executionContext.getInfo().getAttempt() < 4) {
          throw new RuntimeException("fail on purpose");
        }
        blockLatch.await();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      return "";
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (ctx, details, input) -> {
            runningLatch.countDown();
            try {
              blockLatch.await();
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            return "";
          });
    }
  }

  private Map<String, String> getWorkerTags(String workerType) {
    return ImmutableMap.of(
        "worker_type",
        workerType,
        "task_queue",
        testWorkflowRule.getTaskQueue(),
        "namespace",
        "UnitTest");
  }

  @Test
  public void TestTaskSlotsEmittedOnStart() {
    // Verify that no metrics are emitted before the worker is started
    reporter.assertNoMetric(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE, getWorkerTags("WorkflowWorker"));
    reporter.assertNoMetric(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE, getWorkerTags("ActivityWorker"));
    reporter.assertNoMetric(
        MetricsType.WORKER_TASK_SLOTS_AVAILABLE, getWorkerTags("LocalActivityWorker"));
    // Start the worker
    testWorkflowRule.getTestEnvironment().start();
    // All slots should be available
    assertWorkerSlotCount(0, 0, 0, 0);
  }

  @Test
  public void TestActivityTaskSlots() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "activity");
    workflow.unblock();
    runningLatch.await();
    // The activity slot should be taken and the workflow slot should not be taken
    assertWorkerSlotCount(0, 1, 0, 0);

    blockLatch.countDown();
    // Wait for the workflow to finish
    workflow.workflow("activity");
    // All slots should be available
    assertWorkerSlotCount(0, 0, 0, 0);
  }

  @Test
  public void TestNexusTaskSlots() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "nexus");
    workflow.unblock();
    runningLatch.await();
    // The nexus slot should be taken and the workflow slot should not be taken
    assertWorkerSlotCount(0, 0, 0, 1);

    blockLatch.countDown();
    // Wait for the workflow to finish
    workflow.workflow("nexus");
    // All slots should be available
    assertWorkerSlotCount(0, 0, 0, 0);
  }

  @Test
  public void TestLocalActivityTaskSlots() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "local-activity");
    workflow.unblock();
    runningLatch.await();
    // The local activity slot should be taken and the workflow slot should be taken
    assertWorkerSlotCount(1, 0, 1, 0);

    blockLatch.countDown();
    workflow.workflow("local-activity");
    // All slots should be available
    assertWorkerSlotCount(0, 0, 0, 0);
  }

  @Test
  public void TestLocalActivityHeartbeat() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowTaskTimeout(Duration.ofSeconds(1))
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "local-activity");
    workflow.unblock();
    runningLatch.await();
    // The local activity slot should be taken and the workflow slot should be taken
    assertWorkerSlotCount(1, 0, 1, 0);
    // Take long enough to heartbeat
    Thread.sleep(1000);
    assertWorkerSlotCount(1, 0, 1, 0);

    blockLatch.countDown();
    workflow.workflow("local-activity");
    // All slots should be available
    assertWorkerSlotCount(0, 0, 0, 0);
  }

  @Test
  public void TestLocalActivityFailsThenPasses() throws InterruptedException {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowTaskTimeout(Duration.ofSeconds(1))
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "local-activity-fail");
    workflow.unblock();
    runningLatch.await();
    // The local activity slot should be taken and the workflow slot should be taken
    assertWorkerSlotCount(1, 0, 1, 0);

    blockLatch.countDown();
    workflow.workflow("local-activity-fail");
    assertWorkerSlotCount(0, 0, 0, 0);
    // LA slots should only have been used once per attempt
    assertEquals(4, localActivitySlotSupplier.usedCount.get());
    // We should have seen releases *per* attempt as well
    assertEquals(4, localActivitySlotSupplier.releasedCount.get());
  }

  @Test
  public void TestWFTFailure() {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowTaskTimeout(Duration.ofMillis(500))
                .validateBuildWithDefaults());
    WorkflowClient.start(workflow::workflow, "fail");
    workflow.unblock();
    workflow.workflow("fail");
    // All slots should be available
    assertWorkerSlotCount(0, 0, 0, 0);
  }
}
