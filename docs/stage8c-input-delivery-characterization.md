# GTA Enhanced input-delivery characterization (Stage 8C.2)

Stage 8C.1 delivered the guarded single-tap diagnostic and the manual procedure. The real
manual evidence below then showed that the Stage 8C.1 safety guards work but that the
current production input delivery produced no visible GTA reaction. Stage 8C.2 adds a
DIAGNOSTIC-ONLY characterization path that can send exactly one explicitly requested
harmless key with one explicitly selected delivery mode, so the delivery question can be
answered by controlled manual observation.

Nothing in Stage 8C.2 has been characterized yet: no mode has been run against GTA, and
this document does not claim any characterization result. It describes the implementation
and the post-merge manual matrix.

## Real Stage 8C.1 manual evidence (user-reported)

Target executable: `GTA5_Enhanced.exe`, with BattlEye and the normal GTA Online
configuration unchanged.

| # | Test | Result |
| --- | --- | --- |
| A | Negative foreground test (do not foreground GTA) | `TARGET NOT FOREGROUND`, zero input - PASS |
| B | Abort key held during the countdown | `ABORTED`, zero input - PASS |
| C | Current production `WindowsSendInputSink`, `--control UP` | CLI reported `SENT`; GTA phone/menu showed NO visible response |
| D | Current production `WindowsSendInputSink`, `--control SELECT` | CLI reported `SENT`; GTA showed NO visible response |

Control experiment with the SAME merged diagnostic and the SAME current backend:

| Control | Target | Result |
| --- | --- | --- |
| `LEFT` | `Notepad.exe` | visible response - PASS |
| `SELECT` | `Notepad.exe` | visible response - PASS |

What this establishes:

- the JNA native path works;
- `SendInput` reaches ordinary Windows applications;
- the foreground pin works (A and B);
- the current virtual-key mappings work for desktop applications;
- this is NOT a generic "the diagnostic sends nothing anywhere" failure.

Therefore Stage 8C is NOT complete, and the unresolved issue is specific to how GTA
Enhanced handles (or filters) these synthesized keyboard events.

## Open hypotheses

The current production batch submits a virtual-key key-down plus key-up in ONE
`SendInput` call with no hold duration, `wScan = 0` and no `KEYEVENTF_SCANCODE`. The
candidate explanations that the four modes below are designed to separate:

1. GTA samples key state and the zero-duration down/up pair is never observed;
2. GTA responds to physical scan-code semantics rather than the virtual-key
   representation;
3. arrow keys require the correct extended-key representation;
4. GTA Enhanced (or its normal runtime environment) ignores or filters these synthesized
   inputs altogether.

BattlEye is NOT assumed to be the cause. Nothing in this stage disables, bypasses or
otherwise touches anti-cheat, and nothing injects into the GTA process.

## The four delivery modes

| Mode | Delivery shape |
| --- | --- |
| `VK_BATCH` | virtual-key key-down + key-up in ONE batch, no hold - the exact current production `WindowsSendInputSink` semantics, delegated to that unchanged backend |
| `VK_HOLD` | virtual-key key-down, hold, virtual-key key-up as separate native submissions |
| `SCANCODE_BATCH` | scan-code key-down + key-up in ONE batch |
| `SCANCODE_HOLD` | scan-code key-down, hold, scan-code key-up as separate native submissions |

`VK_BATCH` is the untouched baseline: the diagnostic calls the production
`WindowsSendInputSink.tap` itself rather than re-implementing the batch, so a
`VK_BATCH` run is literally the Stage 8C.1 behaviour.

All four modes remain ordinary user-mode Windows keyboard input. No `PostMessage` or
`SendMessage`, no DirectInput injection, no hooks, no drivers, no process injection, no
memory access and no anti-cheat interaction were added, and the CLI exposes no such
option.

## Scan-code contract

For the SCANCODE modes the stroke is built with `KEYEVENTF_SCANCODE`, `wVk` stays zero
(the Win32 contract ignores it when the flag is set) and `wScan` carries a Set-1
(MF-II / PC/AT) make code:

| `--control` | key | Set-1 make code | extended (`E0`) |
| --- | --- | --- | --- |
| `UP` | arrow up | `0x48` | yes |
| `DOWN` | arrow down | `0x50` | yes |
| `LEFT` | arrow left | `0x4B` | yes |
| `RIGHT` | arrow right | `0x4D` | yes |
| `SELECT` | main keyboard Enter | `0x1C` | no |

Extended keys are the ones whose full Set-1 sequence carries the `0xE0` prefix; only the
byte after the prefix belongs in `wScan`, and the prefix itself is represented by
`KEYEVENTF_EXTENDEDKEY`. Arrow-up is therefore the pair (`0x48`, extended), never a
two-byte `0xE048` value.

SELECT is the MAIN keyboard Enter (`0x1C`, no extended flag). The numeric-keypad Enter
shares the `0x1C` make code but carries the `E0` prefix; the probe must press the
ordinary main Enter, exactly like the production virtual-key mapping means `VK_RETURN`.

