package com.chronoscore.coordinator.distribution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Reusable thread-safe consistent hash ring for deterministic task-to-worker distribution.
 * <p>
 * <b>Architectural Purpose &amp; Guarantees:</b>
 * <ul>
 *   <li><b>Deterministic Ownership</b>: Consistently maps the same task ID to the same worker as long
 *       as cluster membership remains unchanged.</li>
 *   <li><b>Minimal Remapping</b>: When a worker joins or leaves, only a fraction of tasks
 *       ({@code 1/N}) are remapped, minimizing cache churn and dispatch disruption.</li>
 *   <li><b>Virtual Nodes</b>: Allocates a configurable number of virtual nodes (default: 128) per
 *       physical worker across the 64-bit integer ring space to promote a statistically balanced distribution.</li>
 *   <li><b>Non-Cryptographic Hashing (MD5)</b>: MD5 is used strictly as a fast, uniform, deterministic
 *       distribution hash function to place keys across the ring.
 *       <b>MD5 is NOT used for security, cryptography, or hashing sensitive information.</b></li>
 *   <li><b>Not a Load Balancer</b>: Consistent hashing provides deterministic ownership, not perfect
 *       load balancing. Dynamic worker saturation is regulated by capacity-aware admission control.</li>
 * </ul>
 */
public class ConsistentHashRing {

    private static final Logger log = LoggerFactory.getLogger(ConsistentHashRing.class);

    private final int virtualNodesPerWorker;
    private final ConcurrentSkipListMap<Long, String> ring = new ConcurrentSkipListMap<>();
    private final Set<String> workers = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<Long>> workerVirtualNodes = new ConcurrentHashMap<>();

    public ConsistentHashRing() {
        this(128);
    }

    public ConsistentHashRing(int virtualNodesPerWorker) {
        if (virtualNodesPerWorker <= 0) {
            throw new IllegalArgumentException("virtualNodesPerWorker must be positive");
        }
        this.virtualNodesPerWorker = virtualNodesPerWorker;
    }

    /**
     * Adds an active worker to the consistent hash ring, registering its virtual nodes.
     *
     * @param workerId unique identifier of the worker node.
     * @return true if the worker was newly added, false if already present.
     */
    public synchronized boolean addWorker(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        if (workers.contains(workerId)) {
            return false;
        }

        Set<Long> nodeHashes = new HashSet<>(virtualNodesPerWorker);
        for (int i = 0; i < virtualNodesPerWorker; i++) {
            String vnodeName = workerId + "#" + i;
            long h = hash(vnodeName);
            ring.put(h, workerId);
            nodeHashes.add(h);
        }

        workers.add(workerId);
        workerVirtualNodes.put(workerId, nodeHashes);
        log.debug("Added worker '{}' to hash ring with {} virtual nodes (total workers: {})",
                workerId, virtualNodesPerWorker, workers.size());
        return true;
    }

    /**
     * Removes a worker from the consistent hash ring, unregistering all of its virtual nodes.
     *
     * @param workerId unique identifier of the worker node.
     * @return true if the worker was removed, false if not present.
     */
    public synchronized boolean removeWorker(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        if (!workers.contains(workerId)) {
            return false;
        }

        Set<Long> nodeHashes = workerVirtualNodes.remove(workerId);
        if (nodeHashes != null) {
            for (Long h : nodeHashes) {
                ring.remove(h);
            }
        } else {
            for (int i = 0; i < virtualNodesPerWorker; i++) {
                String vnodeName = workerId + "#" + i;
                ring.remove(hash(vnodeName));
            }
        }

        workers.remove(workerId);
        log.debug("Removed worker '{}' from hash ring (total workers: {})", workerId, workers.size());
        return true;
    }

    /**
     * Reconciles the hash ring so that it contains exactly the specified collection of active workers.
     *
     * @param activeWorkerIds current active worker identifiers.
     */
    public synchronized void reconcile(Collection<String> activeWorkerIds) {
        Set<String> target = new HashSet<>(activeWorkerIds != null ? activeWorkerIds : Collections.emptySet());

        // Remove workers not in target
        Set<String> toRemove = new HashSet<>(workers);
        toRemove.removeAll(target);
        for (String workerId : toRemove) {
            removeWorker(workerId);
        }

        // Add workers in target not yet in ring
        for (String workerId : target) {
            if (!workers.contains(workerId)) {
                addWorker(workerId);
            }
        }
    }

    /**
     * Finds the primary deterministic worker assigned to the given task identifier.
     *
     * @param taskId task unique identifier.
     * @return Optional containing the selected worker ID, or empty if ring has no workers.
     */
    public Optional<String> getWorker(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (ring.isEmpty()) {
            return Optional.empty();
        }

        long h = hash(taskId);
        Map.Entry<Long, String> entry = ring.ceilingEntry(h);
        if (entry == null) {
            // Wrap around to the start of the ring
            entry = ring.firstEntry();
        }

        return entry != null ? Optional.of(entry.getValue()) : Optional.empty();
    }

    /**
     * Returns an ordered sequence of distinct candidate workers along the ring starting
     * from the position corresponding to {@code taskId} and wrapping around.
     * <p>
     * Used for fallback selection when the primary worker is saturated or temporarily unavailable.
     *
     * @param taskId task unique identifier.
     * @return list of distinct worker IDs in ring traversal order, or empty if ring is empty.
     */
    public List<String> getCandidates(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (ring.isEmpty()) {
            return Collections.emptyList();
        }

        long h = hash(taskId);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        int totalWorkers = workers.size();

        // 1. Advance from ceiling entry through tail of the ring
        for (String workerId : ring.tailMap(h).values()) {
            candidates.add(workerId);
            if (candidates.size() == totalWorkers) {
                return new ArrayList<>(candidates);
            }
        }

        // 2. Wrap around from start of the ring
        for (String workerId : ring.headMap(h).values()) {
            candidates.add(workerId);
            if (candidates.size() == totalWorkers) {
                break;
            }
        }

        return new ArrayList<>(candidates);
    }

    /**
     * Computes the 64-bit integer hash for a string key using MD5.
     * <p>
     * <b>Non-Cryptographic Usage Notice</b>: MD5 is used here solely as a uniform deterministic distribution
     * hash function across the 64-bit integer space. It is not used for security or cryptographic authentication.
     *
     * @param key input string key.
     * @return 64-bit signed integer hash.
     */
    public long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (digest[i] & 0xFFL);
            }
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 MessageDigest not available", e);
        }
    }

    /**
     * Returns the set of virtual node hashes for a given worker.
     *
     * @param workerId worker identifier.
     * @return unmodifiable set of virtual node hashes, or empty set if worker is not registered.
     */
    public Set<Long> getVirtualNodes(String workerId) {
        Set<Long> nodes = workerVirtualNodes.get(workerId);
        return nodes != null ? Collections.unmodifiableSet(nodes) : Collections.emptySet();
    }

    public int getVirtualNodesPerWorker() {
        return virtualNodesPerWorker;
    }

    public int size() {
        return workers.size();
    }

    public int virtualNodeCount() {
        return ring.size();
    }

    public boolean isEmpty() {
        return ring.isEmpty();
    }

    public synchronized void clear() {
        ring.clear();
        workers.clear();
        workerVirtualNodes.clear();
    }

    public Set<String> getWorkers() {
        return Collections.unmodifiableSet(workers);
    }
}
