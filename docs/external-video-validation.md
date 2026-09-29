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

Before any control reading may mutate an attempt, an evaluation-only puzzle-panel
presence gate must report PRESENT. The gate measures four static chrome anchors
(timeout, clone-target, components, access-attempts header bars) plus central green
excess for the HACK SUCCESS overlay. It never uses the predicted fingerprint id, the
candidate set, solver confidence, or the selected set itself. While presence is ABSENT
or AMBIGUOUS the tracker preserves prior state: no round is started, no selection is
recorded, and no four-set is replaced from walls, HACK SUCCESS screens, gameplay, or
other non-puzzle pixels.

After a first four-set, the set freezes as the candidate successful attempt. A clear
immediately after four does not eagerly fail it: the round enters FOUR_SELECTED_PENDING
and waits for either a genuine same-puzzle retry (panel continuously PRESENT, new
selection sequence proving the same puzzle continues, previous four kept as failed)
or a credible structural next-round transition (previous four confirmed, next round
seeded). A panel ABSENT gap after four proves the panel left: post-puzzle ROI readings
are ignored, the frozen four survives, and a later credible transition confirms it.
Direct cuts with no sampled absent frame stay pending; fast next-round input before
recognition re-stabilizes does not convert the frozen four into a failure unless the
same puzzle is proven. Anything indistinguishable fail-closes to NEEDS_REVIEW_AMBIGUOUS.

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

- `screenshots/` - full frames at prediction, final-four, transition, ambiguous, and
  panel-presence moments (occurrence-indexed, for example
  `round-002-final-four-01-full.png`);
- `crops/` - panel, target and candidate crops for every key moment
  (`round-001-start-01.png`, `round-001-prediction-01.png`,
  `round-001-final-four-01.png`, `round-001-transition-01.png`, plus target/candidate
  crops with the same occurrence suffix). Repeated moments never overwrite: the second
  final-four becomes `round-002-final-four-02.png`, and so do its full, target, and
  candidate files. For panel-absent, panel-ambiguous, and transition frames, full plus
  broad panel-area crops are always preserved, but target and C0..C7 crops are written
  only when panel presence on that frame is PRESENT, so walls, HACK SUCCESS screens,
  and characters never produce meaningless candidate crops. Nothing is ever deleted.
- `rounds.csv` - one benchmark row per observed round;
- `events.csv` - changed-only event history (ROUND_START, PREDICTION,
  SELECTION_CHANGE, ATTEMPT_RESET, FOUR_SELECTED, NEW_ROUND_TRANSITION, ROUND_CONFIRMED,
  PANEL_PRESENT, PANEL_ABSENT, PANEL_AMBIGUOUS, AMBIGUOUS, SESSION_END). Presence
  events are logged only on change, never per frame, and explain why post-four
  readings were ignored.
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

## Post-puzzle attribution (youtube-clean-01)

The first clean external session exposed a harness attribution bug, not a solver bug:
after the real fingerprint UI ended, fixed candidate ROIs evaluated over HACK SUCCESS
screens, gameplay, walls, and characters occasionally produced plausible selected sets
(the same bogus [2,3,6,7] appeared in unrelated rounds), fabricating MISMATCH results.
All six recognition predictions had matched the first real human four-sets. The source
itself remains clean: uncropped fullscreen gameplay, no picture-in-picture, no facecam,
six human rounds all solved correctly on the first attempt. The fix is the presence gate
above plus the post-four freeze, pending retry logic, occurrence-indexed evidence, and
present-only target/candidate crops. Old `youtube-clean-01` artifacts must not be
rewritten or claimed as fixed: after review and merge, the user replays the same video
as a fresh `youtube-clean-01-v2` session and compares. No v2 result is claimed here.

## Excluded source (youtube-01)

`target/stage8a/youtube-01/` stays excluded from quantitative Stage 8A evidence because it
contains a second fingerprint gameplay feed as picture-in-picture. It may be used only as
adversarial or debug evidence, never counted in MATCH/MISMATCH numbers.

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
