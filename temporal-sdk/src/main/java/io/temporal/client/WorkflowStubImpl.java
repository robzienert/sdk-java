package io.temporal.client;

import com.google.common.base.Strings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.errordetails.v1.QueryFailedFailure;
import io.temporal.api.errordetails.v1.WorkflowExecutionAlreadyStartedFailure;
import io.temporal.api.errordetails.v1.WorkflowNotReadyFailure;
import io.temporal.api.update.v1.WaitPolicy;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.client.LazyWorkflowUpdateHandleImpl;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.serviceclient.StatusUtils;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class WorkflowStubImpl implements WorkflowStub {
  private final WorkflowClientOptions clientOptions;
  private final WorkflowClientCallsInterceptor workflowClientInvoker;
  private final Optional<String> workflowType;
  // Execution this stub is bound to
  private final AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
  // Full WorkflowExecution that this stub is started if any.
  // After a start, WorkflowStub binds to (workflowId, null) to follow the chain of RunIds.
  // But this field keeps the full (workflowId, runId) execution info that was started by this stub.
  private final AtomicReference<WorkflowExecution> startedExecution = new AtomicReference<>();
  // if null, this stub is created to bound to an existing execution.
  // This stub is created to bound to an existing execution otherwise.
  private final @Nullable WorkflowOptions options;

  WorkflowStubImpl(
      WorkflowClientOptions clientOptions,
      WorkflowClientCallsInterceptor workflowClientInvoker,
      Optional<String> workflowType,
      WorkflowExecution execution) {
    this.clientOptions = clientOptions;
    this.workflowClientInvoker = workflowClientInvoker;
    this.workflowType = workflowType;
    if (execution == null || execution.getWorkflowId().isEmpty()) {
      throw new IllegalArgumentException("null or empty workflowId");
    }
    this.execution.set(execution);
    this.options = null;
  }

  WorkflowStubImpl(
      WorkflowClientOptions clientOptions,
      WorkflowClientCallsInterceptor workflowClientInvoker,
      String workflowType,
      @Nonnull WorkflowOptions options) {
    this.clientOptions = clientOptions;
    this.workflowClientInvoker = workflowClientInvoker;
    this.workflowType = Optional.of(workflowType);
    this.options = options;
  }

  @Override
  public void signal(String signalName, Object... args) {
    checkStarted();
    WorkflowExecution targetExecution = currentExecutionWithoutRunId();
    try {
      workflowClientInvoker.signal(
          new WorkflowClientCallsInterceptor.WorkflowSignalInput(
              targetExecution, signalName, Header.empty(), args));
    } catch (Exception e) {
      Throwable throwable = throwAsWorkflowFailureException(e, targetExecution);
      throw new WorkflowServiceException(targetExecution, workflowType.orElse(null), throwable);
    }
  }

  private WorkflowExecution startWithOptions(WorkflowOptions options, Object... args) {
    checkExecutionIsNotStarted();
    String workflowId = getWorkflowIdForStart(options);
    WorkflowExecution workflowExecution = null;
    try {
      WorkflowClientCallsInterceptor.WorkflowStartOutput workflowStartOutput =
          workflowClientInvoker.start(
              new WorkflowClientCallsInterceptor.WorkflowStartInput(
                  workflowId, workflowType.get(), Header.empty(), args, options));
      workflowExecution = workflowStartOutput.getWorkflowExecution();
      populateExecutionAfterStart(workflowExecution);
      return workflowExecution;
    } catch (StatusRuntimeException e) {
      throw wrapStartException(workflowId, workflowType.orElse(null), e);
    } catch (Exception e) {
      if (workflowExecution == null) {
        // if start failed with exception - there could be no valid workflow execution populated
        // from the server.
        // WorkflowServiceException requires not null workflowExecution, so we have to provide
        // an WorkflowExecution instance with just a workflowId
        workflowExecution = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
      }
      throw new WorkflowServiceException(workflowExecution, workflowType.orElse(null), e);
    }
  }

  @Override
  public WorkflowExecution start(Object... args) {
    if (options == null) {
      throw new IllegalStateException("Required parameter WorkflowOptions is missing");
    }
    return startWithOptions(WorkflowOptions.merge(null, null, options), args);
  }

  @Override
  public <R> WorkflowUpdateHandle<R> startUpdateWithStart(
      UpdateOptions<R> updateOptions, Object[] updateArgs, Object[] startArgs) {
    if (options == null) {
      throw new IllegalStateException(
          "Required parameter WorkflowOptions is missing in WorkflowStub");
    }
    if (options.getWorkflowIdConflictPolicy() == null) {
      throw new IllegalStateException(
          "WorkflowIdConflictPolicy is required in WorkflowOptions for Update-With-Start");
    }
    updateOptions.validate();

    String workflowId = getWorkflowIdForStart(options);
    WorkflowExecution workflowExecution = null;
    try {
      // gather inputs
      WorkflowClientCallsInterceptor.WorkflowStartInput startInput =
          new WorkflowClientCallsInterceptor.WorkflowStartInput(
              workflowId, workflowType.get(), Header.empty(), startArgs, options);
      WorkflowClientCallsInterceptor.StartUpdateInput<R> updateInput =
          startUpdateInput(
              updateOptions,
              updateArgs,
              WorkflowExecution.newBuilder().setWorkflowId(workflowId).build());
      WorkflowClientCallsInterceptor.WorkflowUpdateWithStartInput<R> input =
          new WorkflowClientCallsInterceptor.WorkflowUpdateWithStartInput<>(
              startInput, updateInput);

      WorkflowClientCallsInterceptor.WorkflowUpdateWithStartOutput<R> output =
          workflowClientInvoker.updateWithStart(input);

      // gather outputs
      workflowExecution = output.getWorkflowStartOutput().getWorkflowExecution();
      populateExecutionAfterStart(workflowExecution);
      return output.getUpdateHandle();
    } catch (StatusRuntimeException e) {
      throw wrapStartException(workflowId, workflowType.orElse(null), e);
    } catch (Exception e) {
      if (workflowExecution == null) {
        // If start failed with exception there could be no valid workflow execution populated
        // from the server. WorkflowServiceException requires not null WorkflowExecution, so we have
        // to provide a WorkflowExecution instance with just a workflowId.
        workflowExecution = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
      }
      throw new WorkflowServiceException(workflowExecution, workflowType.orElse(null), e);
    }
  }

  @Override
  public <R> R executeUpdateWithStart(
      UpdateOptions<R> updateOptions, Object[] updateArgs, Object[] startArgs) {
    updateOptions.validateWaitForCompleted();
    UpdateOptions<R> optionsWithWaitStageCompleted =
        updateOptions.toBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build();
    return startUpdateWithStart(optionsWithWaitStageCompleted, updateArgs, startArgs).getResult();
  }

  private WorkflowExecution signalWithStartWithOptions(
      WorkflowOptions options, String signalName, Object[] signalArgs, Object[] startArgs) {
    checkExecutionIsNotStarted();
    String workflowId = getWorkflowIdForStart(options);
    WorkflowExecution workflowExecution = null;
    try {
      WorkflowClientCallsInterceptor.WorkflowSignalWithStartOutput workflowStartOutput =
          workflowClientInvoker.signalWithStart(
              new WorkflowClientCallsInterceptor.WorkflowSignalWithStartInput(
                  new WorkflowClientCallsInterceptor.WorkflowStartInput(
                      workflowId, workflowType.get(), Header.empty(), startArgs, options),
                  signalName,
                  signalArgs));
      workflowExecution = workflowStartOutput.getWorkflowStartOutput().getWorkflowExecution();
      populateExecutionAfterStart(workflowExecution);
      return workflowExecution;
    } catch (StatusRuntimeException e) {
      throw wrapStartException(workflowId, workflowType.orElse(null), e);
    } catch (Exception e) {
      if (workflowExecution == null) {
        // if start failed with exception - there could be no valid workflow execution populated
        // from the server.
        // WorkflowServiceException requires not null workflowExecution, so we have to provide
        // an WorkflowExecution instance with just a workflowId
        workflowExecution = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
      }
      throw new WorkflowServiceException(workflowExecution, workflowType.orElse(null), e);
    }
  }

  private static String getWorkflowIdForStart(WorkflowOptions options) {
    String workflowId = options.getWorkflowId();
    if (workflowId == null) {
      workflowId = UUID.randomUUID().toString();
    }
    return workflowId;
  }

  @Override
  public WorkflowExecution signalWithStart(
      String signalName, Object[] signalArgs, Object[] startArgs) {
    if (options == null) {
      throw new IllegalStateException("Required parameter WorkflowOptions is missing");
    }
    return signalWithStartWithOptions(
        WorkflowOptions.merge(null, null, options), signalName, signalArgs, startArgs);
  }

  @Override
  public Optional<String> getWorkflowType() {
    return workflowType;
  }

  @Override
  public WorkflowExecution getExecution() {
    return options != null ? startedExecution.get() : execution.get();
  }

  @Override
  public <R> R getResult(Class<R> resultClass) {
    return getResult(resultClass, resultClass);
  }

  @Override
  public <R> R getResult(Class<R> resultClass, Type resultType) {
    try {
      // int max to not overflow long
      return getResult(Integer.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
    } catch (TimeoutException e) {
      throw new WorkflowServiceException(execution.get(), workflowType.orElse(null), e);
    }
  }

  @Override
  public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass)
      throws TimeoutException {
    return getResult(timeout, unit, resultClass, resultClass);
  }

  @Override
  public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass, Type resultType)
      throws TimeoutException {
    checkStarted();
    WorkflowExecution targetExecution = execution.get();
    try {
      WorkflowClientCallsInterceptor.GetResultOutput<R> result =
          workflowClientInvoker.getResult(
              new WorkflowClientCallsInterceptor.GetResultInput<>(
                  targetExecution, workflowType, timeout, unit, resultClass, resultType));
      return result.getResult();
    } catch (Exception e) {
      return throwAsWorkflowFailureExceptionForResult(e, resultClass, targetExecution);
    }
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
    return getResultAsync(resultClass, resultClass);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, Type resultType) {
    return getResultAsync(Long.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass) {
    return getResultAsync(timeout, unit, resultClass, resultClass);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, Type resultType) {
    checkStarted();
    WorkflowExecution targetExecution = execution.get();
    WorkflowClientCallsInterceptor.GetResultAsyncOutput<R> result =
        workflowClientInvoker.getResultAsync(
            new WorkflowClientCallsInterceptor.GetResultInput<>(
                targetExecution, workflowType, timeout, unit, resultClass, resultType));
    return result
        .getResult()
        .exceptionally(
            e -> {
              try {
                return throwAsWorkflowFailureExceptionForResult(e, resultClass, targetExecution);
              } catch (TimeoutException ex) {
                throw new CompletionException(ex);
              }
            });
  }

  @Override
  public <R> R query(String queryType, Class<R> resultClass, Object... args) {
    return query(queryType, resultClass, resultClass, args);
  }

  @Override
  public <R> R query(String queryType, Class<R> resultClass, Type resultType, Object... args) {
    checkStarted();
    WorkflowClientCallsInterceptor.QueryOutput<R> result;
    WorkflowExecution targetExecution = execution.get();
    try {
      result =
          workflowClientInvoker.query(
              new WorkflowClientCallsInterceptor.QueryInput<>(
                  targetExecution, queryType, Header.empty(), args, resultClass, resultType));
    } catch (Exception e) {
      return throwAsWorkflowFailureExceptionForQuery(e, resultClass, targetExecution);
    }
    if (result.isQueryRejected()) {
      throw new WorkflowQueryConditionallyRejectedException(
          targetExecution,
          workflowType.orElse(null),
          clientOptions.getQueryRejectCondition(),
          result.getQueryRejectedStatus(),
          null);
    }
    return result.getResult();
  }

  @Override
  public <R> R update(String updateName, Class<R> resultClass, Object... args) {
    checkStarted();
    try {
      UpdateOptions<R> options =
          UpdateOptions.<R>newBuilder()
              .setUpdateName(updateName)
              .setWaitForStage(WorkflowUpdateStage.COMPLETED)
              .setResultClass(resultClass)
              .build();
      return startUpdate(options, args).getResultAsync().get();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw (cause instanceof RuntimeException
          ? (RuntimeException) cause
          : new RuntimeException(cause));
    }
  }

  @Override
  public <R> WorkflowUpdateHandle<R> startUpdate(
      String updateName, WorkflowUpdateStage waitForStage, Class<R> resultClass, Object... args) {
    UpdateOptions<R> options =
        UpdateOptions.<R>newBuilder()
            .setUpdateName(updateName)
            .setWaitForStage(waitForStage)
            .setResultClass(resultClass)
            .setResultType(resultClass)
            .build();

    return startUpdate(options, args);
  }

  @Override
  public <R> WorkflowUpdateHandle<R> startUpdate(UpdateOptions<R> options, Object... args) {
    checkStarted();
    options.validate();
    WorkflowExecution targetExecution = execution.get();
    try {
      WorkflowClientCallsInterceptor.StartUpdateInput<R> input =
          startUpdateInput(options, args, targetExecution);
      return workflowClientInvoker.startUpdate(input);
    } catch (Exception e) {
      Throwable throwable = throwAsWorkflowFailureException(e, targetExecution);
      throw new WorkflowServiceException(targetExecution, workflowType.orElse(null), throwable);
    }
  }

  private <R> WorkflowClientCallsInterceptor.StartUpdateInput<R> startUpdateInput(
      UpdateOptions<R> options, Object[] args, WorkflowExecution targetExecution) {
    String updateId =
        Strings.isNullOrEmpty(options.getUpdateId())
            ? UUID.randomUUID().toString()
            : options.getUpdateId();
    WorkflowClientCallsInterceptor.StartUpdateInput<R> input =
        new WorkflowClientCallsInterceptor.StartUpdateInput<>(
            targetExecution,
            workflowType,
            options.getUpdateName(),
            Header.empty(),
            updateId,
            args,
            options.getResultClass(),
            options.getResultType(),
            options.getFirstExecutionRunId(),
            WaitPolicy.newBuilder()
                .setLifecycleStage(options.getWaitForStage().getProto())
                .build());
    return input;
  }

  @Override
  public <R> WorkflowUpdateHandle<R> getUpdateHandle(String updateId, Class<R> resultClass) {
    return new LazyWorkflowUpdateHandleImpl<>(
        workflowClientInvoker,
        workflowType.orElse(null),
        "",
        updateId,
        execution.get(),
        resultClass,
        resultClass);
  }

  @Override
  public <R> WorkflowUpdateHandle<R> getUpdateHandle(
      String updateId, Class<R> resultClass, Type resultType) {
    return new LazyWorkflowUpdateHandleImpl<>(
        workflowClientInvoker,
        workflowType.orElse(null),
        "",
        updateId,
        execution.get(),
        resultClass,
        resultType);
  }

  @Override
  public void cancel() {
    cancel(null);
  }

  @Override
  public void cancel(@Nullable String reason) {
    checkStarted();
    WorkflowExecution targetExecution = currentExecutionWithoutRunId();
    try {
      workflowClientInvoker.cancel(
          new WorkflowClientCallsInterceptor.CancelInput(targetExecution, reason));
    } catch (Exception e) {
      Throwable failure = throwAsWorkflowFailureException(e, targetExecution);
      throw new WorkflowServiceException(targetExecution, workflowType.orElse(null), failure);
    }
  }

  @Override
  public void terminate(@Nullable String reason, Object... details) {
    checkStarted();
    WorkflowExecution targetExecution = currentExecutionWithoutRunId();
    try {
      workflowClientInvoker.terminate(
          new WorkflowClientCallsInterceptor.TerminateInput(targetExecution, reason, details));
    } catch (Exception e) {
      Throwable failure = throwAsWorkflowFailureException(e, targetExecution);
      throw new WorkflowServiceException(targetExecution, workflowType.orElse(null), failure);
    }
  }

  @Override
  public WorkflowExecutionDescription describe() {
    checkStarted();
    WorkflowExecution targetExecution = execution.get();
    try {
      WorkflowClientCallsInterceptor.DescribeWorkflowOutput result =
          workflowClientInvoker.describe(
              new WorkflowClientCallsInterceptor.DescribeWorkflowInput(targetExecution));
      return result.getDescription();
    } catch (Exception e) {
      Throwable failure = throwAsWorkflowFailureException(e, targetExecution);
      throw new WorkflowServiceException(targetExecution, workflowType.orElse(null), failure);
    }
  }

  @Override
  public Optional<WorkflowOptions> getOptions() {
    return Optional.ofNullable(options);
  }

  @Override
  public WorkflowStub newInstance(WorkflowOptions options) {
    return new WorkflowStubImpl(
        clientOptions, workflowClientInvoker, workflowType.orElse(null), options);
  }

  private void checkStarted() {
    if (execution.get() == null) {
      throw new IllegalStateException("Null workflowId. Was workflow started?");
    }
  }

  private void checkExecutionIsNotStarted() {
    if (execution.get() != null) {
      throw new IllegalStateException(
          "Cannot reuse a stub instance to start more than one workflow execution. The stub "
              + "points to already started execution. If you are trying to wait for a workflow completion either "
              + "change WorkflowIdReusePolicy from AllowDuplicate or use WorkflowStub.getResult");
    }
  }

  /*
   * Exceptions handling and processing for all methods of the stub
   */
  private RuntimeException wrapStartException(
      String workflowId, String workflowType, StatusRuntimeException e) {
    WorkflowExecution.Builder executionBuilder =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId);

    WorkflowExecutionAlreadyStartedFailure f =
        StatusUtils.getFailure(e, WorkflowExecutionAlreadyStartedFailure.class);
    if (f != null) {
      WorkflowExecution exe = executionBuilder.setRunId(f.getRunId()).build();
      populateExecutionAfterStart(exe);
      return new WorkflowExecutionAlreadyStarted(exe, workflowType, e);
    } else {
      WorkflowExecution exe = executionBuilder.build();
      return new WorkflowServiceException(exe, workflowType, e);
    }
  }

  /**
   * RunId can change e.g. workflow does ContinueAsNew. Emptying runId in workflowExecution allows
   * Temporal server figure out the current run id dynamically.
   */
  private WorkflowExecution currentExecutionWithoutRunId() {
    WorkflowExecution workflowExecution = execution.get();
    if (Strings.isNullOrEmpty(workflowExecution.getRunId())) {
      return workflowExecution;
    } else {
      return WorkflowExecution.newBuilder(workflowExecution).setRunId("").build();
    }
  }

  private <R> R throwAsWorkflowFailureExceptionForQuery(
      Throwable failure,
      @SuppressWarnings("unused") Class<R> returnType,
      WorkflowExecution targetExecution) {
    failure = throwAsWorkflowFailureException(failure, targetExecution);
    if (failure instanceof StatusRuntimeException) {
      StatusRuntimeException sre = (StatusRuntimeException) failure;
      if (StatusUtils.hasFailure(sre, QueryFailedFailure.class)) {
        throw new WorkflowQueryException(execution.get(), workflowType.orElse(null), failure);
      } else if (Status.Code.FAILED_PRECONDITION.equals(sre.getStatus().getCode())
          && StatusUtils.hasFailure(sre, WorkflowNotReadyFailure.class)) {
        // Processes the edge case introduced by https://github.com/temporalio/temporal/pull/2826
        throw new WorkflowQueryRejectedException(
            targetExecution, workflowType.orElse(null), failure);
      }
    }
    throw new WorkflowServiceException(targetExecution, workflowType.orElse(null), failure);
  }

  // This function never returns anything, it only throws
  private <R> R throwAsWorkflowFailureExceptionForResult(
      Throwable failure,
      @SuppressWarnings("unused") Class<R> returnType,
      WorkflowExecution targetExecution)
      throws TimeoutException {
    failure = throwAsWorkflowFailureException(failure, targetExecution);
    if (failure instanceof TimeoutException) {
      throw (TimeoutException) failure;
    } else if (failure instanceof CanceledFailure) {
      throw (CanceledFailure) failure;
    }
    throw new WorkflowServiceException(targetExecution, workflowType.orElse(null), failure);
  }

  private Throwable throwAsWorkflowFailureException(
      Throwable failure, WorkflowExecution targetExecution) {
    if (failure instanceof CompletionException) {
      // if we work with CompletableFuture, the exception may be wrapped into CompletionException
      failure = failure.getCause();
    }
    failure = CheckedExceptionWrapper.unwrap(failure);
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure instanceof StatusRuntimeException) {
      StatusRuntimeException sre = (StatusRuntimeException) failure;
      if (Status.Code.NOT_FOUND.equals(sre.getStatus().getCode())) {
        throw new WorkflowNotFoundException(targetExecution, workflowType.orElse(null), sre);
      }
    } else if (failure instanceof WorkflowException) {
      throw (WorkflowException) failure;
    }
    return failure;
  }

  private void populateExecutionAfterStart(WorkflowExecution startedExecution) {
    this.startedExecution.set(startedExecution);
    // bind to an execution without a runId, so queries follow runId chains by default
    this.execution.set(WorkflowExecution.newBuilder(startedExecution).setRunId("").build());
  }
}
