# GTA Casino Fingerprint Solver

An early, recognition-only computer-vision prototype for the GTA Online Diamond Casino Heist fingerprint matching minigame. The puzzle shows one target fingerprint and eight candidate fragments. Exactly four candidates match; the game has four known targets, each with a fixed set of four fragments.

## Status and scope

Stage 0 provides the Java project, native OpenCV verification, and domain and processing contracts. Stage 1 adds the canonical reference dataset (4 targets, 16 fragments, reproducible crops with manifest, generator and preview). Stage 2 adds representative 2560x1440 gameplay ROI extraction (one target plus eight row-major candidates) and deterministic structural normalization shared by reference and gameplay crops. Stage 3 adds structural matching: the gameplay target is scored against all four reference targets, and all eight candidates are scored against the four reference fragments of the ranked target, producing a complete 8x4 similarity matrix. Stage 4 adds constrained recognition: the matrix is solved as an exact 4-of-8 one-to-one assignment with ambiguity measurements, and a conservative provisional policy turns the evidence into a `RecognitionResult` (recognized vs uncertain). Stage 5 adds the live recognition-only runtime: an AWT/Robot desktop capture backend that selects the exact physical display resolution, monitor discovery and explicit selection, a full-frame recognition pipeline and a three-frame decision consensus behind a command-line runtime with `--list-monitors`, `--once` and `--watch`. Stage 5 reads the screen and prints recognition results only: there is still no keyboard or mouse automation, no automatic solving, no round state machine, no game-process interaction, and only the bundled 2560x1440 fixture layout is supported. Stage 6A/6B adds a real-gameplay recording benchmark: human-verified round annotations for two private local recordings, a frozen-pipeline baseline over every frame of the annotated rounds, a strict negative-gameplay false-positive measurement, a consensus replay and an evaluation-only 1920x1080 geometry hypothesis. Stage 6A/6B changed no matcher, no normalization, no policy threshold and no production runtime support; the recordings themselves are private, local and not committed, and Stage 6C/6D (any tuning at all) has not started.

## Stack

- Java 21
- Maven 3.9.16 with the Maven Wrapper
- Bytedeco OpenCV platform 4.14.0-1.5.14 (cross-platform native binaries)
- JUnit 5.14.4

## Architecture

- `capture`: single-frame `ScreenCapture` boundary plus the Stage 5 AWT backend — `AwtScreenCapture` (Robot plus HiDPI-aware multi-resolution capture and exact physical-resolution variant selection), `BufferedImageMatConverter` (captured image to `CV_8UC3` BGR without a per-pixel loop), monitor discovery and selection (`AwtMonitorEnumerator`, `MonitorInfo`, `MonitorSelector`), the capture failure types and the manual `CaptureProbe`.
- `model`: four `FingerprintId` values and immutable `RecognitionResult` with recognized, uncertain, and failed outcomes. Candidate indices are zero-based, from 0 to 7.
- `gameplay`: resolution-specific `GameplayLayout` manifest plus layout-driven `GameplayFrameExtractor` producing an owned `ExtractedPuzzleFrame` (one target, eight row-major candidates), with ROI and normalization debug previews.
- `vision`: deterministic `StructuralNormalizer` (shared achromatic/percentile pipeline, canonical 128x128 fragment and 256x384 target grayscale profiles); `ImageNormalizer` and `FingerprintRecognizer` contracts have no matching algorithms yet.
- `solver`: early `FingerprintSolver` orchestration placeholder over the Stage 0 single-`Mat` contracts; not the active runtime.
- `debug`: OpenCV native health check.
- `app`: Stage 0 OpenCV health check plus the Stage 5 recognition-only runtime entry point `LiveRecognitionMain` (monitor listing, one-frame and watch modes with strict option parsing).
- `dataset`: Stage 1 canonical reference crops, manifest, generator and preview tooling (no matching yet).
- `matching`: structural similarity scoring (translation-tolerant zero-mean normalized cross-correlation), `ReferenceFingerprintLibrary` holding the normalized Stage 1 assets it owns, `TargetMatcher` returning every target score with a deterministic ranking, and `FragmentMatcher` returning the complete 8x4 `FragmentScoreMatrix`; `matching.evaluation` holds the Stage 3 diagnostics tool and the fixture annotation reader.
- `recognition`: exact `ConstrainedAssignmentSolver` over all 1680 legal 4-of-8 assignments, `RecognitionEvidence` measurements, the provisional conservative `RecognitionPolicy`, and `PuzzleRecognitionEngine` producing a `RecognitionDecision` with a `RecognitionResult`; `recognition.evaluation` holds the Stage 4 diagnostics tool.
- `runtime`: Stage 5 live runtime — `FrameRecognitionPipeline` (one full captured frame to a `RecognitionDecision`, reusing one reference library), `RecognitionConsensusTracker` (consecutive-frame decision consensus), `LiveRecognitionRuntime` (capture loop producing a `LiveRecognitionStatus`) and `LiveRecognitionState`.
- `evaluation.recording`: Stage 6A/6B real-gameplay benchmark tooling - the committed recording source/round/hack annotation catalogs, a sequential OpenCV `VideoCapture` decoder, the evaluation-only uniform layout scaler, the positive/negative frame classifiers, the aggregate summaries, the `RecognitionConsensusTracker` replay, the local contact-sheet writer and the benchmark entry point. Evaluation only: no production package reads the recording annotations and the private recordings never leave `local-data/`.

