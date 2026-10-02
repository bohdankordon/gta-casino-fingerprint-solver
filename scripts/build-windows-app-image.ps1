<#
.SYNOPSIS
    Builds the Stage 9A deterministic, self-contained Windows application image.

.DESCRIPTION
    Produces target/dist/app-image for the validated stable live solver
    (GTA V Enhanced, Borderless, physical 2560x1440):

        app-image/
            app/<project jar>            app/lib/<pinned runtime dependency jars>
            runtime/                     application-private Eclipse Temurin 21 runtime
            dataset/, fixtures/          only the runtime data the production solver reads
            config/packaged-files.txt    packaging metadata (path, size, SHA-256)
            BUILD-INFO.txt
            run-app-image.cmd

    The script refuses to run anywhere except Windows x64, refuses a packaging JDK that
    is not Eclipse Temurin 21, refuses a worktree that is not completely clean (so the Git
    SHA recorded in BUILD-INFO.txt always identifies the exact committed sources and
    runtime data that went into the image), builds the runtime with jlink, asserts that the image
    contains no repository metadata, private evidence, recordings, test material or
    experimental 1080p content, and then proves with the bundled runtime alone (PATH
    without any java.exe, JAVA_HOME pointing nowhere) that the image starts, loads
    OpenCV, lists monitors without sending input, resolves the packaged reference data,
    and survives relocation to a path containing spaces.

    Every generated artifact lives below the ignored target/ directory, so running this
    script must not modify a tracked file. It never installs Java and never changes
    PATH, JAVA_HOME or any system-wide Java registration.

.NOTES
    Stage 9A builds an application image only: jpackage, an EXE installer, MSI, Start
    Menu entries, registry uninstall entries, GitHub Releases automation and code
    signing are explicitly out of scope. See docs/windows-packaging.md.

.EXAMPLE
    .\scripts\build-windows-app-image.ps1

.EXAMPLE
    .\scripts\build-windows-app-image.ps1 -PackagingJdk 'C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot'
#>
[CmdletBinding()]
param(
    [string]$PackagingJdk = '',
    [string]$ProjectVersion = '',
    [switch]$KeepRelocationCopy
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# The Stage 9A contract. Anything changed here must be reflected in
# docs/windows-packaging.md.
# ---------------------------------------------------------------------------
$ProductName = 'GTA Casino Fingerprint Solver'
$StableProfile = 'GTA V Enhanced, Borderless, physical 2560x1440'
$LiveSolverMain = 'io.github.bohdankordon.casinofingerprint.app.LiveSolverMain'
$HealthCheckMain = 'io.github.bohdankordon.casinofingerprint.app.FingerprintApplication'
$PreviewMain = 'io.github.bohdankordon.casinofingerprint.gameplay.ReferenceNormalizationPreview'
$RequiredJdkMajor = 21
$AcceptedVendorPattern = 'Adoptium|Temurin'
$MinimumTests = 915
$RuntimeModules = @('java.base', 'java.desktop', 'java.logging', 'java.management', 'jdk.unsupported')
$RuntimeDataFiles = @(
    'dataset/layout/reference-layout.csv',
    'fixtures/gameplay/layout/representative-2560x1440.csv'
)
$RuntimeDataTrees = @('dataset/reference')
$ForbiddenPathSegments = @('.git', '.idea', '.vscode', 'local-data', 'surefire-reports')
$ForbiddenFileNames = @('live-solver.txt', 'run-notes.txt', 'git-state.txt')
$ForbiddenExtensions = @('.mkv', '.mp4', '.java', '.pfx', '.p12', '.pem')
$ForbiddenTrees = @(
    'dataset/source',
    'fixtures/gameplay/source',
    'fixtures/gameplay/annotations',
    'fixtures/gameplay/recordings'
)

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
$RepoRoot = Split-Path -Parent $ScriptDir
$TargetDir = Join-Path $RepoRoot 'target'
$ImageRoot = Join-Path $TargetDir 'dist/app-image'
$AppDir = Join-Path $ImageRoot 'app'
$AppLibDir = Join-Path $AppDir 'lib'
$RuntimeDir = Join-Path $ImageRoot 'runtime'
$ConfigDir = Join-Path $ImageRoot 'config'
$DiagnosticsDir = Join-Path $TargetDir 'stage9a'
$SmokeDir = Join-Path $DiagnosticsDir 'smoke'
$RelocationRoot = Join-Path $TargetDir 'stage9a relocation smoke'
$RelocationImage = Join-Path $RelocationRoot 'GTA Casino Fingerprint Solver'
$LauncherSource = Join-Path $RepoRoot 'packaging/app-image/run-app-image.cmd'
$LauncherName = 'run-app-image.cmd'
$Lf = [string][char]10
$Cr = [string][char]13
$Slash = [char]47
$Backslash = [char]92

# ---------------------------------------------------------------------------
# Diagnostics log (kept in memory so that "mvn clean" can delete target/ safely)
# ---------------------------------------------------------------------------
$script:LogLines = New-Object 'System.Collections.Generic.List[string]'
$script:StepNumber = 0

function Add-Log([string]$Line) {
    $script:LogLines.Add($Line)
}

function Write-Step([string]$Title) {
    $script:StepNumber = $script:StepNumber + 1
    Write-Host ''
    Write-Host ('--- Step {0}: {1}' -f $script:StepNumber, $Title) -ForegroundColor Cyan
    Add-Log ''
    Add-Log ('--- Step {0}: {1}' -f $script:StepNumber, $Title)
}

function Write-Note([string]$Message) {
    Write-Host ('    ' + $Message)
    Add-Log ('    ' + $Message)
}

function Fail([string]$Message) {
    throw ('STAGE9A: ' + $Message)
}

function Split-Lines([string]$Text) {
    $normalized = $Text.Replace($Cr, $Lf)
    return @($normalized -split $Lf)
}

function Get-RelativeImagePath([string]$FullPath) {
    $rootFull = [System.IO.Path]::GetFullPath($ImageRoot).TrimEnd($Slash, $Backslash)
    $fileFull = [System.IO.Path]::GetFullPath($FullPath)
    if (-not $fileFull.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) {
        Fail ('path is outside the application image: ' + $FullPath)
    }
    return $fileFull.Substring($rootFull.Length).TrimStart($Slash, $Backslash).Replace($Backslash, $Slash)
}

function Remove-WithinTarget([string]$Path) {
    $targetFull = [System.IO.Path]::GetFullPath($TargetDir).TrimEnd($Slash, $Backslash)
    $full = [System.IO.Path]::GetFullPath($Path)
    $prefix = $targetFull + [string]$Backslash
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Fail ('refusing to delete a path outside the repository target directory: ' + $full)
    }
    if (Test-Path -LiteralPath $full) {
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}

function Invoke-Tool {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$WorkingDirectory = $RepoRoot,
        [switch]$AllowFailure
    )
    Add-Log ('> ' + $FilePath + ' ' + ($Arguments -join ' '))
    Push-Location -Path $WorkingDirectory
    $output = ''
    $code = -1
    try {
        $output = (& $FilePath @Arguments 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($output) { Add-Log $output }
    Add-Log ('exit code: ' + $code)
    if ($code -ne 0 -and -not $AllowFailure) {
        Fail ($Label + ' exited with code ' + $code + '; see the build log for the full output')
    }
    return [pscustomobject]@{ Output = $output; ExitCode = $code }
}

function Invoke-WithScrubbedJavaEnvironment {
    param([Parameter(Mandatory = $true)][scriptblock]$Body)
    $savedJavaHome = $env:JAVA_HOME
    $savedPath = $env:PATH
    try {
        $env:JAVA_HOME = (Join-Path $DiagnosticsDir 'no-such-java-home')
        $env:PATH = ((Join-Path $env:SystemRoot 'System32') + ';' + $env:SystemRoot)
        & $Body
    } finally {
        if ($null -eq $savedJavaHome) {
            Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
        } else {
            $env:JAVA_HOME = $savedJavaHome
        }
        $env:PATH = $savedPath
    }
}

function Get-JavaSetting([string]$Output, [string]$Name) {
    foreach ($line in (Split-Lines $Output)) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith($Name + ' = ')) {
            return $trimmed.Substring($Name.Length + 3).Trim()
        }
    }
    return ''
}

