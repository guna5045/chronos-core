package com.chronoscore.coordinator.policy;

import com.chronoscore.coordinator.coordination.RedisKeyFactory;
import com.chronoscore.coordinator.persistence.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages atomic per-tenant concurrency tracking and quota enforcement.
 * <p>
 * Key Guarantees:
 * <ul>
 *   <li><b>Atomic Check-and-Add</b>: Evaluates running capacity and registers task in a single Redis Lua script,
 *       preventing races between concurrent coordinators.</li>
 *   <li><b>Durable Reconciliation</b>: Reconciles Redis tracking state with PostgreSQL's authoritative
 *       {@code chronos_tasks} table upon coordinator failover or recovery sweeps to prevent counter leaks.</li>
 *   <li><b>Safe Release</b>: Idempotently releases tasks on completion, failure, timeout, or cancellation.</li>
 * </ul>
 */
@Component
public class TenantConcurrencyManager {

    private static final Logger log = LoggerFactory.getLogger(TenantConcurrencyManager.class);

    private static final String ACQUIRE_CONCURRENCY_LUA =
            "local key = KEYS[1]\n" +
            "local max_concurrent = tonumber(ARGV[1])\n" +
            "local task_id = ARGV[2]\n" +
            "\n" +
            "if redis.call('SISMEMBER', key, task_id) == 1 then\n" +
            "    return 1\n" +
            "end\n" +
            "\n" +
            "local current_count = redis.call('SCARD', key)\n" +
            "if current_count < max_concurrent then\n" +
            "    redis.call('SADD', key, task_id)\n" +
            "    return 1\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final TaskRepository taskRepository;
    private final RedisScript<Long> acquireScript;

    // Fallback in-memory tracking when Redis is not configured (e.g. standalone tests)
    private final Map<String, Set<UUID>> inMemoryRunning = new ConcurrentHashMap<>();

    @Autowired
    public TenantConcurrencyManager(@Autowired(required = false) StringRedisTemplate redisTemplate,
                                    TaskRepository taskRepository) {
        this.redisTemplate = redisTemplate;
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.acquireScript = new DefaultRedisScript<>(ACQUIRE_CONCURRENCY_LUA, Long.class);
    }

    /**
     * Atomically attempts to acquire a concurrency execution slot for a task under the tenant's limit.
     *
     * @param tenantId           tenant identifier.
     * @param taskId             task UUID.
     * @param maxConcurrentTasks maximum allowed running tasks for this tenant.
     * @return true if admitted, false if concurrency limit reached.
     */
    public boolean tryAcquire(String tenantId, UUID taskId, int maxConcurrentTasks) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");

        if (redisTemplate != null) {
            String key = RedisKeyFactory.tenantRunningKey(tenantId);
            try {
                Long result = redisTemplate.execute(
                        acquireScript,
                        Collections.singletonList(key),
                        String.valueOf(maxConcurrentTasks),
                        taskId.toString()
                );
                return result != null && result == 1L;
            } catch (Exception e) {
                log.warn("Redis error during concurrency evaluation for tenant '{}': {}. Falling back to PostgreSQL count.",
                        tenantId, e.getMessage());
                return fallbackPostgreSqlCheck(tenantId, maxConcurrentTasks);
            }
        }

