# Windows releases

Stage 9C implements release automation. **v0.9.0-beta.1 was published** from
commit `3389c368a5e255b4d19c3d9b1682c99df5cf72c6`. Its real public browser
download/install checkpoint passed: EXE and asset/checksum/provenance verification,
unsigned Authenticode, preserved Mark-of-the-Web, expected SmartScreen, successful
per-user installation without UAC, static installed-layout/private-runtime validation,
Start Menu integration and USERUNMANAGED Windows Installer context.

The observed beta.1 installer UI was minimal (preparation/information gathering,
then window closure), motivating Stage 9B.1 wizard hardening. **v0.9.0-beta.2 is
intended** to qualify the new wizard and the real beta.1 -> beta.2 upgrade; beta.1
must remain installed until then. Stage 9D full GTA qualification remains pending.
Automated packaging, static UI inspection and input-free smokes do not visually
prove the new wizard or replace real release qualification. Never press ARM
during input-free release validation.

## Public tag policy

The standalone `scripts/resolve-release-version.ps1` emits JSON with `tag`,
`semanticVersion`, `windowsPackageVersion`, `isPrerelease`, `channel` and
`prereleaseNumber` (empty channel and null number for stable releases).

Accepted forms are `vMAJOR.MINOR.PATCH`, `vMAJOR.MINOR.0-alpha.N`,
`vMAJOR.MINOR.0-beta.N` and `vMAJOR.MINOR.0-rc.N`. The case-sensitive syntax is:

```text
\Av(?<major>0|[1-9][0-9]{0,2})\.(?<minor>0|[1-9][0-9]{0,2})\.(?<patch>0|[1-9][0-9]{0,3})(?:-(?<channel>alpha|beta|rc)\.(?<number>[1-9][0-9]{0,3}))?\z
```

The resolver additionally requires major/minor 0..255, stable patch 0..9999,
prerelease patch exactly 0 and prerelease number 1..9999. A prerelease targeting
0.0.0 has no predecessor slot and fails. Leading zeros, whitespace, partial
matches, arbitrary suffixes, unsupported channels and build metadata (`+build`)
fail closed. These bounds deliberately reserve Windows build-number ranges.

Run the deterministic policy tests with:

```powershell
./scripts/resolve-release-version.ps1 -SelfTest
./scripts/resolve-release-version.ps1 -ReleaseTag v0.9.0-beta.1
```

## Two distinct versions

The public Git tag, release title, installer filename and Maven/JAR metadata
carry SemVer. Windows Installer ProductVersion is an internal numeric version
used to order upgrades; it cannot carry a SemVer prerelease suffix.

Stable `vM.m.p` maps to `M.m.p`. For prereleases targeting `M.m.0`, choose the
predecessor slot `(M, m-1)` when `m > 0`, otherwise `(M-1, 255)` when `M > 0`.
Set the third Windows component to `10000 + N` for alpha, `30000 + N` for beta
or `50000 + N` for rc. Thus every prerelease sorts above all supported stable
patches in the preceding slot and below the target stable release. Alpha,
beta and rc occupy disjoint ranges; their numbers remain unique.

| Public tag | Maven / semantic version | Windows ProductVersion |
| --- | --- | --- |
| v0.9.0-beta.1 | 0.9.0-beta.1 | 0.8.30001 |
| v0.9.0-beta.2 | 0.9.0-beta.2 | 0.8.30002 |
| v1.0.0-rc.1 | 1.0.0-rc.1 | 0.255.50001 |
| v1.2.0-alpha.7 | 1.2.0-alpha.7 | 1.1.10007 |
| v1.2.0-beta.7 | 1.2.0-beta.7 | 1.1.30007 |
| v1.2.0-rc.7 | 1.2.0-rc.7 | 1.1.50007 |
| v1.2.0 | 1.2.0 | 1.2.0 |
| v1.2.3 | 1.2.3 | 1.2.3 |

