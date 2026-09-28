package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.ContactSheet;
import io.github.bohdankordon.casinofingerprint.model.FingerprintId;
import io.github.bohdankordon.casinofingerprint.model.RecognitionResult;
import io.github.bohdankordon.casinofingerprint.recognition.RecognitionDecision;
import io.github.bohdankordon.casinofingerprint.recognition.UncertaintyReason;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionStatus;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionIdentity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Per-hack witness state machine: feeds every full-rate frame of one hack window through the
 * production consensus tracker, freezes the content baseline at exactly the frame a future
 * orchestration would consume, and measures every other frame against the frozen baseline.
 *
 * <p>Evaluation only. The analyzer never sends input, never sleeps and never adapts a baseline: a
 * frozen baseline stays frozen for its whole round.
 *
 * <p>Scopes assigned by the state machine (offline evaluation metadata only - the witness never
 * reads them):
 *
 * <pre>
 * window start .. round-1 consumed frame - 1        ENTRY (measured against the round-1 baseline
 *                                                     once that baseline exists)
 * round-1 consumed frame                            BASELINE + EXACT_REPEAT_CONTROL
 * consumed frame + 1 .. first new recognized - 1    SAME_ROUND (round 1 baseline)
 * first new recognized .. first new stable          TRANSITION (round 1 baseline)
 * first new stable + 1 .. + {@value #TRANSITION_OBSERVATION_FRAMES}
 *                                                   TRANSITION_OBSERVATION (still measured
 *                                                     against the OLD baseline; persistence)
 * first new stable + 1 .. last stable of round 2    SAME_ROUND (round 2 baseline)
 * last stable of round 2 + 1 .. window end          EXIT (round 2 baseline)
 * </pre>
 *
 * <p>Ownership: every {@link PuzzleContentBaseline} the analyzer freezes and every contact-sheet
 * thumbnail is owned here and released by {@link #close()}, which is idempotent. Analyzed samples
 * stay owned by the caller and are closed by it immediately, so the analyzer never holds a native
 * profile of an off-baseline frame across frames.
 */
public final class WitnessAnalyzer implements AutoCloseable {
    /** Frames after the first new stable answer still compared against the OLD baseline. */
    public static final int TRANSITION_OBSERVATION_FRAMES = 30;
    private static final int THUMBNAIL_WIDTH = 480;
    private static final int WORST_FRAMES_PER_ROUND = 6;
    private static final int SHEET_FRAME_STRIDE = 5;
    private static final int SHEET_MAX_TILES = 24;
    private static final List<Integer> EXIT_CAPTURE_OFFSETS = List.of(1, 2, 3, 5, 10, 15, 30, 60);

    /** Immutable description of one analyzed hack. */
    public record HackScope(
            String sourceId,
            String resolution,
            int hackId,
            RecognitionIdentity roundOneAnswer,
            RecognitionIdentity roundTwoAnswer) {

        public HackScope {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(resolution, "resolution");
            if (hackId < 1) {
                throw new IllegalArgumentException("hackId must be positive, got " + hackId);
            }
            Objects.requireNonNull(roundOneAnswer, "roundOneAnswer");
            Objects.requireNonNull(roundTwoAnswer, "roundTwoAnswer");
        }

        /** {@code recording_1440p-H1}. */
        public String hackLabel() {
            return sourceId + "-H" + hackId;
        }

        /** {@code H1R1}. */
        public String roundScope(int roundId) {
            return "H" + hackId + "R" + roundId;
        }

        /** {@code recording_1440p-H1R1}. */
        public String baselineId(int roundId) {
            return sourceId + "-" + roundScope(roundId);
        }

        /** {@code H1ENTRY}. */
        public String entryScope() {
            return "H" + hackId + "ENTRY";
        }

        /** {@code H1EXIT}. */
        public String exitScope() {
            return "H" + hackId + "EXIT";
        }

        /** {@code H1R1R2}: transition and persistence scope. */
        public String transitionScope() {
            return "H" + hackId + "R1R2";
        }
    }

    /** Offline event anchors of one hack, in frame indices; null when the event was not observed. */
    public record HackEvents(
            Long roundOneBaselineFrame,
            Long roundOneBaselineTimestampMs,
            Long roundTwoBaselineFrame,
            Long roundTwoBaselineTimestampMs,
            Long lastOldRecognizedFrame,
            Long lastOldStableFrame,
            Long firstNewRecognizedFrame,
            Long firstNewStableFrame,
            Long lastStableRoundTwoFrame,
            Long lastRecognizedRoundTwoFrame,
            Long lastAnalyzedFrame,
            int entryFramesMeasured,
            int featureLessFrames) {
    }

    /** One frame of the real consensus timeline, as plain data, for the counterfactual replay. */
    public record FrameState(
            long frameIndex,
            LiveRecognitionState consensusState,
            RecognitionIdentity identity,
            int streak) {

        public FrameState {
            Objects.requireNonNull(consensusState, "consensusState");
        }
    }

    private final HackScope scope;
    private final PuzzleContentWitness witness;
    private final RecognitionConsensusTracker consensus;
    private final List<FrameRecord> records = new ArrayList<>();
    private final List<ContactSheet.Tile> entryTiles = new ArrayList<>();
    private final List<ContactSheet.Tile> transitionTiles = new ArrayList<>();
    private final List<ContactSheet.Tile> exitTiles = new ArrayList<>();
    private final List<ContactSheet.Tile> worstTiles = new ArrayList<>();
    private final Set<Long> exitCaptureFrames = new LinkedHashSet<>();
    private final Set<Long> worstCaptureFrames = new LinkedHashSet<>();

    private PuzzleContentBaseline roundOneBaseline;
    private PuzzleContentBaseline roundTwoBaseline;
    private Long roundOneBaselineFrame;
    private Long roundOneBaselineTimestampMs;
    private Long roundTwoBaselineFrame;
    private Long roundTwoBaselineTimestampMs;
    private Long lastOldRecognizedFrame;
    private Long lastOldStableFrame;
    private Long firstNewRecognizedFrame;
    private Long firstNewStableFrame;
    private Long lastStableRoundTwoFrame;
    private Long lastRecognizedRoundTwoFrame;
    private ContactSheet.Tile pendingEntryTile;
    private int entryFramesMeasured;
    private long analyzedFrames;
    private boolean sheetsPrepared;
    private boolean closed;

    /**
     * @param scope immutable description of the analyzed hack
     * @param witness content witness; borrowed, owned by the caller
     */
    public WitnessAnalyzer(HackScope scope, PuzzleContentWitness witness) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.witness = Objects.requireNonNull(witness, "witness");
        this.consensus = new RecognitionConsensusTracker(
                RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES);
    }

    /** Analyzed hack description. */
    public HackScope scope() {
        return scope;
    }

    /**
     * Accepts one decoded frame of the hack window, in decode order.
     *
     * @param frameIndex decoded frame index, reporting only
     * @param timestampMs decoded frame timestamp, reporting only
     * @param fullFrame the decoded frame; borrowed and never closed here
     * @param sample analyzed sample of exactly that frame; borrowed, closed by the caller
     */
    public void accept(long frameIndex, long timestampMs, Mat fullFrame,
            PuzzleContentWitness.Sample sample) {
        requireOpen();
        Objects.requireNonNull(fullFrame, "fullFrame");
        Objects.requireNonNull(sample, "sample");
        analyzedFrames++;
        RecognitionDecision decision = sample.decision();
        LiveRecognitionStatus status = consensus.accept(decision);
        FrameRecord record = new FrameRecord(frameIndex, timestampMs, status, decision);
        records.add(record);

        RecognitionIdentity stableIdentity = stableIdentityOf(status);
        if (roundOneBaseline == null && scope.roundOneAnswer().equals(stableIdentity)) {
            freezeRoundOne(frameIndex, timestampMs, sample);
        } else if (roundOneBaseline != null && roundTwoBaseline == null
                && scope.roundTwoAnswer().equals(stableIdentity)) {
            freezeRoundTwo(frameIndex, timestampMs, sample);
        }
        trackAnchors(record);
        measure(record, sample);
        captureOnlineThumbnails(record, fullFrame);
    }

    /** Rows of the whole hack, one per scope measurement, in frame order. */
    public List<WitnessFrameRow> rows() {
        requireOpen();
        List<WitnessFrameRow> rows = new ArrayList<>(records.size());
        for (FrameRecord record : records) {
            if (isFrame(roundOneBaselineFrame, record.frameIndex)
                    && record.activeFeatures != null) {
                rows.add(row(record, scope.roundScope(1), WitnessScope.BASELINE,
                        record.activeFeatures, scope.baselineId(1)));
                rows.add(row(record, scope.roundScope(1), WitnessScope.EXACT_REPEAT_CONTROL,
                        record.activeFeatures, scope.baselineId(1)));
                continue;
            }
            if (record.controlRow && record.activeFeatures != null) {
                if (record.observationFeatures != null) {
                    rows.add(row(record, scope.transitionScope(), WitnessScope.TRANSITION,
                            record.observationFeatures, scope.baselineId(1)));
                }
                rows.add(row(record, scope.roundScope(2), WitnessScope.EXACT_REPEAT_CONTROL,
                        record.activeFeatures, scope.baselineId(2)));
                continue;
            }
            if (record.activeFeatures != null) {
                rows.add(row(record, roundScopeOf(record), scopeOf(record), record.activeFeatures,
                        record.activeBaselineId));
            }
            if (record.observationFeatures != null) {
                rows.add(row(record, scope.transitionScope(), WitnessScope.TRANSITION_OBSERVATION,
                        record.observationFeatures, scope.baselineId(1)));
            }
        }
        return List.copyOf(rows);
    }

    /** Offline anchor frames the analysis reports and evaluates rules against. */
    public HackEvents events() {
        requireOpen();
        return new HackEvents(roundOneBaselineFrame, roundOneBaselineTimestampMs,
                roundTwoBaselineFrame, roundTwoBaselineTimestampMs, lastOldRecognizedFrame,
                lastOldStableFrame, firstNewRecognizedFrame, firstNewStableFrame,
                lastStableRoundTwoFrame, lastRecognizedRoundTwoFrame, lastAnalyzedFrame(),
                entryFramesMeasured, featureLessFrames());
    }

    /** Frozen round-1 baseline, when the round reached a consumed stable answer. */
    public PuzzleContentBaseline roundOneBaseline() {
        requireOpen();
        return roundOneBaseline;
    }

    /** Frozen round-2 baseline, when the next round reached a consumed stable answer. */
    public PuzzleContentBaseline roundTwoBaseline() {
        requireOpen();
        return roundTwoBaseline;
    }

    /** Frame index of the baseline frozen for {@code roundId}; null when it was never frozen. */
    public Long baselineFrame(int roundId) {
        return roundId == 1 ? roundOneBaselineFrame : roundTwoBaselineFrame;
    }

    /** Frames that never received a baseline measurement. */
    public int featureLessFrames() {
        int missing = 0;
        for (FrameRecord record : records) {
            if (record.activeFeatures == null) {
                missing++;
            }
        }
        return missing;
    }

    /** Frames analyzed for this hack. */
    public long analyzedFrames() {
        return analyzedFrames;
    }

    /**
     * Frame indices before the round-1 consumption point. They have no baseline at the time they
     * are decoded, so their features are measured in the decode-only second pass by
     * {@link #acceptEntryFrame(long, PuzzleContentWitness.Sample)}.
     *
     * @return entry frame indices in decode order; empty when no round-1 baseline was frozen
     */
    public List<Long> entryFrameIndexes() {
        requireOpen();
        if (roundOneBaselineFrame == null) {
            return List.of();
        }
        List<Long> frames = new ArrayList<>();
        for (FrameRecord record : records) {
            if (record.frameIndex < roundOneBaselineFrame) {
                frames.add(record.frameIndex);
            }
        }
        return List.copyOf(frames);
    }

    /**
     * Measures one pre-baseline frame against the frozen round-1 baseline. Called by the
     * decode-only second pass, which is the only place where those frames can be compared to a
     * baseline that did not exist yet when they were decoded.
     *
     * @param frameIndex index of the decoded frame
     * @param sample analyzed sample of exactly that frame; borrowed, closed by the caller
     */
    public void acceptEntryFrame(long frameIndex, PuzzleContentWitness.Sample sample) {
        requireOpen();
        Objects.requireNonNull(sample, "sample");
        FrameRecord record = recordAt(frameIndex);
        if (record == null || record.activeFeatures != null) {
            return;
        }
        if (roundOneBaseline == null) {
            throw new IllegalStateException("Entry frames can only be measured after the round-1 "
                    + "baseline of " + scope.hackLabel() + " was frozen");
        }
        record.activeBaselineId = scope.baselineId(1);
        record.activeFeatures = witness.measure(sample, roundOneBaseline);
        entryFramesMeasured++;
    }

    /**
     * The real per-frame consensus timeline of this hack: one entry per analyzed frame, in decode
     * order. The counterfactual identity replay consumes exactly this timeline.
     */
    public List<FrameState> frameStates() {
        requireOpen();
        List<FrameState> states = new ArrayList<>(records.size());
        for (FrameRecord record : records) {
            states.add(new FrameState(record.frameIndex, record.consensusState, record.identity,
                    record.streak));
        }
        return List.copyOf(states);
    }

    /**
     * Computes the exact frame set of the deterministic review sheets that need knowledge of the
     * whole hack: the worst same-round deviations of each round and the exit anchors.
     *
     * @param worstFramesPerRound tiles kept per round scope
     * @return the frame indices the second (decode-only) pass must deliver to
     *         {@link #captureSheetFrame(long, Mat)}, in capture order
     */
    public List<Long> prepareContactSheets(int worstFramesPerRound) {
        requireOpen();
        if (worstFramesPerRound < 0) {
            throw new IllegalArgumentException(
                    "worstFramesPerRound must not be negative, got " + worstFramesPerRound);
        }
        sheetsPrepared = true;
        exitCaptureFrames.clear();
        worstCaptureFrames.clear();
        if (lastStableRoundTwoFrame != null) {
            exitCaptureFrames.add(lastStableRoundTwoFrame);
            for (int offset : EXIT_CAPTURE_OFFSETS) {
                exitCaptureFrames.add(lastStableRoundTwoFrame + offset);
            }
            if (lastRecognizedRoundTwoFrame != null) {
                exitCaptureFrames.add(lastRecognizedRoundTwoFrame);
            }
            exitCaptureFrames.add(lastAnalyzedFrame());
        }
        worstCaptureFrames.addAll(worstSameRoundFrames(worstFramesPerRound, 1));
        worstCaptureFrames.addAll(worstSameRoundFrames(worstFramesPerRound, 2));
        LinkedHashSet<Long> requested = new LinkedHashSet<>(exitCaptureFrames);
        requested.addAll(worstCaptureFrames);
        requested.remove(null);
        return List.copyOf(requested);
    }

    /**
     * Captures one frame requested by {@link #prepareContactSheets(int)} during the decode-only
     * second pass.
     *
     * @param frameIndex index of the decoded frame
     * @param fullFrame the decoded frame; borrowed and never closed here
     */
    public void captureSheetFrame(long frameIndex, Mat fullFrame) {
        requireOpen();
        if (!sheetsPrepared) {
            throw new IllegalStateException(
                    "prepareContactSheets must run before frames are captured");
        }
        FrameRecord record = recordAt(frameIndex);
        if (record == null) {
            return;
        }
        if (exitCaptureFrames.contains(frameIndex)) {
            exitTiles.add(tile("exit f" + label(record), fullFrame));
        }
        if (worstCaptureFrames.contains(frameIndex) && record.activeFeatures != null
                && scopeOf(record) == WitnessScope.SAME_ROUND) {
            worstTiles.add(tile(String.format(Locale.ROOT, "%s f%d %.3fs minRegion %.3f",
                    roundScopeOf(record), record.frameIndex, record.timestampMs / 1000.0,
                    record.activeFeatures.minimumRegionSimilarity()), fullFrame));
        }
    }

    /**
     * Writes the local review contact sheets of this hack. The sheets show untouched recording
     * frames: they are private review material and are only ever written below the ignored output
     * directory.
     *
     * @param directory target directory of the sheets
     * @return written sheet paths, in writing order
     */
    public List<Path> writeContactSheets(Path directory) throws IOException {
        requireOpen();
        Objects.requireNonNull(directory, "directory");
        List<Path> written = new ArrayList<>(4);
        addSheet(written, directory, "entry",
                "entry: window start to the consumed frame", entryTilesWithTail());
        addSheet(written, directory, "transition",
                "transition: last old frames, first new recognized, first new stable, persistence",
                transitionTiles);
        addSheet(written, directory, "exit", "exit: last stable frame to the window end",
                exitTiles);
        addSheet(written, directory, "worst-same-round",
                "largest same-round deviation (top " + WORST_FRAMES_PER_ROUND
                        + " per round by weakest region similarity)", worstTiles);
        return List.copyOf(written);
    }

    /** Releases the baselines, every retained sample and every thumbnail. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeQuietly(roundOneBaseline, failure);
        failure = closeQuietly(roundTwoBaseline, failure);
        for (List<ContactSheet.Tile> tiles : List.of(entryTiles, transitionTiles, exitTiles,
                worstTiles)) {
            for (ContactSheet.Tile tile : tiles) {
                failure = closeQuietly(tile.frame(), failure);
            }
            tiles.clear();
        }
        failure = closeQuietly(pendingEntryTile == null ? null : pendingEntryTile.frame(), failure);
        pendingEntryTile = null;
        if (failure != null) {
            throw failure;
        }
    }

    private void freezeRoundOne(long frameIndex, long timestampMs,
            PuzzleContentWitness.Sample sample) {
        roundOneBaseline = witness.freeze(scope.baselineId(1), frameIndex, timestampMs, sample);
        roundOneBaselineFrame = frameIndex;
        roundOneBaselineTimestampMs = timestampMs;
        FrameRecord baseline = records.get(records.size() - 1);
        baseline.activeBaselineId = scope.baselineId(1);
        baseline.activeFeatures = witness.measure(sample, roundOneBaseline);
    }

    private void freezeRoundTwo(long frameIndex, long timestampMs,
            PuzzleContentWitness.Sample sample) {
        roundTwoBaseline = witness.freeze(scope.baselineId(2), frameIndex, timestampMs, sample);
        roundTwoBaselineFrame = frameIndex;
        roundTwoBaselineTimestampMs = timestampMs;
        firstNewStableFrame = frameIndex;
        FrameRecord record = records.get(records.size() - 1);
        record.controlRow = true;
        record.observationBaselineId = scope.baselineId(1);
        record.observationFeatures = witness.measure(sample, roundOneBaseline);
        record.activeBaselineId = scope.baselineId(2);
        record.activeFeatures = witness.measure(sample, roundTwoBaseline);
    }

    private void trackAnchors(FrameRecord record) {
        if (record.identity == null) {
            return;
        }
        if (roundTwoBaseline == null) {
            if (record.identity.equals(scope.roundTwoAnswer())) {
                if (firstNewRecognizedFrame == null) {
                    firstNewRecognizedFrame = record.frameIndex;
                }
            } else if (record.identity.equals(scope.roundOneAnswer())) {
                lastOldRecognizedFrame = record.frameIndex;
                if (record.consensusState == LiveRecognitionState.STABLE_RECOGNIZED) {
                    lastOldStableFrame = record.frameIndex;
                }
            }
            return;
        }
        if (record.identity.equals(scope.roundTwoAnswer())) {
            lastRecognizedRoundTwoFrame = record.frameIndex;
            if (record.consensusState == LiveRecognitionState.STABLE_RECOGNIZED) {
                lastStableRoundTwoFrame = record.frameIndex;
            }
        }
    }

    private void measure(FrameRecord record, PuzzleContentWitness.Sample sample) {
        if (roundOneBaseline == null) {
            // No baseline exists yet: an entry frame, measured in the decode-only second pass.
            return;
        }
        if (record.controlRow) {
            return;
        }
        if (roundTwoBaseline == null) {
            record.activeBaselineId = scope.baselineId(1);
            record.activeFeatures = witness.measure(sample, roundOneBaseline);
            return;
        }
        record.activeBaselineId = scope.baselineId(2);
        record.activeFeatures = witness.measure(sample, roundTwoBaseline);
        long offset = record.frameIndex - roundTwoBaselineFrame;
        if (offset > 0 && offset <= TRANSITION_OBSERVATION_FRAMES) {
            record.observationBaselineId = scope.baselineId(1);
            record.observationFeatures = witness.measure(sample, roundOneBaseline);
        }
    }

    private void captureOnlineThumbnails(FrameRecord record, Mat fullFrame) {
        if (roundOneBaseline == null) {
            if (entryTiles.size() < SHEET_MAX_TILES
                    && (entryTiles.isEmpty() || record.frameIndex % SHEET_FRAME_STRIDE == 0)) {
                entryTiles.add(tile("entry f" + label(record), fullFrame));
            }
            pendingEntryTile = replaceTile(pendingEntryTile,
                    "last pre-baseline f" + label(record), fullFrame);
            return;
        }
        if (record.controlRow) {
            transitionTiles.add(tile("first new stable f" + label(record), fullFrame));
            return;
        }
        if (roundTwoBaseline != null) {
            long offset = record.frameIndex - roundTwoBaselineFrame;
            if (offset > 0 && offset <= TRANSITION_OBSERVATION_FRAMES
                    && (offset == 5 || offset == 15 || offset == TRANSITION_OBSERVATION_FRAMES)) {
                transitionTiles.add(tile("new stable +" + offset + " f" + label(record), fullFrame));
            }
            return;
        }
        if (record.identity == null) {
            return;
        }
        if (record.identity.equals(scope.roundTwoAnswer())) {
            if (isFrame(firstNewRecognizedFrame, record.frameIndex)) {
                transitionTiles.add(tile("first new recognized f" + label(record), fullFrame));
            }
            return;
        }
        if (record.identity.equals(scope.roundOneAnswer())) {
            replaceAt(transitionTiles, 0, "last old stable f" + label(record), fullFrame,
                    record.consensusState == LiveRecognitionState.STABLE_RECOGNIZED);
            replaceAt(transitionTiles, 1, "last old recognized f" + label(record), fullFrame, true);
        }
    }

    /** Frames of the same-round population of {@code roundId} with the weakest region similarity. */
    private List<Long> worstSameRoundFrames(int perRound, int roundId) {
        if (perRound == 0) {
            return List.of();
        }
        List<FrameRecord> candidates = new ArrayList<>();
        for (FrameRecord record : records) {
            if (record.activeFeatures == null || scopeOf(record) != WitnessScope.SAME_ROUND) {
                continue;
            }
            if (!roundScopeOf(record).equals(scope.roundScope(roundId))) {
                continue;
            }
            candidates.add(record);
        }
        candidates.sort(Comparator.comparingDouble(
                record -> record.activeFeatures.minimumRegionSimilarity()));
        List<Long> frames = new ArrayList<>(perRound);
        for (FrameRecord record : candidates) {
            if (frames.size() >= perRound) {
                break;
            }
            frames.add(record.frameIndex);
        }
        return frames;
    }

    private FrameRecord recordAt(long frameIndex) {
        for (FrameRecord record : records) {
            if (record.frameIndex == frameIndex) {
                return record;
            }
        }
        return null;
    }

    private Long lastAnalyzedFrame() {
        return records.isEmpty() ? null : records.get(records.size() - 1).frameIndex;
    }

    private static boolean isFrame(Long frame, long frameIndex) {
        return frame != null && frame == frameIndex;
    }

    private List<ContactSheet.Tile> entryTilesWithTail() {
        List<ContactSheet.Tile> tiles = new ArrayList<>(entryTiles);
        if (pendingEntryTile != null) {
            tiles.add(pendingEntryTile);
        }
        return tiles;
    }

    private void addSheet(List<Path> written, Path directory, String kind, String title,
            List<ContactSheet.Tile> tiles) throws IOException {
        if (tiles.isEmpty()) {
            return;
        }
        Path output = directory.resolve(scope.hackLabel() + "-" + kind + ".png");
        ContactSheet.write(List.copyOf(tiles), 4, THUMBNAIL_WIDTH,
                scope.hackLabel() + " " + title, output);
        written.add(output);
    }

    /** Replaces a slot when {@code replace} is true, otherwise leaves the tiles alone. */
    private void replaceAt(List<ContactSheet.Tile> tiles, int index, String label, Mat fullFrame,
            boolean replace) {
        if (!replace) {
            return;
        }
        while (tiles.size() <= index) {
            tiles.add(null);
        }
        ContactSheet.Tile previous = tiles.get(index);
        tiles.set(index, tile(label, fullFrame));
        if (previous != null) {
            previous.frame().close();
        }
    }

    private ContactSheet.Tile replaceTile(ContactSheet.Tile previous, String label, Mat fullFrame) {
        ContactSheet.Tile replacement = tile(label, fullFrame);
        if (previous != null) {
            previous.frame().close();
        }
        return replacement;
    }

    private ContactSheet.Tile tile(String label, Mat fullFrame) {
        Mat thumbnail = new Mat();
        try {
            opencv_imgproc.resize(fullFrame, thumbnail,
                    new Size(THUMBNAIL_WIDTH,
                            Math.max(1, THUMBNAIL_WIDTH * fullFrame.rows() / fullFrame.cols())),
                    0, 0, opencv_imgproc.INTER_AREA);
            return new ContactSheet.Tile(label, thumbnail);
        } catch (RuntimeException e) {
            thumbnail.close();
            throw e;
        }
    }

    private static String label(FrameRecord record) {
        return String.format(Locale.ROOT, "%d %.3fs", record.frameIndex,
                record.timestampMs / 1000.0);
    }

    private WitnessFrameRow row(FrameRecord record, String roundScope, WitnessScope rowScope,
            PuzzleContentFeatures features, String baselineId) {
        return new WitnessFrameRow(scope.sourceId(), scope.resolution(), scope.hackId(), roundScope,
                baselineId, rowScope, record.frameIndex, record.timestampMs, record.status,
                record.identityCode, record.consensusState, record.streak,
                record.uncertaintyReasons, features);
    }

    private String roundScopeOf(FrameRecord record) {
        if (roundOneBaselineFrame == null || record.frameIndex < roundOneBaselineFrame) {
            return scope.entryScope();
        }
        if (roundTwoBaselineFrame == null || record.frameIndex <= roundTwoBaselineFrame) {
            return scope.roundScope(1);
        }
        if (lastStableRoundTwoFrame != null && record.frameIndex > lastStableRoundTwoFrame) {
            return scope.exitScope();
        }
        return scope.roundScope(2);
    }

    private WitnessScope scopeOf(FrameRecord record) {
        if (roundOneBaselineFrame == null || record.frameIndex < roundOneBaselineFrame) {
            return WitnessScope.ENTRY;
        }
        if (isFrame(roundOneBaselineFrame, record.frameIndex)) {
            return WitnessScope.BASELINE;
        }
        if (roundTwoBaselineFrame == null || firstNewRecognizedFrame == null
                || record.frameIndex < firstNewRecognizedFrame) {
            return WitnessScope.SAME_ROUND;
        }
        if (record.frameIndex <= roundTwoBaselineFrame) {
            return WitnessScope.TRANSITION;
        }
        if (lastStableRoundTwoFrame != null && record.frameIndex > lastStableRoundTwoFrame) {
            return WitnessScope.EXIT;
        }
        return WitnessScope.SAME_ROUND;
    }

    private static RecognitionIdentity stableIdentityOf(LiveRecognitionStatus status) {
        if (status.state() != LiveRecognitionState.STABLE_RECOGNIZED) {
            return null;
        }
        FingerprintId fingerprint = status.fingerprint().orElseThrow();
        return RecognitionIdentity.of(fingerprint, status.selectedCandidates());
    }

    private static RuntimeException closeQuietly(AutoCloseable closeable,
            RuntimeException failure) {
        if (closeable == null) {
            return failure;
        }
        try {
            closeable.close();
        } catch (RuntimeException e) {
            return failure == null ? e : failure;
        } catch (Exception e) {
            return failure == null ? new IllegalStateException(e) : failure;
        }
        return failure;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Witness analyzer for " + scope.hackLabel()
                    + " is closed; its baselines are released");
        }
    }

    /** One analyzed frame, before scope assignment. */
    private static final class FrameRecord {
        private final long frameIndex;
        private final long timestampMs;
        private final RecognitionResult.Status status;
        private final RecognitionIdentity identity;
        private final String identityCode;
        private final LiveRecognitionState consensusState;
        private final int streak;
        private final List<UncertaintyReason> uncertaintyReasons;
        private PuzzleContentFeatures activeFeatures;
        private String activeBaselineId = "";
        private PuzzleContentFeatures observationFeatures;
        private String observationBaselineId = "";
        private boolean controlRow;

        private FrameRecord(long frameIndex, long timestampMs, LiveRecognitionStatus status,
                RecognitionDecision decision) {
            this.frameIndex = frameIndex;
            this.timestampMs = timestampMs;
            this.status = decision.result().status();
            this.identity = status.fingerprint()
                    .map(fingerprint -> RecognitionIdentity.of(fingerprint,
                            status.selectedCandidates()))
                    .orElse(null);
            this.identityCode = identity == null ? "" : identity.code();
            this.consensusState = status.state();
            this.streak = status.streak();
            this.uncertaintyReasons = decision.uncertaintyReasons();
        }
    }

}
