package com.chronoscore.coordinator.coordination.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * Service contract for acquiring, maintaining, and safely releasing distributed locks in Redis.
 * <p>
 * Guarantees:
 * <ul>
 *   <li><b>Atomic Acquisition</b>: Single-step atomic acquisition using {@code SET ... NX EX}.</li>
 *   <li><b>Owner-Checked Release</b>: Locks are only released if the token matches via atomic Lua script.</li>
 *   <li><b>Stale Owner Protection</b>: Prevents slow or expired lock holders from deleting newer locks.</li>
 * </ul>
 */
public interface DistributedLockService {

    /**
     * Attempts to acquire a distributed lock on a given resource with a specified TTL.
     *
     * @param resource unique resource name (e.g. task ID).
     * @param ttl      lock lease duration.
     * @return Optional containing the {@link LockHandle} if acquired, or empty if lock is held by another.
     */
    Optional<LockHandle> tryAcquire(String resource, Duration ttl);

    /**
     * Safely releases a previously acquired distributed lock.
     * <p>
     * Executes an atomic Lua script verifying that the current Redis value matches
     * the handle's owner token before deleting the key.
     *
     * @param handle the lock handle returned by {@link #tryAcquire(String, Duration)}.
     * @return true if released, false if the lock was already lost, expired, or held by another owner.
     */
    boolean release(LockHandle handle);

    /**
     * Atomically extends the TTL of an actively held lock if this handle is still the owner.
     *
     * @param handle the lock handle.
     * @param ttl    extended TTL duration.
     * @return true if successfully renewed, false if lost or owned by another.
     */
    boolean renew(LockHandle handle, Duration ttl);

    /**
     * Checks if the handle's owner token currently matches the lock value in Redis.
     *
     * @param handle the lock handle.
     * @return true if currently owned in Redis, false otherwise.
     */
    boolean isOwner(LockHandle handle);
}
