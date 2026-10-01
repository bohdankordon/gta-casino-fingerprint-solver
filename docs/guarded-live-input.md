# Guarded live input execution (Stage 7B)

Stage 7B adds the FIRST gameplay input capability: a validated Stage 7A dry-run plan can be
executed against GTA on Windows, but only behind strict safety gates and with visual
verification between every action.

```
ROUND_READY
    -> DryRunPlanner + PlanValidator
    -> LIVE EXECUTION PREFLIGHT (plan, abort, foreground, visual start state)
    -> lifecycle claim of the SAME observation
    -> one abstract action = one key tap + visual verification on fresh frames
    -> PROCEED exactly once, then a bounded acknowledgement read (never retried)
```

Stage 7B is NOT the final live-game acceptance stage. Stage 8 performs controlled real GTA
end-to-end testing and tunes whatever execution timings or UI-state thresholds live use
reveals. No claim of live-game reliability is made here.

## Safety principle

Inputs are NEVER blindly replayed with fixed sleeps. Every input except the final
round-advance action has a visually confirmed postcondition before another gameplay input
may be sent. A single mismatch stops input, latches the execution failure, and requires an
explicit reset or an application restart. There are no automatic retries after a partially
executed round: once input begins, duplicate automatic execution of the same round is more
dangerous than losing automatic retry.

## Production scope (exact)

Stage 7B is production 2560x1440 only. 1080p rows below are evaluation-only geometry, as in
every earlier stage. The live orchestrator refuses any non-1440p layout, and the live CLI
refuses to start on any non-Windows OS.

Stage 7B adds (new packages only, no solver-intelligence change):

- a production control-state detector (`control`: `PuzzleControlState`,
  `PuzzleControlStateDetector`, `ControlThresholds`) reading RAW gameplay UI state;
- a Windows input backend (`input`: `GameControl`, `GameInputSink`, plus the narrow
  `input.win32` package with the SendInput tap, the foreground-target guard and the
  emergency-abort poll);
- a guarded action executor (`execution`: `GuardedPlanExecutor` with an explicit
  IDLE/EXECUTING/COMPLETED/FAULTED/ABORTED ownership state, `VerificationPolicy`, a
  clock/sleeper abstraction and fresh-frame poll sources);
- a live orchestrator (`orchestration.LiveSolveOrchestrator`) that runs the Stage 7A
  recognition chain unchanged and defers lifecycle consumption until the same-frame
  preflight passes;
- a separate live CLI (`app.LiveSolverMain`, explicit opt-in, explicit target executable);
- a real-recording control-state evaluation (`evaluation.recording.control`) with a
  committed annotation-only CSV.

Stage 7B does NOT modify: `StructuralNormalizer`, matching, `RecognitionPolicy`, the
assignment solver, consensus, lifecycle, transition-witness thresholds, the Stage 7A
planner, the 24-order optimization, the proven navigation graph, the ROI manifest or
production resolution support. `DryRunSolverMain` stays input-free and unchanged.

## Control-state characterization: signals

The detector inspects RAW full-frame pixels (never normalized fingerprint content) through
two independent signals, re-characterized from the two private recordings
(`local-data/stage6/casino-heist-1440p.mkv`, `local-data/stage6/casino-heist-1080p.mp4`) plus
the Stage 7A navigation timeline. The exploratory probe scripts are not committed; what
follows is what they measured.

### Focus: selector corner brackets

A same-tile focus/unfocus difference image shows the game draws bright corner-bracket arms
in a frame offset ~14..24 px OUTSIDE the focused tile: focusing adds ~940 hot pixels and
removes none. The tile edge itself is useless (every tile carries the same dim dotted
border), the 4 px ring hugging the tile is identical focused or not (means 40 vs 40), and
the inter-row divider lines plus the tile-interior fingerprint pattern pollute naive
top-strip and inner-edge counts. The production score is therefore the count of pixels
brighter than gray 150 in four side bands at the bracket offset (left, right, top, bottom,
full side length, 14..24 px out), a geometry that skips the dotted borders, the dividers
and the fingerprint artwork by construction.

Measured at 1440p (bracket bands of 6080 px per tile):

- focused tiles score 288..369 (C0..C1 read ~346..369, C6..C7 read ~288..300);
- neighbouring bleed (the focused tile arms overlap neighbour bands across the 39 px
  inter-tile gap) and unfocused tiles score 0..97 on clean frames;
