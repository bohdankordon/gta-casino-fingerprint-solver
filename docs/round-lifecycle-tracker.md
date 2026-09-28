# Production round lifecycle tracker (Stage 6C.1B)

Stage 6C.1A measured the temporal carryover problem at full source frame rate before any
state machine was written. This stage implements the small production tracker that answers
one question:

> Is this stable recognition a NEW round that downstream code may consume?

Position in the pipeline:

```
RecognitionDecision -> RecognitionConsensusTracker -> RoundLifecycleTracker
```

The tracker does not solve the puzzle, does not send input, does not know timestamps, video
annotations, or how many rounds a door contains. There is still no gameplay automation of
any kind.

## Why consensus alone is insufficient

Measured on all four real inter-round transitions (Stage 6C.1A):

- the old recognized answer changes directly to the new answer, with zero UNCERTAIN
  decisions between old and new;
- the old answer remains stable until immediately before the new answer;
- resetting the consensus tracker at the nominal boundary re-stabilized the OLD answer on
  2 of 4 transitions, so a consensus reset is not a round-boundary solution;
- suppressing stable answers equal to the consumed answer identity separated every observed
  carryover from every observed next round (4 of 4), with 0 ms latency relative to the
  first stable new answer.

So a stable answer is not automatically a new round: the previous round's answer stays
stable across the boundary. The lifecycle layer remembers which identity was already
consumed and only promotes a DIFFERENT stable identity.

## Answer identity

The lifecycle identity of an answer is the target fingerprint PLUS the sorted set of
selected candidates (`runtime.RecognitionIdentity`). A different fingerprint alone is a
different identity, and so is a different candidate set on the same fingerprint: two of
the four observed transitions reuse the same candidate set with a different target
(`FP_4[1;4;5;6]` -> `FP_3[1;4;5;6]`), so the fingerprint alone is not the contract.
Either half differing is enough; both do not need to differ.

The value type is immutable and holds no decision, no `Mat`, no evidence, no timestamps
and no native resources. Candidates are defensively copied, sorted canonically, and
validated: exactly four distinct indices `0..7`. Equality never depends on incoming
order. It is deliberately separate from the evaluation-only
`evaluation.recording.transition.AnswerIdentity`; production runtime never depends on
evaluation code.

## State machine

```
WAITING_FOR_STABLE --stable onset(X)--> ROUND_READY(X)
ROUND_READY(P)     --stable onset(P)--> ROUND_READY(P)    (pending re-stabilized)
ROUND_READY(P)     --stable onset(Q!=P)--> DESYNCHRONIZED (pending P kept, stale)
ROUND_READY(P)     --anything else--> ROUND_READY(P)
ROUND_CONSUMED(C)  --stable onset(C)--> ROUND_CONSUMED(C) (suppressed, never new)
ROUND_CONSUMED(C)  --stable onset(Q!=C)--> ROUND_READY(Q)
DESYNCHRONIZED     --anything--> DESYNCHRONIZED           (only reset() clears it)
```

Only stable-EPISODE onsets are lifecycle events. The consensus tracker reports
`STABLE_RECOGNIZED` on every frame after the required streak, but `stable A, stable A,
stable A` produces ONE onset, not three. An onset is a stable identity that differs from
the previously observed stable identity, or any stable identity after a non-stable
observation.

Only `STABLE_RECOGNIZED` may create a round. `WAITING`, `UNSUPPORTED_FRAME`,
`CAPTURE_ERROR`, `UNCERTAIN`, `RECOGNIZED` (single-frame) and `CANDIDATE_RECOGNITION` are
never actionable. `UNCERTAIN` is a refusal of the conservative policy, not a claim that
no puzzle is on screen.

Each `accept` call returns an immutable `RoundLifecycleStatus`: the state, the ready /
consumed / current-stable identities, and which of the three per-observation events fired
(`newRoundReady`, `consumedIdentityRepeated`, `desynchronizedNow`; at most one).

## Consumption contract

`consumeReadyRound()` is lifecycle acknowledgement only: downstream has accepted ownership
of the round identity. It is not gameplay input and never implies a key was pressed or a
round was solved.

- Valid only while the state is `ROUND_READY`; waiting, already-consumed and
  desynchronized trackers reject consumption with `IllegalStateException`. No silent
  double consume.
- Valid only while the most recently accepted consensus observation is still
  `STABLE_RECOGNIZED` of exactly the ready identity. If the consensus has ceased to be
  stably that identity (an `UNCERTAIN` frame, a candidate, a capture problem), the consume
  call is rejected instead of silently acknowledging a stale round. No time grace period.
- If the pending identity becomes stable again while still pending, consuming it may
  succeed again.
- Returns exactly the canonical ready identity and transitions to `ROUND_CONSUMED`.
- Never resets the consensus tracker, never clears the consumed identity.

## Fail-closed rules

- Same identity: after an identity is consumed, that SAME identity can never become ready
  again — as a direct continuation, after any number of `UNCERTAIN` frames, after capture
  errors or unsupported frames, or after a consensus reset re-stabilization. No count or
  duration of uncertainty clears lifecycle memory.
- Uncertainty and capture problems never reset lifecycle memory. Doing so would re-enable
  the carryover bug. Lifecycle memory is separate from the consensus streak: the consensus
  tracker may reset its streak after a capture failure while lifecycle memory retains the
  consumed identity.