Maven uses `<version>${revision}</version>` with default revision
`0.1.0-SNAPSHOT`. Normal wrapper commands retain that developer version.
Release packaging supplies `-Drevision=0.9.0-beta.1` without editing the POM
or running `versions:set`. Both packaging scripts accept optional
`-ProjectVersion`; Stage 9B forwards it into a fresh full Stage 9A build and
checks BUILD-INFO against the requested version. Without that parameter, the
committed default revision is used. `-AppVersion` remains a separate numeric
Windows version (development default `0.1.0`).

For the first prerelease fixture the Stage 9A image contains exactly
`gta-casino-fingerprint-solver-0.9.0-beta.1.jar`, and BUILD-INFO records
`Project version: 0.9.0-beta.1`.
The JAR manifest's `Implementation-Version` comes from `${project.version}`:
`0.1.0-SNAPSHOT` for default builds and `0.9.0-beta.1` for this fixture.
Stage 9A validates the actual packaged JAR with the selected JDK's `JarFile` /
`Manifest` parser, including continuation-line handling, and fails if the main
attribute is missing or differs from the effective version. BUILD-INFO records
that verified value; release orchestration checks it against SemVer and carries
it into provenance. The publish job independently checks the provenance field.

Stage 9B independently verifies the MSI
ProductVersion is `0.8.30001` and preserves the permanent upgrade UUID
`855C5415-0A97-4FEA-AE54-3B308FE8D1E4`.

## Local release bundle

Use a completely clean Windows x64 checkout. Build tools are selected explicitly;
the script does not install them. The release line requires Eclipse Temurin
21.0.6+7 x64 (`java.runtime.version=21.0.6+7-LTS`, `os.arch=amd64`),
jpackage 21.0.6 and matching WiX candle/light 3.14.1.8722.
The setup-java selector is the exact Adoptium catalog SemVer
`21.0.6+7.0.LTS`; the independently checked Java runtime reports
`21.0.6+7-LTS`. Neither selector nor runtime validation floats to another build.

```powershell
./scripts/build-release-bundle.ps1 -ReleaseTag v0.9.0-beta.1 `
  -PackagingJdk 'C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot' `
  -WixBinDir 'C:\Dev\wix-3.14.1'
./scripts/build-release-bundle.ps1 -ReleaseTag v0.9.0-beta.1 -VerifyOnly
```

This builds the full Stage 9B installer, including Maven verification (at least
915 tests, no failures/errors/skips), the private jlink runtime, jpackage image,
all 16 packaged smokes and static MSI inspection. It then verifies both stage
summaries and creates exactly these UTF-8-text/binary assets in `target/release/`:

1. `GTA-Casino-Fingerprint-Solver-v0.9.0-beta.1-windows-x64.exe`
2. `SHA256SUMS.txt`
3. `release-provenance.txt`

The public installer is a byte-identical copy of the validated Stage 9B EXE,
proved by SHA-256 before and after copying. No MSI, logs, temporary packaging
directories, certificates, experimental 1080p content or manually copied source
archive is included. GitHub supplies its own tag source archives.

Provenance summarizes authoritative Stage 9B fields and adds the release tag,
both versions, prerelease flag, exact Git commit/tree/ref, repository/run/runner
image information when available, toolchain, FlatLaf, upgrade UUID, tests,
smokes, installer name/bytes/hash, signing and input/startup contracts. It omits
local installation paths from the public summary. Only ignored `target/` is
written; Git SHA/tree and clean status are checked again after packaging.

## Checksums and unsigned installation

