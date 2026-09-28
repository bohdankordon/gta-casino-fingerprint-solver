# Independent visual round-transition witness characterization (Stage 6C.1C)

Stage 6C.1B made the production round lifecycle fail closed on a repeated answer identity: after an
answer has been consumed, the same identity can never become ready again, no matter how many
uncertain frames or capture problems sit between the two stable episodes. That is the right default,
because the recordings contain no example of two consecutive rounds with the exact same identity,
and guessing there would risk double-consuming one round.

Stage 6C.1C asks the next question and answers it with measurements: can an INDEPENDENT visual or
content signal prove that the puzzle content changed, even when `RecognitionIdentity` hypothetically
stays the same?

This stage is characterization only. It adds no production transition witness, changes nothing in
`RoundLifecycleTracker` or `RecognitionConsensusTracker`, weakens nothing in the current fail-closed
behaviour, sends no input, adds no timer or cooldown, tunes no matcher, threshold or ROI, and adds no
production 1920x1080 support. It measures; the decision gate at the end of this document is a
recommendation for a later stage.

## Method

For every one of the eight annotated rounds:

1. every decoded frame inside the hack window padded by 2.0 s is decoded sequentially at full source
   rate (no sampling) and pushed through the unmodified production recognition path;
2. the decisions are fed through the unmodified production `RecognitionConsensusTracker`;
3. the first `STABLE_RECOGNIZED` frame whose identity equals the annotated round answer freezes a
   content baseline - the same point a Stage 6C.1B consumer immediately consumes. The baseline is one
   deterministic snapshot and is never adapted;
4. every other frame is measured against that frozen baseline (entry frames against the round-1
   baseline once it exists, new-round frames against the new baseline, plus a bounded window that
   keeps the OLD baseline so persistence can be measured);
5. the measured frames are split into populations - same round, transition, persistence window,
   entry and exit - and every candidate rule is scored against all of them.

| source | resolution | geometry | analyzed frames | decoded frames | wall time |
| --- | --- | --- | --- | --- | --- |
| `recording_1440p` | 2560x1440 | production layout, unmodified | 1890 | 4262 | ~103 s |
| `recording_1080p` | 1920x1080 | evaluation-only uniform 0.75 | 2552 | 4988 | ~110 s |

4442 analyzed frames in total. The recordings stayed local: their committed size and SHA-256 were
verified before anything was decoded, no frame is committed, and every artifact lands below the
ignored `target/` tree.

The consumed-frame baselines match the Stage 6C.1B consumption points exactly, frame for frame:

| hack | round 1 consumed at | round 1 identity | round 2 consumed at | round 2 identity |
| --- | --- | --- | --- | --- |
| `recording_1440p` H1 | f525 / 17.500 s | `FP_4[1;4;5;6]` | f1019 / 33.967 s | `FP_3[1;4;5;6]` |
| `recording_1440p` H2 | f3582 / 119.400 s | `FP_1[1;3;4;6]` | f3896 / 129.867 s | `FP_3[2;4;6;7]` |
| `recording_1080p` H1 | f1181 / 40.791 s | `FP_3[0;1;3;6]` | f1707 / 58.929 s | `FP_1[0;4;6;7]` |
| `recording_1080p` H2 | f3885 / 134.033 s | `FP_3[1;3;6;7]` | f4412 / 152.205 s | `FP_4[1;2;6;7]` |

## CANDIDATE WITNESSES

Five families were measured independently. W0 is a control, not a candidate.

| family | what it compares | notes |
| --- | --- | --- |
| **W0** raw panel difference | mean absolute grayscale difference over the whole puzzle panel | control; expected to react to interface state |
| **W1** target-only structure | production `StructuralSimilarityScorer` between the current normalized target and the frozen baseline target (radius 8 px on the 256x384 profile) | cannot see anything when the target repeats |
| **W2** per-candidate structure | the same scorer between each current candidate and the SAME POSITION baseline candidate (radius 4 px on the 128x128 profile), reported as C0..C7 plus min/mean/median | brightness-robust: the normalizer's percentile stretch removes the unselected/selected difference |
| **W3** full puzzle signature | aggregates over target plus eight candidates: weakest and mean similarity, and the number of changed regions at analysis cuts | the family a production rule would most plausibly use |
| **W4** matching evidence signature | target score vector across `FP_1..FP_4`, the eight by four fragment grid against the baseline's fixed reference fingerprint, and the assignment diagnostics | measured, but two of its components are identity-linked and are excluded from a witness |