PROCEED/Tab has no mapping of any kind and remains forbidden in every mode.

## Hold contract

- the HOLD modes submit the key-down and the key-up as SEPARATE native calls;
- `--hold-ms` is explicit CLI data, bounded to 10..200 ms, default 50 ms when omitted;
- the BATCH modes never sleep between key-down and key-up, and an explicit `--hold-ms`
  with a BATCH mode is refused as a contradictory option;
- the hold polls the configured abort key in 10 ms steps and releases the key early when
  the abort key fires;
- the production `WindowsSendInputSink` gains no hold of any kind and stays
  byte-for-byte unchanged (pinned by a source-digest regression test).

## Key-up safety

Once a HOLD-mode key-down has been submitted, the key-up is ALWAYS attempted, even when:

- the hold sleep is interrupted;
- an abort fires during the hold;
- the normal key-up submission fails;
- an unexpected native error occurs.

The failure path performs a best-effort key-up cleanup (one additional release attempt,
never a key-down), reports INPUT ERROR semantics and NEVER retries the key-down and NEVER
sends a second logical tap. There is no deliberate path that can leave a held arrow down,
and if the cleanup itself cannot be confirmed the CLI prints an explicit warning telling
the operator to press and release that key once manually.

BATCH partial delivery keeps the existing truthful contract: a batch that reports fewer
inserted events than submitted is never confirmed, takes the same best-effort key-up
cleanup path and is reported as INPUT ERROR with no retry.

## Abort during a hold

- the countdown, the pre-tap gates and the hold all poll the configured abort key;
- an abort before the tap is a pre-tap refusal with guaranteed zero input;
- an abort AFTER the key-down (during the hold) is never reported as "zero input": the
  key is released early, the key-up is still submitted, and the run truthfully reports
  that the one tap was already attempted and fully submitted, with the early release
  recorded in the delivery outcome;
- key-up cleanup has priority over the full hold duration.

## Input attempt semantics

Every characterization result distinguishes exactly three outcomes:

A. pre-keydown refusal - zero input guaranteed (Windows gate, opt-in, forbidden control,
   countdown abort, target not foreground, focus lost, final abort check);

B. keydown attempted but the hold/key-up path failed - input was attempted, complete tap
   NOT confirmed, partial native input may have occurred, key-up cleanup attempted, no
   retry;

C. key-down and key-up successfully submitted - delivery submission succeeded; the tool
   never claims that GTA visibly reacted, because only the human operator can determine
   that.

The CLI prints the recorded delivery outcome (mode, requested/actual hold, whether the
key-down and key-up were confirmed, whether a cleanup was attempted, whether the abort
fired during the hold) for both successful and failed deliveries.

## Configurable abort key

F12 is a bad default: the real Stage 8C run showed that pressing F12 as the emergency
abort also creates a Steam screenshot. Stage 8C.2 therefore requires an explicitly chosen
abort key and never picks one automatically:

```
--abort-key <symbolic-name>
```

Supported keys are `F1`..`F24`, `PAUSE` and `SCROLL_LOCK` - ordinary physical keys
whose Win32 virtual-key values are unambiguous (`VK_F1`..`VK_F24` = `0x70`..`0x87`,
`VK_PAUSE` = `0x13`, `VK_SCROLL` = `0x91`).

Refused as abort keys, in every spelling: `UP`, `DOWN`, `LEFT`, `RIGHT`, `ENTER`,
`RETURN`, `SELECT`, `TAB`, `PROCEED` (they overlap the controls under test).

### Known shortcut conflicts to check before choosing

These are documented, not exhaustive; the user's own configured bindings are
authoritative:

| Key | Documented conflict |
| --- | --- |
| `F12` | Steam screenshot shortcut; `Ctrl+F12` is the Steam Game Recording marker |
| `F11` | `Ctrl+F11` starts manual Steam recording when that mode is enabled |
| `F1`, `F2`, `F3` | Rockstar Editor recording shortcut group |
| `F9` | GTA default: drop weapon |
| `F10` | GTA default: drop ammo (also in the historical Rockstar Editor group) |
| `Home` | Rockstar Games in-game overlay (not offered as an abort key) |

The probe prints a warning when a key with a documented conflict is chosen explicitly, so
the choice is visible in the run log instead of being silently assumed.

## CLI contract

```
InputDeliveryProbeMain --enable-input --target-exe <exact.exe>
    --control <UP|DOWN|LEFT|RIGHT|SELECT>
    --delivery-mode <VK_BATCH|VK_HOLD|SCANCODE_BATCH|SCANCODE_HOLD>
    --abort-key <F1..F24|PAUSE|SCROLL_LOCK>
    [--hold-ms <n>] [--countdown-seconds <n>]
InputDeliveryProbeMain --help
```

