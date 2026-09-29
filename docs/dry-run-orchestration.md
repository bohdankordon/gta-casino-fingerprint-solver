# Dry-run solve orchestration (Stage 7A)

Stage 7A builds the first end-to-end SOLVER ORCHESTRATION layer with ZERO gameplay input.

The production flow is now conceptually:

```
captured frame
    -> FrameRecognitionPipeline.observe (SAME-frame owned observation)
    -> RecognitionConsensusTracker
    -> RoundLifecycleWitnessCoordinator
    -> ROUND_READY(identity)
    -> dry-run solve planner
    -> deterministic abstract action plan
    -> lifecycle consume + witness baseline re-arm (executable plans only)
```

NO action is sent to GTA. The output is a DRY-RUN plan only: domain intentions
`(Navigate, Select, Proceed)`, never key codes, never Robot calls, never native input.

## Scope (exact)

Stage 7A adds:

- a pure navigation value model (`navigation`: `Move`, `GridPosition`,
  `GridNavigationPolicy`, `ProvenGridNavigationPolicy`);
- a pure dry-run planner (`DryRunPlanner`) with BFS shortest paths and exhaustive
  24-order optimization, plus its result type (`DryRunPlan`) and an independent validator
  (`PlanValidator`);
- one production orchestration component (`orchestration.DryRunSolveOrchestrator`) that
  combines the existing pipeline, consensus, lifecycle and witness pieces in the mandated
  same-frame order, builds a plan on every NEW_ROUND_READY, and consumes executable rounds
  through the coordinator with the SAME observation;
- a separate dry-run CLI (`DryRunSolverMain`, every line labelled DRY RUN);
- a real-recording dry-run replay (`DryRunReplay` / `DryRunReplayMain`) over the same
  full-rate private dataset, with ignored artifacts below `target/`;
- synthetic CI tests and input-freedom surface guards.

Stage 7A does NOT add, touch or tune:

- `java.awt.Robot`, key/mouse events, SendInput, native input of any kind;
- auto-solving, sleeps for gameplay, key-hold timing, input delays;
- production 1080p support (1080p stays evaluation-only geometry, as in Stage 6);
- matcher, policy, witness, consensus or lifecycle tuning;
- new recognition heuristics;
- changes to `StructuralNormalizer`, `StructuralSimilarityScorer`, `RecognitionPolicy`,
  matching radii, solver assignment, ROI layout, capture backend, witness thresholds,
  consensus semantics or lifecycle semantics.

If Stage 7A ever required changing any of those, the stage would stop and report instead.
It did not: the production diff is purely additive (new packages plus two new app/evaluation
entry points; `LiveRecognitionMain` is unchanged).

## Navigation characterization: facts vs unknowns

The two private recordings under `local-data/stage6/` (8 annotated rounds, 2 resolutions)
were traced with a top-edge focus-brightness signal plus brightness-digit panel renders
(`target/stage7a-nav/`, ignored; method scripts kept alongside the CSV). Raw per-sample
traces are summarized in the ignored `target/stage7a-navigation-observations.csv`.

### FACTS (observed, with evidence)

1. **Round-start focus is C0, 8/8 rounds**, both resolutions, from the first actionable
   frame (puzzle fully visible, pre-first-Enter).
2. **Round transitions reset focus to C0, 4/4 hacks** (prior end focus C1/C4/C6/C3/C1
   all returned to C0). The reset lands inside the transition animation; the exact
   trigger frame is unobservable, the C0 outcome is certain.
3. **Moves are discrete single orthogonal steps.** A 30 fps micro-test shows a C5->C4
   move completing in ONE frame (33 ms) with no glide: arrow-step dynamics, not a mouse
   sweep. Every resolved move in all 8 rounds lands on an orthogonally adjacent tile;
   no diagonal or remote jump was ever observed.
4. **All 20 interior directed transitions were witnessed** (each direction of every
   orthogonal adjacency at least once), so the interior graph is strongly connected.
5. **Enter holds focus: 33/33 presses** (32 correct + 1 wrong) left the selector
   unmoved; the cursor never auto-moves after Enter.
6. **Selected candidates stay navigable**: focus routinely enters, rests on and leaves
   already-selected tiles.
7. **Wrong-selection ERROR (1080p H1R2, one example)**: focus held C7 through the whole
   74.4-76.4 s ERROR oscillation, then the game reset focus to C0 at 76.5 s. The wrong
   C5 cleared while focused at ~78.3 s; the round completed with the correct set.
8. **Round advance lands ~2-4 s after the 4th selection** (presumed Tab; the keypress
   itself is not visible in video).

### UNKNOWNS (marked, never assumed)

