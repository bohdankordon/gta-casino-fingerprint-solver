# Windows packaging (Stage 9A)

Stage 9A turns the validated stable live solver into a deterministic, self-contained Windows
application image. It is deliberately **not** the final installer: the image is the proven input
that the Stage 9B jpackage step will consume. The validated solver code is not touched by any of
this work.

Validated product context: Stage 8D.1 real GTA V Enhanced, Borderless, physical 2560x1440,
GTA5_Enhanced.exe, SCANCODE_BATCH delivery (see [stage8d1-live-validation.md](stage8d1-live-validation.md)).
1920x1080 stays frozen and out of scope; the experimental PR #20 branch is never packaged, merged or
copied.

## Goals

- The packaged application runs outside Maven, outside the repository development classpath and
  outside any Java installed on the machine.
- The target machine needs Windows x64 only: no Java, no Maven, no Git, no OpenCV and no JNA
  installation.
- The image is deterministic and reproducible from one exact git commit and one exact JDK build:
  the build refuses to start unless the Git worktree is completely clean.
- Nothing tracked in the repository is modified by a packaging build; every artifact lands below the
  ignored target/ directory.

## Distribution direction (frozen)

The final distribution is a Windows EXE installer with a bundled private Java runtime, based on
Eclipse Temurin 21, installed with a normal Windows uninstall and published through GitHub Releases
with automated GitHub Actions release builds. Stage 9A implements only the first piece of that
chain: the application image.

Code signing is explicitly deferred. There is no Authenticode material, no certificate, no PFX
secret, no SignPath or Azure signing configuration and no signing CI anywhere in Stage 9A, and the
generated image is marked NOT SIGNED in BUILD-INFO.txt. Because the image is a normal directory
tree, a signing step can later be inserted between installer generation and publication without
changing the packaging architecture.

## Why Eclipse Temurin 21

Temurin 21 x64 is the canonical build and runtime source, and the packaging build refuses any other
vendor. The reason is determinism, not preference: jlink output, module inventory, native library
packaging and the exact java.runtime.version string all vary between vendors and between JDK builds,
and the bundled runtime is the environment the solver is validated in. One canonical vendor keeps the
release artifact identical to the validated environment.

The build script checks:

- java.version starts with 21.
- java.vendor plus java.vendor.version matches Adoptium or Temurin
- java, javac, jar, jlink and jdeps all exist in that JDK

If any check fails, the script stops with an actionable message that names the detected vendor and
version and points at the separate installation step. It never installs Java, never edits PATH or
JAVA_HOME for the machine and never registers a system-wide JVM.

## Private runtime policy

The packaged application always uses the runtime inside its own directory (runtime/bin/java.exe).
There is deliberately no "use the system Java 21 when it exists, otherwise fall back" logic:

- deterministic runtime class of environment between validation and release
- no JAVA_HOME or PATH ambiguity, no vendor, build or module variation
- no dependency on anything the user installed, and no version negotiation at startup
- the private runtime can be removed by the installer in Stage 9B without leaving a system Java behind

The runtime is built with jlink from the packaging JDK with a conservative module set:

- java.base - the core
- java.desktop - AWT Robot screen capture, BufferedImage and ImageIO
- java.logging - logging used by the JavaCPP and JNA stack
- java.management - JVM management surface used by native library loaders
- jdk.unsupported - sun.misc.Unsafe, which JNA and JavaCPP rely on

The module list is intentionally conservative: correctness first, size second. jdeps runs over the
application jar and every staged dependency jar on each build, and the build fails if jdeps reports a
module that is not in the list. Static inference alone is never trusted for reflection-heavy and
native-heavy libraries, which is why the runtime is proven by running the packaged application, not by
argument. On the Stage 9A build machine the jlink image contains 148 files and 46.7 MB.

## Build prerequisites

- Windows x64
- a completely clean Git worktree: git status --porcelain must be empty, so the recorded Git SHA
  identifies the committed sources and runtime data (ignored build and local-data artifacts do not
  block a build)
