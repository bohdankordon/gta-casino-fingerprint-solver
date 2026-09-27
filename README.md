# GTA Casino Fingerprint Solver

An early, recognition-only computer-vision prototype for the GTA Online Diamond Casino Heist fingerprint matching minigame. The puzzle shows one target fingerprint and eight candidate fragments. Exactly four candidates match; the game has four known targets, each with a fixed set of four fragments.

## Status and scope

Stage 0 provides the Java project, native OpenCV verification, and domain and processing contracts. Stage 1 adds the canonical reference dataset (4 targets, 16 fragments, reproducible crops with manifest, generator and preview). Stage 2 adds representative 2560x1440 gameplay ROI extraction (one target plus eight row-major candidates) and deterministic structural normalization shared by reference and gameplay crops. Stage 3 adds structural matching: the gameplay target is scored against all four reference targets, and all eight candidates are scored against the four reference fragments of the ranked target, producing a complete 8x4 similarity matrix. Stage 4 adds constrained recognition: the matrix is solved as an exact 4-of-8 one-to-one assignment with ambiguity measurements, and a conservative provisional policy turns the evidence into a `RecognitionResult` (recognized vs uncertain). There is still no live capture, no automatic screen detection, no keyboard navigation and no input automation, and only the bundled 2560x1440 fixture layout is supported.

## Stack

- Java 21
- Maven 3.9.16 with the Maven Wrapper
- Bytedeco OpenCV platform 4.14.0-1.5.14 (cross-platform native binaries)
- JUnit 5.14.4

## Architecture

- `capture`: single-frame `ScreenCapture` boundary; no capture implementation yet.
- `model`: four `FingerprintId` values and immutable `RecognitionResult` with recognized, uncertain, and failed outcomes. Candidate indices are zero-based, from 0 to 7.
- `gameplay`: resolution-specific `GameplayLayout` manifest plus layout-driven `GameplayFrameExtractor` producing an owned `ExtractedPuzzleFrame` (one target, eight row-major candidates), with ROI and normalization debug previews.
- `vision`: deterministic `StructuralNormalizer` (shared achromatic/percentile pipeline, canonical 128x128 fragment and 256x384 target grayscale profiles); `ImageNormalizer` and `FingerprintRecognizer` contracts have no matching algorithms yet.
- `solver`: early `FingerprintSolver` orchestration placeholder over the Stage 0 single-`Mat` contracts; not the active runtime.
- `debug`: OpenCV native health check.
- `app`: command-line entry point for the health check only.
- `dataset`: Stage 1 canonical reference crops, manifest, generator and preview tooling (no matching yet).
- `matching`: structural similarity scoring (translation-tolerant zero-mean normalized cross-correlation), `ReferenceFingerprintLibrary` holding the normalized Stage 1 assets it owns, `TargetMatcher` returning every target score with a deterministic ranking, and `FragmentMatcher` returning the complete 8x4 `FragmentScoreMatrix`; `matching.evaluation` holds the Stage 3 diagnostics tool and the fixture annotation reader.
- `recognition`: exact `ConstrainedAssignmentSolver` over all 1680 legal 4-of-8 assignments, `RecognitionEvidence` measurements, the provisional conservative `RecognitionPolicy`, and `PuzzleRecognitionEngine` producing a `RecognitionDecision` with a `RecognitionResult`; `recognition.evaluation` holds the Stage 4 diagnostics tool.

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

## Roadmap

1. Stage 0 - foundation
2. Stage 1 - reference dataset
3. Stage 2 - representative gameplay ROI extraction and deterministic structural normalization
4. Stage 3 - structural target and fragment matching (complete: scores and rankings only)
5. Stage 4 - constrained solver and confidence model (complete: conservative recognition decision, no automation)
6. Stage 5 - live screen capture and recognition-only runtime
7. Stage 6 - robustness evaluation and tuning
8. Possible later stage - optional input automation