OpenCV `Mat` results from capture and normalization are owned by their callers and must be closed. `FingerprintSolver` closes the normalized image; the input frame remains the caller's responsibility.

## Build and test

Install a Java 21 JDK. The wrapper downloads Maven and OpenCV dependencies automatically; no separate OpenCV installation or native library path is needed.

On Windows:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
.\mvnw.cmd compile exec:java
```

On macOS/Linux, use `./mvnw` in place of `.\mvnw.cmd`. `compile exec:java` performs a trivial OpenCV operation and prints a health-check result.

## Matching

Stage 3 matching is documented in [docs/structural-matching.md](docs/structural-matching.md): the chosen algorithm, the approaches that were measured and rejected, the score semantics, the translation tolerance and the current representative-fixture results.

Run the Stage 3 diagnostics on the representative fixture:

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.matching.evaluation.MatchingEvaluation"
```

It writes `target/stage3-target-scores.csv`, `target/stage3-fragment-score-matrix.csv`, `target/stage3-matching-report.txt`, `target/stage3-fragment-score-heatmap.png` and `target/stage3-fragment-match-preview.png` (build output, never committed). The tool reports scores and rankings only; it produces no recognition result and no claim that a puzzle is safe to automate.

## Recognition

Stage 4 recognition is documented in [docs/constrained-recognition.md](docs/constrained-recognition.md): the exact 1680-assignment search, best vs runner-up vs best-different-selection ambiguity measurements, the recognition evidence model, the provisional conservative policy with its rationale, and the confidence semantics (deterministic structural evidence strength, not a probability).

Run the Stage 4 diagnostics on the representative fixture:

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.recognition.evaluation.RecognitionEvaluation"
```

It writes `target/stage4-recognition-report.txt` and `target/stage4-top-assignments.csv` (build output, never committed). On the representative fixture the engine returns `RECOGNIZED` with `FP_1` and candidates `[0, 3, 6, 7]`; ambiguous synthetic controls stay `UNCERTAIN` with recorded reasons. Thresholds are provisional and must be re-evaluated against user-captured fixtures before input automation.

## Live recognition runtime

Stage 5 is documented in [docs/live-recognition-runtime.md](docs/live-recognition-runtime.md): the AWT/Robot capture backend, logical versus physical resolution on scaled Windows displays, exact capture-variant selection, monitor selection, the one-frame pipeline, the three-frame decision consensus, the runtime states, resource ownership, the fullscreen caveat and the manual capture probe.

The runtime is recognition-only. It captures the desktop, recognizes the fingerprint puzzle and prints results; it never sends keyboard or mouse input.

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain" "-Dexec.args=--list-monitors"
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain" "-Dexec.args=--monitor 0 --once"
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain" "-Dexec.args=--monitor 0 --watch"
```