- **Edge behaviour**: no outward edge step (UP from row 0, DOWN from row 3, LEFT from
  column 0, RIGHT from column 1) was ever attempted in 100+ observed moves. Clamp-vs-wrap
  is UNKNOWN and unmodelled: edge transitions are simply absent from the proven graph.
  No optimal tour from C0 over the interior graph ever needs one.
- **Six rapid double-taps** (<0.11 s, diagonal endpoints, intermediate unobserved) were
  excluded from the proven set: each is two fast arrows, but the middle tile is unresolvable
  at 10 fps sampling, so they prove adjacency exists, not which path was taken.
- **Direction-label convention**: moves are labelled by screen geometry (row-1 = UP etc.).
  The labels are behaviourally consistent (efficient human play under the standard mapping,
  zero counterexamples); the eventual key mapping is Stage 7B work.
- **C5-clear mechanism** (manual deselect vs auto-clear) is not distinguishable on video;
  the fact (cleared while focused, focus unmoved) is what the stage relies on.

## Grid model

The production model is the graph of PROVEN transitions, not an invented complete rule:

- 8 positions (`GridPosition` C0..C7, row-major 0 1 / 2 3 / 4 5 / 6 7);
- 20 directed interior transitions (every orthogonal adjacency, both directions);
- 12 outward edge transitions ABSENT (unknown, fail-closed);
- start C0 supplied by the characterized `NavigationContext`.

Breadth-first search over this graph routes every pair (the interior graph is strongly
connected), so all 8 real rounds plan executably. If wrap-around were proven one day, every
emitted plan would stay executable (all its moves are witnessed-legal); only theoretical
optimality could improve. That limitation is documented, not guessed away.

## Optimal selection-order algorithm

Given the four correct candidates and start C0, the planner prices every one of the 4! = 24
visit orders (lexicographic enumeration via next-permutation over the sorted set):

```
cost(order) = dist(start, first) + dist(first, second)
             + dist(second, third) + dist(third, fourth)
```

where each `dist` is the BFS shortest-path length in moves. The minimum wins; ties break
lexicographically by candidate-index sequence (first minimum kept during ordered
enumeration, so the outcome is deterministic). Timing never enters the cost and SELECT
actions are never optimized away: the price is pure navigation. An independent Manhattan
oracle in CI re-proves optimality on many start/set combinations without sharing planner code.

## Shortest-path algorithm

Breadth-first search from the leg origin over the proven graph (2x4 cells: trivially small,
robust to any future edge semantics, no giant movement table). Neighbours expand in `Move`
declaration order (UP, DOWN, LEFT, RIGHT) under FIFO discipline, so every shortest path is
deterministic; CI asserts all 64 pairwise paths equal their Manhattan clamp distance (truly
minimal) and reproduce byte-identically across runs.

## Abstract action semantics

A READY plan renders abstractly, for example:

```
START C0
RIGHT
SELECT C1
DOWN
SELECT C3
DOWN
SELECT C5
LEFT
SELECT C4
PROCEED
```

(illustrative: the real H1R1 plan is `START C0 RIGHT SELECT C1 DOWN DOWN SELECT C5 LEFT
SELECT C4 DOWN SELECT C6 PROCEED`, 5 moves, order [1,5,4,6]).

- `Navigate(move)` (rendered as the bare direction) is the intention to step the selector
  along one proven transition.
- `Select(candidate)` (rendered `SELECT Cn`) confirms the focused candidate.
- `Proceed` (rendered once, last) represents the future round advance.

SELECT and PROCEED are DOMAIN ACTIONS ONLY. The docs may say they will eventually map to
arrows/Enter/Tab in Stage 7B, but no code contains key codes, Robot calls or Windows input
calls, and the recognition-only surface tests were extended to keep it that way.

## Plan validation

`PlanValidator` independently replays (start, actions, policy) as pure Java and proves:
legal Navigate transitions; Select of the focused candidate only; no duplicate selection;
selected set exactly the expected four; PROCEED only after all four selections, exactly once,
last, with nothing after it. The real replay validates every emitted plan; CI feeds the
validator both planner output (must pass) and six hand-built failure classes (must fail).

The whole-plan overload `validate(DryRunPlan, GridNavigationPolicy)` additionally proves the
READY metadata itself: start present; order of exactly four distinct indices equal to the
identity set; exactly four SELECTs in order sequence; Navigate count equal to
`navigationMoveCount` (non-negative); `actionCount` equal to the action list size; exactly
one PROCEED. `DryRunPlan.ready(...)` rejects inconsistent metadata at construction (wrong
order set, drifting SELECT sequence, wrong move count, missing/multiple/non-final PROCEED,
negative moves); navigation legality stays the validators job because it needs the policy.