The structural comparison reuses the production `StructuralSimilarityScorer` with the production
profile sizes and translation radii, because a frozen consumed frame is exactly the kind of reference
profile production already compares against. Nothing in production was changed.

## FACTS FROM DATA

### Within-round robustness (the population a witness must not fire on)

3800 same-round frames were measured. They include selector movement, 0/1/2/3 selected candidates,
the fully selected state, the real wrong C5 selection with the ERROR banner and its clearing, and
every recognition interruption and re-stabilization.

| signal | worst same-round | weakest true transition | clean separation |
| --- | --- | --- | --- |
| W0 raw panel difference | 24.70 | 7.45 | no: overlap (the control is rejected) |
| W1 target similarity | 0.5032 | 0.2500 | yes, margin 0.2532 (target-only) |
| W2 weakest candidate similarity | 0.0593 | 0.0400 | yes, margin 0.0193 (thin) |
| W2 mean candidate similarity | 0.7785 | 0.1422 | yes, margin 0.6362 |
| W3 changed regions below 0.90 | 3 of 9 | 9 of 9 | yes, 6 regions |
| W3 changed regions below 0.50 | 2 of 9 | 9 of 9 | yes, 7 regions |
| W4 target score vector delta | 0.3591 | 0.6101 | yes, margin 0.2510 |
| W4 fixed-reference grid mean delta | 0.0309 | 0.1413 | yes, margin 0.1105 |

Two facts stand out.

1. **W0 is polluted, exactly as expected.** The worst same-round raw panel difference (24.70) is
   larger than the weakest true transition (7.45). Its sensitivity comes from interface state, not
   content: at cut 2.0 it fires on 3068 of 3800 same-round frames; at cut 30.0 it is silent but then
   detects 0 of 4 transitions. Raw pixel difference must not be promoted.
2. **The dominant same-round deviation is one recurring UI element.** Every top same-round frame of
   every round - visible directly in the local review sheets under
   `target/stage6c1c-witness-contact-sheets/` - is the white SIGNAL PATCH notification banner that
   covers part of the target and of one or two candidate tiles. It is why the weakest same-round
   region similarity sits at 0.059 instead of ~1.0, and it is also why the production recognition
   itself refuses 11 of those frames (`TARGET_SCORE_TOO_LOW`). It is a measured limitation of any
   content witness; it is not puzzle content.

The real wrong-selection ERROR episode left no structural trace: all 723 same-round frames of that
round stayed `RECOGNIZED` with the correct answer, and no frame of any round exceeded 3 changed
regions at the 0.90 cut or 2 at the 0.50 cut.

### Real transitions

All four R1 to R2 transitions, measured against the OLD consumed baseline:

| transition | last old stable | first new recognized | first new stable | changed regions at 0.90 there |
| --- | --- | --- | --- | --- |
| `recording_1440p` H1 | f1016 | f1017 | f1019 | 9 of 9 |
| `recording_1440p` H2 | f3893 | f3894 | f3896 | 9 of 9 |
| `recording_1080p` H1 | f1704 | f1705 | f1707 | 9 of 9 |
| `recording_1080p` H2 | f4409 | f4410 | f4412 | 9 of 9 |

The content change is complete: the weakest of the nine regions at a transition is still below 0.50,
so a rule that requires a majority of regions to change has a wide gap to the worst same-round
observation (2 of 9).

### Rule and threshold exploration

104 rules were evaluated over the 3800 same-round frames and the four transitions. 52 are clean on
this dataset: zero same-round false triggers AND every transition detected. 47 already fire inside an
observed same round; 5 stay quiet same-round but miss a transition.

The best clean rules by *balanced* margin, which is the smaller of the worst-case safety gap and the
transition slack:

| rule | same-round safety | transition slack | balanced |
| --- | --- | --- | --- |
| `W3_REGIONS_LT_0.5_K6` (at least 6 of 9 regions below 0.50) | 4 | 3 | 3 |
| `W3_REGIONS_LT_0.5_K4` | 2 | 5 | 2 |
| `W2_CANDIDATES_LT_0.9_K4` (at least 4 of 8 candidates below 0.90) | 2 | 4 | 2 |
| `W3_REGIONS_LT_0.9_K4` | 1 | 5 | 1 |

Detection timing is identical for all of them: the rule fires on the first frame of the new content,
which is also the first frame the new answer is recognized (offset 0 versus first new recognized,
-2 frames versus first new stable). No clean rule fires before the old answer disappears, which
would have been an early-trigger warning sign.

### Entry and exit