- an interactive Windows desktop session (the packaged monitor-listing smoke enumerates displays)
- Eclipse Temurin JDK 21 x64, installed separately by the developer:

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact
```

or a manual download from https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64 .
Then point JAVA_HOME at that JDK for the shell, or pass -PackagingJdk with its path:

```powershell
$env:JAVA_HOME = "C:/Program Files/Eclipse Adoptium/jdk-21.0.6.7-hotspot"
./scripts/build-windows-app-image.ps1
```

- network access for the first Maven run (the wrapper downloads Maven and the pinned dependencies)
- no administrator rights are needed; the build is confined to the worktree and its target/ directory

## Build command

```powershell
./scripts/build-windows-app-image.ps1
```

Optional parameters: -PackagingJdk <path> selects the packaging JDK explicitly, and
-KeepRelocationCopy keeps the relocation smoke copy for inspection instead of deleting it.

The script performs, in order:

1. Windows x64 check.
2. Clean-worktree gate: git status --porcelain must be empty, so the recorded git SHA and branch
   identify the committed sources and data. Then the required inputs are checked and the reference
   manifest contract (20 crops, every asset present) is verified.
3. Packaging JDK check: Java 21, Temurin vendor, all packaging tools present.
4. mvnw.cmd -B -ntp clean verify package, with a test baseline gate (at least 859 tests, zero
   failures, zero errors).
5. Runtime dependency collection from the Maven staging step into app/lib.
6. jdeps module analysis followed by jlink --strip-debug --no-header-files --no-man-pages --compress=zip-6.
7. Application image assembly: project jar, runtime data, launcher.
8. BUILD-INFO.txt and config/packaged-files.txt (path, size and SHA-256 per file).
9. Forbidden content assertions over every file in the image.
10. Packaged input-free smoke tests with the bundled runtime.
11. Relocation smoke in a path containing spaces, launched from a working directory outside the image.
12. Final assertion that the tracked worktree state is unchanged by the build.

Diagnostics are written to target/stage9a/: build.log, one log per smoke test, and the printed summary.

## Provenance and the clean worktree gate

Packaging requires a completely clean Git worktree. The build refuses dirty or uncommitted state
before compilation, before jlink and before any packaging work: the gate runs right after the exact
git SHA and branch are recorded, and it fails with the offending git status --porcelain entries plus
instructions to commit or remove them.

The reason is that Maven compiles directly from the working tree and the packaging step copies the
runtime data directly from the working tree. Without the gate, an uncommitted change to a Java source,
pom.xml, dataset/reference, dataset/layout or fixtures/gameplay/layout could end up inside the image
while BUILD-INFO.txt still recorded the commit SHA only. With the gate, the recorded Git SHA uniquely
identifies the committed source and data tree that the image was built from.

Ignored content (target/ and local-data/) does not appear in git status --porcelain and therefore never
blocks a legitimate build. The script never restores, resets, cleans or stashes anything; cleaning up
is always a deliberate developer action.

## Application image structure

```
target/dist/app-image/
    app/
        gta-casino-fingerprint-solver-<version>.jar
        lib/                 staged pinned runtime dependency jars (JavaCPP, OpenCV, OpenBLAS, JNA, plus FlatLaf since Stage 9B)
    runtime/                 private Eclipse Temurin 21 runtime built with jlink
    dataset/
        layout/reference-layout.csv
        reference/fp_1..fp_4/{target.png,fragments/fragment_1..4.png}
    fixtures/
        gameplay/layout/representative-2560x1440.csv
    config/
        packaged-files.txt   packaging metadata: SHA-256, size and path of every other packaged file (the manifest lists payload files present when it is generated and never itself)
    BUILD-INFO.txt
    run-app-image.cmd        launcher: sets cwd to this directory and uses runtime/bin/java.exe
