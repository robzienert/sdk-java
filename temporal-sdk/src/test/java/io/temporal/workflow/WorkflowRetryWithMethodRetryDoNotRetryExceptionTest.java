package io.temporal.workflow;

import io.temporal.client.WorkflowException;
import io.temporal.common.MethodRetry;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowRetryWithMethodRetryDoNotRetryExceptionTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(
                      IllegalStateException.class, IllegalArgumentException.class)
                  .build(),
              TestWorkflowRetryWithMethodRetryImpl.class)
          .build();

  @Test
  public void testWorkflowRetryWithMethodRetryDoNotRetryException() {
    TestWorkflowRetryWithMethodRetry workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowRetryWithMethodRetry.class);
    try {
      workflowStub.execute(testName.getMethodName());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IllegalArgumentException.class.getName(), ((ApplicationFailure) e.getCause()).getType());
      Assert.assertEquals(
          "message='simulated 3', type='java.lang.IllegalArgumentException', nonRetryable=false",
          e.getCause().getMessage());
    }
  }

  @WorkflowInterface
  public interface TestWorkflowRetryWithMethodRetry {

    @WorkflowMethod
    @MethodRetry(
        initialIntervalSeconds = 1,
        maximumIntervalSeconds = 1,
        maximumAttempts = 30,
        doNotRetry = "java.lang.IllegalArgumentException")
    String execute(String testName);
  }

  public static class TestWorkflowRetryWithMethodRetryImpl
      implements TestWorkflowRetryWithMethodRetry {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int c = count.incrementAndGet();
      if (c < 3) {
        throw new IllegalStateException("simulated " + c);
      } else {
        throw new IllegalArgumentException("simulated " + c);
      }
    }
  }
}
