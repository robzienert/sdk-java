package io.temporal.workflow.updateTest;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.UpdateInfo;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.WorkflowWithUpdate;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UpdateInfoTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestUpdateWorkflowImpl.class).build();

  @Test
  public void testUpdateInfo() throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithUpdate workflow = workflowClient.newWorkflowStub(WorkflowWithUpdate.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    UpdateOptions.Builder<String> updateOptionsBuilder =
        UpdateOptions.newBuilder(String.class)
            .setUpdateName("update")
            .setWaitForStage(WorkflowUpdateStage.COMPLETED);

    WorkflowUpdateHandle handle1 =
        stub.startUpdate(updateOptionsBuilder.setUpdateId("update id 1").build(), 0, "");
    assertEquals("update:update id 1", handle1.getResultAsync().get());

    WorkflowUpdateHandle handle2 =
        stub.startUpdate(updateOptionsBuilder.setUpdateId("update id 2").build(), 0, "");
    assertEquals("update:update id 2", handle2.getResultAsync().get());

    Assert.assertThrows(
        WorkflowUpdateException.class,
        () ->
            stub.startUpdate(updateOptionsBuilder.setUpdateId("reject").build(), 0, "")
                .getResult());
    workflow.complete();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals(" update id 1 update id 2", result);
  }

  public static class TestUpdateWorkflowImpl implements WorkflowWithUpdate {
    String state = "initial";
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      if (Workflow.getCurrentUpdateInfo().isPresent()) {
        throw ApplicationFailure.newFailure("update info should not be present", "TestFailure");
      }
      promise.get();
      return updates.stream().reduce("", (a, b) -> a + " " + b);
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public String update(Integer index, String value) {
      UpdateInfo updateInfo = Workflow.getCurrentUpdateInfo().get();
      updates.add(updateInfo.getUpdateId());
      return updateInfo.getUpdateName() + ":" + updateInfo.getUpdateId();
    }

    @Override
    public void updateValidator(Integer index, String value) {
      UpdateInfo updateInfo = Workflow.getCurrentUpdateInfo().get();
      if (updateInfo.getUpdateId().equals("reject")) {
        throw new RuntimeException("Rejecting update");
      }
    }

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {}
  }
}
