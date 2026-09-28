# Production structural round-transition witness (Stage 6C.1D)

Stage 6C.1B made the production round lifecycle fail closed on a repeated answer identity.
Stage 6C.1C measured whether an independent visual signal can prove puzzle content changed
even when the answer identity hypothetically stays the same, and found that it can on the
observed data. This stage promotes exactly one narrowly scoped rule into production.

Pipeline: RecognitionDecision to RecognitionConsensusTracker to STABLE identity plus
structural content witness to RoundLifecycleTracker.

## Production rule and measured provenance

Regions are the normalized target plus eight same-position normalized candidates (9 total).
Comparison reuses the unmodified StructuralSimilarityScorer with TargetMatcher radius 8 and
FragmentMatcher radius 4. A region counts as changed iff similarity is strictly below 0.50
(exactly 0.50 is NOT changed). A transition is confirmed iff at least 6 of 9 regions changed.

Measured origin (Stage 6C.1C, 4442 full-rate frames, 3800 same-round, four real transitions):
at cut 0.50 the worst same-round frame changed 2 of 9 regions while the weakest real transition
changed 9 of 9. Detection fired on the first new recognized frame with zero same-round false
triggers. K=6 sits in the middle of that gap (4 safety, 3 slack).

These constants are data-backed provisional production constants, NOT calibrated probabilities
and NOT universal bounds. Four transitions are evidence, not a distribution. There is no runtime
threshold-tuning UI.

## Identity independence

The witness reads only normalized Mat profiles. It has no field, parameter or branch mentioning
a fingerprint, a selected candidate set, a recognition decision, a consensus state or a lifecycle
state. It compares current normalized content with a frozen baseline and returns plain evidence.
Lifecycle integration decides whether that evidence matters. A static test enforces isolation.

## Baseline ownership

The witness owns the frozen baseline: normalized target clone plus eight candidate clones.
No panel or screenshot is kept. The baseline is immutable after arming, owns all native Mats,
is deterministically closed (AutoCloseable, idempotent, no finalization), is replaced safely on
later consumption, is cleared on explicit reset, survives UNCERTAIN and capture problems, and is
never continuously adapted.

## Arming

The witness is armed only after a round is successfully consumed. Even while armed, lifecycle
consults evidence only together with STABLE_RECOGNIZED, so entry and exit content changes can
never create a round alone. Panel disappeared is never a round on its own.

## Consumption plus re-arm

After EVERY successful consumption, including a witnessed repeated-identity round, the baseline
becomes the exact content of THAT consumed round. The coordinator consumeReadyRound method pairs
lifecycle consumption with baseline replacement so a stale baseline cannot be left behind.
Failure order: clone first, validate consumable, consume lifecycle, install prepared baseline,
close previous. If preparation fails the lifecycle stays unconsumed. If consumption fails the
prepared baseline is closed and the old baseline stays intact.

## Lifecycle semantics

The old accept method behaves exactly as before (absent evidence). The additive overload adds
one path: ROUND_CONSUMED plus STABLE of the same consumed identity plus confirmed evidence
becomes ROUND_READY with transitionWitnessUsed set. Different-identity STABLE becomes READY
without witness. Same-identity STABLE without witness stays suppressed. Every other combination
ignores the witness: non-stable consensus, WAITING, ROUND_READY pending, DESYNCHRONIZED.
A witness alone can never create a round.

STABLE consensus stays mandatory because the witness measures content change, not validity.
An A to A transition may have no new consensus onset because consensus compares identity, not
pixels, so continued STABLE plus confirmed evidence is sufficient while ROUND_CONSUMED.

## Pipeline ownership

FrameRecognitionPipeline.observe runs the same single pass as recognize with no second
normalization, but returns an owned FrameRecognitionObservation (decision plus owned normalized
puzzle plus timings) that the caller must close. recognize keeps its API by using observe
internally and closing before returning the plain result. LiveRecognitionRuntime is unchanged.

## Replays

ProductionWitnessReplay replays every full-rate padded hack frame through observe plus
production consensus plus the production coordinator, with immediate consume through the
coordinator and no input. Normal identities: 8 READY and 8 consumed, 0 duplicates, 0
unexplained, 0 desync, 0 failures; full-source 4 READY per source. Counterfactual A to A keeps
real pixels and substitutes round-2 identity with round-1 before consensus: 8 READY, 8 consumed,
4 witnessed, 0 duplicates, 0 desync, 0 failures. Exact visual repeats stay fail-closed.

## Limits and next

No real A to A recording exists. Exact repeats are undetectable. Overlay diversity is limited.
Constants are provisional. 1080p stays evaluation-only. No gameplay input starts in this PR.
Next is Stage 7 dry-run orchestration after independent review and merge.