- Desynchronization: if a different stable identity appears while a previous round is
  still waiting to be consumed, the tracker enters non-actionable `DESYNCHRONIZED` and
  never silently replaces the pending round. Nothing observed afterwards — `UNCERTAIN`,
  more stable answers, same or different — recovers it. Only an explicit reset clears it,
  after which the next stable answer may become ready normally.

## Explicit reset

`reset()` clears the ready identity, the consumed identity, the desynchronization and the
stable-episode memory, returning to `WAITING_FOR_STABLE`. It is a session/external control
primitive only and is never called automatically — in particular never because the stream
contains `UNCERTAIN`, `CAPTURE_ERROR` or `UNSUPPORTED_FRAME`. It is not wired into
`LiveRecognitionRuntime` in this stage.

## Same-answer limitation and identity reuse

Two consecutive real rounds with the exact same answer identity stay suppressed: without
an independent transition witness the tracker must fail closed and must not guess that a
repeated identical identity is a new round. The recordings contain no example of that
case, so it is unproven by construction.

Only the LATEST consumed identity is remembered — there is deliberately no history set.
After `A -> consume, B -> consume`, a stable `A` differs from the current consumed
identity `B` and may become ready, so a legitimate later round may reuse an identity from
two rounds ago. A global set of all consumed identities would incorrectly suppress that.

## Real-recording replay results

Stage 6C.1B evaluation (`evaluation.recording.transition.RoundLifecycleReplay`, CLI
`RoundLifecycleReplayMain`) replays every decoded frame inside each padded hack window
through the UNMODIFIED production consensus tracker and then the PRODUCTION lifecycle
tracker — no second copy of the algorithm. A simulated well-behaved consumer immediately
consumes every `NEW_ROUND_READY` event; the simulation sends no input. A second scope
replays each full source through ONE tracker across all padded hack regions in
chronological order.

Per-hack replay (one tracker per hack, immediate consume):

| hack | ready identities (frames) | consumed | order | duplicates | unexplained | desync |
| --- | --- | --- | --- | --- | --- | --- |
| `recording_1440p` H1 | `FP_4[1;4;5;6]` (525/17.500 s), `FP_3[1;4;5;6]` (1019/33.967 s) | 2 | yes | 0 | 0 | 0 |
| `recording_1440p` H2 | `FP_1[1;3;4;6]` (3582/119.400 s), `FP_3[2;4;6;7]` (3896/129.867 s) | 2 | yes | 0 | 0 | 0 |
| `recording_1080p` H1 | `FP_3[0;1;3;6]` (1181/40.791 s), `FP_1[0;4;6;7]` (1707/58.929 s) | 2 | yes | 0 | 0 | 0 |
| `recording_1080p` H2 | `FP_3[1;3;6;7]` (3885/134.033 s), `FP_4[1;2;6;7]` (4412/152.205 s) | 2 | yes | 0 | 0 | 0 |

Totals: exactly 8 `ROUND_READY` events and 8 consumed rounds; every identity equals the
human annotation in chronological round order; 0 duplicate actionable carryover answers;
0 unexplained actionable answers; 0 desynchronizations; 0 consume rejections. Latency of
each round-2 `READY` relative to the first stable new answer is 0 ms.

Critically verified: after round 1 is consumed, the 1080p carryover answers
(`FP_3[0;1;3;6]` in H1, `FP_3[1;3;6;7]` in H2) never become `READY` again, while the new
answers (`FP_1[0;4;6;7]`, `FP_4[1;2;6;7]`) become `READY` at the first stable new answer.
In-round same-identity re-stabilizations — two in `recording_1440p` H1 (frames 985, 989)
and the known `recording_1080p` H2R2 interruption (frames 4874, 4897) — were suppressed
and never created another `READY` after consumption.

Full-source replay (ONE tracker per source): `recording_1440p` 4 `READY` in annotated
order, `recording_1080p` 4 `READY` in annotated order, 8 total. `UNCERTAIN` stretches
between hacks do not clear the consumed identity, and the next hack's different identity
still becomes ready. This does not solve same-answer hack entry: both observed next-hack
identities happen to differ from the previously consumed identity.

Artifacts (ignored build output): `target/stage6c1b-lifecycle-events.csv`,
`target/stage6c1b-lifecycle-summary.csv`, `target/stage6c1b-lifecycle-report.txt`.
Per-event rows carry the source, hack, frame index, timestamp (reporting only), consensus
state, recognition identity, lifecycle state and event, ready/consumed identities, and
whether evaluation consumed the round immediately. No `Mat` objects are retained.

## What is still missing

- No independent transition witness exists, so the repeated-identical-identity case stays
  fail-closed (Stage 6C.1C future work).
- No gameplay input: `LiveRecognitionMain` semantics are unchanged, and any diagnostic
  integration stays recognition-only and never auto-consumes as if gameplay input had
  occurred.
- No FP_2 real-game coverage; 1080p replay remains evaluation-only with the uniform 0.75
  geometry — production still supports only 2560x1440.
- No timers: lifecycle correctness depends on no clock, sleep, cooldown or duration;
  evaluation timestamps are reporting only.

