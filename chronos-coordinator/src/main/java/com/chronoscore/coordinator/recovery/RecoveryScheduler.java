package com.chronoscore.coordinator.recovery;

import com.chronoscore.coordinator.coordination.exception.StaleLeaderException;
import com.chronoscore.coordinator.coordination.fencing.FencingTokenService;
import com.chronoscore.coordinator.coordination.leader.LeaderElectionService;
import com.chronoscore.coordinator.coordination.model.FencingToken;
import com.chronoscore.coordinator.coordination.model.LeaderIdentity;
import com.chronoscore.coordinator.coordination.model.LeadershipStateListener;
import com.chronoscore.coordinator.recovery.config.RecoveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leader-aware background scheduler for recovering expired execution leases and timed-out tasks.
 * <p>
 * Architectural Guarantees:
 * <ul>
 *   <li><b>Leader-Only Operation</b>: Recovery loops execute strictly on the active leader coordinator.</li>
 *   <li><b>Fencing Assertion</b>: Protects against zombie/stale leaders executing recoveries after lease loss.</li>
 *   <li><b>Concurrency Gate</b>: Prevents overlapping recovery sweeps.</li>
 * </ul>
 */
@Component
public class RecoveryScheduler implements SmartLifecycle, LeadershipStateListener {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final TaskRecoveryService recoveryService;
    private final LeaderElectionService leaderElectionService;
    private final FencingTokenService fencingTokenService;
    private final RecoveryProperties recoveryProperties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean sweepInProgress = new AtomicBoolean(false);
    private ScheduledExecutorService executorService;

    public RecoveryScheduler(TaskRecoveryService recoveryService,
                             LeaderElectionService leaderElectionService,
                             FencingTokenService fencingTokenService,
                             RecoveryProperties recoveryProperties) {
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService must not be null");
        this.leaderElectionService = Objects.requireNonNull(leaderElectionService, "leaderElectionService must not be null");
        this.fencingTokenService = Objects.requireNonNull(fencingTokenService, "fencingTokenService must not be null");
        this.recoveryProperties = Objects.requireNonNull(recoveryProperties, "recoveryProperties must not be null");

        this.leaderElectionService.addListener(this);
    }

    /**
     * Executes a single recovery sweep over expired leases and timed-out tasks.
     * Accessible for testing and scheduled ticks.
     *
     * @return total number of tasks recovered during this sweep.
     */
    public int executeRecoverySweep() {
        if (!leaderElectionService.isLeader()) {
            return 0;
        }

        // Assert leader fencing token validity before executing recovery mutations
        FencingToken fencingToken;
        try {
            fencingToken = fencingTokenService.getCurrentFencingToken();
            fencingTokenService.assertActiveLeader(fencingToken.getEpoch());
        } catch (StaleLeaderException ex) {
            log.warn("Recovery sweep skipped: stale leader detected ({})", ex.getMessage());
            return 0;
        }

        Instant now = Instant.now();
        int batchSize = recoveryProperties.getBatchSize();
        int recoveredCount = 0;

        try {
            // 1. Recover expired execution leases
            int expiredLeasesRecovered = recoveryService.recoverExpiredLeases(now, batchSize);
            recoveredCount += expiredLeasesRecovered;

            // 2. Recover timed out tasks
            int timedOutRecovered = recoveryService.recoverTimedOutTasks(now, batchSize);
            recoveredCount += timedOutRecovered;

            if (recoveredCount > 0) {
                log.info("Recovery sweep completed: {} expired leases recovered, {} timed-out tasks recovered (leader epoch: {})",
                        expiredLeasesRecovered, timedOutRecovered, fencingToken.getEpoch());
            } else {
                log.trace("Recovery sweep clean: 0 tasks required recovery");
            }
        } catch (Exception ex) {
            log.error("Unexpected error during recovery sweep: {}", ex.getMessage(), ex);
        }

        return recoveredCount;
    }

    private void runScheduledTick() {
        if (!running.get() || !leaderElectionService.isLeader()) {
            return;
        }

        if (!sweepInProgress.compareAndSet(false, true)) {
            log.debug("Previous recovery sweep still active; skipping tick");
            return;
        }

        try {
            executeRecoverySweep();
        } finally {
            sweepInProgress.set(false);
        }
    }

    @Override
    public void start() {
        if (!recoveryProperties.isEnabled()) {
            log.info("RecoveryScheduler is disabled by configuration");
            return;
        }

        if (running.compareAndSet(false, true)) {
            long intervalMs = Math.max(100, recoveryProperties.getPollInterval().toMillis());
            log.info("Starting RecoveryScheduler (poll interval: {}ms, batch size: {})",
                    intervalMs, recoveryProperties.getBatchSize());

            this.executorService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chronos-recovery-scheduler");
                t.setDaemon(true);
                return t;
            });

            this.executorService.scheduleWithFixedDelay(
                    this::runScheduledTick,
                    intervalMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping RecoveryScheduler");
            if (executorService != null) {
                executorService.shutdownNow();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get() && leaderElectionService.isLeader();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 90;
    }

    @Override
    public void onLeadershipAcquired(LeaderIdentity identity) {
        log.info("Recovery scheduler RESUMED: coordinator acquired leadership (epoch: {})",
                identity != null ? identity.getLeaderEpoch() : 0);
    }

    @Override
    public void onLeadershipLost(LeaderIdentity identity) {
        log.warn("Recovery scheduler PAUSED: coordinator lost leadership (epoch: {})",
                identity != null ? identity.getLeaderEpoch() : 0);
    }
}
