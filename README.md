# GTA Casino Fingerprint Solver

An early, recognition-only computer-vision prototype for the GTA Online Diamond Casino Heist fingerprint matching minigame. The puzzle shows one target fingerprint and eight candidate fragments. Exactly four candidates match; the game has four known targets, each with a fixed set of four fragments.

## Status and scope

Stage 0 provides the Java project, native OpenCV verification, and domain and processing contracts. Stage 1 adds the canonical reference dataset (4 targets, 16 fragments, reproducible crops with manifest, generator and preview). Stage 2 adds representative 2560x1440 gameplay ROI extraction (one target plus eight row-major candidates) and deterministic structural normalization shared by reference and gameplay crops. Stage 3 adds structural matching: the gameplay target is scored against all four reference targets, and all eight candidates are scored against the four reference fragments of the ranked target, producing a complete 8x4 similarity matrix. Stage 4 adds constrained recognition: the matrix is solved as an exact 4-of-8 one-to-one assignment with ambiguity measurements, and a conservative provisional policy turns the evidence into a `RecognitionResult` (recognized vs uncertain). Stage 5 adds the live recognition-only runtime: an AWT/Robot desktop capture backend that selects the exact physical display resolution, monitor discovery and explicit selection, a full-frame recognition pipeline and a three-frame decision consensus behind a command-line runtime with `--list-monitors`, `--once` and `--watch`. Stage 5 reads the screen and prints recognition results only: there is still no keyboard or mouse automation, no automatic solving, no round state machine, no game-process interaction, and only the bundled 2560x1440 fixture layout is supported. Stage 6A/6B adds a real-gameplay recording benchmark: human-verified round annotations for two private local recordings, a frozen-pipeline baseline over every frame of the annotated rounds, a strict negative-gameplay false-positive measurement, a consensus replay and an evaluation-only 1920x1080 geometry hypothesis. Stage 6A/6B changed no matcher, no normalization, no policy threshold and no production runtime support; the recordings themselves are private, local and not committed, and Stage 6C/6D (any tuning at all) has not started. Stage 6C.1A characterizes the round and hack transitions of those recordings at full source frame rate and simulates candidate lifecycle guards offline, so the temporal carryover problem is measured before any state machine is written. Stage 6C.1A implements NO round lifecycle state machine, no round consumption API, no input automation and no sleep, and it changes no production behaviour either. Stage 6C.1B adds the production round lifecycle tracker documented in [docs/round-lifecycle-tracker.md](docs/round-lifecycle-tracker.md): stable-episode onsets after recognition consensus become ROUND_READY rounds, downstream code explicitly consumes them, the consumed identity stays suppressed fail-closed, a different stable identity becomes the next round, and an unconsumed pending round followed by a different answer desynchronizes instead of being replaced. The full-rate replay over both recordings yields exactly 8 ready and 8 consumed rounds in annotated order with zero duplicates, zero unexplained answers and zero desynchronizations; repeated identical consecutive identities stay suppressed without an independent transition witness, and there is still no input automation, no solving action and no production 1080p support.

Stage 6C.1C characterizes the independent visual round-transition witness candidates documented in [docs/transition-witness-analysis.md](docs/transition-witness-analysis.md): five witness families (raw panel difference as a control, target-only structural similarity, per-candidate same-position structural similarity, the full puzzle signature and the matcher evidence signatures) are measured on every decoded frame of both recordings against one deterministic consumed-round content baseline per round, and the four real R1 to R2 transitions, 3800 same-round frames, a 104 rule threshold sweep, a counterfactual same-identity replay and an exact-visual-repeat control show that an identity-independent content witness is measurable without being promoted. Stage 6C.1C adds NO production transition witness, changes nothing in `RoundLifecycleTracker` or `RecognitionConsensusTracker`, weakens nothing in the fail-closed same-identity behaviour, promotes no threshold, sends no input, adds no timer and adds no production 1080p support.

## Stack

- Java 21
- Maven 3.9.16 with the Maven Wrapper
- Bytedeco OpenCV platform 4.14.0-1.5.14 (cross-platform native binaries)
- JUnit 5.14.4
- JNA plus jna-platform 5.17.0 (pinned; Windows SendInput, foreground checks and abort polling for Stage 7B only, behind `input.win32`)

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
 - `runtime`: Stage 5 live runtime — `FrameRecognitionPipeline` (one full captured frame to a `RecognitionDecision`, reusing one reference library), `RecognitionConsensusTracker` (consecutive-frame decision consensus), `LiveRecognitionRuntime` (capture loop producing a `LiveRecognitionStatus`) and `LiveRecognitionState`; plus the Stage 6C.1B production round lifecycle — `RecognitionIdentity` (fingerprint plus sorted candidates), `RoundLifecycleTracker` (stable-episode onsets into `ROUND_READY`, explicit `consumeReadyRound()` acknowledgement, same-identity suppression, fail-closed `DESYNCHRONIZED`, explicit `reset()` only) with `RoundLifecycleState` and the immutable `RoundLifecycleStatus` snapshot. The lifecycle tracker answers whether a stable recognition is a new round; it sends no input and keeps no clocks or durations.