`--once` prints a single frame's decision and never calls it stable. `--watch` captures at `--interval-ms` (default 200), reports `STABLE` only after `--stable-frames` (default 3) consecutive identical answers, prints state changes instead of repeating identical lines, and ends with a summary on Ctrl+C.

Only the bundled 2560x1440 layout is supported. A capture that does not carry exactly those physical pixels — for example a 2048x1152 logical image from a 125% scaled display — is refused by the backend or reported as `UNSUPPORTED_FRAME`; a logical image is never resized into the layout. Random or non-puzzle screens normally stay `UNCERTAIN`, which is not a claim that no puzzle is on screen.

The manual Windows smoke test for capture itself is `capture.CaptureProbe`:

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.capture.CaptureProbe"
```

It lists monitors, prints every returned capture variant and the selected one, and saves `target/stage5-capture-probe.png` (local debug output, never committed and never uploaded).

## Recording benchmark (Stage 6A/6B)

Real gameplay round annotations and a frozen-pipeline baseline over two private local recordings are
documented in [docs/recording-benchmark.md](docs/recording-benchmark.md). Stage 6A/6B measures; it
does not tune.

- Committed: `fixtures/gameplay/recordings/stage6-sources.csv` (generic source metadata and
  SHA-256), `stage6-rounds.csv` (8 rounds, 4 hacks, human verified), `stage6-hack-windows.csv` and a
  README with provenance.
- Private and ignored: the recordings live in `local-data/stage6/` and are never committed, copied
  into fixtures, uploaded or attached to a pull request. No full gameplay frame is committed either;
  every preview the benchmark generates stays under `target/`.
- Measured baseline (unchanged production pipeline, `RecognitionPolicy.defaultPolicy()`): 1440p
  1491 of 1499 frames match the nominal round, 1 previous-round carryover, 0 unexplained mismatch,
  7 uncertain; 1080p (evaluation-only 0.75 geometry) 2188 of 2255 match, 35 previous-round
  carryover, 0 unexplained mismatch, 32 uncertain; zero false positives on 1331 sampled and 7989
  exhaustive strict-negative gameplay frames; all eight rounds reach a stable correct answer, with
  2 stable previous-round carryover episodes and 0 stable unexplained mismatches. A nominal-round
  disagreement counts as carryover only when the prediction equals the previous annotated round's
  target AND candidate set exactly; no time tolerance is applied.
- 1920x1080 uses an evaluation-only derived geometry (uniform 0.75 edge scaling of the production
  layout, native frames, no resize, no ROI tuning). `LiveRecognitionMain` still supports 2560x1440
  only.
- FP_2 has no real-game round in these recordings, so no FP_2 claim is made.

Run it locally (the private recordings must exist first):

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.evaluation.recording.RecordingBenchmarkMain" "-Dexec.args=--exhaustive-negative"
```

It writes the per-frame results, the round, resolution and consensus summaries, the benchmark report,
the derived 1080p manifest and the review contact sheets below `target/` (build output, never
committed).

## Roadmap

1. Stage 0 - foundation
2. Stage 1 - reference dataset
3. Stage 2 - representative gameplay ROI extraction and deterministic structural normalization
4. Stage 3 - structural target and fragment matching (complete: scores and rankings only)
5. Stage 4 - constrained solver and confidence model (complete: conservative recognition decision, no automation)
6. Stage 5 - live screen capture and recognition-only runtime (complete: AWT backend, HiDPI-safe selection, full-frame pipeline, three-frame consensus, CLI; no automation)
7. Stage 6 - robustness evaluation and tuning
   - Stage 6A/6B - real gameplay recording dataset and frozen baseline benchmark (complete: annotations, private recording ingestion, positive and negative frame benchmarks, consensus replay and an evaluation-only 1080p geometry; no algorithm or policy change)
   - Stage 6C/6D - any tuning based on the measured baseline (NOT started)
8. Possible later stage - optional input automation
