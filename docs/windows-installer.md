# Windows installer (Stages 9B and 9B.1)

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
--win-dir-chooser, --resource-dir for the reviewed UI resources, --win-upgrade-uuid
with the permanent UUID below, and the same name/version/vendor.
There is intentionally no desktop shortcut, no service, no startup task and no elevation.

## Interactive setup (Stage 9B.1)

The package now includes this standard WiX wizard flow:

1. **Welcome** identifies GTA Casino Fingerprint Solver and its full application version.
   It explains that the private Temurin 21 Java runtime, OpenCV/native dependencies and
   Start Menu integration are included. No separate Java or OpenCV installation is required.
2. **Destination Folder** defaults to `%LOCALAPPDATA%\GTA Casino Fingerprint Solver`.
   The standard Change/Browse dialog can select another folder. The recommended default
   stays per-user; choosing a protected folder does not add custom elevation behavior.
3. **Ready to install** confirms the application version, current-user installation,
   included Java and native dependencies, Start Menu shortcut and no additional downloads.
   Install begins the real installation; Back allows review before files are installed.
4. **Progress** uses the actual `ProgressDlg` Windows Installer progress bar and action
   messages, including file installation, shortcut creation and product registration.
   There are no timers, simulated stages or dependency downloads.
5. **Completed** remains visible until Finish, with successful-installation text and a
   **Launch GTA Casino Fingerprint Solver** checkbox, checked by default for a fresh install
   (also a new-product major upgrade). Unchecking it prevents launch.

All runtime components are bundled; installation adds no network dependency. The displayed
application version comes from the verified Stage 9A project/JAR version, supplied as MSI
property `SOLVER_APPLICATION_VERSION`. It is deliberately distinct from the internal numeric
MSI `ProductVersion`; prerelease users do not see the Windows upgrade-ordering number as
their application version. No future release version is hard-coded in the resources.

This flow has been verified by package generation and static MSI table inspection. **The new
wizard has not been visually or interactively executed.** Its first interactive proof is
reserved for the public v0.9.0-beta.2 qualification after independent review and merge,
including the real beta.1 -> beta.2 upgrade. The real public beta.1 remains installed for that
upgrade; development installers and their MSIs must not be executed on that machine.

## Exact jpackage UI baseline and overrides

The UI override is based on resources generated locally by **Eclipse Temurin 21.0.6+7 x64**
(`java.runtime.version=21.0.6+7-LTS`, `jpackage --version=21.0.6`) with
**WiX 3.14.1.8722**, using `--win-per-user-install --win-dir-chooser --win-menu` and
the permanent upgrade UUID. Before authoring the override, `--temp --verbose` captured
`ui.wxf`, `main.wxs`, `overrides.wxi`, `InstallDirNotEmptyDlg.wxs`, the four localization
files and `wixhelper.dll`. Temporary directories and generated packages are ignored.

The exact stock `ui.wxf` references `WixUI_InstallDir` and `InstallDirNotEmptyDlg`, sets
`WIXUI_INSTALLDIR=INSTALLDIR`, and publishes Welcome Next -> InstallDir and InstallDir
Back -> Welcome at order 6 with `NOT Installed`. Those lines remain in the override.
The JDK-generated nonempty-folder check and standard WiX navigation are retained.

Baseline SHA-256 values (generated bytes, before customization):

| Resource | SHA-256 |
| --- | --- |
| ui.wxf | E019F67EB24B3700FF9A119ABCD6FE8E5F8E55331EA8C64B5D087C9AC8713EC3 |
| main.wxs | B7C9244D3095D4A2446EC9D0FAD6D6AE4F07F7A62D6C92C9E37CAF104C5D75EA |
| overrides.wxi | C2AD0713BF562468CA9F5B789CF401FFCD3BC934D6BC9BCDD72C4FE75940AB22 |
| InstallDirNotEmptyDlg.wxs | A4322A255C63E2BAFF829BEB20B4037CFE40304240E11319F89C6B6D6CA22A22 |
| MsiInstallerStrings_en.wxl | 31A1C07CCF997219B771D93DFDFC3FC00BAEF5B45390B54A83802A5A10B460D9 |

Only two tracked overrides live in `packaging/windows/jpackage-resources/`:

- `ui.wxf`: the generated fragment plus version/success/checkbox properties, a standard
  Finish action and `WixUI_ErrorProgressText` for actual MSI action/status text.
