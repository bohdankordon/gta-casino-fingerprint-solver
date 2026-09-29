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
 *   <li>a final four-set with no strong success proof fail-closes to review
 *       (NEEDS_REVIEW_FINAL_EXIT or NEEDS_REVIEW_AMBIGUOUS) instead of inventing a weak
 *       success detector;</li>
 *   <li>a NEW_ROUND_READY event that introduces the FIRST prediction assigned to the active
 *       human round can never simultaneously confirm that same round: CURRENT-ROUND
 *       PREDICTION and LATER-ROUND TRANSITION are separate roles, never played by one
 *       event;</li>
 *   <li>a selection clear or shrink never by itself upgrades the previous four-set to
 *       successful ground truth, and a later FIRST prediction after such a reset never
 *       retroactively confirms the old four-set;</li>
 *   <li>a different prediction identity arriving while the old four-set is still visibly
 *       displayed never confirms by identity change alone: it is surfaced diagnostically
 *       and fail-closed.</li>
 *   <li>an ambiguous boundary is a latch for automatic ground truth: once set, the round
 *       keeps collecting diagnostic evidence (later selections and four-sets are recorded),
 *       but no later structural transition may auto-confirm it, not even when the later
 *       four-set equals the solver prediction, because solver output cannot adjudicate its
 *       own benchmark ground truth. The round fail-closes to NEEDS_REVIEW_AMBIGUOUS at the
 *       next credible lifecycle boundary or at session end, and the incoming prediction of
 *       that boundary still seeds the next tracked round.</li>
 * </ul>
 *
 * <p>Production feeds one combined {@link #onDryRunEvent} call per frame that carried a
 * lifecycle event, plus {@link #onControl} for every captured frame. The combined entry is an
 * event-attribution state machine. An incoming NEW_ROUND_READY is classified as:
 * <ul>
 *   <li>A. first prediction for the currently active human round: recorded as that round's
 *       own prediction (ON_TIME or LATE), its transition claim ignored, never confirming;</li>
 *   <li>B. duplicate or current-round prediction: diagnostically deduped, never confirming;</li>
 *   <li>C. credible subsequent-round event: only this may automatically confirm the previous
 *       four-set (different identity after the old four-set left the display, or a witnessed
 *       same-identity transition after the old four-set left the display);</li>
 *   <li>D. ambiguous: everything else fail-closed to NEEDS_REVIEW_AMBIGUOUS with evidence
 *       preserved, never to automatic MATCH, MISMATCH, NO_PREDICTION or LATE_PREDICTION.</li>
 * </ul>
 * Prediction attribution assumes the control stream leads the lifecycle (the new round's empty
 * selection is visible before recognition re-stabilizes). A clean next-round prediction should
 * ordinarily arrive after the new empty/C0 state is visible. When the control stream still
 * shows the previous four-set at prediction time, the event stays with the previous round
 * diagnostically and confirms nothing. NO_PREDICTION is especially conservative: a human
 * round that never received a prediction is only auto-labelled successful on a genuine
 * structural transition via the primitive {@link #onNewRoundTransition} path; the combined
 * path never fabricates it from a clear plus a later first prediction.
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
    private ExternalPanelPresence currentPresence = ExternalPanelPresence.PRESENT;
    private ExternalPanelPresence lastLoggedPresence;

    /**
     * Feeds one evaluation-only panel-presence reading of the same borrowed frame.
     *
     * <p>Changed-only logging: PANEL_PRESENT, PANEL_ABSENT, and PANEL_AMBIGUOUS events are
     * recorded only when presence changes, never one row per frame. When a four-set has
     * already been observed, absent marks the frozen candidate as awaiting transition proof:
     * later control readings are ignored until a credible new-round event confirms it.
     * Presence is independent of solver recognition and never invents ground truth.
     */
    public void onPanelPresence(long timestampMs, ExternalPanelPresence presence) {
        requireOpen();
        requireTimestamp(timestampMs);
        Objects.requireNonNull(presence, "presence");
        ExternalPanelPresence previous = currentPresence;
        currentPresence = presence;
        if (presence == lastLoggedPresence) {
            if (active != null && active.lastFourSet != null
                    && presence == ExternalPanelPresence.ABSENT) {
                active.absentAfterFour = true;
            }
            return;
        }
        lastLoggedPresence = presence;
        int round = active == null ? 0 : active.roundNumber;
        String prediction = active == null ? "" : active.predictionCode();
        switch (presence) {
            case PRESENT -> log(timestampMs, round, EventType.PANEL_PRESENT, "", "", prediction,
                    previous == null ? "panel present" : "panel returned; was " + previous);
            case ABSENT -> {
                if (active != null && active.lastFourSet != null) {
                    active.absentAfterFour = true;
                    log(timestampMs, round, EventType.PANEL_ABSENT, "", "", prediction,
                            "panel absent after four " + formatSet(active.lastFourSet)
                                    + "; frozen as candidate, post-puzzle control ignored");
                } else {
                    log(timestampMs, round, EventType.PANEL_ABSENT, "", "", prediction,
                            "panel absent; no control round invented");
                }
            }
            case AMBIGUOUS -> log(timestampMs, round, EventType.PANEL_AMBIGUOUS, "", "",
                    prediction, "panel ambiguous; state preserved, nothing invented");
        }
    }

    /**
     * Combined per-frame entry: presence first, then control, same timestamp. The runner
     * calls this once per captured frame so gating and observation stay atomic.
     */
    public void onObservation(long timestampMs, ExternalPanelPresence presence,
            PuzzleControlState state) {
        onPanelPresence(timestampMs, presence);
        onControl(timestampMs, state);
    }

    /**
     * Feeds one passive control reading of the same borrowed frame, gated by panel presence.
     *
     * <p>Only present frames may mutate attempts. Absent or ambiguous frames preserve prior
     * state: no round is started, no selection change is recorded, no four-set is replaced,
     * and no failed attempt is invented from walls, HACK SUCCESS, or gameplay pixels.
     */
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
        if (currentPresence != ExternalPanelPresence.PRESENT) {
            if (active == null) {
                return;
            }
            if (active.lastFourSet != null) {
                active.absentAfterFour = true;
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
                started.fourPending = true;
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
                if (active.fourPending && active.lastFourSet != null
                        && !selected.equals(active.lastFourSet)) {
                    SortedSet<Integer> oldFour = new TreeSet<>(active.lastFourSet);
                    active.currentSelected.clear();
                    active.currentSelected.addAll(selected);
                    active.currentFocus = focus;
                    active.selectedAfterFour = true;
                    markAmbiguousBoundary(timestampMs,
                            "new four " + formatSet(selected) + " after prior four "
                                    + formatSet(oldFour)
                                    + " with no absent gap; retry vs next-round early input"
                                    + " indistinguishable without independent same-puzzle"
                                    + " evidence, frozen preserved awaiting transition. ");
                    log(timestampMs, active.roundNumber, EventType.FOUR_SELECTED, focus,
                            formatSet(selected), active.predictionCode(),
                            "four selected after an ambiguous boundary; kept as evidence,"
                                    + " never success proof");
                } else {
                    active.lastFourSet = new TreeSet<>(selected);
                    active.selectedAfterFour = false;
                    active.fourPending = true;
                    log(timestampMs, active.roundNumber, EventType.FOUR_SELECTED, focus,
                            formatSet(selected), active.predictionCode(),
                            "four selected; needs transition proof");
                }
            } else if (active.fourPending && active.lastFourSet != null) {
                active.selectedAfterFour = true;
                markAmbiguousBoundary(timestampMs,
                        "new selection " + formatSet(selected) + " after four "
                                + formatSet(active.lastFourSet)
                                + " with no absent gap; retry vs next-round early input"
                                + " indistinguishable, frozen preserved awaiting transition. ");
            }
            return;
        }
        if (!previous.isEmpty() && selected.isEmpty()) {
            if (active.lastFourSet != null) {
                active.fourPending = true;
                active.currentSelected.clear();
                active.currentFocus = focus;
                active.notes.append("clear after four ").append(formatSet(active.lastFourSet))
                        .append(" at ").append(timestampMs)
                        .append("ms; pending retry vs transition, no failure recorded. ");
                return;
            }
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
                    active.fourPending = true;
                    log(timestampMs, active.roundNumber, EventType.FOUR_SELECTED, focus,
                            formatSet(selected), active.predictionCode(),
                            "four selected; needs transition proof");
                }
                return;
            }
            if (active.lastFourSet != null && selected.size() == 4
                    && !selected.equals(active.lastFourSet)) {
                SortedSet<Integer> oldFour = new TreeSet<>(active.lastFourSet);
                active.currentSelected.clear();
                active.currentSelected.addAll(selected);
                active.currentFocus = focus;
                active.selectedAfterFour = true;
                active.fourPending = true;
                markAmbiguousBoundary(timestampMs,
                        "contradictory four " + formatSet(selected) + " after prior four "
                                + formatSet(oldFour)
                                + " with no absent gap; retry vs next-round early input"
                                + " indistinguishable without independent same-puzzle"
                                + " evidence, frozen preserved awaiting transition. ");
                log(timestampMs, active.roundNumber, EventType.FOUR_SELECTED, focus,
                        formatSet(selected), active.predictionCode(),
                        "four selected after an ambiguous boundary; kept as evidence,"
                                + " never success proof");
            } else {
                active.failedAttempts.add(previous);
                active.currentSelected.clear();
                active.currentSelected.addAll(selected);
                active.currentFocus = focus;
                if (selected.size() == 4) {
                    active.lastFourSet = new TreeSet<>(selected);
                    active.selectedAfterFour = false;
                    active.fourPending = true;
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
                "structural next-round transition after prior four-set; automatic "
                        + "confirmation gated by the attributed history");
        confirmActiveOrFailClosed(timestampMs, witnessUsed, null,
                "structural next-round transition"
                        + (readyIdentity == null ? "" : " " + readyIdentity.code())
                        + " after a latched ambiguous boundary; previous merged history "
                        + "never becomes ground truth.");
    }

    /**
     * Combined production entry for one frame that carried a dry-run lifecycle event.
     *
     * <p>Event-attribution safety, stated once: a NEW_ROUND_READY event that introduces the
     * FIRST prediction assigned to the active human round cannot simultaneously confirm that
     * active round. CURRENT-ROUND PREDICTION and LATER-ROUND TRANSITION never share one event.
     * A selection clear or shrink never upgrades the previous four-set to ground truth, and a
     * different identity while the old four-set is still displayed never confirms by identity
     * change alone. Ambiguous boundaries fail closed to NEEDS_REVIEW_AMBIGUOUS with evidence
     * preserved.
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
        if (active == null) {
            if (prediction != null) {
                onPrediction(timestampMs, prediction);
            }
            if (newRoundReady) {
                if (active != null && active.lastFourSet == null) {
                    log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                            active.currentFocus, formatSet(active.currentSelected),
                            readyIdentity == null ? "" : readyIdentity.code(),
                            "transition with no observed four-set; round stays open");
                } else if (active == null) {
                    log(timestampMs, 0, EventType.NEW_ROUND_TRANSITION, "", "",
                            readyIdentity == null ? "" : readyIdentity.code(),
                            "transition with no active round; ignored");
                } else {
                    onNewRoundTransition(timestampMs, readyIdentity, transitionWitnessUsed);
                }
            }
            return;
        }
        if (active.prediction == null) {
            if (prediction == null) {
                if (newRoundReady) {
                    if (active.lastFourSet == null) {
                        log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                                active.currentFocus, formatSet(active.currentSelected),
                                readyIdentity == null ? "" : readyIdentity.code(),
                                "transition without an observed four-set; round stays open");
                    } else {
                        markAmbiguousBoundary(timestampMs,
                                "bare NEW_ROUND_READY without any prediction while a four-set "
                                        + formatSet(active.lastFourSet)
                                        + " exists; cannot prove success, staying open. ");
                        log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                                active.currentFocus, formatSet(active.currentSelected),
                                readyIdentity == null ? "" : readyIdentity.code(),
                                "bare transition without prediction ignored; fail-closed, no confirmation");
                    }
                }
                return;
            }
            if (active.lastFourSet == null) {
                onPrediction(timestampMs, prediction);
                if (newRoundReady) {
                    log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                            active.currentFocus, formatSet(active.currentSelected),
                            readyIdentity == null ? "" : readyIdentity.code(),
                            "NEW_ROUND_READY on the round's own first prediction; not a transition, ignored");
                }
                return;
            }
            if (active.currentSelected.equals(active.lastFourSet) && !active.absentAfterFour) {
                onPrediction(timestampMs, prediction);
                markAmbiguousBoundary(timestampMs,
                        "FIRST prediction " + prediction.identity().code()
                                + " arrived while prior four-set " + formatSet(active.lastFourSet)
                                + " still displayed; recorded as late current-round prediction, "
                                + "transition claim on the same event ignored, no confirmation. ");
                if (newRoundReady) {
                    log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                            active.currentFocus, formatSet(active.currentSelected),
                            readyIdentity == null ? "" : readyIdentity.code(),
                            "first-prediction transition claim ignored; one event cannot be both prediction and proof");
                }
                return;
            }
            String incomingCode = prediction.identity().code();
            SortedSet<Integer> priorFour = new TreeSet<>(active.lastFourSet);
            String priorNotes = active.notes.toString();
            log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                    active.currentFocus, formatSet(active.currentSelected),
                    readyIdentity == null ? "" : readyIdentity.code(),
                    "ambiguous boundary: FIRST prediction " + incomingCode
                            + " after prior four-set " + priorFour
                            + " left the display (now " + formatSet(active.currentSelected)
                            + "); cannot distinguish ERROR reset from next round, fail-closed");
            finalizeAmbiguousCurrentAndSeedNext(timestampMs, prediction,
                    "ambiguous first prediction " + incomingCode + " after prior four-set "
                            + priorFour
                            + " left the display; previous four-set never becomes ground truth; "
                            + "this prediction starts the next observed round for manual review. "
                            + priorNotes);
            return;
        }
        if (prediction == null) {
            if (newRoundReady) {
                onNewRoundTransition(timestampMs, readyIdentity, transitionWitnessUsed);
            }
            return;
        }
        if (prediction.identity().equals(active.prediction.identity())) {
            if (!newRoundReady) {
                onPrediction(timestampMs, prediction);
                return;
            }
            if (active.lastFourSet == null) {
                onPrediction(timestampMs, prediction);
                log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                        active.currentFocus, formatSet(active.currentSelected),
                        readyIdentity == null ? "" : readyIdentity.code(),
                        "same-identity NEW_ROUND_READY without an observed four-set; round stays open");
                return;
            }
            if (!transitionWitnessUsed) {
                onPrediction(timestampMs, prediction);
                log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                        active.currentFocus, formatSet(active.currentSelected),
                        readyIdentity == null ? "" : readyIdentity.code(),
                        "same-identity NEW_ROUND_READY without witness treated as current-round duplicate; no confirmation");
                return;
            }
            if (active.currentSelected.equals(active.lastFourSet) && !active.absentAfterFour) {
                onPrediction(timestampMs, prediction);
                markAmbiguousBoundary(timestampMs,
                        "witnessed same-identity " + prediction.identity().code()
                                + " arrived while prior four-set " + formatSet(active.lastFourSet)
                                + " still displayed; fail-closed, no confirmation. ");
                log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                        active.currentFocus, formatSet(active.currentSelected),
                        readyIdentity == null ? "" : readyIdentity.code(),
                        "witnessed same-identity transition while four still displayed ignored; awaiting empty/new-round evidence");
                return;
            }
            log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                    active.currentFocus, formatSet(active.currentSelected),
                    readyIdentity == null ? "" : readyIdentity.code(),
                    "witnessed same-identity next-round transition after prior four-set; "
                            + "automatic confirmation gated by the attributed history");
            confirmActiveOrFailClosed(timestampMs, true, prediction,
                    "witnessed same-identity transition after a latched ambiguous boundary; "
                            + "previous merged history never becomes ground truth; prediction "
                            + prediction.identity().code()
                            + " starts the next observed round for manual review.");
            return;
        }
        if (!newRoundReady) {
            onPrediction(timestampMs, prediction);
            return;
        }
        if (active.lastFourSet == null) {
            onPrediction(timestampMs, prediction);
            log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                    active.currentFocus, formatSet(active.currentSelected),
                    readyIdentity == null ? "" : readyIdentity.code(),
                    "different-identity transition without an observed four-set; round stays open");
            return;
        }
        if (active.currentSelected.equals(active.lastFourSet) && !active.absentAfterFour) {
            onPrediction(timestampMs, prediction);
            markAmbiguousBoundary(timestampMs,
                    "different prediction " + prediction.identity().code()
                            + " arrived while prior four-set " + formatSet(active.lastFourSet)
                            + " still displayed (first " + active.prediction.identity().code()
                            + " stays primary); identity change alone never confirms, fail-closed. ");
            log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                    active.currentFocus, formatSet(active.currentSelected),
                    readyIdentity == null ? "" : readyIdentity.code(),
                    "different-identity transition while four still displayed ignored; no confirmation");
            return;
        }
        log(timestampMs, active.roundNumber, EventType.NEW_ROUND_TRANSITION,
                active.currentFocus, formatSet(active.currentSelected),
                readyIdentity == null ? "" : readyIdentity.code(),
                "credible subsequent-round prediction " + prediction.identity().code()
                        + " after prior four-set left the display; automatic confirmation "
                        + "gated by the attributed history");
        confirmActiveOrFailClosed(timestampMs, transitionWitnessUsed, prediction,
                "credible subsequent-round prediction after a latched ambiguous boundary; "
                        + "previous merged history never becomes ground truth; prediction "
                        + prediction.identity().code()
                        + " starts the next observed round for manual review.");
    }

    /**
     * Closes the session: finalizes any pending round, logs SESSION_END and returns every
     * finalized round in number order. Pending rounds never become ground truth here: a final
     * four-set with no later contradiction is NEEDS_REVIEW_FINAL_EXIT (panel exit, banner and
     * success are indistinguishable), a contradicted or partial selection is INCOMPLETE, and a
    * prediction that never met any control observation is ORPHAN_PREDICTION.
     * An ambiguous boundary (first-prediction transition claim ignored, bare transition
     * without prediction, or different identity while the old four-set is still displayed)
     * fail-closes to NEEDS_REVIEW_AMBIGUOUS with all evidence preserved.
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
            } else if (pending.ambiguousBoundary) {
                finalized.add(buildRound(pending, endTimestampMs, null,
                        ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, timingOf(pending), false,
                        "ambiguous boundary evidence; fail-closed, no automatic ground truth. "
                                + pending.notes));
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

    /**
     * The single automatic-confirmation gate. A latched ambiguous boundary makes a round
     * ineligible for automatic ground truth: later selections and four-sets stay evidence,
     * never success proof, no matter what the solver predicted. The gate refuses by
     * fail-closing the round to NEEDS_REVIEW_AMBIGUOUS with every attempt, note and event
     * preserved, and the incoming prediction still seeds the next tracked round so the
     * video keeps being watched without converting merged history into ground truth.
     */
    private void confirmActiveOrFailClosed(long timestampMs, boolean transitionWitnessUsed,
            ExternalPrediction nextRoundPrediction, String failClosedReason) {
        ActiveRound round = active;
        if (round.ambiguousBoundary) {
            finalizeAmbiguousCurrentAndSeedNext(timestampMs, nextRoundPrediction,
                    failClosedReason + " " + round.notes);
            return;
        }
        confirmActive(timestampMs, transitionWitnessUsed);
        if (nextRoundPrediction != null) {
            seedNextRound(timestampMs, nextRoundPrediction);
        }
    }

    private void confirmActive(long timestampMs, boolean transitionWitnessUsed) {
        ActiveRound round = active;
        if (round.ambiguousBoundary) {
            // Unreachable through confirmActiveOrFailClosed: a latched ambiguous boundary
            // must never become automatic ground truth, so this stays a hard invariant.
            throw new IllegalStateException(
                    "An unresolved ambiguous boundary must never confirm automatically");
        }
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
        int attempts;
        if (success != null) {
            attempts = round.failedAttempts.size() + 1;
        } else {
            attempts = round.failedAttempts.size()
                    + (round.currentSelected.isEmpty() ? 0 : 1);
            if (round.firstSelectionMs != null && attempts == 0) {
                attempts = 1;
            }
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
                "next-round prediction seeded after the previous round closed");
    }

    private void markAmbiguousBoundary(long timestampMs, String detail) {
        if (active == null) {
            return;
        }
        active.ambiguousBoundary = true;
        active.notes.append(detail).append(" ");
        log(timestampMs, active.roundNumber, EventType.AMBIGUOUS, active.currentFocus,
                formatSet(active.currentSelected), active.predictionCode(), detail);
    }

    private void finalizeAmbiguousCurrentAndSeedNext(long timestampMs,
            ExternalPrediction nextPrediction, String reason) {
        ActiveRound round = active;
        active = null;
        if (round.lastFourSet != null && round.fourPending
                && round.failedAttempts.stream().noneMatch(a -> a.equals(round.lastFourSet))) {
            round.failedAttempts.add(new TreeSet<>(round.lastFourSet));
        }
        ExternalObservedRound ambiguous = buildRound(round, timestampMs, null,
                ExternalRoundOutcome.NEEDS_REVIEW_AMBIGUOUS, timingOf(round), false,
                reason == null ? "" : reason);
        finalized.add(ambiguous);
        log(timestampMs, round.roundNumber, EventType.AMBIGUOUS, round.currentFocus,
                formatSet(round.currentSelected), round.predictionCode(),
                "round fail-closed as NEEDS_REVIEW_AMBIGUOUS: " + reason);
        if (nextPrediction != null) {
            seedNextRound(timestampMs, nextPrediction);
        }
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
        boolean fourPending;
        boolean absentAfterFour;
        boolean hadControl;
        boolean lastFedValid = true;
        boolean ambiguousBoundary;

        ActiveRound(int roundNumber, long firstSeenMs) {
            this.roundNumber = roundNumber;
            this.firstSeenMs = firstSeenMs;
        }

        String predictionCode() {
            return prediction == null ? "" : prediction.identity().code();
        }
    }
}
