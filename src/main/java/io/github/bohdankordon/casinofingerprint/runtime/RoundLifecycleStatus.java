package io.github.bohdankordon.casinofingerprint.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * One immutable snapshot of the {@link RoundLifecycleTracker}: the lifecycle state, the
 * identities the tracker currently remembers, and which lifecycle event the last accepted
 * consensus observation produced, if any.
 *
 * <p>Absent identities are {@code null} on the record components; prefer the {@code Optional}
 * accessors below. At most one of {@code newRoundReady}, {@code consumedIdentityRepeated} and
 * {@code desynchronizedNow} is true for a single accepted observation.
 *
 * @param state current lifecycle state
 * @param readyIdentity round waiting for downstream acknowledgement; present only in
 *        {@code ROUND_READY}, and (as a stale, non-actionable diagnostic) in
 *        {@code DESYNCHRONIZED}
 * @param consumedIdentity last acknowledged round identity; present only after a consumption
 *        identity ("last acknowledged round"); present only after a consumption. It stays
 *        remembered while the same identity continues, while observations are non-stable,
 *        while a different identity becomes and remains {@code ROUND_READY}, and while the
 *        tracker is {@code DESYNCHRONIZED}. It is replaced only by a later successful
 *        {@code consumeReadyRound()} and cleared only by {@code reset()}
 * @param stableIdentity identity of the most recently accepted consensus observation, when
 *        that observation is still {@code STABLE_RECOGNIZED}; {@code null} otherwise
 * @param newRoundReady true when this accepted observation produced a {@code NEW_ROUND_READY}
 *        event, i.e. a stable-episode onset of an identity that may become a new round
 * @param consumedIdentityRepeated true when this accepted observation re-observed the
 *        already-consumed identity and was therefore suppressed: a stable-episode onset of
 *        the consumed identity is never promoted to a new round
 * @param desynchronizedNow true when this accepted observation caused the transition into
 *        {@code DESYNCHRONIZED}; later observations while already desynchronized report
 *        {@code false} because the transition happened only once
 */
public record RoundLifecycleStatus(
        RoundLifecycleState state,
        RecognitionIdentity readyIdentity,
        RecognitionIdentity consumedIdentity,
        RecognitionIdentity stableIdentity,
        boolean newRoundReady,
        boolean consumedIdentityRepeated,
        boolean desynchronizedNow) {

    public RoundLifecycleStatus {
        Objects.requireNonNull(state, "state");
        if (state == RoundLifecycleState.ROUND_READY && readyIdentity == null) {
            throw new IllegalArgumentException(
                    "ROUND_READY must expose a ready identity");
        }
        if (state == RoundLifecycleState.WAITING_FOR_STABLE && readyIdentity != null) {
            throw new IllegalArgumentException(
                    "WAITING_FOR_STABLE must not expose a ready identity");
        }
        if (state == RoundLifecycleState.ROUND_CONSUMED && readyIdentity != null) {
            throw new IllegalArgumentException(
                    "ROUND_CONSUMED must not expose a ready identity");
        }
        if (state == RoundLifecycleState.WAITING_FOR_STABLE && consumedIdentity != null) {
            throw new IllegalArgumentException(
                    "WAITING_FOR_STABLE must not expose a consumed identity");
        }
        int events = 0;
        if (newRoundReady) {
            events++;
        }
        if (consumedIdentityRepeated) {
            events++;
        }
        if (desynchronizedNow) {
            events++;
        }
        if (events > 1) {
            throw new IllegalArgumentException(
                    "At most one lifecycle event per accepted observation");
        }
    }

    /** Round waiting for downstream acknowledgement, when there is one. */
    public Optional<RecognitionIdentity> ready() {
        return Optional.ofNullable(readyIdentity);
    }

    /** Last acknowledged round identity, when there is one. */
    public Optional<RecognitionIdentity> consumed() {
        return Optional.ofNullable(consumedIdentity);
    }

    /** Identity of the latest observation while it is still stably recognized, if any. */
    public Optional<RecognitionIdentity> stable() {
        return Optional.ofNullable(stableIdentity);
    }

    /**
     * One-line description used by diagnostics and tests. A desynchronized tracker keeps its
     * stale pending identity visible here, but that identity is explicitly NOT actionable:
     * only {@code ROUND_READY} exposes a round downstream code may consume.
     */
    public String describe() {
        StringBuilder text = new StringBuilder(state.name());
        if (readyIdentity != null) {
            text.append(" ready=").append(readyIdentity.code());
        }
        if (consumedIdentity != null) {
            text.append(" consumed=").append(consumedIdentity.code());
        }
        if (stableIdentity != null) {
            text.append(" stable=").append(stableIdentity.code());
        }
        if (newRoundReady) {
            text.append(" NEW_ROUND_READY");
        }
        if (consumedIdentityRepeated) {
            text.append(" SAME_IDENTITY_SUPPRESSED");
        }
        if (desynchronizedNow) {
            text.append(" DESYNCHRONIZED_NOW");
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
