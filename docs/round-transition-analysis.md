# Full-rate round-transition characterization (Stage 6C.1A)

Stage 6A/6B measured the frozen recognition system on two private real-gameplay recordings and found
the temporal problem this stage exists for: a previous round can stay recognizable after the
approximate annotation has entered the next round. A future automation must never treat that
carryover answer as the answer of a new round.

Stage 6C.1A characterizes the transitions at FULL source frame rate before any production lifecycle
state machine is written. It measures; it implements nothing:

- no `RoundLifecycleTracker`, no round consumption API, no lifecycle state in the runtime;
- no keyboard, mouse, arrow, Enter or Tab input, no solving action, no sleep, no fixed delay;
- no matcher, normalizer, policy threshold or ROI change;
- no production 1920x1080 support.

Everything below is either a measured fact from the two recordings, a design inference from those
facts, or an explicitly unproven case. The three are kept apart on purpose.

## Why full rate

Stage 6A/6B replayed whole recordings at a deterministic 5 fps cadence for some temporal checks.
That cadence is fine for false-positive measurement and useless for transitions: a direct
round-to-round answer switch in this dataset lasts one frame interval (33-34 ms). Every conclusion
here comes from every decoded source frame inside each hack window padded by 2.0 s on both sides,
decoded sequentially.

| source | resolution | analyzed full-rate frames | hack windows (approximate) | transition regions |
| --- | --- | --- | --- | --- |
| `recording_1440p` | 2560x1440 | 1890 | 16.0-50.0 s, 119.0-140.0 s | 14.0-52.0 s, 117.0-142.0 s |
| `recording_1080p` | 1920x1080 | 2552 | 40.0-84.0 s, 134.0-170.0 s | 38.0-86.0 s, 132.0-172.0 s |

The recordings are private local material; their SHA-256 and size are verified before anything is
measured, and both matched. 1440p uses the bundled production 2560x1440 layout unmodified; 1080p
uses the Stage 6B evaluation-only uniform 0.75 geometry. The consensus replay uses the unmodified
production `RecognitionConsensusTracker` (three consecutive identical recognized answers).

Two recordings, four hacks, two rounds per hack: twelve transitions (4 hack entry, 4 inter-round,
4 hack exit). The committed hack windows and round intervals are anchors only. They are approximate
by design and are never treated as frame-exact truth anywhere in this stage.

## Answer identity

The lifecycle identity of an answer is the target fingerprint PLUS the sorted set of selected
candidates. A different fingerprint is a different identity, and so is a different candidate set on
the same fingerprint. Two of the four inter-round transitions reuse the same candidate set with a
different target (`FP_4[1;4;5;6] -> FP_3[1;4;5;6]`), which is exactly why the fingerprint alone is
not the contract.

## FACTS FROM DATA

### ROUND_1 -> ROUND_2 (the carryover problem)

All four transitions, measured on every frame:

| transition | old answer | new answer | last old recognized / stable | first new recognized | first new stable | uncertain frames between | direct switch | old frames at/after the nominal boundary | reset re-stabilizes old |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `recording_1440p` H1 | `FP_4[1;4;5;6]` | `FP_3[1;4;5;6]` | 33.867 s | 33.900 s | 33.967 s | 0 | yes | 0 (0 ms) | no |
| `recording_1440p` H2 | `FP_1[1;3;4;6]` | `FP_3[2;4;6;7]` | 129.767 s | 129.800 s | 129.867 s | 0 | yes | 1 (17 ms) | no |
| `recording_1080p` H1 | `FP_3[0;1;3;6]` | `FP_1[0;4;6;7]` | 58.826 s | 58.860 s | 58.929 s | 0 | yes | 17 (576 ms) | YES |
| `recording_1080p` H2 | `FP_3[1;3;6;7]` | `FP_4[1;2;6;7]` | 152.102 s | 152.136 s | 152.205 s | 0 | yes | 18 (602 ms) | YES |

What this says:

1. The switch is direct in all four transitions: there is no UNCERTAIN decision between the old and
   the new recognized answer, and the last old frame and the first new frame are adjacent
   (33-34 ms apart). The answer changes from one decoded frame to the next.
2. The previous round's answer stays recognized AND stable right up to the frame before the new
   answer appears. Nothing in the recognition stream marks the end of a round.