- `evaluation.recording`: Stage 6A/6B real-gameplay benchmark tooling - the committed recording source/round/hack annotation catalogs, a sequential OpenCV `VideoCapture` decoder, the evaluation-only uniform layout scaler, the positive/negative frame classifiers, the aggregate summaries, the `RecognitionConsensusTracker` replay, the local contact-sheet writer and the benchmark entry point. Evaluation only: no production package reads the recording annotations and the private recordings never leave `local-data/`.
- `evaluation.recording.transition`: Stage 6C.1A full-rate transition characterization - the per-frame temporal trace and its run compression, the three transition summaries (hack entry, inter-round, hack exit), the offline reset experiment, the offline G0-G4 guard simulations and the transition report/contact-sheet tooling. Evaluation only, measurement only: no lifecycle state machine, no round consumption API and no production behaviour.
- `evaluation.recording.transition`: Stage 6C.1A full-rate transition characterization - the per-frame temporal trace and its run compression, the three transition summaries (hack entry, inter-round, hack exit), the offline reset experiment, the offline G0-G4 guard simulations and the transition report/contact-sheet tooling; plus the Stage 6C.1B production lifecycle replay - every full-rate frame through the unmodified production consensus tracker and then the production lifecycle tracker, with a simulated well-behaved consumer that immediately consumes every ready round and sends no input, verified per hack and per full source against the human annotations. Evaluation only, measurement only: no input automation and no production behaviour change.
- `evaluation.recording.transition.witness`: Stage 6C.1C independent visual round-transition witness characterization - W0..W4 witness families measured on every full-rate frame against one frozen consumed-round content baseline per round, the same-round / transition / entry / exit populations, the rule and threshold sweep with worst-case separation margins, the counterfactual same-identity replay and the exact-visual-repeat control. Evaluation only, measurement only: no production transition witness, no lifecycle change, no input, no tuning and no production 1080p support.
- `control`: Stage 7B production control-state detector over raw gameplay pixels (selector corner brackets plus tile-interior brightness, never normalized content) with calibrated fail-closed bounds.
- `input` plus `input.win32`: Stage 7B gameplay input intentions (`GameControl`, `GameInputSink`, foreground-target guard, abort signal) with the Windows-only SendInput tap, foreground pinning and F12 abort poll isolated in `input.win32` (JNA, pinned).
- `execution`: Stage 7B guarded action executor (same-frame preflight, same-observation lifecycle claim, per-action visual verification, PROCEED exactly once, latched fail-closed faults) with clock-free correctness plus `VerificationPolicy` bounds.
- `orchestration` plus `app`: Stage 7B live wiring (`LiveSolveOrchestrator`, pending-round re-attempts, `LiveFrameResult`) and the separate opt-in live CLI (`LiveSolverMain`); `DryRunSolverMain` stays input-free.
- `evaluation.recording.control`: Stage 7B control-state evaluation over both private recordings with the committed annotation-only CSV (evaluation only, no production dependency).

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

## Round transition analysis (Stage 6C.1A)

The full-rate round/hack transition characterization is documented in
[docs/round-transition-analysis.md](docs/round-transition-analysis.md). It answers the temporal
question Stage 6A/6B left open - what exactly happens at a hack entry, between two rounds and at a
hack exit - and it does NOT implement the lifecycle state machine that would consume those answers.

Measured on every decoded frame inside each hack window padded by 2.0 s (1890 frames of
`recording_1440p`, 2552 of `recording_1080p`, twelve transitions):

- every round-to-round switch is direct: 0 of 4 transitions contain an UNCERTAIN decision between
  the old and the new answer, and the previous round's answer stays recognized and stable until the
  frame before the new one appears;
- a consensus reset at the nominal boundary re-stabilizes the OLD answer on 2 of 4 transitions, so a
  reset alone is not a round-boundary solution;
- of the offline guards, only the suppress-stable-answers-equal-to-the-consumed-identity rule keeps
  every observed next round actionable (4 of 4, 0 ms latency) while the old answer never becomes
  actionable again (0 of 4);
- 0 unexplained recognized answers and 0 unexplained stable answers occurred during transitions.

