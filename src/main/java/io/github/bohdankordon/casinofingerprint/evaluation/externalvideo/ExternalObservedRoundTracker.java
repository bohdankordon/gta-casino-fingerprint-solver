package io.github.bohdankordon.casinofingerprint.evaluation.externalvideo;

import io.github.bohdankordon.casinofingerprint.control.PuzzleControlState;
import io.github.bohdankordon.casinofingerprint.evaluation.externalvideo.ExternalSessionEvent.EventType;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Passive evaluation state machine describing what happens in the VIDEO, not the production
 * puzzle lifecycle.
 *
 * <p>It receives timestamped plain-data observations only: control states, dry-run prediction
 * events and structural new-round transition events. It owns no Mat, no native resource and no
 * production lifecycle object, and it never invents ground truth. The contract, stated once:
 *
 * <ul>
 *   <li>a set of four selected tiles is NOT ground truth: the player may be wrong;</li>
 *   <li>the only automatic ground truth is a human four-set followed by a genuine structural
 *       next-round transition, compared as a SET independent of selection order;</li>
 *   <li>a selection that clears or shrinks while the same puzzle continues ends the current
 *       attempt: the previous attempt is kept in history as failed or ambiguous, never as
 *       ground truth;</li>
 *   <li>brief detector ambiguity preserves state and invents no change;</li>
 *   <li>a final four-set with no strong success proof fail-closes to
 *       NEEDS_REVIEW_FINAL_EXIT instead of inventing a weak success detector.</li>
 * </ul>
 *
 * <p>Production feeds one combined {@link #onDryRunEvent} call per frame that carried a
 * lifecycle event, plus {@link #onControl} for every captured frame. The combined entry
 * disambiguates the round boundary: the first NEW_ROUND_READY of a round is that round's own
 * prediction (the active round has no four-set yet), while a later NEW_ROUND_READY confirms
 * the previous round and seeds the next one. Prediction attribution assumes the control
 * stream leads the lifecycle (the new round's empty selection is visible before recognition
 * re-stabilizes); when the control stream still shows the previous four-set at prediction
 * time, a next-round prediction is attributed to the previous round and stays visible in its
 * notes, and any resulting MISMATCH is a mandatory manual-review case.
 *
 * <p>State is deterministic and single-threaded. Timestamps are session elapsed milliseconds
 * assigned by the caller; they must be non-negative but are otherwise never interpreted as
 * durations and drive no decision except ordering comparisons.
 */
public final class ExternalObservedRoundTracker {
    private final List<ExternalObservedRound> finalized = new ArrayList<>();
    private final List<ExternalSessionEvent> events = new ArrayList<>();
    private final List<ExternalSessionEvent> freshEvents = new ArrayList<>();
    private ActiveRound active;
    private int nextRoundNumber = 1;
    private boolean sessionClosed;

    /** Feeds one passive control reading of the same borrowed frame. */
    public void onControl(long timestampMs, PuzzleControlState state) {
        requireOpen();
        requireTimestamp(timestampMs);
        Objects.requireNonNull(state, "state");
        if (!state.valid()) {
            if (active != null && active.lastFedValid) {
                log(timestampMs, active.roundNumber, EventType.AMBIGUOUS, "", "",
                        active.predictionCode(), "control ambiguous: " + state.detail()
                                + "; state preserved, no change invented");
            }
            if (active != null) {
                active.lastFedValid = false;
            }
            return;
        }
        String focus = state.focus().map(Object::toString).orElse("");
        SortedSet<Integer> selected = new TreeSet<>(state.selected());
        if (active == null) {
            ActiveRound started = new ActiveRound(nextRoundNumber++, timestampMs);
            started.hadControl = true;
            started.lastFedValid = true;
            started.currentFocus = focus;
            started.currentSelected.addAll(selected);
            if (!selected.isEmpty()) {
                started.firstSelectionMs = timestampMs;
            }
            if (selected.size() == 4) {
                started.lastFourSet = new TreeSet<>(selected);
            }
            active = started;
            log(timestampMs, started.roundNumber, EventType.ROUND_START, focus,
                    formatSet(selected), "", "first clean round observation");
            if (!selected.isEmpty()) {
                log(timestampMs, started.roundNumber, EventType.SELECTION_CHANGE, focus,
                        formatSet(selected), "", "selection observed at round start");
            }
            if (selected.size() == 4) {
                log(timestampMs, started.roundNumber, EventType.FOUR_SELECTED, focus,
                        formatSet(selected), "", "four selected; needs transition proof");
            }
            return;
        }
        active.lastFedValid = true;
        if (!active.hadControl) {
            active.hadControl = true;
            log(timestampMs, active.roundNumber, EventType.ROUND_START, focus,
                    formatSet(selected), active.predictionCode(),
                    "first control observation of the seeded round");
        }
        if (active.lastFourSet != null && !selected.isEmpty()
                && !selected.equals(active.lastFourSet)) {
            active.selectedAfterFour = true;
        }
        if (selected.equals(active.currentSelected)) {
            active.currentFocus = focus;
            return;
        }
        if (active.firstSelectionMs == null && !selected.isEmpty()) {
            active.firstSelectionMs = timestampMs;
        }
        SortedSet<Integer> previous = new TreeSet<>(active.currentSelected);
        if (previous.isEmpty() && !selected.isEmpty()) {
            active.currentSelected.clear();
            active.currentSelected.addAll(selected);
            active.currentFocus = focus;
            log(timestampMs, active.roundNumber, EventType.SELECTION_CHANGE, focus,
                    formatSet(selected), active.predictionCode(),
                    "selection grew from empty to " + formatSet(selected));
            if (selected.size() == 4) {
                active.lastFourSet = new TreeSet<>(selected);
                active.selectedAfterFour = false;
                log(timestampMs, active.roundNumber, EventType.FOUR_SELECTED, focus,
                        formatSet(selected), active.predictionCode(),
                        "four selected; needs transition proof");
            }
            return;
        }
        if (!previous.isEmpty() && selected.isEmpty()) {
            active.failedAttempts.add(previous);
            active.currentSelected.clear();
            active.currentFocus = focus;
            active.notes.append("attempt ").append(formatSet(previous))
                    .append(" ended by selection clear at ").append(timestampMs)
                    .append("ms; kept as failed/ambiguous. ");
            log(timestampMs, active.roundNumber, EventType.ATTEMPT_RESET, focus, "[]",
                    active.predictionCode(), "selection cleared from " + formatSet(previous)
                            + "; previous attempt kept, never ground truth");
            return;
        }
        if (!previous.isEmpty() && !selected.isEmpty()) {
            if (selected.size() > previous.size() && selected.containsAll(previous)) {
                active.currentSelected.clear();
                active.currentSelected.addAll(selected);
                active.currentFocus = focus;
                log(timestampMs, active.roundNumber, EventType.SELECTION_CHANGE, focus,
                        formatSet(selected), active.predictionCode(),
                        "selection grew monotonically to " + formatSet(selected));
                if (selected.size() == 4) {
                    active.lastFourSet = new TreeSet<>(selected);
                    active.selectedAfterFour = false;
                    log(timestampMs, active.roundNumber, EventType.FOUR_SELECTED, focus,
                            formatSet(selected), active.predictionCode(),
                            "four selected; needs transition proof");
                }
                return;
            }
            active.failedAttempts.add(previous);
            active.currentSelected.clear();
            active.currentSelected.addAll(selected);
            active.currentFocus = focus;
            if (selected.size() == 4) {
                active.lastFourSet = new TreeSet<>(selected);
                active.selectedAfterFour = false;
            }
            active.notes.append("attempt ").append(formatSet(previous))
                    .append(" ended by contradictory selection ").append(formatSet(selected))
                    .append(" at ").append(timestampMs).append("ms; kept as failed/ambiguous. ");
            log(timestampMs, active.roundNumber, EventType.ATTEMPT_RESET, focus,
                    formatSet(selected), active.predictionCode(),
                    "selection changed from " + formatSet(previous) + " to "
                            + formatSet(selected) + "; previous attempt kept, never ground truth");
            if (selected.size() == 4) {
                log(timestampMs, active.roundNumber, EventType.FOUR_SELECTED, focus,
                        formatSet(selected), active.predictionCode(),
                        "four selected; needs transition proof");
            }
        }
    }

    /**
     * Feeds one executable solver prediction (primitive entry).
     *
     * <p>With no active round the prediction seeds a pending round that still needs control:
     * if control never arrives it finalizes as ORPHAN_PREDICTION at session end. Identical
     * repeats of the same identity create no duplicate event; a different identity inside one
     * human round is kept diagnostically while the first prediction stays primary.
     */
    public void onPrediction(long timestampMs, ExternalPrediction prediction) {
        requireOpen();
        requireTimestamp(timestampMs);
        Objects.requireNonNull(prediction, "prediction");
        if (active == null) {
            ActiveRound seeded = new ActiveRound(nextRoundNumber++, timestampMs);
            seeded.prediction = prediction;
            seeded.predictionMs = timestampMs;
            active = seeded;
            log(timestampMs, seeded.roundNumber, EventType.PREDICTION, "", "",
                    prediction.identity().code(),
                    "prediction-seeded round; awaiting first control observation");
            return;
        }
        if (active.prediction == null) {
            active.prediction = prediction;
            active.predictionMs = timestampMs;
            log(timestampMs, active.roundNumber, EventType.PREDICTION, active.currentFocus,
                    formatSet(active.currentSelected), prediction.identity().code(),
                    "prediction " + prediction.identity().code() + " order="
                            + prediction.order() + " moves=" + prediction.navigationMoves());
            return;
        }
        if (active.prediction.identity().equals(prediction.identity())) {
            return;
        }
        if (active.secondPrediction == null) {
            active.secondPrediction = prediction;
            active.secondPredictionMs = timestampMs;
            active.notes.append("unexpected second prediction ")
                    .append(prediction.identity().code()).append(" at ").append(timestampMs)
                    .append("ms; kept first ").append(active.prediction.identity().code())
                    .append(" as primary. ");
        }
        log(timestampMs, active.roundNumber, EventType.SECOND_PREDICTION, active.currentFocus,
                formatSet(active.currentSelected), prediction.identity().code(),
                "unexpected second prediction " + prediction.identity().code()
                        + "; first " + active.prediction.identity().code() + " stays primary");
    }

    /**
     * Feeds one structural new-round lifecycle event (primitive entry).
     *
     * <p>A genuine transition confirms the previous round only through its last observed
     * four-set, compared as a set. Same-identity witnessed transitions confirm exactly like
     * identity changes. Without a four-set the round stays open and the event is only logged.
     */
    public void onNewRoundTransition(long timestampMs, RecognitionIdentity readyIdentity,
            boolean witnessUsed) {
        requireOpen();
        requireTimestamp(timestampMs);
        if (active == null) {
            log(timestampMs, 0, EventType.NEW_ROUND_TRANSITION, "", "",
                    readyIdentity == null ? "" : readyIdentity.code(),
                    "transition with no active round; ignored");
            return;
        }
        if (active.lastFourSet == null) {
            log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                    active.currentFocus, formatSet(active.currentSelected),
                    readyIdentity == null ? "" : readyIdentity.code(),
                    "transition without an observed four-set; round stays open");
            return;
        }
        log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                active.currentFocus, formatSet(active.currentSelected),
                readyIdentity == null ? "" : readyIdentity.code(),
                "structural next-round transition; confirming previous four-set");
        confirmActive(timestampMs, witnessUsed);
    }

    /**
     * Combined production entry for one frame that carried a dry-run lifecycle event.
     *
     * @param timestampMs session elapsed milliseconds
     * @param prediction executable prediction of this frame, or null (blocked plan or no plan)
     * @param newRoundReady whether the lifecycle reported NEW_ROUND_READY on this frame
     * @param readyIdentity ready identity of the lifecycle event, may be null
     * @param transitionWitnessUsed whether the lifecycle event used the transition witness
     */
    public void onDryRunEvent(long timestampMs, ExternalPrediction prediction,
            boolean newRoundReady, RecognitionIdentity readyIdentity,
            boolean transitionWitnessUsed) {
        requireOpen();
        requireTimestamp(timestampMs);
        if (newRoundReady && active != null && active.lastFourSet != null && prediction != null
                && active.prediction != null
                && !prediction.identity().equals(active.prediction.identity())) {
            log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                    active.currentFocus, formatSet(active.currentSelected),
                    readyIdentity == null ? "" : readyIdentity.code(),
                    "next-round prediction arrived while the previous round holds a four-set;"
                            + " confirming previous, seeding next");
            confirmActive(timestampMs, transitionWitnessUsed);
            seedNextRound(timestampMs, prediction);
            return;
        }
        if (newRoundReady && active != null && active.lastFourSet != null
                && active.prediction == null && prediction != null
                && active.currentSelected.isEmpty()) {
            log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                    active.currentFocus, formatSet(active.currentSelected),
                    readyIdentity == null ? "" : readyIdentity.code(),
                    "previous round ended without a prediction; this prediction starts the next");
            confirmActive(timestampMs, transitionWitnessUsed);
            seedNextRound(timestampMs, prediction);
            return;
        }
        if (prediction != null) {
            onPrediction(timestampMs, prediction);
        }
        if (newRoundReady) {
            onNewRoundTransition(timestampMs, readyIdentity, transitionWitnessUsed);
        }
    }

    /**
     * Closes the session: finalizes any pending round, logs SESSION_END and returns every
     * finalized round in number order. Pending rounds never become ground truth here: a final
     * four-set with no later contradiction is NEEDS_REVIEW_FINAL_EXIT (panel exit, banner and
     * success are indistinguishable), a contradicted or partial selection is INCOMPLETE, and a
     * prediction that never met any control observation is ORPHAN_PREDICTION.
     */
    public List<ExternalObservedRound> closeSession(long endTimestampMs) {
        requireOpen();
        requireTimestamp(endTimestampMs);
        sessionClosed = true;
        if (active != null) {
            ActiveRound pending = active;
            active = null;
            if (!pending.hadControl && pending.prediction != null) {
                finalized.add(new ExternalObservedRound(pending.roundNumber, pending.firstSeenMs,
                        pending.predictionMs, null, endTimestampMs, pending.prediction.identity(),
                        pending.prediction.order(), pending.prediction.navigationMoves(), null, 0,
                        List.of(), ExternalRoundOutcome.ORPHAN_PREDICTION,
                        ExternalPredictionTiming.NONE, pending.prediction.witnessUsed(),
                        "prediction " + pending.prediction.identity().code()
                                + " never met any observed control round" + pending.notes));
                log(endTimestampMs, pending.roundNumber, EventType.ORPHAN_PREDICTION, "", "",
                        pending.prediction.identity().code(),
                        "prediction without an observed round");
            } else if (pending.lastFourSet != null && !pending.selectedAfterFour) {
                finalized.add(buildRound(pending, endTimestampMs, null,
                        ExternalRoundOutcome.NEEDS_REVIEW_FINAL_EXIT, timingOf(pending), false,
                        "final four-set without strong success proof; panel exit, banner, cut"
                                + " and success are indistinguishable here " + pending.notes));
            } else if (pending.firstSelectionMs != null) {
                String detail = "session ended during a partial selection";
                if (pending.lastFourSet != null) {
                    detail += "; observed four-set " + pending.lastFourSet
                            + " was later contradicted and is not ground truth";
                }
                finalized.add(buildRound(pending, endTimestampMs, null,
                        ExternalRoundOutcome.INCOMPLETE, timingOf(pending), false,
                        detail + " " + pending.notes));
            } else {
                finalized.add(buildRound(pending, endTimestampMs, null,
                        ExternalRoundOutcome.INCOMPLETE, timingOf(pending), false,
                        "session ended with no selection observed" + pending.notes));
            }
        }
        log(endTimestampMs, 0, EventType.SESSION_END, "", "", "",
                "session closed with " + finalized.size() + " finalized rounds");
        return finalizedRounds();
    }

    /** Every finalized round in number order. */
    public List<ExternalObservedRound> finalizedRounds() {
        return Collections.unmodifiableList(finalized);
    }

    /** Every session event in time order. */
    public List<ExternalSessionEvent> events() {
        return Collections.unmodifiableList(events);
    }

    /** Events since the previous drain; lets the runner react without rescanning history. */
    public List<ExternalSessionEvent> drainNewEvents() {
        List<ExternalSessionEvent> fresh = List.copyOf(freshEvents);
        freshEvents.clear();
        return fresh;
    }

    /** One-based number of the active round, or 0 when no round is open. */
    public int activeRoundNumber() {
        return active == null ? 0 : active.roundNumber;
    }

    /** True once {@link #closeSession} ran. */
    public boolean sessionClosed() {
        return sessionClosed;
    }

    private void confirmActive(long timestampMs, boolean transitionWitnessUsed) {
        ActiveRound round = active;
        active = null;
        List<Integer> success = new ArrayList<>(round.lastFourSet);
        ExternalPredictionTiming timing = timingOf(round);
        ExternalRoundOutcome result;
        if (round.prediction == null) {
            result = ExternalRoundOutcome.NO_PREDICTION;
        } else if (timing == ExternalPredictionTiming.LATE) {
            result = ExternalRoundOutcome.LATE_PREDICTION;
        } else if (new TreeSet<>(round.prediction.identity().candidates()).equals(
                new TreeSet<>(success))) {
            result = ExternalRoundOutcome.MATCH;
        } else {
            result = ExternalRoundOutcome.MISMATCH;
        }
        boolean witness = transitionWitnessUsed
                || (round.prediction != null && round.prediction.witnessUsed());
        StringBuilder note = new StringBuilder();
        if (result == ExternalRoundOutcome.LATE_PREDICTION && round.prediction != null) {
            boolean setsEqual = new TreeSet<>(round.prediction.identity().candidates())
                    .equals(new TreeSet<>(success));
            note.append("late prediction was set-").append(setsEqual ? "correct" : "wrong")
                    .append("; not counted as production success. ");
        }
        note.append(round.notes);
        ExternalObservedRound confirmed = buildRound(round, timestampMs, success, result, timing,
                witness, note.toString());
        finalized.add(confirmed);
        log(timestampMs, round.roundNumber, EventType.ROUND_CONFIRMED, round.currentFocus,
                formatSet(round.currentSelected), round.predictionCode(),
                "round confirmed: " + result + " human=" + success);
    }

    private ExternalObservedRound buildRound(ActiveRound round, long endMs,
            List<Integer> success, ExternalRoundOutcome result, ExternalPredictionTiming timing,
            boolean transitionWitnessUsed, String notes) {
        int attempts = round.failedAttempts.size()
                + (round.currentSelected.isEmpty() ? 0 : 1);
        if (round.firstSelectionMs != null && attempts == 0) {
            attempts = 1;
        }
        if (success != null && attempts == 0) {
            attempts = 1;
        }
        return new ExternalObservedRound(round.roundNumber, round.firstSeenMs,
                round.predictionMs, round.firstSelectionMs, endMs,
                round.prediction == null ? null : round.prediction.identity(),
                round.prediction == null ? List.of() : round.prediction.order(),
                round.prediction == null ? 0 : round.prediction.navigationMoves(),
                success == null ? null : List.copyOf(success), attempts,
                List.copyOf(round.failedAttempts), result, timing, transitionWitnessUsed,
                notes == null ? "" : notes);
    }

    private static ExternalPredictionTiming timingOf(ActiveRound round) {
        if (round.predictionMs == null) {
            return ExternalPredictionTiming.NONE;
        }
        if (round.firstSelectionMs == null) {
            return ExternalPredictionTiming.ON_TIME;
        }
        return round.predictionMs < round.firstSelectionMs
                ? ExternalPredictionTiming.ON_TIME
                : ExternalPredictionTiming.LATE;
    }

    private void seedNextRound(long timestampMs, ExternalPrediction prediction) {
        ActiveRound seeded = new ActiveRound(nextRoundNumber++, timestampMs);
        seeded.prediction = prediction;
        seeded.predictionMs = timestampMs;
        active = seeded;
        log(timestampMs, seeded.roundNumber, EventType.PREDICTION, "", "",
                prediction.identity().code(),
                "next-round prediction seeded after confirming the previous round");
    }

    private void log(long timestampMs, int round, EventType type, String focus, String selected,
            String prediction, String detail) {
        ExternalSessionEvent event =
                new ExternalSessionEvent(timestampMs, round, type, focus, selected, prediction,
                        detail == null ? "" : detail);
        events.add(event);
        freshEvents.add(event);
    }

    private static String formatSet(SortedSet<Integer> selected) {
        return selected.toString();
    }

    private static void requireTimestamp(long timestampMs) {
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must be non-negative");
        }
    }

    private void requireOpen() {
        if (sessionClosed) {
            throw new IllegalStateException("The session is closed");
        }
    }

    /** Mutable per-round evaluation state; plain data only, no Mat ownership. */
    private static final class ActiveRound {
        final int roundNumber;
        final long firstSeenMs;
        final SortedSet<Integer> currentSelected = new TreeSet<>();
        final List<SortedSet<Integer>> failedAttempts = new ArrayList<>();
        final StringBuilder notes = new StringBuilder();
        String currentFocus = "";
        Long firstSelectionMs;
        ExternalPrediction prediction;
        Long predictionMs;
        ExternalPrediction secondPrediction;
        Long secondPredictionMs;
        SortedSet<Integer> lastFourSet;
        boolean selectedAfterFour;
        boolean hadControl;
        boolean lastFedValid = true;

        ActiveRound(int roundNumber, long firstSeenMs) {
            this.roundNumber = roundNumber;
            this.firstSeenMs = firstSeenMs;
        }

        String predictionCode() {
            return prediction == null ? "" : prediction.identity().code();
        }
    }
}
