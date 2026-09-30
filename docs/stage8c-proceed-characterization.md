# Dedicated PROCEED (Tab) scan-code characterization (Stage 8C.3)

Stage 8C.2 characterized the delivery question for five controls and left exactly one
solver control unvalidated: PROCEED (Tab). This stage adds ONE dedicated diagnostic-only
entry point whose only purpose is to send exactly one PROCEED / Tab using the already
validated SCANCODE_BATCH representation, after the same safety gates proven in Stage
8C.1/8C.2. The normal InputDeliveryProbeMain keeps refusing PROCEED; the dedicated
ProceedInputProbeMain is the only Stage 8C diagnostic allowed to emit Tab.

Nothing in this document claims a Tab result yet: the user runs the single manual Tab
validation after review and merge. Until that human observation passes, production
promotion remains blocked and Stage 8D remains blocked.

## Real manual evidence encoded

Target executable: `GTA5_Enhanced.exe`.

Current production VK_BATCH:

- UP: no visible GTA response.
- SELECT: no visible GTA response.

Notepad control with the same production backend:

- LEFT: PASS.
- SELECT: PASS.

Therefore generic Windows input works, but GTA Enhanced does not respond to the
production virtual-key batch representation in this environment.

Stage 8C.2 manual characterization (user-reported, merged):

SCANCODE_BATCH:

- UP: PASS, exactly one visible movement.
- DOWN: PASS, exactly one visible movement.
- LEFT: PASS, exactly one visible movement.
- RIGHT: PASS, exactly one visible movement.
- SELECT: PASS, exactly one visible action.

SCANCODE_HOLD 50 ms:

- UP: PASS.
- SELECT: PASS.

SCROLL_LOCK abort:

- PASS, ABORTED, zero input.

Conclusion supported by evidence: the scan-code representation is required by GTA
Enhanced in this environment; a non-zero hold is NOT required for
UP/DOWN/LEFT/RIGHT/SELECT.

Production VK_BATCH no-response history is preserved above: the baseline was reused, not
rewritten, and its lack of visible GTA reaction is the reason the scan-code path was
characterized.

## Only unvalidated solver control

The only unvalidated solver control is PROCEED / Tab. Stage 8C.2 scan-code
characterization passed for arrows + SELECT. PROCEED/Tab validation remains pending.
Production promotion remains blocked.

## Dedicated double-opt-in probe

Because Stage 8C previously forbade Tab, the dedicated probe requires one additional
explicit acknowledgement:

```
ProceedInputProbeMain --enable-input --enable-proceed-test
    --target-exe GTA5_Enhanced.exe --abort-key SCROLL_LOCK [--countdown-seconds 5]
```

Real input requires ALL of: Windows, `--enable-input`,
`--enable-proceed-test`, `--target-exe <exact.exe>` and an explicit
`--abort-key`. Missing either opt-in means zero input; there is no default
proceed-test acknowledgement.

The CLI is permanently `control = PROCEED`,
`delivery = SCANCODE_BATCH`, `hold = 0`. There is no `--control`,
`--delivery-mode` or `--hold-ms` option: those spellings refuse as unknown
options. This keeps the surface deliberately tiny and prevents any broadening of the
general probe.

The abort key is explicitly required and never defaulted. SCROLL_LOCK was manually
validated as working in GTA (ABORTED, zero input), but the dedicated probe does not
hardcode it; F12 is not restored.

## Tab Set-1 0x0F, non-extended

PROCEED is the main keyboard Tab key, standard Set-1 make code `0x0F`:

- key-down: `wVk = 0`, `wScan = 0x0F`, `flags = KEYEVENTF_SCANCODE`;
- key-up: `wVk = 0`, `wScan = 0x0F`,
  `flags = KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP`.

Tab is NOT extended: `KEYEVENTF_EXTENDEDKEY` is never set for Tab, and
`VK_TAB` is never used by this diagnostic. The mapping lives only in the
dedicated `ScanCodeSpec.tabForProceedProbe()` method; the general
`ScanCodeSpec.forControl(PROCEED)` and the general
`DiagnosticDeliveryPlan.forTap(PROCEED, ...)` keep throwing, and the Stage 8C.1
diagnostic and Stage 8C.2 probe keep refusing PROCEED.

## Intended harmless manual GTA context

The user tests this after merge in GTA pause map. In the real GTA UI the bottom help
text shows `Point Of Interest    Tab`. This is the intended harmless visible test
context. The diagnostic itself does not know about or inspect the map; the human prepares
the safe context and visually confirms exactly one effect.

## Exactly one Tab maximum

One invocation sends at most ONE Tab: no automatic retry, no second Tab, no loop, no
matrix mode. Safety gates reuse the Stage 8C.1 ordering: Windows, explicit double
opt-in, countdown, abort idle, foreground pin by HWND + PID + executable, abort idle, pin
still valid, abort idle immediately before the tap, exactly one tap, return.

Truthful result semantics match Stage 8C.2: pre-tap refusal means zero input
guaranteed; native tap attempt failure means INPUT_ERROR where partial delivery may have
occurred with best-effort Tab key-up cleanup and no retry; successful down+up submission
means SENT, which never claims GTA reacted. The human validates the visible Point Of
Interest behavior.

## No claim until the user runs it

No Tab characterization result exists yet. SENT will mean Windows accepted the native
submission; whether GTA visibly performed exactly one Point Of Interest / Tab action is
determined only by the human operator on the pause map. If no visible action occurs: STOP
and do not promote production. If INPUT_ERROR occurs: treat Tab as potentially delivered
and restore known UI state before any later action.

## Manual validation plan (document only, user-run after merge)

1. Open GTA V Enhanced normally with BattlEye unchanged.
2. Open the pause map.
3. Confirm the bottom help shows a harmless Tab action such as Point Of Interest.
4. Start the dedicated probe with `--enable-input --enable-proceed-test --target-exe
   GTA5_Enhanced.exe --abort-key SCROLL_LOCK --countdown-seconds 5`.
5. Alt+Tab back to the GTA map.
6. Observe exactly ONE Point Of Interest / Tab action.

Expected CLI: `SENT`. Do not run this manually during implementation.

## Stage 8D blocked

Stage 8D remains blocked until the human-observed Tab result passes. No production file
changed in this stage: `WindowsSendInputSink`, `LiveSolverMain`,
`LiveSolverOptions`, `GuardedPlanExecutor`, `VerificationPolicy`,
navigation, recognition and capture are untouched. This PR is diagnostic-only.
