# Stage 8D.2A friend helper: clean-verify then run the experimental native 1920x1080
# profile. Input-free preflights by default; -Live sends REAL input and additionally
# requires -ConfirmExperimentalLiveInput. All logs go below
# target/stage8d-1080p-live/<timestamp>/ (build output, never committed).
# Run from the repository root after cloning. See docs/experimental-1080p-live.md.
param(
    [switch]$ListMonitors,
    [switch]$RecognitionOnly,
    [switch]$Live,
    [int]$Monitor = -1,
    [switch]$ConfirmExperimentalLiveInput,
    [switch]$Once,
    [string]$TargetExe = "GTA5_Enhanced.exe",
    [string]$AbortKey = "SCROLL_LOCK",
    [int]$IntervalMs = 200,
    [int]$StableFrames = 3
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message, [int]$Code = 2) {
    Write-Error $Message
    exit $Code
}
# Native-call note: stderr text from the build (for example the expected OpenCV warning
# inside the corrupt-asset unit test) must never become a terminating script error, so
# every mvnw.cmd pipeline below runs inline with $ErrorActionPreference relaxed to
# Continue and restores Stop afterwards. Pipelines stay inline (never inside a function)
# so their console output is not captured into a return value; the native exit code is
# read from $LASTEXITCODE immediately (Tee-Object is a cmdlet and never replaces it).

if (-not (Test-Path ".\mvnw.cmd")) { Fail "Not a repository root: mvnw.cmd not found." }
if (-not (Test-Path ".\fixtures\gameplay\layout\representative-2560x1440.csv")) { Fail "Not a repository root: stable 1440p layout missing." }
if (-not (Test-Path ".\fixtures\gameplay\layout\experimental-1920x1080.csv")) { Fail "Experimental 1080p manifest missing: checkout predates Stage 8D.2A." }
if (-not (Test-Path ".\.git")) { Fail "Not a git checkout: .git not found." }

$modes = 0
if ($ListMonitors) { $modes++ }
if ($RecognitionOnly) { $modes++ }
if ($Live) { $modes++ }
if ($modes -ne 1) { Fail "Choose exactly one mode: -ListMonitors, -RecognitionOnly or -Live." }

if ($Live -and -not $ConfirmExperimentalLiveInput) {
    Fail "-Live sends REAL input to GTA. Re-run with -ConfirmExperimentalLiveInput."
}
if (($Live -or $RecognitionOnly) -and $Monitor -lt 0) {
    Fail "-Monitor <n> is required. Run -ListMonitors first and pick physical 1920x1080."
}

$head = (git rev-parse HEAD).Trim()
Write-Output "git HEAD: $head"
$dirty = git status --porcelain
if ($dirty) {
    Write-Output $dirty
    Fail "Tracked working tree is dirty: commit or stash before a validation run."
}
Write-Output "Working tree is clean."
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path "target/stage8d-1080p-live" $timestamp
$verifyLog = Join-Path $runDir "verify.log"
$monitorsLog = Join-Path $runDir "monitors.log"
$runLog = Join-Path $runDir "run.log"
# The verify log must NOT live below target/ while `mvn clean` runs: clean deletes
# target/, so a log opened there by Tee-Object could break the delete on Windows and
# the durable destination would be wiped by the very command it records. The final
# target/stage8d-1080p-live/<timestamp>/ directory is therefore created only AFTER
# Maven has finished, and the temp log is copied there.
$unique = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$tempVerifyLog = Join-Path ([System.IO.Path]::GetTempPath()) "gta-fingerprint-1080-verify-$timestamp-$unique.log"
Write-Output "Running clean verify (rebuilds target/classes from scratch)..."
Write-Output "Temporary verify log: $tempVerifyLog"
$verifyExit = 1
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & .\mvnw.cmd -B -ntp clean verify 2>&1 | Tee-Object -FilePath $tempVerifyLog
    $verifyExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    if (Test-Path -LiteralPath $tempVerifyLog) {
        Copy-Item -LiteralPath $tempVerifyLog -Destination $verifyLog -Force
        Remove-Item -LiteralPath $tempVerifyLog -Force
    }
}
if ($verifyExit -ne 0) {
    Fail "clean verify FAILED (exit $verifyExit). Preserved log: $verifyLog. Stopping: no monitor listing, no recognition, no live run." 3
}
Write-Output "clean verify passed. Log preserved at $verifyLog."
Write-Output "Listing monitors (input-free, no opt-in needed)..."
$monitorsExit = 1
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & .\mvnw.cmd -B -ntp -q compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveSolverMain" "-Dexec.args=--list-monitors" 2>&1 | Tee-Object -FilePath $monitorsLog
    $monitorsExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($monitorsExit -ne 0) { Fail "Monitor listing FAILED (exit $monitorsExit). See $monitorsLog." 3 }