Consecutive rounds with the exact same answer identity (fingerprint plus sorted candidate set) do
not occur in this dataset. Such a case cannot be solved by any answer-identity guard and must fail
closed until an independent transition witness exists; see the document's UNPROVEN CASES section.

Run it locally (the private recordings must exist first):

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.RoundTransitionAnalysisMain"
```

It writes `target/stage6c-transition-frames.csv`, `target/stage6c-transition-runs.csv`,
`target/stage6c-transition-summary.csv`, `target/stage6c-guard-simulation.csv`,
`target/stage6c-transition-report.txt` and the dense local contact sheets under
`target/stage6c-transition-contact-sheets/` (all build output, never committed).

## Round transition witness analysis (Stage 6C.1C)

The independent visual round-transition witness characterization is documented in
[docs/transition-witness-analysis.md](docs/transition-witness-analysis.md). It answers the question
Stage 6C.1B left open - can an independent visual/content signal prove that the puzzle content
changed, even when the answer identity hypothetically stays the same - and it is **witness
characterization, not production integration**.

Measured on every decoded frame of both recordings (4442 analyzed frames in total, no sampling),
against one deterministic content baseline per round frozen at exactly the frame Stage 6C.1B
consumes:

- 3800 same-round frames (selector movement, 0/1/2/3 selected candidates, the fully selected state,
  the real wrong C5 selection with the ERROR banner and every recognition interruption) were measured
  against five candidate witness families W0..W4;
- 52 of 104 candidate rules never fired on any same-round frame *and* detected all four real
  R1 -> R2 transitions, at the first frame of the new content (offset 0 versus first new recognized);
- raw panel difference (the W0 control) overlaps the same round and the transition and is rejected;
  the useful family is structural multi-region change (at least K of the 9 regions changed), which on
  this dataset separates a 2-of-9 worst same-round case from a 9-of-9 transition;
- the counterfactual same-identity replay keeps the witness firing while the unmodified production
  `RoundLifecycleTracker` suppresses the repeated identity, so the witness is independent of
  `RecognitionIdentity`; an exact visual repetition stays fail-closed and remains unprovable;
- the dominant same-round deviation is a recurring UI notification banner, not puzzle content; that
  limitation and the absence of any real same-identity consecutive round pair are documented.

This stage implements no production transition witness, changes nothing in `RoundLifecycleTracker`
or `RecognitionConsensusTracker`, sends no input, adds no timer and adds no production 1080p support.
Its decision gate recommends one carefully scoped candidate for a later stage; it promotes no
threshold to production.

Run it locally (the private recordings must exist first):

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness.TransitionWitnessAnalysisMain"
```

It writes `target/stage6c1c-witness-frames.csv`, `target/stage6c1c-witness-transitions.csv`,
`target/stage6c1c-witness-distributions.csv`, `target/stage6c1c-witness-separation.csv`,
`target/stage6c1c-witness-rules.csv`, `target/stage6c1c-witness-counterfactual.csv`,
`target/stage6c1c-witness-same-target.csv`, `target/stage6c1c-witness-report.txt` and the local
review sheets under `target/stage6c1c-witness-contact-sheets/` (all build output, never committed).

## Production transition witness (Stage 6C.1D)

Stage 6C.1D promotes one narrowly scoped structural rule from the 6C.1C measurement into
production: at cut 0.50 at least 6 of the 9 normalized regions (target plus eight candidates)
must change, compared with the unmodified StructuralSimilarityScorer and production radii.
The witness is identity-independent and armed only after consumption; the coordinator pairs
lifecycle consumption with baseline re-arm, and the lifecycle gains exactly one additive path
where a repeated consumed identity plus STABLE consensus plus confirmed content change becomes
a witnessed ROUND_READY. Documented in docs/production-transition-witness.md. No gameplay input,
no matcher or policy change, no production 1080p support. Normal real-recording replay stays at
8 READY and 8 consumed with no extra witness events; the counterfactual A to A production replay
yields 8 READY, 8 consumed and 4 witnessed repeated-identity READY events.

## Dry-run solve orchestration (Stage 7A)

Stage 7A adds the first end-to-end solver orchestration layer with ZERO gameplay input:
a pure navigation model, a dry-run planner (BFS shortest paths plus exhaustive 24-order
optimization), one production orchestrator combining the existing pipeline, consensus,
lifecycle and witness pieces, a separate dry-run CLI, and a real-recording dry-run replay.
The real recordings establish the navigation contract used here: every round starts with the
selector on C0, moves are discrete single orthogonal steps along the twenty witnessed interior
transitions, Enter never moves focus, and grid edges stay unmodelled (no outward step was
ever attempted). Documented in docs/dry-run-orchestration.md. The full-rate dry-run replay
yields 8 ROUND_READY, 8 executable plans, 8 consumptions, 0 blocked and 0 desynchronizations;
no input is sent anywhere.