Production consumption gate: the orchestrator re-validates every READY plan through the
independent validator BEFORE `consumeReadyRound` is reachable. A violation converts the
result to BLOCKED (`planner validation failed for ...` plus the violations) and the round
stays pending. No invalid plan is ever lifecycle-consumed.

## Orchestrator call chain

```
DryRunSolveOrchestrator.onFrame(frame):
  observation = pipeline.observe(frame)            // SAME-frame owned observation
  status      = consensus.accept(observation.decision())
  lifecycle   = coordinator.accept(status, observation)
  if lifecycle.newRoundReady():
      plan = DryRunPlanner.plan(lifecycle.readyIdentity, navigationContext)
      checked = requireValid(plan, navigationContext.policy) // independent re-validation
      if checked.executable:
          coordinator.consumeReadyRound(observation) // SAME observation, re-arms baseline
      // invalid plans convert to BLOCKED with "planner validation failed: ..." reasons and
      // are never consumed: the round stays ROUND_READY, nothing is reset or cleared.
```

Decision-free conditions (unsupported size, capture errors) take the existing no-observation
coordinator path: streak reset, no plan, no hack ending, no lifecycle/witness clearing.

## Round ownership / consumption semantics

Consumed in Stage 7A means the orchestrator took ownership of the dry-run round for replay
bookkeeping. It does NOT mean GTA received input and NOT that the puzzle succeeded.
Concretely it pairs two atomic facts: the lifecycle round is acknowledged AND the witness
baseline re-arms on the exact content of the consumed frame (the coordinators existing
prepare-consume-install order). No cross-frame consumption exists: the observation accepted
is the observation consumed, enforced by reference identity. BLOCKED plans are never
consumed, so unresolved navigation assumptions stay pending and visible.

## Real replay (private dataset, full rate)

`DryRunReplay` runs every decoded frame inside the padded hack windows through the
PRODUCTION orchestrator (no second consensus/lifecycle/witness/planner copy):

```
8 ROUND_READY, 8 executable dry-run plans, 8 lifecycle consumptions,
0 blocked, 0 incorrect sets, 0 duplicate selections, 0 desync, 0 consume failures.
```

Per round it records the READY frame, identity, start C0, chosen order, abstract actions,
move count, consumption, validator pass, annotation match (round1/round2) and determinism
re-plan. The counterfactual A-to-A check plans the substituted repeated identity through the
pure planner (READY, valid, deterministic on all four hacks) without touching any coordinator
boundary; the production witness A-to-A behaviour itself is still proven by the re-run
Stage 6C.1D replay (8 READY, 8 consumed, 4 witnessed). Artifacts:

- `target/stage7a-dry-run-events.csv` (one row per lifecycle event with plan columns);
- `target/stage7a-dry-run-summary.csv` (one row per hack);
- `target/stage7a-dry-run-report.txt` (human-readable totals plus counterfactual rows).
- `target/stage7a-navigation-observations.csv` (focus-trace evidence for the grid model).

## Blocked-plan contract

Unknown start or any unreachable leg yields BLOCKED with the exact missing preconditions
(e.g. `unknown selector start position...`, `no proven navigation from C2 to C7`) and an
EMPTY action list: no pretend sequence. The orchestrator reports the blocked plan and does
not consume, so the round stays ROUND_READY until a reset or a desynchronization. CI covers
BLOCKED-on-unknown, BLOCKED-on-unreachable, empty actions, and never-consumed (state stays
ROUND_READY, nothing acknowledged, witness unarmed).

## No-input guarantee

- New packages (`navigation`, `orchestration`, dry-run app/evaluation entries) contain no
  `java.awt.Robot`, `KeyEvent`, `MouseEvent`, `keyPress/keyRelease`, `mousePress/mouseRelease`,
  `SendInput` or `java.awt.event` (pinned by `DryRunSurfaceTest` with precise tokens, so
  neutral words like UP/DOWN/SELECT/PROCEED stay usable).
- Orchestration correctness is event/state based: no `Thread.sleep`, clocks or durations in
  `navigation`/`orchestration` (the CLI capture-loop interval is scheduling, like the
  recognition-only watch loop, and lives only in the CLI).
- Every CLI line is labelled DRY RUN and ends with NO INPUT SENT; the CLI has no code path
  that could press anything.
- `LiveRecognitionMain` is byte-identical: recognition-only behaviour did not change.

## What Stage 7B adds later (not here)

Actual input: arrow-key movement, Enter selection, Tab advance, timing, focus verification,
and live solving policy. Only after independent review and merge of this stage, and only with
the proven transition graph plus the C0-start characterization as its contract. Nothing in
Stage 7A presses, holds, sleeps for gameplay, or maps an abstract action to a key code.