```

Measured on the Stage 9A build machine: 212 files, about 464 MB uncompressed, of which 415.1 MB are the
staged dependency jars and 46.7 MB are the private runtime. The exact byte total moves by a few bytes
between builds because BUILD-INFO.txt records the build timestamp.

The dependency size is dominated by opencv-platform, which pulls JavaCPP, OpenCV and OpenBLAS natives
for every supported platform; the Windows x64 subset alone is about 82 MB. Stage 9A deliberately does
not trim that set, because the classifier split has not been proven safe for native loading yet.
Trimming is a later packaging optimization candidate, not a Stage 9A goal.

## Runtime data audit

Only the files the production live path actually opens are packaged. The production call paths are
LiveSolverMain to GameplayLayout.representative(fixtures/gameplay/layout/representative-2560x1440.csv),
and LiveSolverMain to ReferenceFingerprintLibrary.load(projectRoot), which calls
ReferenceLayout.read(dataset/layout/reference-layout.csv) and then one imread per manifest crop.

Packaged:

- dataset/layout/reference-layout.csv - the Stage 1 manifest the reference library reads.
- dataset/reference/fp_1..fp_4/target.png and dataset/reference/fp_1..fp_4/fragments/fragment_1..4.png -
  the 20 canonical crops named by that manifest.
- fixtures/gameplay/layout/representative-2560x1440.csv - the gameplay layout the live solver reads
  before anything else.

Excluded, with the reason:

- dataset/source/casino-fingerprints-reference.png - read only by ReferenceDatasetGenerator, a dataset
  generation tool, never by the production runtime.
- fixtures/gameplay/source/representative-fingerprint-minigame-2560x1440.png - read only by preview and
  evaluation tools (GameplayNormalizationPreview, GameplayRoiOverlay, MatchingEvaluation,
  RecognitionEvaluation, external-video characterization).
- fixtures/gameplay/annotations/ and fixtures/gameplay/recordings/ - evaluation-only annotation
  catalogs; no production class reads them.
- docs/, recording metadata, benchmark assets, local-data/ and all Stage 8 evidence - not runtime material.
- the experimental 1080p layout and code from PR #20 - frozen, unmerged and out of scope.

The image keeps the repository-relative layout (dataset/..., fixtures/...), because the validated code
resolves its data relative to user.dir and Stage 9A does not change that.

## Packaged smoke tests

Maven tests are not packaged-runtime proof. The script runs the generated image instead, with JAVA_HOME
forced to a nonexistent path and PATH reduced to System32 only, so no system Java can be reached:
java.exe is simply not found on that PATH, and that fact is recorded in the logs.

| Test | Command (bundled runtime, cwd = image root) | Proves |
| --- | --- | --- |
| A | runtime/bin/java.exe -version | the bundled runtime starts and reports Temurin 21 |
| B | -cp app/*;app/lib/* LiveSolverMain --help | the application classpath loads |
| C | -cp app/*;app/lib/* FingerprintApplication | native OpenCV loading works from the image |
| D | -cp app/*;app/lib/* LiveSolverMain --list-monitors | AWT monitor enumeration plus packaged layout resolution, and no input sent |
| E | -cp app/*;app/lib/* ReferenceNormalizationPreview | the packaged manifest and all 20 crops decode and normalize through production code |
| F | runtime/bin/java.exe -XshowSettings:properties -version | java.home is the runtime inside the image |
| G-J | the same checks on a relocated copy | paths with spaces and an outside working directory |

Each test writes its exact command, working directory, forced environment, exit code and output to
target/stage9a/smoke/. The OpenCV data test may create a preview under the image target directory; the
script removes it and asserts that the packaged file count is unchanged afterwards.

## Relocation smoke

The finished image is copied to target/stage9a relocation smoke/GTA Casino Fingerprint Solver/ - spaces
in every path segment. From a working directory outside the image, the script re-runs the OpenCV health
check, the monitor listing and the reference data test with the relocated private runtime, and runs the
shipped run-app-image.cmd launcher itself, which must change into its own directory for the data to
resolve. The copy is deleted afterwards unless -KeepRelocationCopy is given, and no Maven, no repository
classpath and no system Java participate in any of it.

## Package content assertions

The build fails if the image contains repository metadata or private material: .git, .idea, .vscode,
local-data/, surefire reports, a src/ tree, *.java, *.mkv, *.mp4, live-solver.txt, run-notes.txt,
git-state.txt, Stage 7 and Stage 8 evidence files, any path containing 1080, or any evaluation-only
tree (dataset/source, fixtures/gameplay/source, fixtures/gameplay/annotations,
fixtures/gameplay/recordings). Certificate material (*.pfx, *.p12, *.pem) is refused as well, because
signing is deferred and must not creep into the image. Third-party licence and notice files inside the
bundled runtime and dependency jars are expected and are not forbidden.

## Current limitations

- No installer: no jpackage output, no MSI, no Start Menu entry, no shortcuts, no registry uninstall
  entry and no upgrade identity. That is Stage 9B.
- No release automation: no tag workflow, no GitHub Release publishing and no version bump automation.
  That is Stage 9C.
- No code signing. Windows SmartScreen may warn about an unsigned launcher; this is accepted for now.
- The image is uncompressed and about 464 MB because all-platform native artifacts are bundled.
- A packaging build needs the Temurin 21 JDK and an interactive desktop session on the build machine.
- Live input is not exercised anywhere in Stage 9A. No Stage 9A command sends keyboard input, and the
  packaged smokes are input-free by construction.

## Stage 9B handoff

target/dist/app-image is the deterministic input for the installer stage. Stage 9B is expected to run
jpackage with --runtime-image pointing at image/runtime, --input pointing at image/app and the
application entry point, then verify - not assume - that the installed layout still resolves dataset/
and fixtures/ relative to the installed application directory exactly as the Stage 9A relocation smoke
proves for the image root. Stage 9B must also keep the private-runtime policy (no system Java, no PATH
or JAVA_HOME changes), must produce a normal Windows uninstall, and must re-run input-free packaged
smoke tests against the installed application. Only after that does a signing step become meaningful,
and only then should release publication be automated.