function Invoke-SmokeTest {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [string[]]$Expect = @()
    )
    $logFile = Join-Path $SmokeDir ($Name + '.log')
    $entry = New-Object 'System.Collections.Generic.List[string]'
    $entry.Add('# Stage 9A packaged smoke test: ' + $Name)
    $entry.Add('command: ' + $FilePath + ' ' + ($Arguments -join ' '))
    $entry.Add('working directory: ' + $WorkingDirectory)
    $entry.Add('JAVA_HOME forced to: ' + $env:JAVA_HOME)
    $entry.Add('PATH forced to: ' + $env:PATH)
    Push-Location -Path $WorkingDirectory
    $output = ''
    $code = -1
    try {
        $output = (& $FilePath @Arguments 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $entry.Add('exit code: ' + $code)
    $entry.Add('output:')
    $entry.Add($output)
    [System.IO.File]::WriteAllLines($logFile, $entry)
    Add-Log ('smoke ' + $Name + ': exit=' + $code + ' log=' + $logFile)
    foreach ($marker in $Expect) {
        if ($output -notlike ('*' + $marker + '*')) {
            Fail ('smoke test ' + $Name + ' did not print the expected marker "' + $marker + '"; see ' + $logFile)
        }
    }
    if ($code -ne 0) {
        Fail ('smoke test ' + $Name + ' exited with code ' + $code + '; see ' + $logFile)
    }
    Write-Note ('smoke ' + $Name + ': OK (exit 0)')
    return $output
}

function Write-Diagnostics {
    if (-not (Test-Path -LiteralPath $DiagnosticsDir)) {
        New-Item -ItemType Directory -Path $DiagnosticsDir -Force | Out-Null
    }
    [System.IO.File]::WriteAllLines((Join-Path $DiagnosticsDir 'build.log'), $script:LogLines)
}

# Any terminating error writes the diagnostics log before the script stops, so a failed
# build still leaves the full commands and output on disk.
trap {
    Add-Log ('FAILED: ' + $_)
    Write-Diagnostics
    Write-Host ''
    Write-Host ('STAGE9A: build failed: ' + $_) -ForegroundColor Red
    Write-Host ('Full log: ' + (Join-Path $DiagnosticsDir 'build.log')) -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# Step 1: Windows x64
# ---------------------------------------------------------------------------
Write-Step 'Verify Windows x64'
Add-Log ('PowerShell: ' + $PSVersionTable.PSVersion.ToString())
if (-not ($env:OS -eq 'Windows_NT' -and [System.Environment]::OSVersion.Platform -eq 'Win32NT')) {
    Fail 'Stage 9A packaging requires Windows.'
}
$architecture = $env:PROCESSOR_ARCHITECTURE
try {
    $architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
} catch {
    Add-Log 'OSArchitecture unavailable; falling back to PROCESSOR_ARCHITECTURE'
}
if ($architecture -notin @('X64', 'AMD64')) {
    Fail ('Stage 9A packaging requires 64-bit Windows (x64), found "' + $architecture + '".')
}
Write-Note ('Windows x64 confirmed (' + $architecture + ').')

# ---------------------------------------------------------------------------
# Step 2: repository and worktree suitability
# ---------------------------------------------------------------------------
Write-Step 'Verify the repository worktree'
$insideWorkTree = (Invoke-Tool -Label 'git rev-parse --is-inside-work-tree' -FilePath 'git' -Arguments @('-C', $RepoRoot, 'rev-parse', '--is-inside-work-tree')).Output.Trim()
if ($insideWorkTree -ne 'true') {
    Fail ($RepoRoot + ' is not inside a git work tree; run this script from a repository checkout.')
}
$gitSha = (Invoke-Tool -Label 'git rev-parse HEAD' -FilePath 'git' -Arguments @('-C', $RepoRoot, 'rev-parse', 'HEAD')).Output.Trim()
$gitBranch = (Invoke-Tool -Label 'git rev-parse --abbrev-ref HEAD' -FilePath 'git' -Arguments @('-C', $RepoRoot, 'rev-parse', '--abbrev-ref', 'HEAD')).Output.Trim()
$gitStatusBefore = (Invoke-Tool -Label 'git status --porcelain' -FilePath 'git' -Arguments @('-C', $RepoRoot, 'status', '--porcelain')).Output.Trim()
Write-Note ('git SHA: ' + $gitSha)
Write-Note ('git branch: ' + $gitBranch)
if ($gitStatusBefore) {
    Write-Host '    dirty worktree entries reported by git status --porcelain:' -ForegroundColor Yellow
    Add-Log 'dirty worktree entries reported by git status --porcelain:'
    foreach ($entry in (Split-Lines $gitStatusBefore)) {
        if ($entry.Trim()) {
            Write-Host ('      ' + $entry) -ForegroundColor Yellow
            Add-Log ('      ' + $entry)
        }
    }
    Fail ('packaging requires a completely clean Git worktree, but git status --porcelain reports the entries above. The build compiles and copies files straight from the working tree while BUILD-INFO.txt records only the committed Git SHA ' + $gitSha + ', so uncommitted sources or runtime data would silently make the packaged image differ from that commit. Commit the changes, or remove the untracked files, and run the build again. This script never restores, resets, cleans or stashes your work.')
}
Write-Note 'worktree is clean: the recorded Git SHA identifies every packaged source and data file'
$requiredInputs = @('pom.xml', 'mvnw.cmd', 'src/main/java', 'packaging/app-image/run-app-image.cmd') + $RuntimeDataFiles + $RuntimeDataTrees
foreach ($relative in $requiredInputs) {
    $candidate = Join-Path $RepoRoot $relative
    if (-not (Test-Path -LiteralPath $candidate)) {
        Fail ('required build input is missing: ' + $relative)
    }
}
$referenceManifest = Join-Path $RepoRoot 'dataset/layout/reference-layout.csv'
$manifestLines = Split-Lines ([System.IO.File]::ReadAllText($referenceManifest))
$expectedCrops = New-Object 'System.Collections.Generic.List[string]'
foreach ($line in $manifestLines) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('fingerprint_id,')) { continue }
    $fields = @($trimmed -split ',')
    $expectedCrops.Add($fields[$fields.Count - 1].Trim())
}
if ($expectedCrops.Count -ne 20) {
    Fail ('the reference manifest must describe exactly 20 crops, found ' + $expectedCrops.Count)
}
foreach ($crop in $expectedCrops) {
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $crop))) {
        Fail ('the reference manifest points at a missing asset: ' + $crop)
    }
}
Write-Note ('reference data contract checked: ' + $expectedCrops.Count + ' canonical crops')