- a weak overlay edge raises a non-focused tile to at most ~234;
- a covering overlay banner (SIGNAL PATCH, ERROR text, success flashes) floods at least
  one tile with 664..6080 hot pixels, far above any focus reading;
- every one of the ~100 traced Stage 7A moves lands on the bracket argmax within one
  frame (moves render in a single frame at 30 fps, no glide), and the 33/33 Enter presses
  hold focus as Stage 7A reported.

The production bounds split the measured gaps: winner floor 150 and winner-minus-runner-up
margin 150 (gap 97..288), per-tile ceiling 600 (gap 369..664). A winner below the floor
(blank panel, transitions, panel gone), a contested winner (weak overlay edge, exit
garbage), any tile above the ceiling, or a tied winner all report fail-closed invalid with
an empty focus and an empty selected set.

### Selected state: candidate-interior brightness

Selecting a tile brightens its interior: unselected tiles read 17..29 mean gray (6 px
inset), selected tiles read 60..107 at 1440p and 61..109 at 1080p, across all 32 real
selections of the 8 annotated rounds. The production rule selects a tile when its interior
reaches an absolute floor of 45 AND leads the darkest tile of the same frame by at least
20 (gap 29..60 on both sides of the floor). Selection rendering is a single-frame cut like
movement: interior means flip between consecutive 30 fps frames at each annotated
selection time.

### The nine characterized categories

1. Focused unselected tile: every round start (8/8, both resolutions) reads C0 at
   ~346..369 with an empty selected set and no overlay.
2. Focused selected tile: every post-selection dwell reads the just-confirmed candidate
   with exactly the grown selected set (Enter holds focus, verified on 33 presses).
3. Non-focused unselected tiles: 0..97 bracket score, 17..29 interior mean.
4. Non-focused selected tiles: same bracket scores, 60..107 interior mean; focus
   routinely rests on and leaves already-selected tiles.
5. ERROR episode (1080p H1R2, 74.4..76.4 s): the banner blinks at ~0.55 s period; blink-on
   phases contest the winner (margin 37..64) and are correctly refused, blink-off phases
   read the true C7 focus. No committed evaluation row samples a sub-second blink phase;
   the ERROR onset wobble, the C0 reset at 76.5 s and the post-reset states are covered.
6. SIGNAL PATCH overlay: post-completion white-banner phases flood neighbouring bracket
   bands (weak edge ~120..234, covering phases 1342..1558) and partially cover interiors
   (an unselected tile read 73 while covered). Weak phases keep or refuse the true winner
   by margin; covering phases trip the ceiling. Banner phases oscillate, so only phases
   stable over >= 0.3 s back committed rows.
7. Round-transition frames: all four R1 to R2 cuts are single-frame content changes with no
   blend (old selected set and focus one frame, cleared selection with reset C0 focus the
   next). Transitions read as clean new-round states, never as ambiguity.
8. Selected-state clearing: observed at every transition (all interiors back to 17..29 with
   focus already C0) and at the wrong-C5 clearing (~78.8 s, 88 -> 24 in one frame).
9. Candidate brightness under all observed rounds: the 17..29 vs 60..107 split holds for
   every tile, every round and both resolutions; no tile or fingerprint pattern crosses it.

### Decoder sensitivity and fragile rows (no silent claims)

Two findings stay out of the committed evaluation with documented causes:

- WEAK-BANNER MARGINS ARE DECODER-SENSITIVE. The 1440p 126.00 s frame reads margin 125
  (refused) under FFmpeg but 185 (valid) under MSMF: banner-edge pixels binarize
  differently across decoders, swinging weak-banner margins by ~60. Rows whose margin
  sits within ~70 of the 150 line are characterization evidence, not regression gates;
  126.00 is dropped from the CSV for an outright decoder disagreement.