Every clean rule fires on essentially every entry frame (305) and every exit frame (302): before the
puzzle panel exists and after it is cleared, the structural comparison sees a total change. A
production witness would therefore have to be armed only while `RoundLifecycleTracker` holds a
consumed round; the entry and exit populations are excluded from the same-round population of this
measurement for exactly that reason.

### Same-target analysis

Two same-target round pairs are directly comparable (same capture geometry, non-consecutive rounds):

| pair | target | W1 target similarity | W3 changed regions at 0.90 | W4 grid mean delta |
| --- | --- | --- | --- | --- |
| `recording_1440p` H1R2 vs H2R2 | `FP_3` | 0.998 | 7 of 9 | 0.203 |
| `recording_1080p` H1R1 vs H2R1 | `FP_3` | 0.992 | 7 of 9 | 0.189 |

Target-only W1 cannot separate them (it is ~1.0 by construction), while the candidate grid and the
region aggregates can. Further same-target pairs cross capture geometries (1440p against the
evaluation-only 1080p geometry) and are deliberately not compared, because a resampled capture would
mix a real content change with a resolution change.

These pairs are not consecutive rounds. They support the "same target, different candidate layout"
case and they prove nothing about a future consecutive round pair.

## COUNTERFACTUAL SAME-IDENTITY TEST

The recordings contain no A to A transition, so the four real transitions were replayed as if the
round-2 identity were the round-1 identity:

- pixels are untouched; only the identity metadata of the analysis is rewritten;
- the witness rule reads content features only and produced a bit-identical outcome, which the
  analysis verifies and reports (`features_identical_under_counterfactual`);
- the real per-frame consensus timeline was replayed through the unmodified production
  `RoundLifecycleTracker` with the same identity override: one ready round, and the second onset
  reported as a repeated consumed identity (1 to 3 suppressed onsets per hack), so the second round
  never becomes ready - the exact fail-closed limitation this stage is about.

This proves exactly one thing: if the visual content changes like these observed transitions, the
witness does not need an identity change to see it. It does not prove that every real same-identity
round pair changes its visual content, and it is not evidence that such a pair exists.

## EXACT-VISUAL-REPEAT LIMITATION

Every consumed baseline frame was also measured against itself (8 control rows). The minimum region
similarity is 1.0, the raw panel difference is 0.0 and the evidence signatures do not move, so no rule
in the sweep fires.

An exact visual repetition - same target, same eight tile contents, same positions - is therefore
observationally indistinguishable to this witness and must stay fail-closed. The witness can prove
that content changed; it can never prove that content is new.

## DESIGN INFERENCE

What the measurements support for a later stage:

1. A content witness is possible without any identity input: the feature computation has no field,
   parameter or branch that mentions a fingerprint, a selected candidate set, a consensus state or a
   lifecycle state, and a static test enforces that.
2. The useful family is the multi-region structural change (W3, corroborated by W2 at the same
   positions and by the identity-free W4 signatures). A target-only rule would fail exactly in the
   case a witness is needed for, and a raw pixel rule is dominated by interface state.
3. The plausible rule shape is "at least K of the nine regions structurally changed, at a similarity
   cut around 0.50". On this dataset K between 4 and 8 separates the populations; the balanced choice
   is around 6, which tolerates the observed UI banner four times over while still seeing every true
   transition at its first frame.
4. The witness must be armed: it fires on entry and exit frames unconditionally. Its natural arming
   condition is "a round was consumed and is still the latest consumed identity".
5. The witness may only ever ADD information: a content change may permit a repeated identity to be
   treated as a new round, but a missing content change must keep the current fail-closed behaviour,
   and the witness must never be able to create a round on its own.
6. No threshold from this stage is a production constant. The cut and the count are measurements of
   four transitions, and the next stage must re-measure them before fixing anything.

## DECISION GATE FOR NEXT STAGE

1. **Which witness families were measured?** W0 raw panel difference (control), W1 target-only
   structural similarity, W2 per-candidate same-position structural similarity, W3 the nine-region
   aggregate, W4 matching and assignment evidence signatures.
2. **Which are rejected and why?** W0 is rejected: its same-round worst case (24.70) exceeds its
   weakest transition (7.45), because it measures interface state rather than content. A rule that
   uses only W1 is rejected as a witness for the case that needs one, because two rounds can share a
   target fingerprint. W4's assignment-mapping component is rejected as a witness input because it
   depends on the recognition answer; only its identity-free components (target score vector,
   fixed-reference grid) are measured as candidates. 47 of 104 rules are rejected because they fire
   inside an observed same round.
3. **Does any candidate produce ZERO false transition events over all observed same-round frames?**
   Yes: 52 rules never fired on any of the 3800 same-round frames, including selections, the fully
   selected state, the wrong-selection ERROR episode and every interruption.