# ---------------------------------------------------------------------------
# Steps 3-5: canonical packaging JDK (Eclipse Temurin 21 x64)
# ---------------------------------------------------------------------------
Write-Step 'Validate the packaging JDK (Eclipse Temurin 21)'
$jdkRoot = $PackagingJdk
$jdkSource = '-PackagingJdk'
if (-not $jdkRoot) {
    $jdkRoot = $env:JAVA_HOME
    $jdkSource = 'JAVA_HOME'
}
if (-not $jdkRoot) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $jdkRoot = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
        $jdkSource = 'java.exe on PATH'
    }
}
if (-not $jdkRoot) {
    Fail ('no packaging JDK found. Install Eclipse Temurin 21 (x64) separately, for example with "winget install --id EclipseAdoptium.Temurin.21.JDK --exact", or download it from https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64, then set JAVA_HOME or pass -PackagingJdk <path>. This script never installs or registers Java.')
}
if (-not (Test-Path -LiteralPath $jdkRoot)) {
    Fail ('the packaging JDK path from ' + $jdkSource + ' does not exist: ' + $jdkRoot)
}
if ((Split-Path -Leaf $jdkRoot) -eq 'bin') {
    $jdkRoot = Split-Path -Parent $jdkRoot
    Write-Note ('normalized a .../bin JAVA_HOME to its parent: ' + $jdkRoot)
}
$jdkBin = Join-Path $jdkRoot 'bin'
$missingTools = New-Object 'System.Collections.Generic.List[string]'
foreach ($tool in @('java.exe', 'javac.exe', 'jar.exe', 'jlink.exe', 'jdeps.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $jdkBin $tool))) { $missingTools.Add($tool) }
}
if ($missingTools.Count -gt 0) {
    Fail ('the packaging JDK is missing required tools: ' + ($missingTools -join ', ') + '. Stage 9A needs a full JDK 21 (java, javac, jar, jlink, jdeps) from Eclipse Temurin.')
}
$javaExe = Join-Path $jdkBin 'java.exe'
$settingsOutput = (Invoke-Tool -Label 'java -XshowSettings:properties -version' -FilePath $javaExe -Arguments @('-XshowSettings:properties', '-version')).Output
$javaVersion = Get-JavaSetting $settingsOutput 'java.version'
$javaVendor = Get-JavaSetting $settingsOutput 'java.vendor'
$javaVendorVersion = Get-JavaSetting $settingsOutput 'java.vendor.version'
$javaRuntimeVersion = Get-JavaSetting $settingsOutput 'java.runtime.version'
$javaVmName = Get-JavaSetting $settingsOutput 'java.vm.name'
$javaHome = Get-JavaSetting $settingsOutput 'java.home'
if (-not $javaVersion) {
    Fail ('could not read java.version from ' + $javaExe)
}
if (-not $javaVersion.StartsWith($RequiredJdkMajor.ToString() + '.')) {
    Fail ('Stage 9A requires a Java ' + $RequiredJdkMajor + ' packaging JDK, but ' + $javaExe + ' reports java.version=' + $javaVersion + '. Install Eclipse Temurin ' + $RequiredJdkMajor + ' (x64) and point JAVA_HOME or -PackagingJdk at it.')
}
if (-not (($javaVendor + ' ' + $javaVendorVersion) -match $AcceptedVendorPattern)) {
    Fail ('Stage 9A requires Eclipse Temurin as the canonical packaging JDK, but ' + $javaExe + ' reports java.vendor="' + $javaVendor + '" java.vendor.version="' + $javaVendorVersion + '". Install Eclipse Temurin ' + $RequiredJdkMajor + ' (x64) with "winget install --id EclipseAdoptium.Temurin.21.JDK --exact" or from https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64, then run this script with JAVA_HOME or -PackagingJdk pointing at that JDK. Arbitrary installed Java vendors are deliberately refused so the bundled runtime is the canonical build.')
}
Write-Note ('packaging JDK: ' + $javaVendor + ' ' + $javaVendorVersion)
Write-Note ('java.runtime.version: ' + $javaRuntimeVersion)
Write-Note ('java.vm.name: ' + $javaVmName)
Write-Note ('java.home: ' + $javaHome)
Write-Note ('JDK resolved from: ' + $jdkSource)

# ---------------------------------------------------------------------------
# Step 6: Maven build (project jar + tests + staged runtime dependencies)
# ---------------------------------------------------------------------------
Write-Step 'Build and test with the Maven wrapper'
$pomXml = [xml](Get-Content -LiteralPath (Join-Path $RepoRoot 'pom.xml') -Raw)
# PowerShell variables are case-insensitive: preserve the parameter as the effective version.
if ($ProjectVersion -ceq '') {
    $ProjectVersion = [string]$pomXml.project.properties.revision
}
if ($ProjectVersion -cnotmatch '\A[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?\z') {
    Fail '-ProjectVersion must be a nonblank safe Maven version (for example 0.9.0-beta.1).'
}
if ([string]$pomXml.project.version -cne '${revision}') {
    Fail 'pom.xml must use the CI-friendly ${revision} project version.'
}
Write-Note ('project version: ' + $ProjectVersion)
$mavenArguments = @('-B', '-ntp', ('-Drevision=' + $ProjectVersion), 'clean', 'verify', 'package')
$mavenResult = Invoke-Tool -Label 'Maven clean verify package' -FilePath (Join-Path $RepoRoot 'mvnw.cmd') -Arguments $mavenArguments
$testSummary = Split-Lines $mavenResult.Output | Where-Object { $_ -like '*Tests run:*' -and $_ -notlike '* -- in *' } | Select-Object -Last 1
if (-not $testSummary) {
    Fail 'the Maven output did not contain a test summary; refusing to package an unverified build'
}
$trimmedSummary = $testSummary.Trim()
$testsMatch = $trimmedSummary -match 'Tests run: ([0-9]+)'
$testsRun = 0
if ($testsMatch) { $testsRun = [int]$Matches[1] }
if ($trimmedSummary -notlike '*Failures: 0*' -or $trimmedSummary -notlike '*Errors: 0*' -or $trimmedSummary -notlike '*Skipped: 0*') {
    Fail ('the test summary reports failures or errors: ' + $trimmedSummary)
}
if ($testsRun -lt $MinimumTests) {
    Fail ('the test baseline regressed: expected at least ' + $MinimumTests + ' tests, found ' + $testsRun + ' (' + $trimmedSummary + ')')
}
Write-Note ('tests: ' + $trimmedSummary)

# ---------------------------------------------------------------------------
# Step 7: staged runtime dependencies
# ---------------------------------------------------------------------------
Write-Step 'Collect the staged runtime dependencies'
if (-not (Test-Path -LiteralPath $AppLibDir)) {
    Fail ('Maven did not stage the runtime dependencies into ' + $AppLibDir + '; check the maven-dependency-plugin execution in pom.xml')
}
$libJars = @(Get-ChildItem -LiteralPath $AppLibDir -File -Filter '*.jar')
if ($libJars.Count -eq 0) {
    Fail ('no runtime dependency jars were staged into ' + $AppLibDir)
}
foreach ($pattern in @('opencv-*-windows-x86_64.jar', 'javacpp-*.jar', 'jna-5.17.0.jar', 'jna-platform-5.17.0.jar')) {
    if (-not ($libJars | Where-Object { $_.Name -like $pattern })) {
        Fail ('expected runtime dependency missing from ' + $AppLibDir + ': ' + $pattern)
    }
}
$libBytes = ($libJars | Measure-Object -Property Length -Sum).Sum
Write-Note ('runtime dependency jars: ' + $libJars.Count + ' (' + [math]::Round($libBytes / 1MB, 1) + ' MB, all platforms as pinned by opencv-platform)')
$expectedJarName = 'gta-casino-fingerprint-solver-' + $projectVersion + '.jar'
$appJarPath = Join-Path $TargetDir $expectedJarName
if (-not (Test-Path -LiteralPath $appJarPath)) {
    Fail ('the project jar was not built: ' + $appJarPath)
}

# ---------------------------------------------------------------------------
# Step 8: conservative application-private runtime
# ---------------------------------------------------------------------------
Write-Step 'Build the application-private runtime with jlink'
$classpath = (($libJars | ForEach-Object { $_.FullName }) -join ';')
$jdepsResult = Invoke-Tool -Label 'jdeps analysis' -FilePath (Join-Path $jdkBin 'jdeps.exe') -Arguments @('--multi-release', '21', '--ignore-missing-deps', '--print-module-deps', '--class-path', $classpath, $appJarPath)
$moduleLine = Split-Lines $jdepsResult.Output | Where-Object { $_ -like '*java.base*' } | Select-Object -Last 1
if (-not $moduleLine) {
    Fail 'jdeps did not report a module list; refusing to guess the runtime module set'
}
$suggestedModules = @($moduleLine.Trim() -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$unexpectedModules = @($suggestedModules | Where-Object { $RuntimeModules -notcontains $_ })
if ($unexpectedModules.Count -gt 0) {
    Fail ('jdeps reports modules that the conservative Stage 9A module list does not contain: ' + ($unexpectedModules -join ', ') + '. Re-validate the runtime before adding them.')
}
Write-Note ('jdeps suggests: ' + ($suggestedModules -join ','))
Write-Note ('jlink modules: ' + ($RuntimeModules -join ','))
if (Test-Path -LiteralPath $RuntimeDir) { Remove-WithinTarget $RuntimeDir }
$jlinkResult = Invoke-Tool -Label 'jlink' -FilePath (Join-Path $jdkBin 'jlink.exe') -Arguments @('--add-modules', ($RuntimeModules -join ','), '--output', $RuntimeDir, '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=zip-6')
$bundledJava = Join-Path $RuntimeDir 'bin/java.exe'
if (-not (Test-Path -LiteralPath $bundledJava)) {
    Fail ('jlink did not produce a usable runtime: ' + $bundledJava + ' is missing')
}
$runtimeFiles = @(Get-ChildItem -LiteralPath $RuntimeDir -Recurse -File)
$runtimeBytes = ($runtimeFiles | Measure-Object -Property Length -Sum).Sum
Write-Note ('bundled runtime: ' + $runtimeFiles.Count + ' files, ' + [math]::Round($runtimeBytes / 1MB, 1) + ' MB')

# ---------------------------------------------------------------------------
# Step 9: assemble the application image
# ---------------------------------------------------------------------------
Write-Step 'Assemble the application image'
if (-not (Test-Path -LiteralPath $AppDir)) {
    New-Item -ItemType Directory -Path $AppDir -Force | Out-Null
}
Copy-Item -LiteralPath $appJarPath -Destination (Join-Path $AppDir $expectedJarName) -Force
foreach ($relative in $RuntimeDataFiles) {
    $source = Join-Path $RepoRoot $relative
    $destination = Join-Path $ImageRoot $relative
    $parent = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -LiteralPath $source -Destination $destination -Force
}
foreach ($relativeTree in $RuntimeDataTrees) {
    $source = Join-Path $RepoRoot $relativeTree
    $destination = Join-Path $ImageRoot $relativeTree
    $parent = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
}
foreach ($crop in $expectedCrops) {
    if (-not (Test-Path -LiteralPath (Join-Path $ImageRoot $crop))) {
        Fail ('the packaged reference asset is missing: ' + $crop)
    }
}
$packagedCrops = @(Get-ChildItem -LiteralPath (Join-Path $ImageRoot 'dataset/reference') -Recurse -File -Filter '*.png')
if ($packagedCrops.Count -ne $expectedCrops.Count) {
    Fail ('the image holds ' + $packagedCrops.Count + ' reference crops but the manifest describes ' + $expectedCrops.Count + '; only manifest assets may be packaged')
}
if (Test-Path -LiteralPath (Join-Path $ImageRoot 'dataset/source')) {
    Fail 'dataset/source must never be packaged: the production runtime does not read it'
}
$launcherText = [System.IO.File]::ReadAllText($LauncherSource)
$launcherText = $launcherText.Replace($Cr + $Lf, $Lf).Replace($Lf, $Cr + $Lf)
[System.IO.File]::WriteAllText((Join-Path $ImageRoot $LauncherName), $launcherText, (New-Object System.Text.ASCIIEncoding))
Write-Note ('packaged data: ' + ($RuntimeDataFiles -join ', ') + ', ' + ($RuntimeDataTrees -join ', '))
Write-Note ('launcher: ' + $LauncherName + ' (line endings normalized to CRLF for cmd.exe)')

# ---------------------------------------------------------------------------
# Step 10: BUILD-INFO.txt and packaging metadata
# ---------------------------------------------------------------------------
Write-Step 'Write BUILD-INFO.txt and packaging metadata'
$buildTimestamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
# The clean-worktree gate above guarantees this state: a dirty worktree never reaches this point.
$worktreeState = 'clean'
$buildInfo = New-Object 'System.Collections.Generic.List[string]'
$buildInfo.Add('Product:         ' + $ProductName)
$buildInfo.Add('Project version: ' + $projectVersion)
$buildInfo.Add('Git SHA:         ' + $gitSha)
$buildInfo.Add('Git branch:      ' + $gitBranch)
$buildInfo.Add('Git worktree:    ' + $worktreeState)
$buildInfo.Add('Build timestamp: ' + $buildTimestamp)
$buildInfo.Add('Build host:      Windows x64')
$buildInfo.Add('Packaging JDK:   Eclipse Temurin ' + $RequiredJdkMajor)
$buildInfo.Add('                 java.vendor          = ' + $javaVendor)
$buildInfo.Add('                 java.vendor.version  = ' + $javaVendorVersion)
$buildInfo.Add('                 java.runtime.version = ' + $javaRuntimeVersion)
$buildInfo.Add('                 java.vm.name         = ' + $javaVmName)
$buildInfo.Add('Bundled runtime: private application runtime built with jlink (no system Java is used or required)')
$buildInfo.Add('                 modules = ' + ($RuntimeModules -join ','))
$buildInfo.Add('Stable profile:  ' + $StableProfile)
$buildInfo.Add('Input delivery:  SCANCODE_BATCH, strictly opt-in (this image sends no input by default)')
$buildInfo.Add('Packaged data:   ' + ($RuntimeDataFiles -join ', '))
$buildInfo.Add('                 ' + ($RuntimeDataTrees -join ', ') + ' (the 20 canonical crops named by the manifest)')
$buildInfo.Add('Distribution:    self-contained application image only; no installer is built in Stage 9A')
$buildInfo.Add('Code signing:    NOT SIGNED - code signing is deferred (no Authenticode material is present)')
[System.IO.File]::WriteAllLines((Join-Path $ImageRoot 'BUILD-INFO.txt'), $buildInfo)
$imageJars = @(Get-ChildItem -LiteralPath $AppDir -File -Filter '*.jar')
if ($imageJars.Count -ne 1 -or $imageJars[0].Name -ne $expectedJarName) {
    Fail ('the application directory must hold exactly the project jar ' + $expectedJarName)
}
if (-not (Test-Path -LiteralPath $ConfigDir)) {
    New-Item -ItemType Directory -Path $ConfigDir -Force | Out-Null
}
$packagedFiles = @(Get-ChildItem -LiteralPath $ImageRoot -Recurse -File -Force | Sort-Object -Property FullName)
$manifestOut = New-Object 'System.Collections.Generic.List[string]'
$manifestOut.Add('# ' + $ProductName + ' ' + $projectVersion + ' - packaged file manifest')
$manifestOut.Add('# git SHA ' + $gitSha + ', built ' + $buildTimestamp)
$manifestOut.Add('# sha256                                                           bytes  path')
foreach ($file in $packagedFiles) {
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifestOut.Add(($hash + '  ' + $file.Length + '  ' + (Get-RelativeImagePath $file.FullName)))
}
[System.IO.File]::WriteAllLines((Join-Path $ConfigDir 'packaged-files.txt'), $manifestOut)
$imageFiles = @(Get-ChildItem -LiteralPath $ImageRoot -Recurse -File -Force)
$imageBytes = ($imageFiles | Measure-Object -Property Length -Sum).Sum
Write-Note ('BUILD-INFO.txt written; config/packaged-files.txt lists ' + $packagedFiles.Count + ' files')

# ---------------------------------------------------------------------------
# Step 11: package content assertions
# ---------------------------------------------------------------------------
Write-Step 'Assert the application image contains no forbidden content'
foreach ($file in $imageFiles) {
    $relative = Get-RelativeImagePath $file.FullName
    $segments = @($relative -split '/')
    foreach ($segment in $segments) {
        if ($ForbiddenPathSegments -contains $segment) {
            Fail ('forbidden content in the application image: ' + $relative)
        }
    }
    if ($segments[0] -eq 'src') {
        Fail ('Java sources must never be packaged: ' + $relative)
    }
    if ($ForbiddenFileNames -contains $file.Name) {
        Fail ('forbidden content in the application image: ' + $relative)
    }
    foreach ($extension in $ForbiddenExtensions) {
        if ($file.Name.EndsWith($extension)) {
            Fail ('forbidden content in the application image: ' + $relative)
        }
    }
    if ($relative -like '*1080*') {
        Fail ('the experimental 1080p content of PR #20 must never be packaged: ' + $relative)
    }
    if ($file.Name -like 'stage8*' -or $file.Name -like 'stage7*') {
        Fail ('private stage evidence must never be packaged: ' + $relative)
    }
    foreach ($tree in $ForbiddenTrees) {
        if ($relative.StartsWith($tree + '/')) {
            Fail ('evaluation-only material must never be packaged: ' + $relative)
        }
    }
}
Write-Note ('scanned ' + $imageFiles.Count + ' files: no repository metadata, private evidence, recordings, test material or 1080p content')

# ---------------------------------------------------------------------------
# Step 12: packaged input-free smoke tests
# ---------------------------------------------------------------------------
Write-Step 'Run packaged input-free smoke tests with the bundled runtime'
if (Test-Path -LiteralPath $DiagnosticsDir) { Remove-WithinTarget $DiagnosticsDir }
New-Item -ItemType Directory -Path $SmokeDir -Force | Out-Null
$imageFileCountBeforeSmoke = $imageFiles.Count
Invoke-WithScrubbedJavaEnvironment {
    Write-Note ('JAVA_HOME forced to ' + $env:JAVA_HOME + ' and PATH to System32 only: the smokes cannot reach a system Java')
    $whereJava = Get-Command java.exe -ErrorAction SilentlyContinue
    $whereJavaText = 'not found'
    if ($whereJava) { $whereJavaText = $whereJava.Source }
    Write-Note ('java.exe on the scrubbed PATH: ' + $whereJavaText)
    Add-Log ('java.exe on the scrubbed PATH: ' + $whereJavaText)
    Invoke-SmokeTest -Name 'A-bundled-java-version' -FilePath $bundledJava -Arguments @('-version') -WorkingDirectory $ImageRoot -Expect @('Temurin') | Out-Null
    $probeSettings = Invoke-SmokeTest -Name 'F-bundled-java-home' -FilePath $bundledJava -Arguments @('-XshowSettings:properties', '-version') -WorkingDirectory $ImageRoot
    $bundledHome = Get-JavaSetting $probeSettings 'java.home'
    $imageRootFull = [System.IO.Path]::GetFullPath($ImageRoot).TrimEnd($Slash, $Backslash)
    if (-not $bundledHome.StartsWith($imageRootFull, [StringComparison]::OrdinalIgnoreCase)) {
        Fail ('the smoke test used a Java runtime outside the application image: java.home=' + $bundledHome)
    }
    Write-Note ('bundled java.home: ' + $bundledHome)
    Invoke-SmokeTest -Name 'B-application-classpath' -FilePath $bundledJava -Arguments @('-cp', 'app/*;app/lib/*', $LiveSolverMain, '--help') -WorkingDirectory $ImageRoot -Expect @('Guarded live casino fingerprint solver') | Out-Null
    Invoke-SmokeTest -Name 'C-opencv-health-check' -FilePath $bundledJava -Arguments @('-cp', 'app/*;app/lib/*', $HealthCheckMain) -WorkingDirectory $ImageRoot -Expect @('OpenCV native health check passed.') | Out-Null
    Invoke-SmokeTest -Name 'D-monitor-listing' -FilePath $bundledJava -Arguments @('-cp', 'app/*;app/lib/*', $LiveSolverMain, '--list-monitors') -WorkingDirectory $ImageRoot -Expect @('LIVE: monitors (', 'LIVE: no input sent.') | Out-Null
    Invoke-SmokeTest -Name 'E-packaged-reference-data' -FilePath $bundledJava -Arguments @('-cp', 'app/*;app/lib/*', $PreviewMain) -WorkingDirectory $ImageRoot -Expect @('Preview written to:') | Out-Null
}
$smokeOutputDir = Join-Path $ImageRoot 'target'
if (Test-Path -LiteralPath $smokeOutputDir) { Remove-WithinTarget $smokeOutputDir }
$imageFilesAfterSmoke = @(Get-ChildItem -LiteralPath $ImageRoot -Recurse -File -Force)
if ($imageFilesAfterSmoke.Count -ne $imageFileCountBeforeSmoke) {
    Fail ('the smoke tests left ' + ($imageFilesAfterSmoke.Count - $imageFileCountBeforeSmoke) + ' extra files in the image')
}

# ---------------------------------------------------------------------------
# Step 13: relocation smoke (path with spaces, caller outside the image)
# ---------------------------------------------------------------------------
Write-Step 'Run the relocation smoke in a path containing spaces'
if (Test-Path -LiteralPath $RelocationRoot) { Remove-WithinTarget $RelocationRoot }
New-Item -ItemType Directory -Path $RelocationRoot -Force | Out-Null
Copy-Item -LiteralPath $ImageRoot -Destination $RelocationImage -Recurse -Force
$relocatedJava = Join-Path $RelocationImage 'runtime/bin/java.exe'
if (-not (Test-Path -LiteralPath $relocatedJava)) {
    Fail ('the relocation copy is incomplete: ' + $relocatedJava + ' is missing')
}
Write-Note ('relocation copy: ' + $RelocationImage)
Invoke-WithScrubbedJavaEnvironment {
    Invoke-SmokeTest -Name 'G-relocation-opencv-health-check' -FilePath $relocatedJava -Arguments @('-cp', 'app/*;app/lib/*', $HealthCheckMain) -WorkingDirectory $RelocationImage -Expect @('OpenCV native health check passed.') | Out-Null
    Invoke-SmokeTest -Name 'H-relocation-monitor-listing' -FilePath $relocatedJava -Arguments @('-cp', 'app/*;app/lib/*', $LiveSolverMain, '--list-monitors') -WorkingDirectory $RelocationImage -Expect @('LIVE: no input sent.') | Out-Null
    Invoke-SmokeTest -Name 'I-relocation-reference-data' -FilePath $relocatedJava -Arguments @('-cp', 'app/*;app/lib/*', $PreviewMain) -WorkingDirectory $RelocationImage -Expect @('Preview written to:') | Out-Null
    $relocatedLauncher = Join-Path $RelocationImage $LauncherName
    $outsideCwd = $env:TEMP
    if (-not $outsideCwd) { $outsideCwd = $RepoRoot }
    Invoke-SmokeTest -Name 'J-relocation-launcher-from-outside-cwd' -FilePath $relocatedLauncher -Arguments @('--list-monitors') -WorkingDirectory $outsideCwd -Expect @('LIVE: no input sent.') | Out-Null
}
if (-not $KeepRelocationCopy) {
    Remove-WithinTarget $RelocationRoot
    Write-Note 'relocation copy removed'
} else {
    Write-Note ('relocation copy kept at ' + $RelocationImage)
}

# ---------------------------------------------------------------------------
# Step 14: tracked files untouched, then the final summary
# ---------------------------------------------------------------------------
Write-Step 'Verify that the worktree is still clean'
$gitStatusAfter = (Invoke-Tool -Label 'git status --porcelain (after)' -FilePath 'git' -Arguments @('-C', $RepoRoot, 'status', '--porcelain')).Output.Trim()
if ($gitStatusAfter) {
    Fail ('running this script changed the worktree state, which would invalidate the image provenance. git status --porcelain now reports: [' + $gitStatusAfter + ']')
}
Write-Note 'worktree state unchanged by the build: still clean'
if ($env:_JAVA_OPTIONS -or $env:JAVA_TOOL_OPTIONS) {
    Write-Note 'warning: _JAVA_OPTIONS or JAVA_TOOL_OPTIONS is set in this shell and could affect Java runs outside this script'
}
$finalFiles = @(Get-ChildItem -LiteralPath $ImageRoot -Recurse -File -Force)
$finalBytes = ($finalFiles | Measure-Object -Property Length -Sum).Sum
$summaryLines = New-Object 'System.Collections.Generic.List[string]'
$summaryLines.Add('Stage 9A application image complete.')
$summaryLines.Add('  git SHA           : ' + $gitSha)
$summaryLines.Add('  git branch        : ' + $gitBranch)
$summaryLines.Add('  project version   : ' + $projectVersion)
$summaryLines.Add('  packaging JDK     : ' + $javaVendor + ' ' + $javaRuntimeVersion)
$summaryLines.Add('  bundled runtime   : ' + ($RuntimeModules -join ','))
$summaryLines.Add('  tests             : ' + $trimmedSummary)
$summaryLines.Add('  application image : ' + $ImageRoot)
$summaryLines.Add('  file count        : ' + $finalFiles.Count)
$summaryLines.Add('  uncompressed size : ' + $finalBytes + ' bytes (' + [math]::Round($finalBytes / 1MB, 1) + ' MB)')
$summaryLines.Add('  diagnostics       : ' + $DiagnosticsDir)
$summaryLines.Add('  installer         : NOT BUILT in Stage 9A (jpackage installer is Stage 9B)')
$summaryLines.Add('  code signing      : NOT SIGNED (deferred)')
Add-Log ''
foreach ($line in $summaryLines) { Add-Log $line }
Write-Diagnostics
Write-Host ''
foreach ($line in $summaryLines) { Write-Host $line -ForegroundColor Green }
