
package io.temporal.serviceclient

import io.temporal.kotlin.TemporalDsl
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/**
 * Create gRPC connection stubs using default options.
 *
 * @see WorkflowServiceStubs.newInstance
 */
@Deprecated("Use LocalWorkflowServiceStubs()", replaceWith = ReplaceWith("LocalWorkflowServiceStubs()"))
fun WorkflowServiceStubs(): WorkflowServiceStubs {
  @Suppress("DEPRECATION")
  return WorkflowServiceStubs.newInstance()
}

/**
 * Create gRPC connection stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newInstance
 */
@Deprecated("Use LazyWorkflowServiceStubs(options) or ConnectedWorkflowServiceStubs(options)")
inline fun WorkflowServiceStubs(
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  @Suppress("DEPRECATION")
  return WorkflowServiceStubs.newInstance(WorkflowServiceStubsOptions(options))
}

/**
 * Create WorkflowService gRPC stubs pointed on to the locally running Temporal Server.
 *
 * @see WorkflowServiceStubs.newLocalServiceStubs
 */
fun LocalWorkflowServiceStubs(): WorkflowServiceStubs {
  return WorkflowServiceStubs.newLocalServiceStubs()
}

/**
 * Create WorkflowService gRPC stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newServiceStubs
 */
inline fun LazyWorkflowServiceStubs(
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions(options))
}

/**
 * Create WorkflowService gRPC stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newConnectedServiceStubs
 */
@ExperimentalTime
@Deprecated(
  "Use ConnectedWorkflowServiceStubs(timeout, options)",
  ReplaceWith("ConnectedWorkflowServiceStubs(timeout, options)")
)
inline fun ConnectedWorkflowServiceStubs(
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit,
  timeout: Duration
): WorkflowServiceStubs {
  return ConnectedWorkflowServiceStubs(timeout, options)
}

/**
 * Create WorkflowService gRPC stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newConnectedServiceStubs
 */
@ExperimentalTime
inline fun ConnectedWorkflowServiceStubs(
  timeout: Duration,
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  return ConnectedWorkflowServiceStubs(timeout.toJavaDuration(), options)
}

/**
 * Create WorkflowService gRPC stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newConnectedServiceStubs
 */
inline fun ConnectedWorkflowServiceStubs(
  timeout: JavaDuration? = null,
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  return WorkflowServiceStubs.newConnectedServiceStubs(WorkflowServiceStubsOptions(options), timeout)
}
