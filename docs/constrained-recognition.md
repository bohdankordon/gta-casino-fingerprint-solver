# Constrained recognition (Stage 4)

Stage 3 measures similarity: four target scores with a deterministic ranking, plus a
complete 8x4 candidate/reference-fragment matrix for the top ranked target. Stage 4 turns
those measurements into a conservative recognition decision.

    TargetMatchResult + FragmentScoreMatrix
      -> constrained assignment (exact 4-of-8 optimum + alternatives)
      -> assignment + target evidence
      -> conservative policy
      -> strong / unambiguous  -> RecognitionResult.RECOGNIZED
      -> weak / ambiguous      -> RecognitionResult.UNCERTAIN

Scope: the engine starts from an already extracted and normalized puzzle. Stage 5 adds live
screen capture, frame-size validation and a recognition-only runtime around this engine
(see docs/live-recognition-runtime.md); the recognition engine itself is unchanged, there is
still no automatic screen detection and there is no keyboard or mouse automation.

## The assignment problem

Four reference fragments must be matched against eight live candidates:

- every fragment 1..4 is assigned to exactly one candidate;
- every candidate is used at most once;
- exactly four distinct candidates are selected.

The objective is the MEAN of the four pair similarities, which keeps the familiar
0.0..1.0 range (maximizing the mean and the sum are equivalent for four fragments).
The four individual pair scores are kept alongside the mean.

The problem is tiny: P(8,4) = 8 * 7 * 6 * 5 = 1680 legal mappings. The solver
(ConstrainedAssignmentSolver) enumerates all 1680 exhaustively and ranks them best
first. No Hungarian algorithm and no extra dependency: at this size exhaustive search
makes correctness and every alternative easy to reason about.

Ranking is deterministic: descending mean with normal Double comparison, then
lexicographic ascending order of the canonical [candidateForF1 .. candidateForF4]
representation for exact ties. No epsilon tie-breaking is used inside the optimization.

The solver never picks the top candidate independently per fragment column: that greedy
shortcut can select one candidate several times. Synthetic tests cover the collision
case explicitly (two fragments locally prefer the same candidate while the legal global
optimum drops one of them to its second-best candidate).

## Best, runner-up, and best different selection

Three ranked entries matter, and the second and third are different questions:

1. BEST assignment: the mathematical optimum, the highest-scoring distinct
   fragment-to-candidate mapping.
2. RUNNER-UP assignment: the next highest-scoring distinct mapping. It may reuse the
   same four candidates with a different fragment mapping.
3. BEST ALTERNATIVE SELECTION: the highest-scoring assignment whose SET of selected
   candidate indices differs from the best set.

The distinction matters because future automation clicks candidate indices: two
assignments that map the SAME four candidates differently do not change which four
tiles would be clicked. On the representative fixture the runner-up already uses a
different set ([6,0,3,2] vs [6,0,3,7]), while rank 19 of the diagnostic CSV shows the
opposite case: the same set [0,3,6,7] mapped differently at a much lower mean (0.5566).
Only the different-selection margin measures ambiguity about which tiles to click.

Exposed margins (all deterministic and finite):

- assignmentMappingMargin = best mean minus runner-up mean (diagnostic only);
- selectionMargin = best mean minus best-different-selection mean (policy gate).

## Local separation

For the best assignment, each fragment also gets a column margin: the assigned pair
score minus the strongest score for the same fragment among every OTHER candidate.
The minimum across the four fragments (minimumFragmentColumnMargin) is an
interpretable local ambiguity measure and a policy gate. It never replaces the global
constrained assignment; it is evidence only.

## Evidence

RecognitionEvidence retains the measurements behind a decision:

- target: best target id, best target score, runner-up target score, target margin;
- assignment: best assignment mean, weakest assigned pair, mapping margin,
  different-selection margin, minimum fragment column margin.

It also retains the underlying AssignmentSearchResult for debugging. Evidence is
measurement, NOT a probability: no correctness probability, no false-positive rate, no
gameplay coverage claim.

When a puzzle completes the pipeline but is too weak or too ambiguous to recognize,
the failing gates are preserved as UncertaintyReason values (several may apply at once):

- TARGET_SCORE_TOO_LOW, TARGET_MARGIN_TOO_LOW;
- ASSIGNMENT_SCORE_TOO_LOW, ASSIGNED_PAIR_TOO_WEAK;
- SELECTION_MARGIN_TOO_LOW, FRAGMENT_MARGIN_TOO_LOW.

There is deliberately no mapping-only reason: the runner-up margin is recorded for
diagnostics but is not a recognition gate, because a same-set runner-up does not change
the clicked tiles.

## Policy

RecognitionPolicy holds all six thresholds in one place; no magic numbers are
scattered through the engine. The default is PROTOTYPE-CONSERVATIVE: deliberately
fail-closed and provisional.

