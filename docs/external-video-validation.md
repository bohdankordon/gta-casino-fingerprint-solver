# External-video validation (Stage 8A)

Passive, input-free validation of the solver against public gameplay videos: one program run
observes exactly one source video, records every solver prediction against the human player's
subsequent selections, and writes screenshots, CSVs and a report locally.

> One run = one video. No program input is ever sent.

## What this validates

The current production pipeline (recognition, consensus, lifecycle, witness, optimal plan)
is exercised against real-world video: scaling, compression, gamma differences, graphics
settings and encoders. Solver intelligence is NOT tuned here: difficult videos produce
evidence (NO_PREDICTION, LATE_PREDICTION, NEEDS_REVIEW), never recognition changes. If
production behaviour looks wrong, it is recorded faithfully and reviewed, not tuned in
this workflow.

## Requirements

1. A physical 2560x1440 monitor and capture. The run refuses any other geometry: the
   production layout is valid for physical 2560x1440 only. The SOURCE video itself may be
   1920x1080, 2560x1440 or any other 16:9 resolution, as long as the browser scales the
   uncropped 16:9 gameplay to the full physical 2560x1440 screen.
2. One uncropped 16:9 gameplay video per run:
   - normal 16:9 gameplay, uncropped GTA frame;
   - no picture-in-picture, no facecam covering the fingerprint panel;
   - no edited zoom around the minigame;
   - browser video in true fullscreen, controls allowed to auto-hide.
3. Java + Maven repository checkout at the Stage 8A commit.

## Workflow

1. Choose ONE video and a fresh session name, for example `youtube-01`.
2. Start the session (Windows example):
   ```powershell
   .\mvnw.cmd -B -ntp compile exec:java `
     "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.ExternalVideoValidationMain" `
     "-Dexec.args=--monitor 0 --watch --session youtube-01"
   ```
   Optional metadata and capture interval:
   ```powershell
   "-Dexec.args=--monitor 0 --watch --session youtube-01 --source-label 'creator - video title' --interval-ms 100"
   ```
   Startup prints:
   ```
   EXTERNAL VIDEO VALIDATION
   PASSIVE CAPTURE ONLY
   NO INPUT WILL BE SENT
   session: youtube-01
   ```
   There is no `--enable-input` flag: the CLI rejects it outright.
3. Switch to the browser, enter true fullscreen, move the mouse away so controls
   auto-hide, and press play.
4. Play normally. DO NOT pause at each fingerprint: every round is detected passively.
5. After the relevant section, return to the terminal and press Ctrl+C.
6. Inspect:
   - `target/stage8a/youtube-01/report.txt`
   - `target/stage8a/youtube-01/rounds.csv`
   - `target/stage8a/youtube-01/screenshots/` and `crops/`
7. Start a NEW session for the next video. An existing session directory is never
   overwritten: the run refuses to start if `target/stage8a/<session>/` exists.

## Session model

One program run is one validation session for one source video. A single video may contain
many fingerprint rounds (round 1, 2, 3, 4, ...); the tracker follows all of them until
Ctrl+C. Several videos are never mixed into one session.

## Ground-truth rule

A set of four selected tiles is NOT automatically ground truth: the player may be wrong
(our own Stage 6 recording contains a wrong-C5 ERROR episode with a retry). Within one
observed round, a selection that clears or shrinks while the same puzzle continues ends
the current attempt: the previous attempt is kept as failed/ambiguous history, never as
ground truth. Brief detector ambiguity preserves state and invents no change.

The only automatic ground truth is a human four-set followed by a genuine structural
next-round transition (the production lifecycle NEW_ROUND_READY, including witnessed
same-identity transitions). Event attribution is explicit: a NEW_ROUND_READY event is
classified as first prediction for the active human round, duplicate current-round
prediction, credible subsequent-round event, or ambiguous. Only a credible
subsequent-round event may automatically confirm the previous four-set. In particular:

- a NEW_ROUND_READY that introduces the FIRST prediction assigned to the active human
  round is that round's own current-round prediction (ON_TIME or LATE); it can never
  simultaneously confirm that same round, even when identities happen to match;
- a selection clear or shrink never by itself upgrades the previous four-set to
  successful ground truth, and a later FIRST prediction after such a reset never
  retroactively confirms the old four-set (an ERROR reset and a successful transition
  are indistinguishable here, so the boundary fail-closes);
