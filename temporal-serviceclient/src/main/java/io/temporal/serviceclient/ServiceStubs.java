package io.temporal.serviceclient;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public interface ServiceStubs<B, F> {
  /**
   * @return Blocking (synchronous) stub that allows direct calls to service.
   */
  B blockingStub();

  /**
   * @return Future (asynchronous) stub that allows direct calls to service.
   */
  F futureStub();

  /**
   * @return the gRPC channel user by the stubs. This channel may be created internally by the stub
   *     or passed to it outside in the Options. This is a "raw" gRPC {@link ManagedChannel}, not an
   *     intercepted channel.
   */
  ManagedChannel getRawChannel();

  void shutdown();

  void shutdownNow();

  boolean isShutdown();

  boolean isTerminated();

  /**
   * Awaits for gRPC stubs shutdown up to the specified timeout. The shutdown has to be initiated
   * through {@link #shutdown()} or {@link #shutdownNow()}.
   *
   * <p>If waiting thread is interrupted, returns false and sets {@link Thread#interrupted()} flag
   *
   * @return false if timed out or the thread was interrupted.
   */
  boolean awaitTermination(long timeout, TimeUnit unit);

  /**
   * Establishes a connection with Temporal Server. If the Server is not available, retries waits
   * for {@code timeout} duration.
   *
   * @param timeout how long to wait for a successful connection with the server. If null,
   *     rpcTimeout configured for this stub will be used.
   * @throws StatusRuntimeException if the server is unavailable after {@code timeout}
   * @throws IllegalStateException if the channel is already shutdown
   */
  void connect(@Nullable Duration timeout);

  /**
   * Checks service health using gRPC standard Health Check:
   * https://github.com/grpc/grpc/blob/master/doc/health-checking.md
   *
   * <p>{@link ServiceStubsOptions#rpcTimeout} is used as a timeout for this call.
   *
   * <p>Please note that this method throws if the service Health Check endpoint can't be reached.
   *
   * @throws StatusRuntimeException if the service Health Check endpoint is unavailable.
   * @return gRPC Health {@link HealthCheckResponse}
   */
  HealthCheckResponse healthCheck();

  /**
   * Note: This method is needed mostly for internal SDK purposes only to adjust behavior for
   * different server versions.
   *
   * @return Supplier of Server Capabilities. This method returns a supplier instead of the instance
   *     to allow for lazy initialization of the capabilities while eager wiring of the components.
   *     This also leaves the flexibility to implement dynamic reloading in the future.
   */
  Supplier<GetSystemInfoResponse.Capabilities> getServerCapabilities();
}
