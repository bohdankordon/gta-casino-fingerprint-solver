# Real gameplay recording benchmark (Stage 6A/6B)

Stage 6A/6B measures the recognition system that already exists on main against two real gameplay
screen recordings. It is a MEASUREMENT stage, not a tuning stage:

- no matcher, radius or scorer changed;
- no normalization step changed;
- no recognition policy threshold changed;
- no 2560x1440 ROI changed;
- no live-runtime capture or resolution support changed.

The numbers below were produced by the frozen system. Every failure they contain is reported as
measured; nothing was tuned to make the dataset pass, and Stage 6C/6D (any change at all) has not
started.

## How a nominal-round disagreement is read

The round annotations are approximate by a few tenths of a second, so the first frames of an
interval can still show the previous round while the annotation has already crossed into the next
one. Reporting every such frame as a recognition error would be a claim the ground truth cannot
support. Each recognized frame inside a nominal round is therefore classified as:

| category | meaning |
| --- | --- |
| CURRENT_ROUND_MATCH | the prediction equals the nominal round's target AND candidate set |
| PREVIOUS_ROUND_CARRYOVER | the prediction equals the immediately preceding annotated round of the SAME source and the SAME hack |
| UNEXPLAINED_MISMATCH | the prediction matches neither: the high-severity recognition error category |
| UNCERTAIN | the policy refused to recognize the frame |

"Previous round" is strictly the immediately preceding annotation of the same source and the same
hack: carryover never crosses a hack boundary, and the first round of a hack has no previous round,
so a disagreement there is always unexplained. No time tolerance is used anywhere in this rule - the
distinction is purely which annotated answer the prediction equals - so a prediction is never
silently promoted to carryover. Every raw value (frame index, timestamp, predicted target, predicted
candidate set, evidence) stays in the per-frame data either way.

## Dataset

Two independent recordings of the complete Diamond Casino Heist vault segment (ordinary gameplay,
several fingerprint door hacks, several rounds per hack, selected and unselected candidate states,
selector movement, round transitions and one real wrong selection with a game-side ERROR).

| source id | resolution | container | fps | frames | duration | hacks | rounds |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `recording_1440p` | 2560x1440 | Matroska (AV1) | 30.000030 | 6366 | 212.200 s | 2 | 4 |
| `recording_1080p` | 1920x1080 | MP4 (H.264) | 28.999881 | 6065 | 209.139 s | 2 | 4 |

The recordings are PRIVATE LOCAL material. They live in the ignored `local-data/stage6/` directory,
are never committed, never copied into fixtures or test resources, never uploaded and never attached
to a pull request. Only their technical metadata (generic source id, plain file name, container,
resolution, fps, decoded frame count, derived duration, byte size, SHA-256) is committed, in
`fixtures/gameplay/recordings/stage6-sources.csv`. The benchmark verifies size and SHA-256 before it
measures anything and refuses to run when they differ.

Round-level ground truth lives in `fixtures/gameplay/recordings/stage6-rounds.csv` with its
provenance in `fixtures/gameplay/recordings/README.md`. It was established by hand from the
recordings, independently of the recognition system; production predictions were never used to
create or modify it.

Fingerprint coverage of these recordings:

| fingerprint | rounds |
| --- | --- |
| FP_1 | 2 |
| FP_2 | 0 |
| FP_3 | 4 |
| FP_4 | 2 |

`FP_2` has NO real-game coverage here. These recordings say nothing about FP_2 robustness, and the
absence of FP_2 failures must not be read as evidence that FP_2 works.

## Running it

The recordings must exist locally, then:

    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingBenchmarkMain" "-Dexec.args=--exhaustive-negative"

Useful options: `--source <source_id>`, `--negative-fps <n>`, `--negative-padding <s>`,
`--no-contact-sheets` and `--decode-sanity` (a repeated open/decode/close resource check). Every
artifact is written below the ignored `target/` tree; nothing is committed and no frame image leaves
the machine.

## Populations

Three populations are measured separately and are never merged into one accuracy number:

| population | selection | cadence |
| --- | --- | --- |
| positive | every decoded frame inside an annotated round interval | all frames of the source |
| negative (required) | gameplay outside every hack window padded by 2.0 s | deterministic frame-index cadence at 5 fps |
| negative (exhaustive, optional) | the same gameplay, every decoded frame | all frames |