        // In-memory atomic fallback
        Set<UUID> set = inMemoryRunning.computeIfAbsent(tenantId, id -> ConcurrentHashMap.newKeySet());
        synchronized (set) {
            if (set.contains(taskId)) {
                return true;
            }
            if (set.size() < maxConcurrentTasks) {
                set.add(taskId);
                return true;
            }
            return false;
        }
    }

    /**
     * Checks if tenant has capacity to admit another task without registering the task yet.
     *
     * @param tenantId           tenant identifier.
     * @param maxConcurrentTasks maximum allowed running tasks.
     * @return true if running count < maxConcurrentTasks.
     */
    public boolean canAdmit(String tenantId, int maxConcurrentTasks) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return getRunningCount(tenantId) < maxConcurrentTasks;
    }

    /**
     * Releases an execution concurrency slot when a task concludes (success, failure, or cancellation).
     *
     * @param tenantId tenant identifier.
     * @param taskId   task UUID.
     */
    public void releaseConcurrency(String tenantId, UUID taskId) {
        release(tenantId, taskId);
    }

    public void release(String tenantId, UUID taskId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");

        if (redisTemplate != null) {
            String key = RedisKeyFactory.tenantRunningKey(tenantId);
            try {
                redisTemplate.opsForSet().remove(key, taskId.toString());
            } catch (Exception e) {
                log.warn("Failed to release concurrency slot in Redis for task '{}' (tenant '{}'): {}",
                        taskId, tenantId, e.getMessage());
            }
        }

        Set<UUID> set = inMemoryRunning.get(tenantId);
        if (set != null) {
            set.remove(taskId);
        }
    }

    /**
     * Returns the current running task count for a tenant.
     *
     * @param tenantId tenant identifier.
     * @return active task count.
     */
    public int getRunningCount(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        if (redisTemplate != null) {
            try {
                Long size = redisTemplate.opsForSet().size(RedisKeyFactory.tenantRunningKey(tenantId));
                return size != null ? size.intValue() : 0;
            } catch (Exception e) {
                log.warn("Redis count query failed for tenant '{}'; querying PostgreSQL", tenantId);
                return taskRepository.countRunningByTenant(tenantId);
            }
        }

        Set<UUID> set = inMemoryRunning.get(tenantId);
        return set != null ? set.size() : 0;
    }

    /**
     * Reconciles Redis tracking state with authoritative PostgreSQL running tasks.
     * Prevents counter leaks caused by coordinator crashes or evicted workers.
     *
     * @param tenantId tenant identifier.
     */
    public void reconcileWithDurableState(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        List<com.chronoscore.common.domain.Task> runningTasks = new java.util.ArrayList<>();
        runningTasks.addAll(taskRepository.findByTenantAndStatus(tenantId, com.chronoscore.common.enums.TaskStatus.DISPATCHED, 1000));
        runningTasks.addAll(taskRepository.findByTenantAndStatus(tenantId, com.chronoscore.common.enums.TaskStatus.RUNNING, 1000));

        Set<String> activeTaskIds = runningTasks.stream()
                .map(t -> t.getTaskId().toString())
                .collect(java.util.stream.Collectors.toSet());

        log.info("Reconciling concurrency state for tenant '{}': durable active tasks count={}",
                tenantId, activeTaskIds.size());

        if (redisTemplate != null) {
            String key = RedisKeyFactory.tenantRunningKey(tenantId);
            try {
                redisTemplate.delete(key);
                if (!activeTaskIds.isEmpty()) {
                    redisTemplate.opsForSet().add(key, activeTaskIds.toArray(new String[0]));
                }
            } catch (Exception e) {
                log.warn("Failed to reconcile Redis concurrency set for tenant '{}': {}", tenantId, e.getMessage());
            }
        }

        Set<UUID> set = ConcurrentHashMap.newKeySet();
        for (com.chronoscore.common.domain.Task t : runningTasks) {
            set.add(t.getTaskId());
        }
        inMemoryRunning.put(tenantId, set);
    }

    /**
     * Resets concurrency tracking state (used in tests).
     *
     * @param tenantId tenant identifier.
     */
    public void reset(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(RedisKeyFactory.tenantRunningKey(tenantId));
            } catch (Exception e) {
                log.warn("Failed to reset Redis concurrency key for tenant '{}': {}", tenantId, e.getMessage());
            }
        }
        inMemoryRunning.remove(tenantId);
    }

    private boolean fallbackPostgreSqlCheck(String tenantId, int maxConcurrentTasks) {
        int running = taskRepository.countRunningByTenant(tenantId);
        return running < maxConcurrentTasks;
    }
}
