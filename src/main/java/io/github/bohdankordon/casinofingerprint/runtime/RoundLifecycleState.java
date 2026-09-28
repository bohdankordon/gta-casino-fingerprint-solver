package io.github.bohdankordon.casinofingerprint.runtime;

/**
 * Lifecycle states of one production {@link RoundLifecycleTracker}.
 *
 * <p>The states answer one question: {@code is this stable recognition a NEW round that
 * downstream code may consume?} They never describe input, gameplay progress or solving
 * actions.
 */
public enum RoundLifecycleState {
    /**
     * No round has become actionable yet. Only a stable-recognition episode onset may produce
     * a ready round; uncertain, candidate and capture-problem observations never do.
     */
    WAITING_FOR_STABLE,

    /**
     * A stable identity has been exposed as a NEW round and has not yet been acknowledged by
     * downstream code. The ready identity stays exposed until it is consumed, until a
     * different stable identity desynchronizes the tracker, or until an explicit reset.
     */
    ROUND_READY,

    /**
     * The previously ready identity has been consumed. It stays remembered indefinitely: the
     * same stable identity is suppressed (never a new round), while a different stable
     * identity becomes the next ready round.
     */
    ROUND_CONSUMED,

    /**
     * Fail-closed state for an impossible or unsafe consumer sequence: a different stable
     * identity appeared while a previous round was still waiting to be consumed. Nothing is
     * actionable here, and nothing observed afterwards recovers the tracker; only an explicit
     * full lifecycle reset clears this state.
     */
    DESYNCHRONIZED,
}