3. The nominal boundary is not the visual transition. In `recording_1440p` H1 it is late (the
   switch happened 0.13 s before it); in the 1080p transitions it is early, leaving 17-18 frames
   (576-602 ms) of the previous round's answer on screen after the annotation has already crossed
   over. The annotation must not be used as a boundary signal.
4. No third answer appears anywhere: 0 unexplained recognized frames and 0 unexplained stable
   episodes across all twelve transitions.
5. Time from the last old stable frame to the first new stable frame is 100-103 ms (three frames of
   the new answer plus the switch), i.e. the new round is stable as soon as it has been on screen
   for the required three consecutive frames.

### HACK_ENTRY -> ROUND_1

| transition | first recognized answer | first candidate-consensus frame | first stable answer | hack entry -> first stable | transient answers | transient became stable |
| --- | --- | --- | --- | --- | --- | --- |
| `recording_1440p` H1 | = round answer, 17.433 s | same frame | 17.500 s | 1500 ms | none | no |
| `recording_1440p` H2 | = round answer, 119.333 s | same frame | 119.400 s | 400 ms | none | no |
| `recording_1080p` H1 | = round answer, 40.722 s | same frame | 40.791 s | 791 ms | none | no |
| `recording_1080p` H2 | = round answer, 133.964 s | same frame | 134.033 s | 33 ms | none | no |

- On every hack the first recognized answer of the region is already the correct round answer, the
  first frame that reaches candidate consensus is the same frame, and the first stable answer is the
  annotated round answer. No wrong or transient answer ever precedes it, and no transient answer
  becomes stable.
- Before the first recognition the region holds only UNCERTAIN decisions (102, 69, 78 and 56 frames
  respectively). UNCERTAIN is a refusal by the conservative policy, not a claim that no puzzle is on
  screen. Visual inspection of the local contact sheets shows the puzzle panel genuinely absent
  before the hack and then appearing progressively while the policy still refuses it, so both cases
  are present in that population.

### ROUND_2 -> HACK_EXIT

| transition | last stable round answer | first uncertain after it | old answer reappears | old answer re-stabilizes | different answer after the last stable one | stable answer at/after the nominal hack end |
| --- | --- | --- | --- | --- | --- | --- |
| `recording_1440p` H1 | 49.600 s | 49.633 s | no | no | no | no |
| `recording_1440p` H2 | 138.700 s | 138.733 s | no | no | no | no |
| `recording_1080p` H1 | 83.860 s | 83.895 s | no | no | no | no |
| `recording_1080p` H2 | 169.240 s | 169.274 s | yes (inside the round) | yes (inside the round) | no | no |

- In all four exits the round answer stops being recognized on the very next frame after its last
  stable frame, and no answer of any kind becomes stable at or after the nominal hack end.
- `recording_1080p` H2R2 is the one case where the round answer disappears and comes back: the
  recognition is interrupted at 167.860 s and the same answer re-stabilizes at 168.136 s and
  168.929 s. Both re-stabilizations sit INSIDE the round (the round's nominal interval ends at
  169.0 s and the last stable frame is 169.240 s), so they are in-round interruptions, not an exit
  flicker. The exit itself is clean.

### The naive reset-consensus idea

Simulated offline on the real frames: reset the unmodified tracker once, immediately before the
first frame that reaches the nominal round boundary, then keep feeding the real frames.

| transition | reset applied | first stable answer after reset | old answer re-stabilized |
| --- | --- | --- | --- |
| `recording_1440p` H1 | yes | 34.067 s `FP_3[1;4;5;6]` (new) | no |
| `recording_1440p` H2 | yes | 129.867 s `FP_3[2;4;6;7]` (new) | no |
| `recording_1080p` H1 | yes | 58.343 s `FP_3[0;1;3;6]` (OLD) | YES |
| `recording_1080p` H2 | yes | 151.585 s `FP_3[1;3;6;7]` (OLD) | YES |

A reset alone re-stabilizes the OLD answer on 2 of 4 transitions, i.e. exactly where the previous
round is still on screen at the boundary. On the two 1440p transitions the old answer had already
left the screen at the boundary, so the reset had nothing to re-stabilize. Resetting consensus is
not a round-boundary solution.

### Offline guard simulation

Each guard was simulated offline over the real frames of every hack, each with its own instance of
the production tracker. No guard exists in production.