## Guarded live input execution (Stage 7B)

Stage 7B adds the first gameplay input capability behind strict safety gates, documented in
docs/guarded-live-input.md: a validated Stage 7A plan executes against GTA on Windows only
after a same-frame live preflight (validated plan, configured foreground executable pinned
by handle plus process, idle F12 abort, visually verified selector on C0 with nothing
selected, consumable lifecycle round), the lifecycle claims that same observation before
the first tap, and every navigation and selection is visually verified on fresh frames
before the next input. Any mismatch latches FAULTED or ABORTED with no automatic retry;
Tab (PROCEED) is sent exactly once and never retried. The control-state detector reads raw
selector brackets plus tile brightness (never normalized content), input goes through
Windows SendInput behind `GameInputSink` with no `java.awt.Robot` on the gameplay path,
and `DryRunSolverMain` stays input-free. Production is 2560x1440 only; the live CLI
(`LiveSolverMain --watch --enable-input --target-exe GTA5.exe`) refuses without its
explicit opt-in and on non-Windows. The real-recording evaluation agrees on 47/47 control
states with no input sent during implementation or testing. Stage 8 still has to prove
live-game reliability with controlled GTA end-to-end testing.

## Roadmap

1. Stage 0 - foundation
2. Stage 1 - reference dataset
3. Stage 2 - representative gameplay ROI extraction and deterministic structural normalization
4. Stage 3 - structural target and fragment matching (complete: scores and rankings only)
5. Stage 4 - constrained solver and confidence model (complete: conservative recognition decision, no automation)
6. Stage 5 - live screen capture and recognition-only runtime (complete: AWT backend, HiDPI-safe selection, full-frame pipeline, three-frame consensus, CLI; no automation)
7. Stage 6 - robustness evaluation and tuning
   - Stage 6A/6B - real gameplay recording dataset and frozen baseline benchmark (complete: annotations, private recording ingestion, positive and negative frame benchmarks, consensus replay and an evaluation-only 1080p geometry; no algorithm or policy change)
   - Stage 6C.1A - full-rate round-transition characterization (complete: per-frame temporal trace, transition summaries, reset experiment, offline guard simulation and a design recommendation; no lifecycle state machine yet)
   - Stage 6C.1B - the production RoundLifecycleTracker, fail-closed and event based (complete: stable-episode onsets, explicit consumption, same-identity suppression and fail-closed desynchronization; the full-rate replay over both recordings yields 8 ready and 8 consumed rounds with zero duplicate carryover, zero unexplained answers and zero desynchronizations; no input and no timing constant)
   - Stage 6C.1C - independent visual round-transition witness characterization (complete: W0..W4 witness families measured on 4442 full-rate frames against one frozen consumed-round baseline per round, the same-round / transition / entry / exit populations, a 104 rule threshold sweep with worst-case separation margins, the counterfactual same-identity replay and the exact-visual-repeat control; measurement only - no production witness, no lifecycle change, no input and no tuning)
   - Stage 6C.1D - production structural transition witness (complete: identity-independent 0.50
     and 6-of-9 rule, owned witness baseline, additive lifecycle path, coordinator consume plus
    re-arm, observation ownership path, normal and counterfactual A to A production replays;
    no input and no tuning)
   - Stage 6C/6D - any tuning based on the measured baseline (NOT started)
  - Stage 7A - dry-run solve orchestration (complete: characterized C0-start navigation,
    pure planner with BFS plus 24-order optimization, production orchestrator with same-frame
    planning and same-observation consumption, dry-run CLI, full-rate dry-run replay at 8 READY
    plus 8 executable plans plus 8 consumptions with zero blocked and zero desync; no input)
   - Stage 7B - guarded live input execution (complete: production control-state detector,
     Windows SendInput backend with foreground pinning and F12 abort, per-action visual
     verification, same-observation claim before the first tap, latched fail-closed faults,
     separate opt-in live CLI, 47/47 real-recording control-state evaluation, unchanged
     Stage 7A and witness replays; production 2560x1440 on Windows only; no live-game
     reliability claimed)
8. Stage 8 - controlled live GTA end-to-end testing (in progress)
   - Stage 8B - real-GTA Borderless capture validation (complete: 2560x1440 physical,
     CV_8UC3, 282 continuous frames, 0 unsupported frames, 0 capture errors, 43.4 ms
     average warmed capture; no Windows Graphics Capture / Desktop Duplication backend
     is needed)
   - Stage 8C - guarded single-tap input diagnostics (implementation pending manual
     validation: the guarded one-tap CLI documented in
     docs/stage8c-input-diagnostics.md; not complete and not yet validated against
     real GTA)