The 5 fps cadence is an integer frame step, `step = max(1, round(fps / 5))`, applied to zero-based
frame indices: step 6 for both recordings, 5.0000 fps effective for the 1440p source and 4.8333 fps
for the 1080p source. It is pure arithmetic, so the same recording always yields the same sample.

Per frame the benchmark classifies exactly one of `CORRECT_RECOGNIZED`, `WRONG_RECOGNIZED` or
`UNCERTAIN` for positive frames, and `FALSE_RECOGNIZED` or `UNCERTAIN` for negative frames.
`UNCERTAIN` is a refusal by the policy, is never counted as a wrong answer and is never called
"puzzle absent".

## Measured baseline

Positive frames (every frame of the eight annotated rounds):

| source | resolution | frames | current-round match | previous-round carryover | unexplained mismatch | uncertain |
| --- | --- | --- | --- | --- | --- | --- |
| recording_1440p | 2560x1440 | 1499 | 1491 (99.47%) | 1 (0.07%) | 0 | 7 |
| recording_1080p | 1920x1080 | 2255 | 2188 (97.03%) | 35 (1.55%) | 0 | 32 |

Per round:

| source | round | frames | current | carryover | unexplained | uncertain | target |
| --- | --- | --- | --- | --- | --- | --- | --- |
| recording_1440p | H1R1 | 487 | 482 | 0 | 0 | 5 | FP_4 |
| recording_1440p | H1R2 | 457 | 457 | 0 | 0 | 0 | FP_3 |
| recording_1440p | H2R1 | 300 | 298 | 0 | 0 | 2 | FP_1 |
| recording_1440p | H2R2 | 255 | 254 | 1 | 0 | 0 | FP_3 |
| recording_1080p | H1R1 | 507 | 494 | 0 | 0 | 13 | FP_3 |
| recording_1080p | H1R2 | 725 | 708 | 17 | 0 | 0 | FP_1 |
| recording_1080p | H2R1 | 515 | 502 | 0 | 0 | 13 | FP_3 |
| recording_1080p | H2R2 | 508 | 484 | 18 | 0 | 6 | FP_4 |

Negative gameplay:

| source | sampled frames | false recognized | exhaustive frames | exhaustive false recognized |
| --- | --- | --- | --- | --- |
| recording_1440p | 746 | 0 | 4476 | 0 |
| recording_1080p | 585 | 0 | 3513 | 0 |

Zero false positives across 1331 sampled and 7989 exhaustive negative frames: on ordinary vault
gameplay the frozen policy never produced a recognized answer, and it never produced a stable one
either (see below). This is the strongest positive result of the baseline.

Worst and typical evidence over every positive frame (nearest-rank percentiles, no interpolation):

| metric | 1440p worst | 1440p p05 | 1440p median | 1080p worst | 1080p p05 | 1080p median |
| --- | --- | --- | --- | --- | --- | --- |
| best target score | 0.0976 | 0.5181 | 0.6400 | 0.0034 | 0.5014 | 0.6137 |
| target margin | 0.0024 | 0.2975 | 0.4354 | 0.0001 | 0.2971 | 0.4024 |
| best assignment mean | 0.0748 | 0.8987 | 0.9610 | 0.0575 | 0.9127 | 0.9599 |
| weakest assigned pair | 0.0283 | 0.7110 | 0.9457 | 0.0292 | 0.7700 | 0.9430 |
| selection margin | 0.0005 | 0.1369 | 0.1899 | 0.0001 | 0.1754 | 0.1943 |
| min fragment column margin | -0.0085 | 0.5476 | 0.7571 | -0.0437 | 0.5781 | 0.7772 |
| evidence strength | 0.0283 | 0.5181 | 0.6400 | 0.0034 | 0.5014 | 0.6137 |

The worst values all come from round-boundary frames where the puzzle is fading in or out, which is
also where every uncertain frame sits:

- 28 uncertain frames fail every gate with target scores of 0.003-0.14: these are the first frames
  of an annotated interval, when the screen is still transitioning into the puzzle.
- 11 uncertain frames fail only `TARGET_SCORE_TOO_LOW` with scores of 0.32-0.35, just under the
  0.35 gate: these sit at the end of a round, when the puzzle is being replaced by the round result.

No frame in the middle of a round stayed uncertain.

## The 36 nominal-round annotation disagreements

Every disagreement is listed in `target/stage6-benchmark-report.txt` with its frame index, timestamp,
nominal answer, previous-round answer and prediction. All 36 are the same finding:

    36 nominal-round annotation disagreements
      - 36 previous-round carryover
      -  0 unexplained mismatches

