package com.chronoscore.coordinator.cluster.model;

/**
 * Deterministic lifecycle state of a Worker node in the Chronos Core cluster.
 * <p>
 * Lifecycle progression:
 * <pre>
 *   REGISTERING ──► ACTIVE ──► SUSPECTED ──► REMOVED
 *                     ▲            │
 *                     │  heartbeat │
 *                     └────────────┘
 * </pre>
 */
public enum WorkerState {

    /**
     * Worker has initiated registration with the coordinator.
     */
    REGISTERING,

    /**
     * Worker is active, verified, and emitting timely heartbeats.
     */
    ACTIVE,

    /**
     * Worker has missed the heartbeat threshold and is suspected of failure or network partition.
     */
    SUSPECTED,

    /**
     * Worker has been evicted from the active cluster after continued absence.
     */
    REMOVED
}