$modeName = "ListMonitors"
if ($RecognitionOnly) { $modeName = "RecognitionOnly" }
if ($Live) { $modeName = "Live" }
Set-Content -Path (Join-Path $runDir "metadata.txt") -Encoding utf8 -Value @(
    "timestamp=$timestamp",
    "git-sha=$head",
    "mode=$modeName",
    "profile=1920x1080-EXPERIMENTAL",
    "selected-monitor=$Monitor",
    "target-exe=$TargetExe",
    "abort-key=$AbortKey",
    "interval-ms=$IntervalMs",
    "stable-frames=$StableFrames",
    "once=$Once",
    "confirm-experimental-live-input=$ConfirmExperimentalLiveInput")
$head | Out-File -FilePath (Join-Path $runDir "git-sha.txt") -Encoding utf8
if ($ListMonitors) {
    Write-Output "Done. Pick the monitor with physical 1920x1080:"
    Write-Output "stable 1440: unsupported; experimental 1080: supported."
    Write-Output "Logs saved below $runDir. No input was sent."
    exit 0
}
Write-Output ""
Write-Output "SCREEN RECORDING SHOULD ALREADY BE RUNNING:"
Write-Output "native physical 1920x1080, full uncropped 16:9 game output, no overlay over the fingerprint UI."
Write-Output "See docs/experimental-1080p-live.md for the full recording requirements."
Write-Output ""
if ($RecognitionOnly) {
    $kind = "--once"
    if (-not $Once) { $kind = "--watch --interval-ms $IntervalMs --stable-frames $StableFrames" }
    $execArgs = "--monitor $Monitor $kind --enable-experimental-1080p"
    Write-Output "Recognition-only preflight (NO input will be sent):"
    Write-Output "LiveRecognitionMain $execArgs"
    $recognitionExit = 1
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & .\mvnw.cmd -B -ntp -q compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveRecognitionMain" "-Dexec.args=$execArgs" 2>&1 | Tee-Object -FilePath $runLog
        $recognitionExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    Write-Output "Exit: $recognitionExit. Log saved to $runLog. No input was sent."
    exit $recognitionExit
}
$execArgs = "--monitor $Monitor --watch --enable-input --enable-experimental-1080p --target-exe $TargetExe --abort-key $AbortKey --interval-ms $IntervalMs --stable-frames $StableFrames"
Write-Output "EXPERIMENTAL 1080p live solver with REAL input:"
Write-Output "LiveSolverMain $execArgs"
Write-Output "Emergency abort: hold $AbortKey. Ctrl+C stops the watcher itself."
$liveExit = 1
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & .\mvnw.cmd -B -ntp -q compile exec:java "-Dexec.mainClass=io.github.bohdankordon.casinofingerprint.app.LiveSolverMain" "-Dexec.args=$execArgs" 2>&1 | Tee-Object -FilePath $runLog
    $liveExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
Write-Output "Exit: $liveExit. Log saved to $runLog."
exit $liveExit