4. **Does that same candidate detect all 4 observed R1 to R2 transitions?** Yes: 4 of 4 for all 52
   clean rules.
5. **At what frame relative to first new recognized / first new stable?** The first new recognized
   frame itself (offset 0) and two frames before the new round is stable (-2). The witness is not
   earlier than recognition on this dataset; its value is independence, not latency.
6. **Is there a clean numerical separation?** Yes for the structural families. The worst same-round
   observation changes at most 2 of the 9 regions below 0.50 (3 of 9 below 0.90) while every true
   transition changes 9 of 9; region-count rules sit in the middle of that gap.
7. **Does it remain effective in the counterfactual A to A identity replay?** Yes: the witness fired
   at all four transitions with the identity forced constant while the unmodified production
   lifecycle tracker suppressed the second round.
8. **Does same-target evidence show that candidate-grid content provides a signal when target-only
   cannot?** Yes for the two comparable same-target pairs (W1 at or above 0.99 while 7 of 9 regions
   changed). Both pairs are non-consecutive rounds.
9. **Does exact visual repetition correctly remain undetectable?** Yes: the self-comparison control is
   a fixed point and no rule fires on it. Exact repetition stays fail-closed.
10. **Is there enough evidence to justify a production witness implementation in the next PR?**
    Yes, for exactly one carefully scoped candidate: a bounded, armed, multi-region structural change
    check (at least K of 9 regions structurally changed, K near 6 at a similarity cut near 0.50) that
    is only ever consulted while a consumed round is held, only ever ADDS the possibility of treating
    a repeated identical identity as a new round, and never creates a round, never changes a stable
    answer and never bypasses consensus. The limitations below are conditions of that implementation,
    not footnotes.

## Limitations

- **No real A to A transition exists in the dataset.** The counterfactual proves identity
  independence; it cannot replace a real example. The next stage should record one.
- **One UI-overlay shape.** The negative population contains a single recurring overlay (the SIGNAL
  PATCH banner) as its dominant perturbation. A larger overlay could in principle cover enough
  regions to reach the rule's cut; the measured worst case is 2 of 9 regions below 0.50 against a
  requirement of 6, and that gap is a measurement of four transitions, not a bound.
- **Four transitions, two recordings.** Evidence, not a distribution. Every count in this document is
  a count of observations.
- The committed annotations are approximate anchors; no annotation timestamp became a runtime signal
  and no frame-exact ground truth is claimed.
- 1920x1080 conclusions use the Stage 6 evaluation-only 0.75 geometry; the live runtime still supports
  2560x1440 only.
- The witness compares normalized structure, so a content change that preserves ridge geometry exactly
  (for example a pure recolour) can stay invisible, like an identical repetition.

## Running it

The private recordings must exist locally (`local-data/stage6/`), then run the witness analysis main
class through the Maven exec plugin, for example:

    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.witness.TransitionWitnessAnalysisMain"

Useful options: `--source <source_id>`, `--padding-seconds <s>` (default 2.0) and
`--no-contact-sheets`. Nothing is committed: every artifact goes below the ignored `target/` tree,
the recordings stay local, and no frame leaves the machine.

## Artifacts (all ignored, below `target/`)

| artifact | content |
| --- | --- |
| `stage6c1c-witness-frames.csv` | one row per scope measurement: scope, frame, recognition and consensus state, W0, W1, C0..C7, W3 aggregates, W4 signatures |
| `stage6c1c-witness-transitions.csv` | witness metrics at every offline anchor of the four real transitions |
| `stage6c1c-witness-distributions.csv` | every signal over every population (min/p05/p50/p95/p99/max and the worst frame) |
| `stage6c1c-witness-separation.csv` | worst same-round observation versus weakest true transition, per signal |
| `stage6c1c-witness-rules.csv` | the full rule and threshold sweep with same-round triggers, transition detection and detection offsets |
| `stage6c1c-witness-counterfactual.csv` | the counterfactual same-identity replay, including the lifecycle outcome |
| `stage6c1c-witness-same-target.csv` | same-target round pairs compared baseline to baseline |
| `stage6c1c-witness-report.txt` | the human-readable report with the same decision gate |
| `stage6c1c-witness-contact-sheets/*.png` | local review sheets: entry, transition, exit and the worst same-round frames per hack |

## Limitations of the tooling itself

- The analysis is offline measurement, not a runtime component. Production packages do not depend on
  it and a static test enforces that.
- Contact sheets show untouched recording frames. They are private review material: local only, never
  committed and never uploaded.