Input requires ALL of: Windows, the explicit `--enable-input` opt-in, an exact
`--target-exe`, exactly one allowed control, exactly one delivery mode and an explicitly
named abort key. Missing any one, an unknown option, a duplicate value option, a malformed
number, a hold outside 10..200 for a HOLD mode, a hold at all for a BATCH mode, or any
spelling of PROCEED refuses with zero input before any backend exists.

One invocation sends at most ONE logical tap: there is no matrix mode, no batch mode over
several modes or controls, no repetition and no retry. Each mode/control case is a
separate process invocation, started manually by the user.

Exit codes: 0 tap sent (or `--help`), 2 usage refusal, 3 runtime refusal or input
failure. Exit code 3 alone does not guarantee zero input: an INPUT ERROR exits 3 after the
one allowed attempt and never prints "no input was sent".

The probe reuses the unchanged Stage 8C.1 safety core (`SingleTapInputDiagnostic`) for
every gate: Windows requirement, opt-in, Tab refusal, countdown with abort polling, exact
executable foreground pin by HWND plus PID plus executable, pin re-check, final abort
check, exactly one tap and no retry. The only additive change to that core is an abort-key
label parameter, so ABORTED messages name the key the user actually configured; the
Stage 8C.1 CLI keeps the exact F12 wording and its tests are unchanged.

## Anti-cheat and scope

- BattlEye remains enabled and completely unchanged.
- No anti-cheat bypass, no disabling, no process injection, no memory access, no hooks and
  no drivers were added.
- If ordinary user-mode input variants are not accepted by GTA Enhanced in the tested
  environment, the characterization STOPS there and reports that result; it does not
  escalate to bypass techniques.
- No production promotion: `LiveSolverMain`, `GuardedPlanExecutor`,
  `VerificationPolicy` and the navigation path are untouched, and Stage 8D is not
  started.

## Manual validation matrix (after review and merge)

The USER performs every step manually; the implementation does not run any of them. Use a
harmless GTA menu/phone, keep BattlEye and the normal GTA Online configuration unchanged,
and remember one invocation = at most one tap.

1. `UP` with each mode, in this order:
   - `VK_BATCH` (known current baseline; may be skipped because the earlier Stage 8C run
     already showed no response);
   - `VK_HOLD --hold-ms 50`;
   - `SCANCODE_BATCH`;
   - `SCANCODE_HOLD --hold-ms 50`.
2. As soon as ONE mode produces exactly ONE visible UP movement:
   - record it;
   - test `SELECT` with that SAME mode.
3. If no mode works: retry only the two HOLD modes at `--hold-ms 100`.
4. If still nothing works: STOP the characterization. Do not escalate to hooks, drivers,
   process injection or anti-cheat bypass; report that ordinary user-mode input variants
   were not accepted by GTA Enhanced in the tested environment.

Example invocation (PowerShell, repository root, compiled first with
`.\mvnw.cmd -B -ntp compile`):

```powershell
.\mvnw.cmd -B -ntp compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.InputDeliveryProbeMain" "-Dexec.args=--enable-input --target-exe GTA5_Enhanced.exe --control UP --delivery-mode SCANCODE_HOLD --hold-ms 50 --abort-key F8 --countdown-seconds 5"
```

Replace `GTA5_Enhanced.exe` with the exact executable file name of the installation,
`SCANCODE_HOLD` and `UP` with the case being run, and `F8` with the unbound key the
user chose after checking the Steam and GTA bindings. The example uses
`GTA5_Enhanced.exe` because the real Stage 8C.1 evidence came from the Enhanced edition.

Optional Notepad control (after merge, user-run, never by the implementation): the earlier
`VK_BATCH` `LEFT` and `SELECT` runs passed in Notepad; one `SCANCODE_HOLD` case may
be checked against Notepad first to confirm the scan-code path is accepted by an ordinary
Windows application.

## What this stage does NOT claim

- No characterization result exists yet: no mode has been tested against GTA, and no mode
  is known to work.
- `VK_BATCH` remaining unresponsive in GTA is the earlier manual observation, not a
  conclusion about the other three modes.
- Nothing is promoted to production; Stage 8C.3 (promotion of an evidenced semantics)
  does not exist yet and Stage 8D stays blocked until the human-observed results pass.
## Stage 8C.4 update: characterization complete, SCANCODE_BATCH promoted

Manual characterization is now complete: SCANCODE_BATCH passes for UP DOWN LEFT RIGHT
SELECT, SCANCODE_HOLD 50 ms passes for UP and SELECT, SCROLL_LOCK aborts with zero input,
and the Tab probe passes (see stage8c-proceed-characterization.md). The scan-code
representation is the material difference; a hold is NOT required, so only SCANCODE_BATCH
is promoted to production. VK_BATCH (virtual-key) is now the historical pre-promotion
baseline, not current production. Production smoke through the actual promoted sink is
still pending; Stage 8D remains blocked. See stage8c-production-promotion.md.

