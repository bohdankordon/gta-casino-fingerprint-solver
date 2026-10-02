# Windows installer (Stage 9B)

Stage 9B turns the proven Stage 9A application image into a normal Windows EXE
installer with a modern operator desktop UI. The validated solver code is not
touched by any of this work: recognition, navigation, input delivery and lifecycle
behavior are exactly the Stage 8D.1 production contract (GTA V Enhanced, Borderless,
physical 2560x1440, GTA5_Enhanced.exe, SCANCODE_BATCH).

Validated product context lives in [stage8d1-live-validation.md](stage8d1-live-validation.md);
the Stage 9A packaging foundation lives in [windows-packaging.md](windows-packaging.md).
1920x1080 stays frozen experimental work (PR #20) and is never packaged.

## UI stack

The operator UI is Java Swing with core FlatLaf 3.7.2 only, in the FlatDarkLaf theme.
No other UI framework is used: no JavaFX, no Compose, no Electron/Tauri, no browser
UI, no .NET/WinUI, and no FlatLaf companion packs (no intellij-themes, no extras).
The dependency is pinned exactly as com.formdev:flatlaf:3.7.2 in pom.xml and is staged
like every other runtime dependency, so Stage 9A dependency collection picks it up with
no special casing. The project keeps a normal project JAR plus runtime dependency JARs;
there is no fat/uber JAR.

FlatLaf is installed before any Swing component is created. If it fails to initialize,
the launcher logs the reason, shows a startup failure when a display is available, and
exits; it never silently falls back to Metal or legacy Swing. Window decorations stay
native Windows (minimize, maximize, close, taskbar, focus and keyboard navigation).

## Stage 9A handoff

The installer build never reinvents packaging. scripts/build-windows-installer.ps1 first
runs the full scripts/build-windows-app-image.ps1 from the current clean commit, then
rejects stale output by requiring the Stage 9A BUILD-INFO Git SHA to equal the current
commit. The jpackage input staging below target/stage9b/jpackage-input/ is populated
solely from that fresh image: the project JAR, every staged runtime dependency JAR
(flat layout, including FlatLaf), the allowlisted dataset and fixtures trees,
config/packaged-files.txt and BUILD-INFO.txt. Nothing else is staged: no launcher cmd,
no sources, no docs, no recordings, no annotations, no dataset/source and no 1080p content.

Developer builds still resolve Maven revision 0.1.0-SNAPSHOT. Stage 9C adds optional
-ProjectVersion forwarding into the full Stage 9A build and verifies that BUILD-INFO
records the requested effective project version. The Windows package version (0.1.0
for development) remains the independent numeric -AppVersion parameter. For release
SemVer and Windows upgrade ordering, see [releases.md](releases.md).

## Private Temurin runtime

The installed application always uses its own private Eclipse Temurin 21 x64 runtime and
never any system Java: no JAVA_HOME, no PATH Java, no vendor negotiation at startup.
The runtime is the freshly built Stage 9A jlink image (java.base, java.desktop,
java.logging, java.management, jdk.unsupported), passed to jpackage as --runtime-image.
The installer build proves the reuse by comparing the runtime release files and aborts if
jpackage ever generated a different runtime. Adding FlatLaf required no new JDK module:
jdeps still reports exactly the proven conservative set, and the build fails closed on any
new module instead of silently expanding the runtime.

End users need nothing installed: no Java, no Maven, no Git, no OpenCV, no JNA, no WiX.
WiX 3.14.1 (candle.exe plus light.exe) is build-time only and is validated before any
packaging work; a missing toolchain fails with installation instructions instead of a
download attempt.

## jpackage architecture

The build generates a jpackage app-image first (jpackage --type app-image) with an explicit
--name, --app-version, --input, --main-jar, --main-class
(io.github.bohdankordon.casinofingerprint.app.WindowsOperatorMain), --runtime-image and
--java-options carrying the packaged application root. There is no --win-console flag, so
the normal launcher is a GUI launcher: Start Menu launch opens no terminal window.
No extra console launcher ships with the product.

The generated launcher config (app/<name>.cfg) is inspected, not assumed: the build proves
the main class, the application-root property and a complete classpath covering the project
JAR plus every staged dependency (FlatLaf, JavaCPP/OpenCV/OpenBLAS, JNA) before continuing.
A missing entry fails the build; working around it with a fat JAR is not allowed.

Only the validated app-image becomes the installer source (jpackage --type exe --app-image
<validated-image>), so the tested image equals the shipped image. Installer options are
--win-per-user-install, --win-menu with the menu group "GTA Casino Fingerprint Solver",
--win-upgrade-uuid with the permanent UUID below, and the same name/version/vendor.
There is intentionally no desktop shortcut, no service, no startup task and no elevation.

## Permanent upgrade identity

win-upgrade-uuid = 855C5415-0A97-4FEA-AE54-3B308FE8D1E4

The UUID is generated once and committed in packaging/windows/win-upgrade-uuid.txt. Every
future release must reuse it; the installer build reads it from that single file and a unit
test pins its single-value well-formed shape so accidental regeneration is caught.

## Runtime application root

The installed launcher passes -Dgta.casino.solver.appRoot=$APPDIR through --java-options,
where $APPDIR is the documented jpackage macro for the installed application payload
directory (the app/ directory holding the JARs, dataset/ and fixtures/). The narrow
ApplicationPaths helper uses that property as the runtime-data root, so a Start Menu launch
resolves packaged data regardless of the caller working directory. Without the property
(plain development runs) the historical user.dir behavior is preserved. The generated .cfg
is inspected to prove the property is present, and packaged smokes run from an unrelated
working directory plus a path-with-spaces relocation copy to prove caller-cwd independence.

## Operator workflow

Every launch starts DISARMED: no input can be sent before the explicit ARM flow, and
launching the application only initializes the UI, enumerates monitors and validates
packaged files. Arming requires all of the following, with actionable messages otherwise:

- a selected monitor whose physical mode is exactly 2560x1440 (one matching monitor may
  preselect; zero or several require an explicit choice, and unsupported monitors stay
  visible for diagnosis only);
- a non-blank target executable, prefilled with GTA5_Enhanced.exe and always visible;
- an explicitly selected emergency abort key (F1..F24, PAUSE, SCROLL_LOCK from the existing
  EmergencyAbortKey enum; no default; documented shortcut conflicts such as the F12 Steam
  screenshot shortcut surface immediately);
- no previously terminated session in this process.

Pressing ARM & START LIVE INPUT never arms directly: a confirmation dialog summarizes the
monitor, the physical 2560x1440 resolution, the target executable, the abort key, the
SCANCODE_BATCH delivery and the production profile, states that the abort key stops further
gameplay input immediately, and offers Cancel (the default, so Enter cannot arm by accident)
beside ARM LIVE INPUT. Only the explicit confirm action starts the live backend worker on a
dedicated thread; the event dispatch thread never runs solver work.

The armed backend is the existing LiveSolverMain/LiveSolverOptions/LiveSolveOrchestrator chain
with the production guards intact: explicit opt-in, exact foreground executable with window
and process pinning, physical abort key polling, visually verified C0 start with an empty
selected set, validated READY plan, same-frame lifecycle ownership, per-action visual
verification with bounded polls, no blind replay, no retry after partial failure, and latched
fault/abort semantics. Interval (200 ms) and stable-frame (3) tuning are not exposed in the UI.

STOP SESSION requests orderly shutdown and is not the emergency stop: the physical abort key
remains the immediate input-stop path, and the UI says so. Closing the window while armed
requests orderly shutdown, waits for worker ownership to end (STOPPING state) and never leaves
a detached worker behind. Once a session ends for any reason (STOPPED, FAULTED, ABORTED),
re-arm is locked for the process: restarting the application is the only reset boundary.

## Session logs

Every armed session automatically creates a UTF-8 log under
%LOCALAPPDATA%/BohdanKordon/GTA Casino Fingerprint Solver/logs/ (file name like
session-20251001-203045-p1234.log, UTC stamp plus process id with numeric-suffix
collision handling), with a documented user-home fallback when LOCALAPPDATA is missing.
The log captures startup, versions, java.home, the application root, OS details, the armed
configuration, lifecycle changes, plans, execution actions, verification failures, abort,
fault, normal stop and the final summary, flushed per line. The same lines feed a bounded
(newest 2000 lines) on-screen activity view; bounding the view never truncates the disk log.
The Session section shows the absolute log path once armed and offers Open Logs Folder
(the product log directory before the first session).

Session logs are user data: normal uninstall removes the application and its private runtime
but preserves historical logs. No uninstall custom action deletes them.

## Uninstall

The per-user installation registers a conventional Windows uninstall entry (Apps / Programs
and Features) that removes the installed application directory, the launcher, the private
runtime and the Start Menu shortcut without touching system Java. User session logs are
retained by policy (see above).

## Unsigned installer expectation

Code signing is intentionally deferred: there are no certificates, secrets or signing steps
anywhere in this stage, and the installer is unsigned. Windows SmartScreen may warn about
the download or launch; that is expected until a later stage adds signing.

## Resolution support

Stable production support remains physical 2560x1440 only. 1080p content stays frozen
experimental work outside this installer: it is never staged, never packaged and never
smoked here.

## Validation evidence

A clean committed build (./scripts/build-windows-installer.ps1) records everything below
the ignored target/stage9b/ directory: build.log with every command and output, one log per
packaged smoke, the generated WiX source (main.wxs), summary.txt (Git SHA and branch, Maven
and Windows versions, Temurin/jpackage/WiX/FlatLaf versions, runtime modules, upgrade UUID,
app-image and installer paths, installer byte size and SHA-256) and the installer EXE itself
under target/stage9b/installer/. Per-build hashes live there, not in this document, because
every build embeds a fresh timestamp.

Packaged smokes run with JAVA_HOME pointed at a nonexistent path and PATH reduced to the
system directories, from an unrelated working directory: private-runtime startup, java.home
inside the image, FlatLaf 3.7.2 initialization, DISARMED operator startup with zero input,
OpenCV health, the 20-crop reference manifest and normalization, the 2560x1440 gameplay
layout, monitor enumeration, the GUI launcher exit code (no console window) and a
path-with-spaces relocation copy.

The installer package itself is inspected statically without executing it: the MSI UpgradeCode
equals the permanent UUID, the Upgrade table references it, no machine-wide marker exists
(per-user package), exactly one shortcut exists below the GTA Casino Fingerprint Solver Start
Menu group (no desktop shortcut), and the install location roots at LocalAppDataFolder.
Install and uninstall validation run against the generated MSI with documented msiexec
switches; the interactive EXE wizard itself remains unexercised until Stage 9D release
qualification. The build script never launches the installer and never automates wizard clicks.
