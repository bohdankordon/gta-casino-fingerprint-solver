package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.Entry;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.Exit;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.InterRound;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Answers the per-transition lifecycle questions from a plain full-rate trace.
 *
 * <p>Evaluation only and purely functional: it reads the frames of ONE hack region (one source, one
 * hack, chronological) and derives the run compression and the three transition summaries from
 * them. No tracker, no threshold and no annotation is modified here; the annotations only supply the
 * old and new answer identity and the nominal boundary the questions are asked about.
 *
 * <p>Every "first" and "last" frame in the results is a decoded frame index of the source, and
 * every duration is a difference of decoder timestamps, never an assumed cadence.
 */
public final class TransitionAnalyzer {
    private final List<TransitionTraceRow> rows;

    /**
     * @param rows frames of one hack region in decode order; must not be empty and must belong to
     *        one source and one hack
     */
    public TransitionAnalyzer(List<TransitionTraceRow> rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("A transition region must hold at least one frame");
        }
        this.rows = List.copyOf(rows);
        TransitionTraceRow first = this.rows.get(0);
        for (TransitionTraceRow row : this.rows) {
            if (!row.sourceId().equals(first.sourceId()) || row.hackId() != first.hackId()) {
                throw new IllegalArgumentException(
                        "A transition region holds frames of exactly one source and hack");
            }
        }
    }

    /** The analyzed frames, in decode order. */
    public List<TransitionTraceRow> rows() {
        return rows;
    }

    /** Source id of the region. */
    public String sourceId() {
        return rows.get(0).sourceId();
    }

    /** Resolution label of the region. */
    public String resolution() {
        return rows.get(0).resolution();
    }

    /** Hack the region belongs to. */
    public int hackId() {
        return rows.get(0).hackId();
    }

    /** Timestamp of the first analyzed frame. */
    public long regionStartMs() {
        return rows.get(0).timestampMs();
    }

    /** Timestamp of the last analyzed frame. */
    public long regionEndMs() {
        return rows.get(rows.size() - 1).timestampMs();
    }

    /** Contiguous runs of the region. */
    public List<TransitionRun> runs() {
        return TransitionRun.compress(rows);
    }

    /**
     * The ROUND_1 -> ROUND_2 questions.
     *
     * @param transitionId stable id used by the summary CSV
     * @param oldAnswer annotated answer of the first round of the hack
     * @param newAnswer annotated answer of the second round of the hack
     * @param nominalBoundaryMs approximate start of the second round, in decoder milliseconds
     * @param resetReplay result of the online reset experiment at the nominal boundary
     * @param runs run compression of this region
     */
    public InterRound interRound(String transitionId, AnswerIdentity oldAnswer,
            AnswerIdentity newAnswer, long nominalBoundaryMs,
            BoundaryResetReplay.Result resetReplay, List<TransitionRun> runs) {
        Objects.requireNonNull(oldAnswer, "oldAnswer");
        Objects.requireNonNull(newAnswer, "newAnswer");
        Objects.requireNonNull(resetReplay, "resetReplay");
        Objects.requireNonNull(runs, "runs");

        int firstStableOld = firstIndex(
                row -> row.stable() && oldAnswer.equals(row.answer()));
        int searchFrom = Math.max(0, firstStableOld);
        int firstNewRecognized = firstIndexFrom(searchFrom,
                row -> row.recognized() && newAnswer.equals(row.answer()));
        int oldEnd = firstNewRecognized < 0 ? rows.size() : firstNewRecognized;
        int lastOldRecognized = lastIndexBefore(oldEnd,
                row -> row.recognized() && oldAnswer.equals(row.answer()));
        int lastOldStable = lastIndexBefore(oldEnd,
                row -> row.stable() && oldAnswer.equals(row.answer()));
        int firstNewStable = firstNewRecognized < 0
                ? -1
                : firstIndexFrom(firstNewRecognized,
                        row -> row.stable() && newAnswer.equals(row.answer()));

        long uncertainBetween = firstNewRecognized < 0 || lastOldRecognized < 0
                ? 0
                : count(lastOldRecognized + 1, firstNewRecognized, row -> !row.recognized());
        long otherBetween = firstNewRecognized < 0 || lastOldRecognized < 0
                ? 0
                : count(lastOldRecognized + 1, firstNewRecognized,
                        row -> row.recognized() && !oldAnswer.equals(row.answer())
                                && !newAnswer.equals(row.answer()));
        Long uncertainDurationMs = firstNewRecognized < 0 || lastOldRecognized < 0
                ? null
                : rows.get(firstNewRecognized).timestampMs() - rows.get(lastOldRecognized).timestampMs();
        boolean directSwitch = firstNewRecognized >= 0 && lastOldRecognized >= 0
                && uncertainBetween == 0 && otherBetween == 0;
        Long lastOldStableToFirstNewStableMs = lastOldStable < 0 || firstNewStable < 0
                ? null
                : rows.get(firstNewStable).timestampMs() - rows.get(lastOldStable).timestampMs();

        long oldFramesAfterBoundary = count(0, oldEnd, row -> row.recognized()
                && oldAnswer.equals(row.answer()) && row.timestampMs() >= nominalBoundaryMs);
        long oldTimeAfterBoundaryMs = lastOldRecognized >= 0
                && rows.get(lastOldRecognized).timestampMs() >= nominalBoundaryMs
                ? rows.get(lastOldRecognized).timestampMs() - nominalBoundaryMs
                : 0L;
        long unexplainedFrames = countRecognizedOtherThan(List.of(oldAnswer, newAnswer), 0,
                rows.size());
        long unexplainedStable = countStableEpisodesOtherThan(runs, List.of(oldAnswer, newAnswer));

        StringBuilder notes = new StringBuilder();
        if (firstNewRecognized < 0) {
            notes.append("the second round's answer was never recognized inside the region; ");
        }
        if (oldFramesAfterBoundary > 0) {
            notes.append(String.format(Locale.ROOT,
                    "the previous round's answer is still recognized on %d frame(s), up to %d ms "
                            + "after the nominal boundary; ",
                    oldFramesAfterBoundary, oldTimeAfterBoundaryMs));
        } else {
            notes.append("the previous round's answer is not recognized at or after the nominal "
                    + "boundary; ");
        }
        if (directSwitch) {
            notes.append("the answer switches directly from the old to the new identity without an "
                    + "uncertain frame; ");
        } else if (firstNewRecognized >= 0 && lastOldRecognized >= 0) {
            notes.append(String.format(Locale.ROOT,
                    "%d uncertain frame(s) sit between the two answers; ", uncertainBetween));
        }
        if (Boolean.TRUE.equals(resetReplay.oldAnswerRestabilized())) {
            notes.append("resetting the consensus tracker at the nominal boundary re-stabilizes the "
                    + "OLD answer; ");
        }
        if (unexplainedFrames > 0 || unexplainedStable > 0) {
            notes.append(String.format(Locale.ROOT,
                    "%d recognized frame(s) and %d stable episode(s) of an unexplained answer; ",
                    unexplainedFrames, unexplainedStable));
        }
        return new InterRound(transitionId, sourceId(), resolution(), hackId(),
                nominalBoundaryMs, regionStartMs(), regionEndMs(), rows.size(), oldAnswer, newAnswer,
                frameOrNull(lastOldRecognized), msOrNull(lastOldRecognized),
                frameOrNull(lastOldStable), msOrNull(lastOldStable),
                frameOrNull(firstNewRecognized), msOrNull(firstNewRecognized),
                frameOrNull(firstNewStable), msOrNull(firstNewStable),
                uncertainBetween, uncertainDurationMs, directSwitch,
                lastOldStableToFirstNewStableMs, oldFramesAfterBoundary, oldTimeAfterBoundaryMs,
                unexplainedFrames, unexplainedStable,
                resetReplay.oldAnswerRestabilized(), resetReplay.firstStableTimestampMs(),
                resetReplay.firstStableAnswer(), resetReplay.stableOnsetsAfterReset(),
                trim(notes));
    }

    /**
     * The HACK_ENTRY -> ROUND_1 questions.
     *
     * @param transitionId stable id used by the summary CSV
     * @param roundAnswer annotated answer of the first round of the hack
     * @param hackWindowStartMs approximate start of the hack window, in decoder milliseconds
     * @param runs run compression of this region
     */
    public Entry entry(String transitionId, AnswerIdentity roundAnswer, long hackWindowStartMs,
            List<TransitionRun> runs) {
        Objects.requireNonNull(roundAnswer, "roundAnswer");
        Objects.requireNonNull(runs, "runs");

        int firstRecognized = firstIndex(TransitionTraceRow::recognized);
        int firstCandidate = firstIndex(
                row -> row.consensusState() == LiveRecognitionState.CANDIDATE_RECOGNITION);
        int firstStable = firstIndex(TransitionTraceRow::stable);
        AnswerIdentity stableAnswer = firstStable < 0 ? null : rows.get(firstStable).answer();
        long uncertainBefore = count(0, firstRecognized < 0 ? rows.size() : firstRecognized,
                row -> !row.recognized());
        int lastUncertain = lastIndexBefore(firstRecognized < 0 ? rows.size() : firstRecognized,
                row -> !row.recognized());

        List<AnswerIdentity> transientAnswers = new ArrayList<>();
        long transientFrames = 0;
        if (firstStable >= 0) {
            TreeSet<AnswerIdentity> distinct = new TreeSet<>();
            for (int index = 0; index < firstStable; index++) {
                TransitionTraceRow row = rows.get(index);
                if (row.recognized() && !row.answer().equals(stableAnswer)) {
                    transientFrames++;
                    distinct.add(row.answer());
                }
            }
            transientAnswers.addAll(distinct);
        }
        long firstStableFrame = firstStable < 0 ? Long.MAX_VALUE : rows.get(firstStable).frameIndex();
        long transientStableEpisodes = runs.stream()
                .filter(run -> run.state() == LiveRecognitionState.STABLE_RECOGNIZED)
                .filter(run -> run.startFrameIndex() < firstStableFrame)
                .filter(run -> !roundAnswer.equals(run.answer()))
                .count();
        boolean transientStable = transientStableEpisodes > 0;
        Long entryToFirstStableMs = firstStable < 0
                ? null
                : rows.get(firstStable).timestampMs() - hackWindowStartMs;
        // The entry questions are about the appearance of the puzzle, so an unexplained answer is
        // a recognized frame of something other than the round answer BEFORE the round answer
        // became stable. Counting the rest of the hack would just count the second round.
        long unexplainedFrames = firstStable < 0
                ? countRecognizedOtherThan(List.of(roundAnswer), 0, rows.size())
                : countRecognizedOtherThan(List.of(roundAnswer), 0, firstStable);
        long unexplainedStable = transientStableEpisodes;

        StringBuilder notes = new StringBuilder();
        if (stableAnswer == null) {
            notes.append("no stable answer inside the region; ");
        } else if (!stableAnswer.equals(roundAnswer)) {
            notes.append("the first stable answer is NOT the annotated round answer; ");
        }
        if (transientFrames == 0) {
            notes.append("no transient recognized answer precedes the first stable answer; ");
        } else {
            notes.append(String.format(Locale.ROOT,
                    "%d transient recognized frame(s) of %s precede the first stable answer; ",
                    transientFrames, transientAnswers.stream().map(AnswerIdentity::code)
                            .reduce((a, b) -> a + "|" + b).orElse("(none)")));
        }
        if (transientStable) {
            notes.append("a transient answer became stable before the round answer; ");
        }
        return new Entry(transitionId, sourceId(), resolution(), hackId(), hackWindowStartMs,
                regionStartMs(), regionEndMs(), rows.size(), roundAnswer,
                frameOrNull(firstRecognized), msOrNull(firstRecognized),
                answerOrNull(firstRecognized),
                frameOrNull(firstCandidate), msOrNull(firstCandidate),
                answerOrNull(firstCandidate),
                frameOrNull(firstStable), msOrNull(firstStable), stableAnswer,
                entryToFirstStableMs, uncertainBefore,
                frameOrNull(lastUncertain), msOrNull(lastUncertain),
                transientFrames, transientAnswers, transientStable,
                unexplainedFrames, unexplainedStable, trim(notes));
    }

    /**
     * The ROUND_2 -> HACK_EXIT questions.
     *
     * @param transitionId stable id used by the summary CSV
     * @param roundAnswer annotated answer of the second round of the hack
     * @param nominalHackEndMs approximate end of the hack window, in decoder milliseconds
     * @param runs run compression of this region
     */
    public Exit exit(String transitionId, AnswerIdentity roundAnswer, long nominalHackEndMs,
            List<TransitionRun> runs) {
        Objects.requireNonNull(roundAnswer, "roundAnswer");
        Objects.requireNonNull(runs, "runs");

        int firstStable = firstIndex(row -> row.stable() && roundAnswer.equals(row.answer()));
        int firstStableEnd = firstStable < 0
                ? -1
                : firstIndexFrom(firstStable + 1,
                        row -> !(row.stable() && roundAnswer.equals(row.answer())));
        // Where the round answer disappears: the first frame the pipeline stops recognizing after
        // its FIRST stable episode. Questions about "does it come back" start here, because the
        // LAST stable frame is usually the re-stabilized answer itself.
        int firstDisappearance = firstStableEnd < 0
                ? -1
                : firstIndexFrom(firstStableEnd, row -> !row.recognized());
        int lastRecognized = lastIndexBefore(rows.size(),
                row -> row.recognized() && roundAnswer.equals(row.answer()));
        int lastStable = lastIndexBefore(rows.size(),
                row -> row.stable() && roundAnswer.equals(row.answer()));
        int firstUncertainAfter = lastStable < 0
                ? -1
                : firstIndexFrom(lastStable + 1, row -> !row.recognized());
        boolean reappears = firstDisappearance >= 0 && firstIndexFrom(firstDisappearance + 1,
                row -> row.recognized() && roundAnswer.equals(row.answer())) >= 0;
        boolean restabilizes = firstDisappearance >= 0 && firstIndexFrom(firstDisappearance + 1,
                row -> row.stable() && roundAnswer.equals(row.answer())) >= 0;
        long differentAfterLastStable = lastStable < 0
                ? 0
                : count(lastStable + 1, rows.size(),
                        row -> row.recognized() && !roundAnswer.equals(row.answer()));
        boolean differentAppears = differentAfterLastStable > 0;
        long stableAfterHackEnd = rows.stream()
                .filter(row -> row.stable() && row.timestampMs() >= nominalHackEndMs).count();
        boolean stableAfterEnd = stableAfterHackEnd > 0;
        long stableEpisodesAfterEnd = runs.stream()
                .filter(run -> run.state() == LiveRecognitionState.STABLE_RECOGNIZED)
                .filter(run -> run.startTimestampMs() >= nominalHackEndMs).count();
        // The exit questions are about what the screen shows after the second round left, so an
        // unexplained answer is one that is not the round answer AFTER its last stable frame.
        int zoneStart = lastStable < 0 ? 0 : lastStable + 1;
        long zoneStartFrame = rows.get(Math.min(zoneStart, rows.size() - 1)).frameIndex();
        long unexplainedFrames = countRecognizedOtherThan(List.of(roundAnswer), zoneStart,
                rows.size());
        long unexplainedStable = runs.stream()
                .filter(run -> run.state() == LiveRecognitionState.STABLE_RECOGNIZED)
                .filter(run -> run.startFrameIndex() >= zoneStartFrame)
                .filter(run -> !roundAnswer.equals(run.answer()))
                .count();

        StringBuilder notes = new StringBuilder();
        if (lastStable < 0) {
            notes.append("the round answer never became stable inside the region; ");
        }
        if (firstUncertainAfter < 0) {
            notes.append("the round answer is still stable at the end of the region; ");
        } else {
            notes.append(String.format(Locale.ROOT,
                    "%d uncertain frame(s) between the last stable round answer and the end of the "
                            + "region; ", count(firstUncertainAfter, rows.size(),
                            row -> !row.recognized())));
        }
        if (firstDisappearance < 0) {
            notes.append("the round answer never disappeared inside the region; ");
        } else {
            notes.append(String.format(Locale.ROOT, "the round answer disappears at %.3f s; ",
                    rows.get(firstDisappearance).timestampMs() / 1000.0));
        }
        if (reappears) {
            notes.append("the round answer is recognized again after disappearing; ");
        }
        if (restabilizes) {
            notes.append("the round answer becomes stable again after disappearing; ");
        }
        if (differentAppears) {
            notes.append("a different answer appears after the last stable round answer; ");
        }
        if (stableAfterEnd) {
            notes.append("a stable answer exists at or after the nominal hack end; ");
        }
        return new Exit(transitionId, sourceId(), resolution(), hackId(), nominalHackEndMs,
                regionStartMs(), regionEndMs(), rows.size(), roundAnswer,
                frameOrNull(lastRecognized), msOrNull(lastRecognized),
                frameOrNull(lastStable), msOrNull(lastStable),
                frameOrNull(firstUncertainAfter), msOrNull(firstUncertainAfter),
                frameOrNull(firstDisappearance), msOrNull(firstDisappearance),
                reappears, restabilizes, differentAfterLastStable, differentAppears,
                stableAfterEnd, stableEpisodesAfterEnd, unexplainedFrames, unexplainedStable,
                trim(notes));
    }

    private long countRecognizedOtherThan(List<AnswerIdentity> known, int from, int to) {
        return count(from, to, row -> row.recognized() && !known.contains(row.answer()));
    }

    private static long countStableEpisodesOtherThan(List<TransitionRun> runs,
            List<AnswerIdentity> known) {
        return runs.stream()
                .filter(run -> run.state() == LiveRecognitionState.STABLE_RECOGNIZED)
                .filter(run -> !known.contains(run.answer()))
                .count();
    }

    private Long frameOrNull(int index) {
        return index < 0 ? null : rows.get(index).frameIndex();
    }

    private Long msOrNull(int index) {
        return index < 0 ? null : rows.get(index).timestampMs();
    }

    private AnswerIdentity answerOrNull(int index) {
        return index < 0 ? null : rows.get(index).answer();
    }

    private int firstIndex(Predicate<TransitionTraceRow> predicate) {
        return firstIndexFrom(0, predicate);
    }

    private int firstIndexFrom(int from, Predicate<TransitionTraceRow> predicate) {
        for (int index = Math.max(0, from); index < rows.size(); index++) {
            if (predicate.test(rows.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private int lastIndexBefore(int end, Predicate<TransitionTraceRow> predicate) {
        for (int index = Math.min(end, rows.size()) - 1; index >= 0; index--) {
            if (predicate.test(rows.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private long count(int from, int to, Predicate<TransitionTraceRow> predicate) {
        long total = 0;
        for (int index = Math.max(0, from); index < Math.min(to, rows.size()); index++) {
            if (predicate.test(rows.get(index))) {
                total++;
            }
        }
        return total;
    }

    private static String trim(StringBuilder notes) {
        String text = notes.toString().trim();
        return text.endsWith(";") ? text.substring(0, text.length() - 1) : text;
    }
}