- `MsiInstallerStrings_en.wxl`: the exact generated English strings plus standard WiX
  welcome/ready text overrides. Localization raises those two text-control heights to 140
  dialog units, inside the existing page content areas, without replacing any dialogs.

`main.wxs`, `overrides.wxi`, launcher identifiers and MSI upgrade generation are not overridden.
The build pins the reviewed UI toolchain, copies only the two allowed resources to the ignored
`target/stage9b/jpackage-resources/`, XML-escapes and substitutes the single application-version
token, then passes that directory to jpackage. It requires verbose acknowledgments of both
custom resources, SHA-256 equality with the retained config files, and the resulting MSI UI
contract. An ignored or partially consumed override fails the build. English is the reviewed
wizard language; another generated language cannot silently pass the text checks.

The supported mechanisms are documented by the
[JDK 21 jpackage command](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)
and [WiX dialog customization](https://docs.firegiant.com/wix3/wixui/wixui_customizations/).
The exact JDK source implements the
[InstallDir fragment](https://github.com/openjdk/jdk21u/blob/jdk-21.0.6-ga/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixUiFragmentBuilder.java)
and [localization resource loading](https://github.com/openjdk/jdk21u/blob/jdk-21.0.6-ga/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WinMsiBundler.java).

## Finish-page launch safety

`SolverLaunchApplication` is an extension-free standard MSI Directory executable action:
`Directory=INSTALLDIR`, quoted executable `[INSTALLDIR]GTA Casino Fingerprint Solver.exe`,
`Execute=immediate`, `Return=asyncNoWait`, `Impersonate=yes`. Its actual MSI type is **226**
(34 + 64 + 128), with neither deferred execution nor the no-impersonation flag. The inspected
File/Component rows prove the named launcher is installed directly in `INSTALLDIR`; no generated
File ID is hard-coded. It adds no runtime requirement or new WiX extension.

The only invocation is `ExitDialog/Finish/DoAction`, order 1 before the standard Finish Return
at order 999. Standard WiX schedules ExitDialog only on successful completion (sequence -1).
The exact condition is:

```text
WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 AND UILevel = 5 AND ACTION = "INSTALL" AND NOT Installed AND NOT REMOVE AND NOT UPGRADINGPRODUCTCODE AND NOT MsiRunningElevated
```

A major upgrade uses the new ProductCode, so `NOT Installed` allows its successful completion;
the old product's upgrade removal cannot launch. Silent/reduced UI, maintenance/repair,
uninstall, administrative installs, elevated sessions, failure and cancellation cannot launch.
The standard checkbox is initially hidden and shown only when its text exists and `NOT Installed`;
normal maintenance/uninstall does not offer launch. Even an elevated interactive session is blocked
by `MsiRunningElevated`. Ordinary per-user setup runs as the interactive user, with the
stock jpackage EXE wrapper and a limited-privilege MSI. The wrapper has no `RT_MANIFEST`
resource; no elevation manifest is introduced. The launched operator starts DISARMED with zero gameplay
input; existing input-free startup and relocation smokes remain mandatory.

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
packaged smoke, the generated WiX source (main.wxs, ui.wxf, localization and stock helper
resources), msi-ui-tables.json, summary.txt (Git SHA and branch, Maven
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
The EXE is statically checked for NotSigned and no requested elevation: the pinned stock wrapper
has no `RT_MANIFEST` resource (if present, an execution-level manifest must be asInvoker).
PE resources are loaded strictly as data without executing the EXE. MSI summary flags must
declare no elevation requirement. `scripts/test-windows-installer-ui.ps1` opens the actual
MSI database read-only and records named-column rows from Dialog, Control, ControlEvent,
ControlCondition, CheckBox, EventMapping, Property, CustomAction, ActionText, File/Component
and install/admin sequence tables. It checks the exact navigation, INSTALLDIR targeting,
real progress subscriptions, success-only ExitDialog, checkbox visibility/default, precise
user-context launch action and guarded Finish invocation. Launch cannot appear in any UI or
execute sequence. Sixteen automated negative tests independently corrupt these actual rows
in memory and require rejection (including missing UI, fake progress, wrong version/location,
unsafe launch conditions, execute-sequence launch, maintenance launch and elevation).
No MSI is modified or executed by those tests. Missing tables fail closed.

Neither development install/uninstall nor visual wizard clicks are part of this build.
Actual interactive qualification and the beta.1 -> beta.2 upgrade remain pending Stage 9D;
cloud PR artifacts cannot visually prove the wizard. Never press ARM during qualification.