Each of them:

- sits 0.016-0.61 s after the approximate start of a round;
- predicts exactly the PREVIOUS annotated round's target AND candidate set;
- is decoded from a frame where the screen still shows the previous round, because the annotation
  boundary is approximate by design.

The frozen matcher is therefore not misreading a puzzle: it is reading the previous puzzle correctly
while the approximate interval already counts the frame as part of the next round. Calling that a
matcher failure would be a claim the ground truth cannot support, which is exactly why the benchmark
separates carryover from an unexplained mismatch. Nothing was excluded, no interval was trimmed and
no timestamp was moved: the same 36 frames are in the per-frame data with their raw predictions.

An unexplained mismatch would be a frame whose prediction equals neither the nominal nor the
previous round's answer; on this dataset there are none, on either resolution.

## Consensus replay

Every per-frame decision sequence is replayed through the UNMODIFIED production
`RecognitionConsensusTracker` with its default requirement of three consecutive identical recognized
answers. One event is recorded per stable episode onset, because the tracker keeps reporting the
same stable answer for every frame of an episode.

Stable episodes are classified with the same rule as single frames, plus two location categories:
`STABLE_UNLABELED_TRANSITION` for a stable answer inside a hack window - or inside its padded
transition zone - but outside every approximate round interval, where no frame-exact round ground
truth exists, and `STABLE_FALSE` for a stable answer in strict negative gameplay.

| scope | stable correct | previous-round carryover | unexplained mismatch | unlabeled transition | stable false | first stable correct | latency from round start |
| --- | --- | --- | --- | --- | --- | --- | --- |
| recording_1440p H1R1 | yes | no | no | no | no | 17.600 s | 100 ms |
| recording_1440p H1R2 | yes | no | no | no | no | 34.100 s | 100 ms |
| recording_1440p H2R1 | yes | no | no | no | no | 119.400 s | 150 ms |
| recording_1440p H2R2 | yes | no | no | no | no | 129.867 s | 117 ms |
| recording_1080p H1R1 | yes | no | no | no | no | 40.791 s | 541 ms |
| recording_1080p H1R2 | yes | yes | no | no | no | 58.929 s | 679 ms |
| recording_1080p H2R1 | yes | no | no | no | no | 134.033 s | 533 ms |
| recording_1080p H2R2 | yes | yes | no | no | no | 152.205 s | 705 ms |
| recording_1440p strict negative | n/a | n/a | n/a | n/a | NO | - | - |
| recording_1080p strict negative | n/a | n/a | n/a | n/a | NO | - | - |
| recording_1440p full replay | yes | no | no | no | no | - | - |
| recording_1080p full replay | yes | no | no | no | no | - | - |

All eight rounds reach a stable correct answer, within 100-150 ms on the 1440p recording and within
0.53-0.71 s on the evaluation-only 1080p geometry:

    2 stable previous-round carryover episodes
    0 stable unexplained mismatches
    0 stable false answers

The two carryover episodes are the same boundary effect as the disagreeing frames: at the very start
of the annotated 1080p H1R2 and H2R2 intervals the previous round is still on screen, so the tracker
correctly stabilizes the previous round's answer for a few frames before the new puzzle appears
(58.343 s and 151.585 s). Both rounds then stabilize the correct answer as well (58.929 s and
152.205 s). No stable false answer occurred anywhere, and the low-rate replay over both complete
recordings found no stale consensus across a transition and no stable answer outside the hack
windows.

## 1080p: evaluation-only geometry hypothesis

The 1080p recording has the same 16:9 aspect ratio as the production layout, so Stage 6B evaluates
exactly one predetermined hypothesis: scale the production layout uniformly by
`1920 / 2560 = 1080 / 1440 = 0.75`, scaling RECTANGLE EDGES rather than width and height
independently:

    scaledLeft   = round(left   * 0.75)
    scaledRight  = round(right  * 0.75)
    scaledTop    = round(top    * 0.75)
    scaledBottom = round(bottom * 0.75)
    scaledWidth  = scaledRight  - scaledLeft
    scaledHeight = scaledBottom - scaledTop

The derived manifest is written to `target/stage6-derived-layout-1920x1080.csv` and read back through
the production `GameplayLayout` reader, so it passes the same contract as a bundled manifest (one
target, candidates 0..7, every region inside the frame). The frame stays native 1920x1080: nothing is
resized, no ROI is tuned and no coordinate is shifted. The result is used only by this benchmark.