| guard | old answer actionable again | new round actionable | notes |
| --- | --- | --- | --- |
| G0 consensus only (every stable frame actionable) | 4 of 4 | 4 of 4 | the answer that is stable across the boundary is actionable again on the very next frame (950/577/1247/1007 actionable frames per hack) |
| G1 reset consensus after consumption | 4 of 4 | 4 of 4 | the first actionable result after the reset is the OLD answer again in every hack, 100-103 ms after the consumption |
| G2 require an UNCERTAIN gap after consumption | 1 of 4 | 2 of 4 | no UNCERTAIN decision occurs at all in two of the four hacks, so nothing ever becomes actionable; in `recording_1440p` H1 it fires on an in-round re-stabilization of the same round 15.3 s after consumption; where it eventually works it is 15.9 s late |
| G3 suppress stable answers equal to the consumed identity | 0 of 4 | 4 of 4 | first actionable answer is the new round in every hack, latency 0 ms relative to the first stable new answer; 2 same-identity onsets suppressed (`recording_1440p` H1 in-round interruptions) |
| G4 conservative hybrid (documented only) | not simulated | not simulated | the stable-different-answer half is G3; the independent transition witness is not implemented and is not invented here |

G2's premise does not hold on this dataset: the round-to-round switch is direct, so waiting for an
UNCERTAIN frame is either never satisfied or satisfied by something else entirely. G3 is the only
simulated guard that separates every observed carryover from every observed next round.

### Aggregate

- All 8 annotated rounds still reach a stable correct answer at full rate; the counts match the
  Stage 6A/6B consensus replay.
- 0 recognized frames and 0 stable episodes of an unexplained answer during any of the 12
  transitions.
- 61 contiguous runs over 4442 analyzed frames (12 stable, 17 uncertain, 32 candidate), i.e. the
  transitions are long stable stretches interrupted by a handful of short events.

## DESIGN INFERENCE

What the measured data supports for a later lifecycle tracker:

1. Consume only on a STABLE answer. Candidate consensuses are not rounds.
2. Never act twice on the same consumed answer identity. The identity is the fingerprint plus the
   sorted candidate set, and a stable answer equal to the consumed identity must never be promoted
   to a new round.
3. The tracker must not treat the continuation of an episode as an event. In this dataset the
   previous round's answer stays stable across the boundary, so still-stable is not evidence of a
   new round and newly-stable-with-the-same-identity is not either.
4. No timing constant is a correctness mechanism. The measured durations (17 ms to 602 ms of
   carryover after the nominal boundary, 100-103 ms from last old stable to first new stable) are
   diagnostics of these four hacks, not thresholds; the tracker must be event/state based and fail
   closed.
5. The approximate annotations may anchor offline analysis. They must never be a runtime signal.
6. An identity-change guard requires an independent transition witness for the case the dataset
   cannot show (see below), and until that witness exists the tracker fails closed on a repeated
   identical answer.

## UNPROVEN CASES

- **Consecutive rounds with the exact same answer identity.** The recordings contain no such pair.
  Nothing here proves that two visually separate rounds with the same fingerprint and the same
  correct candidate set are distinguishable. An answer-identity-change guard alone would FAIL
  CLOSED there: it would keep the second round inactive instead of guessing. A repeated identical
  stable answer must never be interpreted as a new round on its own, and no probability is
  estimated anywhere as a substitute for evidence.
- **FP_2** has no real-game round in this dataset (inherited from Stage 6A/6B).
- **Four hacks and two recordings** are useful evidence, not a distribution. Every count above is a
  count of observations, not an estimate.
- The four inter-round transitions all switched directly; a transition with a genuine UNCERTAIN gap
  between the answers was simply not observed here.
- Transition conclusions cover the padded hack windows only. Outside them, the Stage 6A/6B
  strict-negative semantics (zero false recognized frames in 1331 sampled and 7989 exhaustive
  frames) apply unchanged and were not re-measured.

## VISUAL OBSERVATIONS (local contact sheets, not machine-labelled truth)

The local contact sheets under `target/stage6c-transition-contact-sheets/` show real frames at
0.10 s cadence, labelled with the machine state of the exact decoded frame. Inspection of them
supports the following observations, which are visual and are NOT frame-exact annotations:

- **Round to round is an in-place content change.** On `recording_1080p` H1 the puzzle panel is
  continuously visible from ~56.8 s through ~59.1 s: the target fingerprint and the candidate
  selection change while the panel itself never disappears. This matches the measured direct switch
  and the absent-UNCERTAIN-gap finding.
- **Hack entry has a genuinely empty phase.** On `recording_1440p` H1 the screen shows ordinary
  gameplay until ~16.9 s, then the puzzle panel appears progressively (16.93-17.33 s) while the
  policy still refuses the frames. UNCERTAIN covers both no-puzzle and puzzle-not-yet-complete.
- **Hack exit clears the panel.** On `recording_1440p` H1 the panel is cleared at ~49.63 s and the
  HACK SUCCESS overlay appears, matching the measured last stable frame (49.600 s) and first
  uncertain frame (49.633 s).

## DECISION GATE FOR STAGE 6C.1B

1. **Is an UNCERTAIN gap guaranteed by all 4 observed round-to-round transitions?** No. 0 of 4
   transitions contain an UNCERTAIN decision between the old and the new answer.
2. **Does consensus reset alone prevent carryover?** No. It re-stabilizes the OLD answer on 2 of 4
   transitions (both where the previous round is still on screen at the boundary).
3. **Does answer-identity change suppress all observed carryover episodes?** Yes on this dataset:
   the old answer became actionable again in 0 of 4 hacks, and 2 same-identity stable onsets were
   suppressed.
4. **Does it still detect all 4 observed next rounds?** Yes: 4 of 4, with 0 ms latency relative to
   the first stable frame of the new answer.
5. **Is there any unexplained stable answer during transitions?** No: 0.
6. **What happens on hack entry and hack exit?** Entry: the puzzle appears progressively and the
   first stable answer is the correct round answer in 4 of 4 hacks (33-1500 ms after the nominal
   window start), with no transient or wrong answer before it. Exit: the round answer is followed
   by an UNCERTAIN frame on the very next frame in 4 of 4 exits, and no answer is stable at or after
   the nominal hack end.
7. **What cannot be solved safely with the current signals?** Consecutive rounds with the exact same
   answer identity. Identity comparison cannot distinguish a repeated round from a still-visible
   previous round, and a timing constant would only hide the problem.
8. **Is an independent transition witness needed for that case?** Yes. Stage 6C.1B should implement
   the tracker with fail-closed, event-based invariants - consume only on stable answers, never
   re-act on the consumed identity, require either an absent-puzzle gap or an independent boundary
   witness before a repeated identical answer can become a new actionable round - and it should
   measure any candidate witness on real recordings before input automation is even considered.

## Running it

The private recordings must exist locally (`local-data/stage6/`), then:

    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.RoundTransitionAnalysisMain"

Useful options: `--source <source_id>`, `--padding-seconds <s>` (default 2.0),
`--contact-sheet-half-window-seconds <s>` (default 1.5),
`--contact-sheet-tile-seconds <s>` (default 0.10) and `--no-contact-sheets`. Nothing is
committed: every artifact goes below the ignored `target/` tree, the recordings stay local, and no
frame image leaves the machine.

## Artifacts (all ignored, below `target/`)

| artifact | content |
| --- | --- |
| `stage6c-transition-frames.csv` | one row per analyzed frame: decision, answer identity, evidence, uncertainty reasons, consensus state, streak |
| `stage6c-transition-runs.csv` | the same trace compressed into contiguous decision/consensus runs |
| `stage6c-transition-summary.csv` | one row per transition (4 entry, 4 inter-round, 4 exit) with every question answered |
| `stage6c-guard-simulation.csv` | G0-G4 results per hack |
| `stage6c-transition-report.txt` | the human-readable report with the decision gate and the run appendix |
| `stage6c-transition-contact-sheets/*.png` | 12 dense sheets (entry, inter-round, exit per hack), 0.10 s cadence |

## Limitations

- Four inter-round transitions and four hacks: real evidence, not a distribution.
- Annotation boundaries are approximate by a few tenths of a second and are used as anchors only.
- Visual observations come from a human-inspectable contact sheet, not from OCR or a frame-exact
  annotation of the transition moment.
- The 1080p conclusions use the evaluation-only 0.75 geometry; the live runtime still supports
  2560x1440 only.
- All durations are diagnostics. None of them is proposed as a timeout, cooldown or delay.

