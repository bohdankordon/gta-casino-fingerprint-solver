# Stage 8D.1 stable 2560x1440 live validation

## Status

PASS

This document records the stable production 2560x1440 path validated against real GTA V Enhanced
in a full multi-door end-to-end vault run.

Tested exact commit: 0f1328e13b4c1233c7fa2151afc4bdd5a80b8eaa
Commit subject: feat: promote validated scan-code input to production
Original working tree at test time: clean.

Scope of this validation:

- three fingerprint hacks in one vault visit
- two rounds per hack
- six total real rounds
- the same solver process remained alive across gameplay and loot gaps
- every encountered fingerprint minigame completed automatically

This is a multi-door and multi-round end to end result, not a single-round smoke.

## Scope and acceptance criteria

Stage 8D had to prove the production path end to end on the stable profile:

- real capture of the live game frame
- stable recognition with consensus
- constrained solve to exactly four candidates
- navigation plan from the dry-run planner
- real scan-code input through the promoted production sink
- per-action visual verification before the next input
- exactly four selections per round
- one PROCEED per round
- next-round and final-exit transition handling
- no duplicate replay of a consumed round
- fail-closed safety on any mismatch, abort, or loss of focus

All of the above were exercised in the run recorded here on 2560x1440.
See execution tables below for the per-round and aggregate proof.

## Test environment

- Game: GTA V Enhanced
- target executable: GTA5_Enhanced.exe
- display mode: Borderless
- physical resolution: 2560x1440
- monitor refresh reported by runtime: 165 Hz
- input delivery: SCANCODE_BATCH through the promoted production sink
- emergency abort: SCROLL_LOCK, explicitly configured, no default
- watch interval: 200 ms
- stable frames: 3
- commit under test: 0f1328e13b4c1233c7fa2151afc4bdd5a80b8eaa
- working tree: clean

No other resolution was under test. 1920x1080 was not part of this run.

## Evidence provenance

The full private evidence package is local only and is not committed.
Only hashes and generic technical metadata are committed here for provenance.

Video recording, private local file, not committed:

- codec: AV1
- resolution: 2560x1440
- fps: 30
- duration: 146.833 seconds
- size: 218565173 bytes
- SHA-256: 3c28bfa6268f60ca1dcc4c298d6aabcbdb8c708e3f417e1a5d91261371c3d85b

Live terminal transcript, private local file, not committed:

- SHA-256: 077d97e4a682018b4ce3d7a047f887b08f55162c6bdb6528cff93f4bfffd4d16

Run notes, private local file, not committed:

- SHA-256: 818840128e653732b9b9925b812b3a8230a40dd23d02f09d43ba3354de5d214a

Git state capture, private local file, not committed:

- SHA-256: d57ccd842d87380356ca8e62542d76b53fc1e8755b3a53c4ba82776c7381d090

No video file, no transcript file, no notes file, no git-state file,
no screenshots, no extracted video frames, no contact sheets,
no usernames, no absolute private filesystem paths, and no personal desktop content
are committed. Hashes and generic metadata above are the only provenance record.

## Real execution results

The solver was started before entering the vault and before the first fingerprint hack.
It was stopped only after leaving the vault.
The live transcript records exactly the following six executions.

| # | identity | selected order | navigation moves | actionCount | result |
|---|---|---|---|---|---|
| 1 | FP_4[3;4;5;7] | [3,4,5,7] | 6 | 11 | PASS |
| 2 | FP_2[2;3;4;5] | [2,3,5,4] | 4 | 9 | PASS |
| 3 | FP_3[1;2;4;6] | [1,2,4,6] | 5 | 10 | PASS |
| 4 | FP_1[0;4;5;7] | [0,4,5,7] | 4 | 9 | PASS |
| 5 | FP_3[1;2;4;6] | [1,2,4,6] | 5 | 10 | PASS |
| 6 | FP_2[2;5;6;7] | [2,5,7,6] | 5 | 10 | PASS |

Hack 3 result: PASS.
Two rounds were observed for hack 3, consistent with the transcript and video.

Human observation during solver-owned rounds:

- manual arrows, Enter, or Tab while the solver owned the round: NO
- wrong movement observed: NO
- wrong candidate selection observed: NO
- duplicate input observed: NO
- unexpected PROCEED or Tab observed: NO
- FAULTED observed: NO
- ABORTED observed: NO

The solver stayed running while loot was collected between hacks.

## Aggregate execution results

Exact counters from the saved terminal transcript:

- ROUND_READY: 6
- EXECUTION START: 6
- verified start=C0 selected=[]: 6
- verified navigation taps: 29
- verified SELECT actions: 24
- PROCEED sent: 6
- round advance acknowledged: 6
- EXECUTION COMPLETE: 6

Failure indicators, all zero:

- FAULTED: 0
- ABORTED: 0
- BLOCKED: 0
- CAPTURE_ERROR: 0
- SETUP_ERROR: 0
- INPUT_ERROR: 0
- NO FURTHER INPUT WILL BE SENT: 0

Action arithmetic closes exactly:

- 29 navigation plus 24 SELECT plus 6 PROCEED equals 59 real planned actions
- sum of all six planner actionCount values: 11 + 9 + 10 + 9 + 10 + 10 equals 59

## Multi-door persistence

The run covered, in order:

- fingerprint hack 1, two rounds
- vault gameplay and loot
- fingerprint hack 2, two rounds
- vault gameplay and loot
- fingerprint hack 3, two rounds

The same solver process remained active through the gameplay and loot gaps.
It was not restarted between hacks.
No exact door identity beyond this sequence is claimed.

## Fingerprint coverage

All four known target fingerprints appeared in this one real run:

- FP_1: rounds 4
- FP_2: rounds 2 and 6, with different candidate sets
- FP_3: rounds 3 and 5, same candidate set repeated in a later hack
- FP_4: round 1

No claim is made beyond the six observed rounds listed above.

## Video corroboration

A continuous private local screen recording corroborates three distinct
fingerprint-hack episodes separated by normal vault gameplay and looting.
The file itself is not committed. See Evidence provenance for the hash.

Approximate visual success points, for review navigation only:

- hack 1 HACK SUCCESS: about 38 s
- hack 2 HACK SUCCESS: about 71 s
- hack 3 HACK SUCCESS: about 111 to 112 s

These timestamps are approximate visual review anchors only.
They are not frame-exact measurements.

## Residual coverage

Coverage limitation: the six actual optimized fingerprint plans exercised
DOWN, LEFT, RIGHT, SELECT, and PROCEED, but happened not to require UP
inside the Stage 8D.1 fingerprint end to end run.

This is a coverage observation, not a failure and not an unresolved input-backend blocker.
UP had already been independently validated against the real GTA process
through the Stage 8C production single-tap smoke on the same stable path.

## Result

Stage 8D.1 stable 2560x1440 equals PASS for the tested environment:
GTA V Enhanced Borderless at 2560x1440, commit
0f1328e13b4c1233c7fa2151afc4bdd5a80b8eaa, three hacks and six rounds
with the same solver process, all FP_1 through FP_4 represented,
29 verified navigation taps, 24 verified selections, 6 PROCEED actions,
6 acknowledgements, zero faults, zero aborts, and zero observed wrong actions.

What this does not claim:

- arbitrary resolution reliability
- 1080p live validation
- universal robustness across every possible GTA UI, overlay, or driver scenario
- production readiness for every machine

1920x1080 remains outside the validated stable profile at this commit.
Experimental 1080p work, if any, stays independent and is not validated by this run.