LiveRecognitionMain still supports 2560x1440 only. 1080p is NOT production support, and this stage
makes no claim that the 0.75 hypothesis generalizes to other UI scales or aspect ratios.

The measured result on that hypothesis is 97.03% current-round matches, 1.55% previous-round
carryover, 0 unexplained mismatches and 1.42% uncertain on 2255 frames with zero false positives,
i.e. the hypothesis is promising but unproven, and it is still missing the one thing production
support would need: a user-captured manifest for that resolution.

## The real wrong-selection round

`recording_1080p` hack 1 round 2 contains the genuine player mistake: candidate 5 was selected
incorrectly, the game displayed ERROR, C5 cleared while the other correct selections stayed, and the
correct candidate 0 was selected afterwards. The annotated answer stays `FP_1 0;4;6;7`; the
wrong-selection state is not ground truth, and the ERROR-era frames were deliberately kept inside the
benchmarked interval.

Measured behaviour around the episode (dense 0.25 s contact sheet, visual inspection; no OCR was
used and the ERROR timing is not machine-labeled):

- the ERROR banner is visible from approximately t = 74.0 s to t = 77.0 s of that round;
- recognition stayed `CORRECT_RECOGNIZED FP_1 [0;4;6;7]` for the whole episode and for every frame
  from 58.86 s to 83.24 s (708 consecutive frames);
- the only wrong frames in the round are the 17 transition frames at its start, which carry the
  previous round's answer;
- the stable answer was re-established at 168.136 s and 168.929 s near the end of the round, both
  times still the correct answer.

So the ERROR UI - a large overlay banner across the puzzle panel - did not disturb target matching,
fragment matching or the assignment.

## What the baseline suggests next

Nothing here justifies a threshold change, and none was made. The measurements point at three
separate questions for a later stage:

1. Round-start boundary handling. A stable PREVIOUS-ROUND CARRYOVER answer is available for roughly
   the first 0.6 s of a round, and the intended automation design recognizes before the first Enter,
   so a future state machine must not act on an answer that belongs to the previous round. This is a
   state machine question, not a matcher question, and it needs no threshold change.
2. FP_2 coverage. FP_2 has no real-game round at all; more recordings are needed before any claim
   about all four fingerprints is possible.
3. The target-score gate is the binding constraint at round boundaries: 11 frames failed only that
   gate with scores of 0.32-0.35 against a 0.35 threshold. Whether that is right is a policy
   question to settle with more data, not with this dataset alone.

## Limitations

- Eight rounds, four hacks, two recordings: useful real evidence, not a calibrated distribution.
- Annotation intervals are approximate by a few tenths of a second and are not frame-exact.
- FP_2 has no real-game coverage in this dataset.
- 1920x1080 uses an evaluation-only derived geometry; the live runtime still supports 2560x1440 only.
- `UNCERTAIN` is a refusal, not an absent puzzle, and is never counted as a wrong answer.
- The carryover classification is exact but depends on the previous round's annotation: it requires
  the prediction to equal that round's target and candidate set, so a wrong previous annotation
  would show up as an unexplained mismatch rather than as carryover.
- The ERROR timing in the wrong-selection round was located by visual inspection, not by a
  machine-labeled detector.

## Artifacts (all ignored, below `target/`)

| artifact | content |
| --- | --- |
| `stage6-benchmark-report.txt` | full report: sources, hashes, inventory, every failure, consensus |
| `stage6-positive-frame-results.csv` | one row per benchmarked positive frame |
| `stage6-negative-frame-results.csv` | one row per sampled negative frame |
| `stage6-negative-exhaustive-frame-results.csv` | the optional all-frame negative pass |
| `stage6-round-summary.csv` | per-round counts, rates and worst evidence |
| `stage6-resolution-summary.csv` | per-resolution positive and negative totals |
| `stage6-consensus-summary.csv` | consensus replay per round, per negative population and per full replay |
| `stage6-full-replay-events.csv` | stable episodes of the low-rate replay over both complete recordings |
| `stage6-derived-layout-1920x1080.csv` | the derived evaluation manifest |
| `stage6-1080-layout-overlay.png` | the derived geometry drawn on a native 1920x1080 frame |
| `stage6-round-keyframes-contact-sheet.png` | three keyframes per annotated round |
| `stage6-error-case-contact-sheet.png` | dense 0.25 s sheet of the wrong-selection round |
