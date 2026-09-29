package io.github.bohdankordon.casinofingerprint.execution;

/**
 * Input-execution ownership of one live round, nothing more.
 *
 * <p>This is deliberately NOT a second puzzle lifecycle state machine: the round lifecycle
 * (WAITING_FOR_STABLE, ROUND_READY, ROUND_CONSUMED, DESYNCHRONIZED) is untouched and keeps
 * describing recognition truth. This state describes only whether gameplay input may be sent:
 * IDLE before a round is claimed, EXECUTING between lifecycle consumption and the round
 * verdict, COMPLETED once PROCEED is sent, FAULTED or ABORTED latched fail-closed with no
 * automatic retry. Only an explicit reset (or an application restart) leaves a latched state.
 */
public enum ExecutionState {
    /** No round claimed; preflight failures return here without consuming anything. */
    IDLE,
    /** Round consumed, inputs being sent with per-action visual verification. */
    EXECUTING,
    /** PROCEED sent exactly once; no further input for this round. */
    COMPLETED,
    /** A mismatch, timeout, focus loss or send failure stopped input; latched. */
    FAULTED,
    /** Emergency abort stopped input; latched. */
    ABORTED
}
