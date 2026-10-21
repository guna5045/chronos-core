package com.chronoscore.coordinator.cluster.grpc;

import com.chronoscore.common.proto.ProtoTaskAssignment;
import com.chronoscore.common.proto.ProtoTaskCancelRequest;
import com.chronoscore.common.proto.ProtoTaskCancelResponse;
import com.chronoscore.common.proto.ProtoTaskStateUpdate;
import com.chronoscore.coordinator.cluster.model.WorkerNode;

import java.time.Duration;

/**
 * Client abstraction used by the Coordinator to dispatch tasks and cancellations to worker nodes.
 */
public interface WorkerDispatchClient {

    /**
     * Dispatches a task assignment to the designated worker node via gRPC.
     *
     * @param targetWorker designated target worker node
     * @param assignment protobuf task assignment payload
     * @param deadline maximum timeout duration for the unary dispatch call
     * @return initial state update returned by the worker (e.g. RUNNING or QUEUED)
     */
    ProtoTaskStateUpdate dispatchTask(WorkerNode targetWorker, ProtoTaskAssignment assignment, Duration deadline);

    /**
     * Sends an explicit task cancellation signal to the designated worker node.
     *
     * @param targetWorker designated target worker node
     * @param request cancellation request payload
     * @param deadline maximum timeout duration for the unary cancellation call
     * @return cancellation response returned by the worker
     */
    ProtoTaskCancelResponse cancelTask(WorkerNode targetWorker, ProtoTaskCancelRequest request, Duration deadline);
}