| gate | default | representative fixture | worst accepted perturbation |
| --- | --- | --- | --- |
| minimum target score | 0.35 | 0.6102 | 0.6062 |
| minimum target margin | 0.10 | 0.4315 | 0.4271 |
| minimum assignment mean | 0.60 | 0.9560 | 0.9485 |
| minimum weakest assigned pair | 0.50 | 0.9269 | 0.9159 |
| minimum selection margin | 0.05 | 0.1946 | 0.1799 |
| minimum fragment column margin | 0.20 | 0.7783 | 0.7195 |

Rationale: the fixture passes every gate with wide headroom, and so does every
accepted Stage 3 perturbation (small translations, blur, JPEG q55, brightness and contrast
changes, combined shift+blur+JPEG). No threshold sits infinitesimally below a measured
value: headrooms range from about 0.13 (selection margin) to about 0.43 (weakest pair).
At the same time deliberately weak or ambiguous synthetic controls stay UNCERTAIN with the
correct reasons (low target score, near-tied targets, weak pair, low mean, near-equal
candidate set, weak column separation, exact ties). False negatives are acceptable;
false confidence is not.

These numbers are NOT statistically calibrated: they rest on exactly one
representative gameplay fixture plus its deterministic perturbations. The policy MUST
be re-evaluated against user-captured fixtures before input automation is enabled.

## Confidence semantics

RecognitionResult.confidence is deterministic structural evidence strength:

    min(bestTargetScore, bestAssignmentMean, weakestAssignedPairScore)

It stays in [0,1], uses actual structural similarities, and keeps ambiguity margins as
separate policy gates instead of hiding them in one opaque number. It is NOT a
probability. The RecognitionResult status is authoritative: a high evidence strength
never overrides failed ambiguity gates. On the representative fixture the evidence
strength is 0.6102 (the target score is the binding component).

## Recognition decision

RecognitionDecision pairs the conservative RecognitionResult with the evidence,
the mathematical best assignment, and the uncertainty reasons, so callers and debugging
can always inspect why a puzzle was recognized or stayed uncertain.

- Strong/unambiguous: RECOGNIZED with the top target id and the best assignment
  four distinct candidates sorted ascending. Representative fixture: FP_1, [0, 3, 6, 7].
- Valid but weak/ambiguous: UNCERTAIN with reasons. The best assignment is still
  exposed for debugging, but no alternative is silently substituted: policy gates
  exposure, never the mathematics.
- FAILED is reserved for genuine processing failures where no decision can be
  produced at all; low confidence is never FAILED. Malformed programmer inputs throw
  the existing IllegalArgumentException / IllegalStateException as before.

RecognitionResult invariants are unchanged: exactly four distinct zero-based
candidates when recognized, confidence finite in [0,1].

## Current fixture measurements

Representative fixture (human-verified ground truth
fixtures/gameplay/annotations/representative-2560x1440.csv):

    target: FP_1 0.6102, runner-up FP_2 0.1788, margin 0.4315
    best assignment: F1 -> C6 0.9748, F2 -> C0 0.9546, F3 -> C3 0.9677, F4 -> C7 0.9269
    selected set: [0, 3, 6, 7], mean 0.9560, weakest pair 0.9269
    runner-up: [6, 0, 3, 2] mean 0.7614 (set [0, 2, 3, 6]), mapping margin 0.1946
    best different set: [6, 0, 3, 2] mean 0.7614, selection margin 0.1946
    column margins: F1 0.8642, F2 0.8492, F3 0.8184, F4 0.7783, minimum 0.7783
    evidence strength: 0.6102 -> RECOGNIZED, FP_1, [0, 3, 6, 7], no reasons

Worst evidence across the twelve accepted perturbations (all still RECOGNIZED with
FP_1 and [0, 3, 6, 7]): target 0.6062, target margin 0.4271, assignment mean 0.9485,
weakest pair 0.9159, selection margin 0.1799, minimum column margin 0.7195.
Per-perturbation evidence is written to target/stage4-perturbation-summary.csv by
the robustness test (build output, never committed).

## Reproduce

Run the full suite, then the Stage 4 diagnostics on the representative fixture:

    .\mvnw.cmd -B -ntp verify
    .\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.recognition.evaluation.RecognitionEvaluation"

The evaluation writes target/stage4-recognition-report.txt and
target/stage4-top-assignments.csv (top 20 of 1680 with rank, F1..F4 mapping,
selected set, mean, and whether the set equals the best set). Production recognition
never reads fixture annotations; only tests and diagnostics compare against them.

## Stage boundary

Stage 4 consumes TargetMatchResult plus the 8x4 matrix and produces the
constrained assignment, ambiguity evidence, and the conservative
recognized/uncertain decision. Live screen capture, frame-size validation and the
recognition-only runtime around it are Stage 5 (docs/live-recognition-runtime.md).
Still NOT implemented: automatic screen detection, keyboard navigation, Enter/Tab
automation, and any round state machine. The early Stage 0 scaffolding
(FingerprintRecognizer, ImageNormalizer,
FingerprintSolver) models a simpler single-Mat pipeline and is not the active
runtime; the active Stage 4 entry point is recognition.PuzzleRecognitionEngine,
which starts from an already normalized puzzle.

