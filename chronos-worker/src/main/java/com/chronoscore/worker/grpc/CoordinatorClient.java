package com.chronoscore.worker.grpc;

import com.chronoscore.common.proto.ProtoHeartbeatPing;
import com.chronoscore.common.proto.ProtoHeartbeatPong;
import com.chronoscore.common.proto.ProtoTaskResult;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.common.proto.ProtoWorkerIdentity;
import io.grpc.stub.StreamObserver;

import java.time.Duration;

/**
 * gRPC client interface for Worker-to-Coordinator communication.
 */
public interface CoordinatorClient extends AutoCloseable {

    /**
     * Registers this worker with the coordinator.
     */
    ProtoHeartbeatPong registerWorker(ProtoWorkerIdentity identity, Duration timeout);

    /**
     * Establishes a bidirectional heartbeat stream with the coordinator.
     */
    StreamObserver<ProtoHeartbeatPing> streamHeartbeat(StreamObserver<ProtoHeartbeatPong> responseObserver);

    /**
     * Reports an attempt's execution result back to the coordinator.
     */
    ProtoHeartbeatPong reportTaskResult(ProtoTaskResult result, Duration timeout);

    /**
     * Sends an intermediate task state update to the coordinator.
     */
    ProtoHeartbeatPong updateTaskState(ProtoTaskStateUpdate stateUpdate, Duration timeout);

    @Override
    void close();
}
