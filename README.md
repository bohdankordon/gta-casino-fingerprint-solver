# GTA Casino Fingerprint Solver

An early, recognition-only computer-vision prototype for the GTA Online Diamond Casino Heist fingerprint matching minigame. The puzzle shows one target fingerprint and eight candidate fragments. Exactly four candidates match; the game has four known targets, each with a fixed set of four fragments.

## Status and scope

Stage 0 provides the Java project, native OpenCV verification, and domain and processing contracts. Stage 1 adds the canonical reference dataset (4 targets, 16 fragments, reproducible crops with manifest, generator and preview). Stage 2 adds representative 2560x1440 gameplay ROI extraction (one target plus eight row-major candidates) and deterministic structural normalization shared by reference and gameplay crops. The project does **not** recognize screenshots yet: no matching, no candidate correctness decisions, no live capture and no input automation exist, and only the bundled 2560x1440 fixture layout is supported.

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
- `solver`: `FingerprintSolver` passes a frame through normalization and recognition. Candidate assignment is future work.
- `debug`: OpenCV native health check.
- `app`: command-line entry point for the health check only.
- `dataset`: Stage 1 canonical reference crops, manifest, generator and preview tooling (no matching yet).

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

## Roadmap

1. Stage 0 - foundation
2. Stage 1 - reference dataset
3. Stage 2 - representative gameplay ROI extraction and deterministic structural normalization
4. Stage 3 - target and fragment matching
5. Stage 4 - constrained solver and confidence model
6. Stage 5 - live screen capture and recognition-only runtime
7. Stage 6 - robustness evaluation and tuning
8. Possible later stage - optional input automation