- a different prediction identity arriving while the old four-set is still visibly
  displayed never confirms by identity change alone; it is kept diagnostically with the
  first prediction staying primary, and the round fail-closes unless independent
  subsequent evidence (the old four-set leaving the display plus a later credible
  transition) establishes the boundary;
- a clean next-round prediction ordinarily arrives after the new empty/C0 state is
  visible, because the control stream leads recognition re-stabilization;
- NO_PREDICTION is conservative: a human round that never received a prediction is only
  auto-labelled successful on a genuine structural transition; an ambiguous boundary
  without independently provable success becomes NEEDS_REVIEW_AMBIGUOUS with all
  screenshots and events preserved for manual adjudication, never a fabricated
  NO_PREDICTION success. A manually reviewed successful no-prediction case can still be
  counted later. Fewer automatic confirmations are acceptable; false automatic ground
  truth is worse than manual review.

The comparison is SET-based, independent of selection order:

- predicted set == observed successful set -> MATCH (only when predicted before the
  first observed selection);
- predicted set != observed successful set -> MISMATCH (mandatory review before Stage 8B);
- successful human round with no prediction -> NO_PREDICTION (counted, never hidden);
- prediction after selection began -> LATE_PREDICTION (diagnostically noted, never counted
  as production success);
- final four-set with no strong success proof (panel exit, banner, cut) ->
  NEEDS_REVIEW_FINAL_EXIT with saved screenshots;
- ambiguous boundary (first-prediction transition claim, bare transition without
  prediction while a four-set exists, or identity change while the old four-set is still
  displayed) -> NEEDS_REVIEW_AMBIGUOUS with transition and ambiguous keyframes preserved;
- session ends mid-selection -> INCOMPLETE;
- solver ROUND_READY with no observed control round -> ORPHAN_PREDICTION (possible false
  positive, never silently discarded).

A source whose layout cannot satisfy the production geometry yields diagnostics and no
usable rounds, not fake benchmark failures: `observed rounds = 0` with source-layout
guidance is a valid session outcome.

## Manual review guidance

Normally only these need inspection: MISMATCH, NO_PREDICTION, LATE_PREDICTION and
NEEDS_REVIEW_*. MATCH cases keep keyframe evidence but need no routine work.
Predicted-FP2 cases need a manual target-screenshot review before they count as actual
FP2 coverage: the predicted fingerprint label is a prediction, never independent ground
truth (see the report's predicted-FP-distribution note).

## Artifacts

Everything lands below ignored `target/stage8a/<session>/` and is never committed:

- `screenshots/` - full frames at prediction and final-four moments;
- `crops/` - panel, target and candidate crops for every key moment
  (`round-001-start.png`, `round-001-prediction.png`, `round-001-final-four.png`,
  `round-001-transition.png`, plus target/candidate crops);
- `rounds.csv` - one benchmark row per observed round;
- `events.csv` - changed-only event history (ROUND_START, PREDICTION,
  SELECTION_CHANGE, ATTEMPT_RESET, FOUR_SELECTED, NEW_ROUND_TRANSITION, ROUND_CONFIRMED,
  AMBIGUOUS, SESSION_END);
- `report.txt` - concise human report with counts, predicted FP distribution and
  per-round outcomes;
- `session.json` - session metadata plus outcome counts.

Per-round CSV columns: session, round, first_seen_ms, prediction_ms, first_selection_ms,
success_or_end_ms, predicted_fingerprint, predicted_candidates, planned_order,
navigation_moves, observed_success_candidates, attempts, result, prediction_timing,
transition_witness_used, notes.

## Evidence target for the first real batch

At least 20 externally observed human rounds across at least 3 independent source
videos (more if easy). Report on-time predicted rounds, NO_PREDICTION, LATE_PREDICTION,
MATCH, MISMATCH and NEEDS_REVIEW separately. Any MISMATCH is a mandatory review case
before Stage 8B. Collect predicted-FP2 cases, but count actual FP2 coverage only after
manual target confirmation.

## Rehearsal (maintainers)

Before YouTube, the private Stage 6 recordings rehearse the same tracker without video
replay infrastructure:

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.evaluation.externalvideo.ExternalVideoRehearsalMain"
```

It needs the private recordings below `local-data/stage6/` (never committed, never
required in CI) and writes ignored artifacts below `target/stage8a-rehearsal/`,
including `rehearsal-check.txt` per source comparing tracker reconstruction against the
committed annotations. Strict rules hold: no rule is weakened to reach confirmation
numbers, and review rounds stay manual.