- SAMPLING HAS A DECODER-TIMING FLOOR. The platform-default MSMF backend drifts by a
  variable 0.15..0.5 s against the annotated timeline on the 1080p mp4 (FFmpeg reproduces
  it frame-exactly, but FFmpeg is not shipped in this project's OpenCV build). Committed
  rows therefore sit mid-dwell, >= 0.3 s from the nearest UI change; sub-second dwells,
  blink phases, single-flash frames and fast exit fades are characterization evidence
  only.

Neither finding affects production safety: refused-vs-valid-correct outcomes are both safe
(nothing is sent, or the true state is verified), and a valid-WRONG frame was never
observed in any category. The residual banner-fakes-expected-state scenario is analysed
under verification below.

## Production control-state detector

`PuzzleControlStateDetector` is layout-driven (candidate rectangles from the
`GameplayLayout`, band offsets scaled by tile size) and stateless. `decide` is pure Java
over eight bracket scores plus eight interior means, so synthetic CI tests pin the logic
without recordings; `detect` adds the one grayscale conversion plus indexed pixel reads
(one scratch Mat per call, always released; the frame is borrowed, never modified).

`PuzzleControlState` carries the focused candidate (empty unless valid), the selected set
in ascending order (empty unless valid), the validity flag, a short evidence/refusal note
and the raw per-tile scores for evaluation. Production resolution stays 2560x1440 only:
the live orchestrator refuses any other layout size, and 1080p evaluation uses the
transcribed derived rectangles with band-area-scaled bounds (ratio 0.5250, floor/margin
79, ceiling 315) inside the evaluation package alone.

Real-recording evaluation: 47/47 annotation rows agree (23/23 at 1440p, 24/24 at 1080p)
on focus exact-match, selected-set exact-match and validity, covering starts, dwells,
completed rounds, transitions, clearing, the ERROR episode, weak and covering overlays and
hack exits. Review outputs land ignored below `target/` (`stage7b-control-state-frames.csv`,
`stage7b-control-state-summary.csv`, `stage7b-report.txt`); the committed CSV carries
timestamps plus expected UI states only (no images, no videos, no private paths).

## Windows input backend

A mature Windows native library is used: JNA plus jna-platform, both pinned to 5.17.0
(explicit versions, no ranges; pure Java plus bundled natives, so Linux CI compiles and
tests). Windows APIs live only in `input.win32`; everything else talks to the `input`
interfaces, and every Windows class refuses to construct on a non-Windows OS.

- `GameInputSink.tap` is one complete key tap (down plus up) submitted as a single
  SendInput batch with no hold duration to tune. The backend never exposes raw key-down
  state; when the batch does not report both events delivered, a best-effort key-up is
  submitted before the failure is reported, so no error path leaves a key held.
- Mapping (the project game-control contract): arrows for UP/DOWN/LEFT/RIGHT, Enter for
  SELECT, Tab for PROCEED. The Tab mapping is contract, not recording inference.
- No anti-cheat or bypass work of any kind: ordinary user-style keyboard events only, no
  injection, hooks, memory access, drivers or spoofing.
- No CI test emits OS input: tests use a recording fake and pin only the static key-code
  mapping (0x25/0x26/0x27/0x28, 0x0D, 0x09, F12 0x7B).

## Foreground-target guard

`ForegroundTargetGuard` pins the current foreground window plus its process at execution
preflight and re-checks the pin before every gameplay input and during every
verification poll. The Windows implementation reads `GetForegroundWindow`,
`GetWindowThreadProcessId` and the owning executable file name
(`QueryFullProcessImageName` with read-only limited information): the live CLI requires
an explicit `--target-exe` (for example `GTA5.exe`, never guessed), the pin succeeds only
when the foreground executable equals it, and the handle, process id AND executable name
must all still match on every re-check. Any focus change stops input immediately and
latches FOCUS_LOST (reported inside FAULTED): no automatic retry.

## Emergency abort

`AbortSignal` is polled before execution starts, before every input, while waiting for
visual confirmation and before PROCEED. The Windows implementation polls the high bit of
`GetAsyncKeyState` for F12 by default (documented overlap with platform screenshot
bindings; the key is a constructor parameter). An active abort sends no further input,
latches ABORTED and never resumes automatically.

## Execution commit and lifecycle semantics

Claimed-for-execution and puzzle-solved are carefully separated. Before the first
gameplay input of a round, ALL of these must hold on the SAME frame/observation:

1. the Stage 7A plan is READY;
2. `PlanValidator` reports zero violations (re-validated independently at the gate);
3. the foreground window is the configured executable (handle plus pid pinned);
4. the emergency abort is idle;
5. the control detector reads focus C0 (the characterized start, VERIFIED live, never
   assumed from history);
6. the control detector reads an empty selected set (attaching mid-round with tiles
   already selected must refuse: replaying the full plan could re-toggle a tile);
7. the frame is the observation still consumable by the
   `RoundLifecycleWitnessCoordinator`;
8. the lifecycle still exposes the expected ready identity.

Only then does `consumeReadyRound` run with that same observation, atomically claiming
the lifecycle round and re-arming the structural baseline on the exact pre-input content,
and only then may the first tap be sent. Any preflight failure sends nothing and consumes
nothing: the round stays ROUND_READY, and later stable frames of the same pending identity
re-present the plan for a new preflight with the latest same-frame observation (the
coordinator keeps the latest stable frame consumable while the identity stays ready).
One final gate closes the race between a passing preflight and the commitment: abort and
foreground are re-checked immediately before `consumeReadyRound`. An abort activated in
that window latches ABORTED with no claim and no input; a lost foreground pin latches
FAULTED (FOCUS_LOST) the same way. Only when both still hold is the round claimed exactly
once. After the claim, every failure latches FAULTED or ABORTED with no auto-reset, no
auto-retry and no lifecycle rollback; only an explicit reset or an application restart
clears it.

## Action executor

`GuardedPlanExecutor` runs a validated plan with tracked expectations starting at focus
C0 with an empty selected set.

- NAVIGATE: after the pin/abort gate, exactly one movement tap; then fresh-frame polls
  until the detector reads the planned destination with the unchanged selected set
  (SUCCESS), or a clearly different focus, an unexpected selection change, focus loss,
  abort or timeout fails closed. Pre-render reads (old focus, unchanged selection) and
  ambiguous frames keep polling; they never succeed and never fail.
- SELECT: a bounded visual precondition (actual focus is the target, selection unchanged;
  clear deviations fail, ambiguity waits), one SELECT tap, then polls until focus holds
  and the selected set grew by exactly the focused candidate.
- PROCEED: requires the tracked set to equal all four plan candidates plus a fresh visual
  confirmation of exactly those four; pin and abort are checked again; Tab is sent once
  and never retried. A bounded acknowledgement read follows with four explicit outcomes:
  an observed departure from the old control state acknowledges the transition
  (COMPLETED); an ordinary no-ack timeout completes with a `PROCEED_SENT` note and the
  recognition loop may resume (COMPLETED); an abort activated while awaiting the advance
  latches ABORTED; a lost foreground pin latches FAULTED (FOCUS_LOST). The two safety
  outcomes never roll the lifecycle back (Tab was already sent) but keep the executor
  latched, so no later execution starts automatically and only COMPLETED rounds release
  the executor for the next round. Stage 8 validates real round advance in GTA.

Polling is bounded but never truthful: the interval (default 100 ms) only schedules fresh
reads, the per-action timeout (default 5 s, about 150 frames) only fails, and success needs
two consecutive agreeing fresh reads, which debounces single-frame overlay flicker. The
residual scenario (an overlay faking exactly the expected next state while hiding the true
one across two polls inside all focus/selection bounds) was never observed: real banners
are bimodal (weak edges below the margin, covering floods far above the ceiling), appear
post-completion in this dataset rather than mid-execution, and transitions are
single-frame cuts. The debounce plus the ceiling plus the bimodal gap is the documented
defence; Stage 8 must re-measure it against live GTA behaviour before any reliability
claim.

No second puzzle lifecycle is built: the executor state (IDLE/EXECUTING/COMPLETED/FAULTED/
ABORTED) describes input ownership only. Sleep and the monotonic clock exist solely
behind the injected `ExecutionClock` for polling; recognition, consensus, lifecycle,
witness and planning correctness never touch time, and no unit test ever sleeps.

## Execution logging and CLI

Concise terminal diagnostics:

```
EXECUTION START FP_4[1;4;5;6] order=[1, 5, 4, 6]
verified start=C0 selected=[]
RIGHT -> verified C1
SELECT C1 -> verified selected=[1]
...
PROCEED -> sent
EXECUTION COMPLETE
```

Failures name themselves (`BLOCKED: GTA is not the foreground target`,
`BLOCKED: selector start is C3, expected C0`, `BLOCKED: candidates already selected [1]`,
`ABORTED: emergency key detected`, `FAULTED: expected focus C5, observed C3`,
`FAULTED: selection of C4 was not visually confirmed`) followed by
`NO FURTHER INPUT WILL BE SENT`.

`LiveSolverMain` is separate from the input-free `DryRunSolverMain` and refuses to send
input by construction:

```powershell
LiveSolverMain --monitor 0 --watch --enable-input --target-exe GTA5.exe
```

Without `--enable-input` (plus `--target-exe`) it refuses with exit code 2; on
non-Windows it refuses with exit code 3. The startup banner always prints
`LIVE INPUT ENABLED` with the target executable and the abort key. No input is ever sent
merely because the program starts: taps happen only after a NEW or still-pending
ROUND_READY round with a validated plan passes the same-frame preflight and the
lifecycle claims the same observation. A latched FAULTED/ABORTED ends the run with exit
code 3: restarting the application is the explicit reset.

## Test matrix (synthetic, deterministic, no input, no sleep)

The executor matrix pins all 25 required behaviours plus the two review-driven safety
races (31 tests) with fake sinks, guards, abort signals, scripted control states and a
manual clock: the exact mapped control sequence, the final pre-claim abort/foreground
re-check with no claim on either race, the four distinguished post-PROCEED acknowledgement
outcomes,
start-focus, selected-set, ambiguity, foreground, abort and invalid/BLOCKED-plan refusals
with zero taps and no consumption; first-navigate verification; wrong-focus and
changed-selection faults; select success/timeout/wrong-candidate cases; focus loss and
abort after the first action; timeout without retry; four-selections-before-PROCEED with
exactly one Tab; no full-plan retry after a post-claim fault; pre-consume pending with a
later same-round execution; same-observation consumption before the first tap; the input
mapping contract; and close/reset semantics. The orchestrator layer adds the same-frame
pending-retry proof against the real pipeline and fixture (blocked preflight keeps
ROUND_READY, the next stable frame of the same identity executes and consumes exactly
it), plus unchanged repeat suppression and decision-free behaviour. Detector, threshold,
mapping, CLI-option, CLI-rendering, surface-guard and evaluation-contract tests complete
the suite. No CI test emits OS keyboard input, and no unit test sleeps.

## Regressions

- Stage 7A dry-run replay (full rate, both recordings, production orchestrator): unchanged
  at 8 ROUND_READY, 8 executable plans, 8 consumptions, 0 blocked, 0 desync, identical
  identities, orders and move counts.
- Stage 6C.1D production-witness replay: unchanged (8 normal READY/consumed with 0
  witnessed, counterfactual 8/8 with 4 witnessed, 0 desync, 0 consume failures,
  identical ready frames).
- `DryRunSolverMain` behaviour and surface guards: unchanged and green.
- Full suite: 542 tests, 0 failures, 0 errors (Linux-safe: no test emits input, Windows
  natives never execute in CI).

## Dependencies and platform isolation

JNA 5.17.0 plus jna-platform 5.17.0 (pinned, no ranges): pure Java with bundled natives,
so Linux CI compiles and tests everything except actual native calls. All Windows API use
(SendInput taps, pings via GetForegroundWindow, process identity via
GetWindowThreadProcessId and QueryFullProcessImageName, abort polling via GetAsyncKeyState)
lives in `input.win32` behind the `input` interfaces; every Windows class refuses to
construct off-Windows; static source guards prove no native token escapes that package
and no JDK timer reaches recognition or execution correctness. Recognition-only and
dry-run tools stay platform-neutral apart from their existing capture constraints.

## Data and privacy

No image or video is committed: the control-state evaluation commits annotation-only CSV
rows (source ids, timestamps, expected UI states, notes). No private filesystem paths. All
review sheets and per-frame tables stay ignored below `target/`. The recordings never
leave `local-data/`.

## Limitations (Stage 8 must close these)

- No real GTA automation ran in this stage: no keys were sent to any desktop during
  implementation or testing (proven by construction: fakes everywhere, surface guards,
  and CI without input).
- Execution timings (100 ms polls, 5 s bounds, double confirmation) are conservative
  safety bounds from 30 fps recordings, not live-game measurements.
- Thresholds are calibrated on two recordings from one setup; monitor brightness, GPU
  scaling, HDR and UI-scale differences need live validation.
- The overlay defence (ceiling plus margin plus bimodal gap plus debounce) is measured
  on the observed banners, not on every banner the game can show.
- Outward grid edges stay unknown and unmodelled; only validated interior plans execute.
- 1080p stays evaluation-only.

## Next

Stage 8: controlled live GTA end-to-end testing with the acceptance protocol it defines:
real round-advance timing, live threshold validation, abort/drill behaviour with GTA
focused, and only then any claim of live-game reliability.


Later validation note (Stage 8D.1): the production path described here was later validated in a real 2560x1440 multi-door vault run; see [stage8d1-live-validation.md](stage8d1-live-validation.md).