`SHA256SUMS.txt` has two lines in conventional lowercase
`<sha256>  <filename>` format for the EXE and provenance. `-VerifyOnly` checks
the exact asset set, checksum syntax, duplicates, hashes and version/size
provenance. On Linux, run `sha256sum --check SHA256SUMS.txt` in the bundle directory.
On Windows, independently inspect hashes with:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath './GTA-Casino-Fingerprint-Solver-v0.9.0-beta.1-windows-x64.exe'
Get-FileHash -Algorithm SHA256 -LiteralPath './release-provenance.txt'
```

Compare with the corresponding checksum lines (hash case is immaterial).
Checksums detect corruption; they are not a publisher signature. The Windows
installer is **NOT SIGNED**. SmartScreen may warn. The production profile remains
physical 2560x1440; experimental 1080p PR #20 is excluded. Startup is DISARMED
with zero input, and live SCANCODE_BATCH delivery is strictly opt-in.

## GitHub Actions modes and publication

The Release workflow has three modes:

| Event | Version source | Result |
| --- | --- | --- |
| Push of an existing `v*` tag | Strictly resolved pushed tag | Build, validate, then publish |
| PR changing release/packaging files or POM | Fixture `v0.9.0-beta.1` | Full dry run; publish job skipped |
| Manual workflow_dispatch | Required `release-tag` input | Full dry run; publish job skipped |

The path-scoped heavy PR job is separate from ordinary Windows/Ubuntu Maven CI.
Normal CI adds only the lightweight resolver self-test on Windows. PR and manual
dry runs upload a `stage9c-release-dry-run-<12-character-commit>` Actions artifact
retained for ten days. This artifact is not a GitHub Release. A tag build uses
`stage9c-release-<12-character-commit>`.

The Windows build job uses `windows-2025`, full checkout history and only
`contents: read`. It explicitly selects the exact Temurin version and validates
the preinstalled WiX directory. The hosted runner inventory currently lists
[WiX 3.14.1.8722](https://github.com/actions/runner-images/blob/main/images/windows/Windows2025-Readme.md).
If the image changes, the job fails with instructions to review the pinned
toolchain; it never downloads substitute WiX tooling. The four GitHub-owned
actions are pinned to full commits corresponding to checkout v7, setup-java v6,
upload-artifact v4 and download-artifact v4.

For a real tag push the checkout must match the triggering commit, the tag must
resolve to that commit, and that commit must be an ancestor of `origin/main`.
It need not equal the current main tip. Invalid `vfoo` tags fail before packaging.

The separate Ubuntu publish job runs only after a successful real-tag build and
alone receives `contents: write` via `GH_TOKEN=${{ github.token }}`. It downloads
the exact artifact without checking out or rebuilding application code, then
independently validates the tag policy, checksum set and provenance. It resolves
the existing remote tag (including annotated tags) to the recorded commit and
rechecks ancestry against current main. No workflow creates tags.

Publication rejects any existing release or draft for the tag, creates a draft
using GitHub CLI `--verify-tag --generate-notes`, uploads exactly three assets,
checks their names/count/sizes, downloads the draft assets to independently
verify their bytes/checksums against the bundle, rechecks the remote tag, and
only then publishes the draft with `--verify-tag`. Prereleases prepend the
testing/unsigned/SmartScreen/profile/1080p warning and use `--latest=false`.
Draft creation passes no `--latest` option for either channel. Only the final
publication edit sets `--draft=false` and the explicit prerelease/latest flags:
stable uses `--prerelease=false --latest=true`; prerelease uses
`--prerelease=true --latest=false`. This follows GitHub's
[draft/latest contract](https://docs.github.com/en/rest/releases/releases#create-a-release).
Stable publication semantics are tested against the exact offline publication
code; no stable publication was executed against GitHub during validation.
The publish job does not use a PAT
or a third-party release action. Tag-specific concurrency never cancels an
in-progress release (`cancel-in-progress: false`).

## Failed-release recovery and next boundary

A failed build has no publication. A failed upload or validation leaves an
unpublished draft for human inspection. Reruns deliberately refuse existing
drafts/releases; do not overwrite assets, move a tag, automatically delete a
draft, or treat an API/network error as proof that a release does not exist.
Review the failed run and draft, independently verify tag/commit and assets,
then use a separately authorized recovery action (for example removing a failed
unpublished draft and rerunning **all** jobs). Rerun all jobs so provenance run
attempt matches the publishing attempt. Treat published releases as immutable;
ship a new version for corrections. A changed source commit needs a separately
reviewed tag/version decision.

Stage 9B.1 finishes at installer UI implementation and PR dry-run validation. The
next step is independent review of the exact PR head; do not merge or create
`v0.9.0-beta.2` as part of implementation. After review and merge, a separate
controlled prompt can authorize that tag/release and its first interactive wizard
execution. Stage 9D then continues qualification of the shipped installer and GTA
behavior; signing, updates, telemetry, package managers, attestations, ARM64 and
other-platform installers remain outside this stage.
