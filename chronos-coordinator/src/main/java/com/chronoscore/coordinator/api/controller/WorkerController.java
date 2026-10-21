package com.chronoscore.coordinator.api.controller;

import com.chronoscore.coordinator.api.dto.response.WorkerResponse;
import com.chronoscore.coordinator.cluster.model.WorkerNode;
import com.chronoscore.coordinator.cluster.model.WorkerState;
import com.chronoscore.coordinator.cluster.registry.WorkerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * Read-only REST controller exposing worker cluster membership, health, and capacity.
 */
@RestController
@RequestMapping("/api/v1/workers")
public class WorkerController {

    private final WorkerRegistry workerRegistry;

    public WorkerController(WorkerRegistry workerRegistry) {
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry must not be null");
    }

    @GetMapping
    public ResponseEntity<List<WorkerResponse>> listWorkers(@RequestParam(required = false) WorkerState state) {
        List<WorkerNode> nodes = (state != null)
                ? workerRegistry.getWorkersByState(state)
                : workerRegistry.getAllWorkers();

        List<WorkerResponse> responses = nodes.stream()
                .map(WorkerResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{workerId}")
    public ResponseEntity<WorkerResponse> getWorker(@PathVariable String workerId) {
        WorkerNode node = workerRegistry.getWorker(workerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker not found: " + workerId));

        return ResponseEntity.ok(WorkerResponse.from(node));
    }
}
