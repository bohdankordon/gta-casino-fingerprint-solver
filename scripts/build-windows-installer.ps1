<#
.SYNOPSIS
    Builds the Stage 9B Windows EXE installer with the operator desktop UI.

.DESCRIPTION
    Runs the full Stage 9A application-image build from the current clean commit,
    stages the jpackage input solely from that fresh image, generates a jpackage
    app-image with the private Temurin runtime and the Swing operator launcher,
    proves the app-image with input-free packaged smokes, then builds the per-user
    EXE installer from the validated app-image and inspects the installer statically
    (MSI tables, upgrade identity, shortcut inventory) without executing it.

    The installer itself is never launched by this script. Install and uninstall
    validation use the generated MSI with documented msiexec switches; the
    interactive EXE wizard itself remains unexercised until Stage 9D qualification.

    Every generated artifact lives below the ignored target/ directory, so running
    this script must not modify a tracked file. It never installs Java or WiX and
    never changes machine PATH, JAVA_HOME or any system-wide registration.

.NOTES
    Stage 9B installer details live in docs/windows-installer.md. Code signing is
    explicitly deferred: the installer is unsigned and Windows SmartScreen may warn.

.EXAMPLE
    .\scripts\build-windows-installer.ps1

.EXAMPLE
    .\scripts\build-windows-installer.ps1 -AppVersion 0.1.0 -KeepJpackageTemp
#>
[CmdletBinding()]
param(
    [string]$PackagingJdk = '',
    [string]$AppVersion = '0.1.0',
    [string]$WixBinDir = '',
    [switch]$KeepJpackageTemp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# The Stage 9B contract. Anything changed here must be reflected in
# docs/windows-installer.md.
# ---------------------------------------------------------------------------
$ProductName = 'GTA Casino Fingerprint Solver'
$MenuGroup = 'GTA Casino Fingerprint Solver'
$Vendor = 'Bohdan Kordon'
$MainClass = 'io.github.bohdankordon.casinofingerprint.app.WindowsOperatorMain'
$LiveMainClass = 'io.github.bohdankordon.casinofingerprint.app.LiveSolverMain'
$AppRootProperty = 'gta.casino.solver.appRoot'
$FlatLafVersion = '3.7.2'
$RequiredJdkMajor = 21
$AcceptedVendorPattern = 'Adoptium|Temurin'
$MinimumTests = 859
$StableProfile = 'GTA V Enhanced, Borderless, physical 2560x1440'

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
$RepoRoot = Split-Path -Parent $ScriptDir
$TargetDir = Join-Path $RepoRoot 'target'
$Stage9ADir = Join-Path $TargetDir 'dist/app-image'
$Stage9ABuildInfo = Join-Path $Stage9ADir 'BUILD-INFO.txt'
$Stage9ADiagnostics = Join-Path $TargetDir 'stage9a'
$Stage9BDir = Join-Path $TargetDir 'stage9b'
$JpackageInputDir = Join-Path $Stage9BDir 'jpackage-input'
$JpackageTempAppImage = Join-Path $Stage9BDir 'jpackage-temp-appimage'
$JpackageTempExe = Join-Path $Stage9BDir 'jpackage-temp-exe'
$JpackageAppImage = Join-Path $Stage9BDir $ProductName
$InstallerDir = Join-Path $Stage9BDir 'installer'
$SmokeDir = Join-Path $Stage9BDir 'smoke'
$DiagnosticsDir = $Stage9BDir
$RelocationRoot = Join-Path $TargetDir 'stage9b relocation check'
$RelocationImage = Join-Path $RelocationRoot $ProductName
$UpgradeUuidFile = Join-Path $RepoRoot 'packaging/windows/win-upgrade-uuid.txt'
$Lf = [string][char]10
$Cr = [string][char]13
$Slash = [char]47
$Backslash = [char]92

# ---------------------------------------------------------------------------
# Diagnostics log (kept in memory so that a clean can delete target/ safely)
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
    throw ('STAGE9B: ' + $Message)
}

function Split-Lines([string]$Text) {
    $normalized = $Text.Replace($Cr, $Lf)
    return @($normalized -split $Lf)
}

function ForwardSlash([string]$Path) {
    return $Path.Replace($Backslash, $Slash)
}

function Remove-WithinTarget([string]$Path) {
    $targetFull = [System.IO.Path]::GetFullPath($TargetDir).TrimEnd($Slash, $Backslash)
    $full = [System.IO.Path]::GetFullPath($Path)
    $prefix = $targetFull + [string]$Backslash
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Fail ('refusing to delete a path outside the repository target directory: ' + $full)
    }
    if (Test-Path -LiteralPath $full) {
        foreach ($item in @(Get-ChildItem -LiteralPath $full -Recurse -Force)) {
            try { $item.Attributes = 'Normal' } catch { }
        }
        $deleteAttempts = 0
        while ($true) {
            try {
                [System.IO.Directory]::Delete($full, $true)
                break
            } catch {
                $deleteAttempts++
                if ($deleteAttempts -ge 5) { throw }
                Start-Sleep -Seconds 2
            }
        }    }
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
    $savedJavaOptions = $env:_JAVA_OPTIONS
    $savedToolOptions = $env:JAVA_TOOL_OPTIONS
    $savedJdkOptions = $env:JDK_JAVA_OPTIONS
    try {
        $env:JAVA_HOME = (Join-Path $DiagnosticsDir 'no-such-java-home')
        $env:PATH = ((Join-Path $env:SystemRoot 'System32') + ';' + $env:SystemRoot)
        $env:_JAVA_OPTIONS = $null
        $env:JAVA_TOOL_OPTIONS = $null
        $env:JDK_JAVA_OPTIONS = $null
        & $Body
    } finally {
        $env:JAVA_HOME = $savedJavaHome
        $env:PATH = $savedPath
        $env:_JAVA_OPTIONS = $savedJavaOptions
        $env:JAVA_TOOL_OPTIONS = $savedToolOptions
        $env:JDK_JAVA_OPTIONS = $savedJdkOptions
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
    $entry.Add('# Stage 9B packaged smoke test: ' + $Name)
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

function Invoke-MsiQuery {
    param(
        [Parameter(Mandatory = $true)][string]$MsiPath,
        [Parameter(Mandatory = $true)][string]$Sql
    )
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($MsiPath, 0))
    $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, ($Sql))
    [void]$view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)
    $rows = New-Object 'System.Collections.Generic.List[string]'
    while ($true) {
        $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
        if ($null -eq $record) { break }
        $cells = New-Object 'System.Collections.Generic.List[string]'
        foreach ($index in @(1, 2, 3, 4)) {
            try {
                $cells.Add($record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, $index))
            } catch {
                $cells.Add('')
            }
        }
        $rows.Add(($cells -join '|'))
    }
    [void]$view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null)
    return $rows
}

function Write-Diagnostics {
    if (-not (Test-Path -LiteralPath $DiagnosticsDir)) {
        New-Item -ItemType Directory -Path $DiagnosticsDir -Force | Out-Null
    }
    [System.IO.File]::WriteAllLines((Join-Path $DiagnosticsDir 'build.log'), $script:LogLines)
}

trap {
    Add-Log ('FAILED: ' + $_)
    Write-Diagnostics
    Write-Host ''
    Write-Host ('STAGE9B: build failed: ' + $_) -ForegroundColor Red
    Write-Host ('Full log: ' + (Join-Path $DiagnosticsDir 'build.log')) -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# Step 1: Windows x64
# ---------------------------------------------------------------------------
Write-Step 'Verify Windows x64'
Add-Log ('PowerShell: ' + $PSVersionTable.PSVersion.ToString())
if (-not ($env:OS -eq 'Windows_NT' -and [System.Environment]::OSVersion.Platform -eq 'Win32NT')) {
    Fail 'Stage 9B packaging requires Windows.'
}
$architecture = $env:PROCESSOR_ARCHITECTURE
try {
    $architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
} catch {
    Add-Log 'OSArchitecture unavailable; falling back to PROCESSOR_ARCHITECTURE'
}
if ($architecture -notin @('X64', 'AMD64')) {
    Fail ('Stage 9B packaging requires 64-bit Windows (x64), found "' + $architecture + '".')
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
    Fail 'packaging requires a completely clean Git worktree, but git status --porcelain reports entries. Commit the changes, or remove the untracked files, and run the build again. This script never restores, resets, cleans or stashes your work.'
}
Write-Note 'worktree is clean: the recorded Git SHA identifies every packaged source and data file'
# ---------------------------------------------------------------------------
# Step 3: build-environment contamination gate
# ---------------------------------------------------------------------------
Write-Step 'Reject hidden JVM configuration'
$contaminating = New-Object 'System.Collections.Generic.List[string]'
foreach ($name in @('_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS')) {
    $value = [System.Environment]::GetEnvironmentVariable($name)
    if ($value) {
        $contaminating.Add($name + '=' + $value)
    }
}
if ($contaminating.Count -gt 0) {
    Fail ('hidden JVM configuration would leak into release evidence (' + ($contaminating -join '; ') + '). Unset these variables for a deterministic packaging build and run again.')
}
Write-Note 'no hidden JVM configuration (_JAVA_OPTIONS, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS are unset)'
$mavenOpts = [System.Environment]::GetEnvironmentVariable('MAVEN_OPTS')
if ($mavenOpts) {
    Write-Note ('MAVEN_OPTS is set and recorded (Maven build only): ' + $mavenOpts)
} else {
    Write-Note 'MAVEN_OPTS is unset'
}

# ---------------------------------------------------------------------------
# Step 4: canonical packaging JDK plus jpackage
# ---------------------------------------------------------------------------
Write-Step 'Validate the packaging JDK (Eclipse Temurin 21) and jpackage'
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
    Fail 'no packaging JDK found. Install Eclipse Temurin 21 (x64) separately, then set JAVA_HOME or pass -PackagingJdk <path>. This script never installs or registers Java.'
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
foreach ($tool in @('java.exe', 'javac.exe', 'jar.exe', 'jlink.exe', 'jdeps.exe', 'jpackage.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $jdkBin $tool))) { $missingTools.Add($tool) }
}
if ($missingTools.Count -gt 0) {
    Fail ('the packaging JDK is missing required tools: ' + ($missingTools -join ', ') + '. Stage 9B needs a full JDK 21 (java, javac, jar, jlink, jdeps, jpackage) from Eclipse Temurin.')
}
$javaExe = Join-Path $jdkBin 'java.exe'
$jpackageExe = Join-Path $jdkBin 'jpackage.exe'
$settingsOutput = (Invoke-Tool -Label 'java -XshowSettings:properties -version' -FilePath $javaExe -Arguments @('-XshowSettings:properties', '-version')).Output
$javaVersion = Get-JavaSetting $settingsOutput 'java.version'
$javaVendor = Get-JavaSetting $settingsOutput 'java.vendor'
$javaVendorVersion = Get-JavaSetting $settingsOutput 'java.vendor.version'
$javaRuntimeVersion = Get-JavaSetting $settingsOutput 'java.runtime.version'
if (-not $javaVersion.StartsWith($RequiredJdkMajor.ToString() + '.')) {
    Fail ('Stage 9B requires a Java ' + $RequiredJdkMajor + ' packaging JDK, but ' + $javaExe + ' reports java.version=' + $javaVersion + '.')
}
if (-not (($javaVendor + ' ' + $javaVendorVersion) -match $AcceptedVendorPattern)) {
    Fail ('Stage 9B requires Eclipse Temurin as the canonical packaging JDK, but ' + $javaExe + ' reports java.vendor="' + $javaVendor + '" java.vendor.version="' + $javaVendorVersion + '".')
}
$jpackageVersion = (Invoke-Tool -Label 'jpackage --version' -FilePath $jpackageExe -Arguments @('--version')).Output.Trim()
if (-not $jpackageVersion.StartsWith($RequiredJdkMajor.ToString() + '.')) {
    Fail ('jpackage from the packaging JDK reports an unexpected version: ' + $jpackageVersion)
}
$javaOsArch = Get-JavaSetting $settingsOutput 'os.arch'
$acceptedArch = 'amd64', 'x86_64', 'x64'
if ($acceptedArch -notcontains $javaOsArch) {
    Fail ('Stage 9B requires an x64 packaging JDK, but ' + $javaExe + ' reports os.arch=' + $javaOsArch + '. OS or process architecture is never used for this gate.')
}
Write-Note ('packaging JDK: ' + $javaVendor + ' ' + $javaVendorVersion + ' (' + $javaOsArch + ')')
Write-Note ('java.runtime.version: ' + $javaRuntimeVersion)
Write-Note ('jpackage version: ' + $jpackageVersion)
Write-Note ('JDK resolved from: ' + $jdkSource)

# ---------------------------------------------------------------------------
# Step 5: WiX 3 build-time tooling
# ---------------------------------------------------------------------------
Write-Step 'Validate WiX 3.14 build-time tooling'
$wixDir = ''
if ($WixBinDir) {
    if (-not (Test-Path -LiteralPath (Join-Path $WixBinDir 'candle.exe'))) {
        Fail ('-WixBinDir does not contain candle.exe: ' + $WixBinDir)
    }
    if (-not (Test-Path -LiteralPath (Join-Path $WixBinDir 'light.exe'))) {
        Fail ('-WixBinDir does not contain light.exe: ' + $WixBinDir)
    }
    $wixDir = $WixBinDir
    Write-Note ('WiX resolved from -WixBinDir: ' + $wixDir)
}
if (-not $wixDir) {
    $wixEnv = $env:WIX
    if ($wixEnv) {
        foreach ($candidate in @($wixEnv, (Join-Path $wixEnv 'bin'))) {
            if ((Test-Path -LiteralPath (Join-Path $candidate 'candle.exe')) -and (Test-Path -LiteralPath (Join-Path $candidate 'light.exe'))) {
                $wixDir = $candidate
            }
        }
        if ($wixDir) {
            Write-Note ('WiX resolved from WIX: ' + $wixDir)
        }
    }
}
if (-not $wixDir) {
    $candleCommand = Get-Command candle.exe -ErrorAction SilentlyContinue
    $lightCommand = Get-Command light.exe -ErrorAction SilentlyContinue
    if ($candleCommand -and $lightCommand) {
        $candleDir = Split-Path -Parent $candleCommand.Source
        $lightDir = Split-Path -Parent $lightCommand.Source
        if ($candleDir -ceq $lightDir) {
            $wixDir = $candleDir
            Write-Note ('WiX resolved from PATH: ' + $wixDir)
        }
    }
}
if (-not $wixDir) {
    $programFilesX86 = [System.Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    foreach ($candidate in @((Join-Path $env:ProgramFiles 'WiX Toolset v3.14/bin'), (Join-Path $programFilesX86 'WiX Toolset v3.14/bin'))) {
        if ((Test-Path -LiteralPath (Join-Path $candidate 'candle.exe')) -and (Test-Path -LiteralPath (Join-Path $candidate 'light.exe'))) {
            $wixDir = $candidate
            Write-Note ('WiX resolved from the default install location: ' + $wixDir)
        }
    }
}
if (-not $wixDir) {
    Fail 'WiX 3.14 build tooling (candle.exe plus light.exe from one directory) was not found. Install WiX Toolset 3.14.1 separately, for example with "winget install --id WiXToolset.WiXToolset --version 3.14.1.8722 --exact" (needs administrator rights for the NetFx3 dependency), or download wix314-binaries.zip from the WiX v3 releases and point -WixBinDir (or the WIX environment variable) at the extracted directory. This script never downloads or installs WiX, and WiX is build-time only: it is never bundled into the product and never required on an end-user PC.'
}
$candleExe = Join-Path $wixDir 'candle.exe'
$lightExe = Join-Path $wixDir 'light.exe'
$candleBanner = (Invoke-Tool -Label 'candle.exe version probe' -FilePath $candleExe -Arguments @('-?')).Output
$wixVersion = ''
foreach ($line in (Split-Lines $candleBanner)) {
    if ($line -match 'version ([0-9]+[.][0-9]+[.][0-9]+[.][0-9]+)') {
        $wixVersion = $Matches[1]
    }
}
if (-not $wixVersion) {
    Fail ('could not read the WiX version from ' + $candleExe + '; refusing to guess the toolchain.')
}
if (-not $wixVersion.StartsWith('3.')) {
    Fail ('Stage 9B requires WiX 3.x tooling (canonical 3.14.1), but ' + $candleExe + ' reports ' + $wixVersion + '. A newer WiX major version has different tool names and is not accepted by JDK 21 jpackage.')
}
Write-Note ('WiX version: ' + $wixVersion + ' (build-time only, never bundled)')
Write-Note ('candle.exe: ' + $candleExe)
Write-Note ('light.exe: ' + $lightExe)

# ---------------------------------------------------------------------------
# Step 6: permanent upgrade identity and package version
# ---------------------------------------------------------------------------
Write-Step 'Read the permanent Windows upgrade identity'
if (-not (Test-Path -LiteralPath $UpgradeUuidFile)) {
    Fail ('the committed upgrade UUID file is missing: ' + $UpgradeUuidFile)
}
$uuidLines = @([System.IO.File]::ReadAllLines($UpgradeUuidFile) | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
if ($uuidLines.Count -ne 1) {
    Fail ('the upgrade UUID file must contain exactly one UUID, found ' + $uuidLines.Count)
}
$upgradeUuid = $uuidLines[0].ToUpperInvariant()
$parsedUuid = [System.Guid]::Empty
if (-not [System.Guid]::TryParse($upgradeUuid, [ref]$parsedUuid)) {
    Fail ('the committed upgrade UUID is not a valid UUID: ' + $upgradeUuid)
}
Write-Note ('win-upgrade-uuid (permanent, reused by every release): ' + $upgradeUuid)
$versionParts = $AppVersion.Split('.')
$versionNumeric = $true
if ($versionParts.Count -ne 3) {
    $versionNumeric = $false
} else {
    foreach ($part in $versionParts) {
        $number = 0
        if (-not [int]::TryParse($part, [ref]$number)) {
            $versionNumeric = $false
        }
    }
}
if (-not $versionNumeric) {
    Fail ('-AppVersion must be numeric major.minor.patch for the Windows installer (got "' + $AppVersion + '"). Prerelease mappings such as v0.9.0-beta.1 belong to Stage 9C, not to this script.')
}
Write-Note ('Windows package version: ' + $AppVersion + ' (Maven project stays 0.1.0-SNAPSHOT)')
Write-Note ('FlatLaf version: ' + $FlatLafVersion + ' (core artifact only)')

# ---------------------------------------------------------------------------
# Step 7: run the full Stage 9A build from this commit
# ---------------------------------------------------------------------------
Write-Step 'Run the Stage 9A application-image build'
$stage9AScript = Join-Path $ScriptDir 'build-windows-app-image.ps1'
$stage9AHost = 'powershell.exe'
$pwshCommand = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if ($pwshCommand) {
    $stage9AHost = $pwshCommand.Source
    Write-Note ('Stage 9A runs under PowerShell 7: ' + $stage9AHost)
} else {
    Write-Note 'Stage 9A runs under Windows PowerShell 5.1 (pwsh.exe was not found)'
}
Invoke-Tool -Label 'Stage 9A packaging build' -FilePath $stage9AHost -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $stage9AScript, '-PackagingJdk', $jdkRoot) | Out-Null
Write-Note 'Stage 9A build finished; verifying it consumed this exact commit'
if (-not (Test-Path -LiteralPath $Stage9ABuildInfo)) {
    Fail ('the Stage 9A build did not produce ' + $Stage9ABuildInfo)
}
$buildInfoSha = ''
foreach ($line in ([System.IO.File]::ReadAllLines($Stage9ABuildInfo))) {
    if ($line.StartsWith('Git SHA:')) {
        $buildInfoSha = $line.Substring('Git SHA:'.Length).Trim()
    }
}
if ($buildInfoSha -ne $gitSha) {
    Fail ('stale Stage 9A output: BUILD-INFO records Git SHA ' + $buildInfoSha + ' but this commit is ' + $gitSha + '. Never consume a stale target/dist/app-image.')
}
Write-Note ('Stage 9A BUILD-INFO references this exact commit: ' + $buildInfoSha)
$stage9ALog = Join-Path $Stage9ADiagnostics 'build.log'
$stage9ATests = ''
if (Test-Path -LiteralPath $stage9ALog) {
    foreach ($line in ([System.IO.File]::ReadAllLines($stage9ALog))) {
        if ($line -like '*tests:*Tests run:*') {
            $stage9ATests = $line.Trim()
        }
    }
}
if ($stage9ATests) {
    Write-Note ('Stage 9A tests: ' + $stage9ATests)
}

# ---------------------------------------------------------------------------
# Step 8: stage the jpackage input solely from the fresh Stage 9A image
# ---------------------------------------------------------------------------
Write-Step 'Stage the jpackage input from the fresh Stage 9A image'
Remove-WithinTarget $Stage9BDir
New-Item -ItemType Directory -Path $JpackageInputDir -Force | Out-Null
New-Item -ItemType Directory -Path $SmokeDir -Force | Out-Null
$stage9AAppDir = Join-Path $Stage9ADir 'app'
$stage9ALibDir = Join-Path $stage9AAppDir 'lib'
$projectJars = @(Get-ChildItem -LiteralPath $stage9AAppDir -File -Filter 'gta-casino-fingerprint-solver-*.jar')
if ($projectJars.Count -ne 1) {
    Fail ('the Stage 9A image must hold exactly one project jar, found ' + $projectJars.Count)
}
$mainJarName = $projectJars[0].Name
Write-Note ('project jar: ' + $mainJarName)
Copy-Item -LiteralPath $projectJars[0].FullName -Destination (Join-Path $JpackageInputDir $mainJarName)
$stage9ALibJars = @(Get-ChildItem -LiteralPath $stage9ALibDir -File -Filter '*.jar')
if ($stage9ALibJars.Count -eq 0) {
    Fail ('no runtime dependency jars were staged by Stage 9A into ' + $stage9ALibDir)
}
$flatLafJar = @($stage9ALibJars | Where-Object { $_.Name -like 'flatlaf-*.jar' })
if ($flatLafJar.Count -ne 1) {
    Fail 'exactly one FlatLaf jar must be staged by Stage 9A'
}
if ($flatLafJar[0].Name -ne ('flatlaf-' + $FlatLafVersion + '.jar')) {
    Fail ('staged FlatLaf is not the pinned version: ' + $flatLafJar[0].Name)
}
Write-Note ('FlatLaf staged: ' + $flatLafJar[0].Name)
foreach ($jar in $stage9ALibJars) {
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $JpackageInputDir $jar.Name)
}
Write-Note ('runtime dependency jars staged flat: ' + $stage9ALibJars.Count)
foreach ($tree in @('dataset', 'fixtures', 'config')) {
    $source = Join-Path $Stage9ADir $tree
    if (-not (Test-Path -LiteralPath $source)) {
        Fail ('the Stage 9A image is missing the runtime tree: ' + $tree)
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $JpackageInputDir $tree) -Recurse -Force
}
Copy-Item -LiteralPath $Stage9ABuildInfo -Destination (Join-Path $JpackageInputDir 'BUILD-INFO.txt')
$manifestCsv = Join-Path $JpackageInputDir 'dataset/layout/reference-layout.csv'
$manifestCrops = @([System.IO.File]::ReadAllLines($manifestCsv) | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('fingerprint_id,') })
if ($manifestCrops.Count -ne 20) {
    Fail ('the staged reference manifest must describe exactly 20 crops, found ' + $manifestCrops.Count)
}
$stagedCrops = @(Get-ChildItem -LiteralPath (Join-Path $JpackageInputDir 'dataset/reference') -Recurse -File -Filter '*.png')
if ($stagedCrops.Count -ne 20) {
    Fail ('the staged input holds ' + $stagedCrops.Count + ' reference crops instead of 20')
}
if (-not (Test-Path -LiteralPath (Join-Path $JpackageInputDir 'fixtures/gameplay/layout/representative-2560x1440.csv'))) {
    Fail 'the staged 2560x1440 gameplay layout is missing'
}
$stagedFiles = @(Get-ChildItem -LiteralPath $JpackageInputDir -Recurse -File)
foreach ($file in $stagedFiles) {
    $relative = ForwardSlash($file.FullName.Substring($JpackageInputDir.Length).TrimStart($Slash, $Backslash))
    if ($relative -like '*1080*') {
        Fail ('experimental 1080p content must never be staged: ' + $relative)
    }
    foreach ($extension in @('.java', '.mkv', '.mp4', '.pfx', '.p12', '.pem')) {
        if ($file.Name.EndsWith($extension)) {
            Fail ('forbidden content in the jpackage input: ' + $relative)
        }
    }
    if ($relative.StartsWith('dataset/source/') -or $relative.StartsWith('fixtures/gameplay/source/') -or $relative.StartsWith('fixtures/gameplay/annotations/') -or $relative.StartsWith('fixtures/gameplay/recordings/')) {
        Fail ('evaluation-only material must never be staged: ' + $relative)
    }
}
Write-Note ('staged files: ' + $stagedFiles.Count + ' (project jar, flat dependencies, allowlisted data only)')
# ---------------------------------------------------------------------------
# Step 9: jpackage app-image with the private runtime (no console launcher)
# ---------------------------------------------------------------------------
Write-Step 'Generate the jpackage app-image'
$env:PATH = $wixDir + ';' + $env:PATH
Add-Log ('WiX prepended to this process PATH for jpackage (machine PATH is untouched): ' + $wixDir)
Remove-WithinTarget $JpackageTempAppImage
Remove-WithinTarget $JpackageAppImage
$javaOptionsValue = '-D' + $AppRootProperty + '=$APPDIR'
$appImageArgs = @('--type', 'app-image', '--verbose', '--dest', $Stage9BDir, '--name', $ProductName, '--app-version', $AppVersion, '--vendor', $Vendor, '--description', 'Casino fingerprint solver operator for GTA V Enhanced (2560x1440)', '--copyright', $Vendor, '--input', $JpackageInputDir, '--main-jar', $mainJarName, '--main-class', $MainClass, '--runtime-image', (Join-Path $Stage9ADir 'runtime'), '--java-options', $javaOptionsValue, '--temp', $JpackageTempAppImage)
Invoke-Tool -Label 'jpackage app-image' -FilePath $jpackageExe -Arguments $appImageArgs -WorkingDirectory $Stage9BDir | Out-Null
if (-not (Test-Path -LiteralPath $JpackageAppImage)) {
    Fail ('jpackage did not produce the app-image directory: ' + $JpackageAppImage)
}
Write-Note ('jpackage app-image: ' + $JpackageAppImage)

# ---------------------------------------------------------------------------
# Step 10: inspect the generated app-image (never assume success on exit zero)
# ---------------------------------------------------------------------------
Write-Step 'Inspect the jpackage app-image'
$guiExe = Join-Path $JpackageAppImage ($ProductName + '.exe')
if (-not (Test-Path -LiteralPath $guiExe)) {
    Fail ('the native Windows launcher is missing: ' + $guiExe)
}
Write-Note ('native launcher: ' + $guiExe)
$appPayloadDir = Join-Path $JpackageAppImage 'app'
$launcherCfg = Join-Path $appPayloadDir ($ProductName + '.cfg')
if (-not (Test-Path -LiteralPath $launcherCfg)) {
    Fail ('the generated launcher config is missing: ' + $launcherCfg)
}
$cfgLines = [System.IO.File]::ReadAllLines($launcherCfg)
$cfgMainClass = @($cfgLines | Where-Object { $_.StartsWith('app.mainclass=') })
if ($cfgMainClass.Count -ne 1 -or $cfgMainClass[0] -ne ('app.mainclass=' + $MainClass)) {
    Fail 'the launcher config does not point at the operator main class'
}
Write-Note ('launcher main class: ' + $MainClass)
$expectedPropertyLine = 'java-options=-D' + $AppRootProperty + '=$APPDIR'
if (-not ($cfgLines -contains $expectedPropertyLine)) {
    Fail ('the launcher config does not carry the packaged application root (' + $expectedPropertyLine + ')')
}
Write-Note ('launcher application root: -D' + $AppRootProperty + '=$APPDIR (documented jpackage macro)')
$cfgClasspath = @($cfgLines | Where-Object { $_.StartsWith('app.classpath=') } | ForEach-Object { $_.Substring('app.classpath='.Length) })
$cfgJars = @($cfgClasspath | ForEach-Object { (ForwardSlash($_)).Split('/')[-1] } | Sort-Object -Unique)
$stagedJarNames = @(@($mainJarName) + @($stage9ALibJars | ForEach-Object { $_.Name }) | Sort-Object -Unique)
$missingFromClasspath = @($stagedJarNames | Where-Object { $cfgJars -notcontains $_ })
$extraOnClasspath = @($cfgJars | Where-Object { $stagedJarNames -notcontains $_ })
if ($missingFromClasspath.Count -gt 0) {
    Fail ('the generated classpath omits staged jars: ' + ($missingFromClasspath -join ', ') + '. Fix the staging or jpackage configuration; a fat jar is not allowed.')
}
if ($extraOnClasspath.Count -gt 0) {
    Fail ('the generated classpath references unstaged jars: ' + ($extraOnClasspath -join ', '))
}
Write-Note ('generated classpath: complete (' + $cfgJars.Count + ' jars, including project jar and FlatLaf ' + $FlatLafVersion + ')')
$bundledJava = Join-Path $JpackageAppImage 'runtime/bin/java.exe'
if (-not (Test-Path -LiteralPath $bundledJava)) {
    Fail ('the jpackage app-image has no private runtime: ' + $bundledJava)
}
foreach ($relative in @('dataset/layout/reference-layout.csv', 'fixtures/gameplay/layout/representative-2560x1440.csv', 'BUILD-INFO.txt', 'config/packaged-files.txt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $appPayloadDir $relative))) {
        Fail ('the jpackage application payload is missing: ' + $relative)
    }
}
Write-Note 'application payload data present (dataset manifest, 2560x1440 layout, BUILD-INFO, packaged-files)'
$payloadFiles = @(Get-ChildItem -LiteralPath $appPayloadDir -Recurse -File)
foreach ($file in $payloadFiles) {
    $relative = ForwardSlash($file.FullName.Substring($appPayloadDir.Length).TrimStart($Slash, $Backslash))
    if ($relative -like '*1080*') {
        Fail ('experimental 1080p content reached the app-image: ' + $relative)
    }
}
Write-Note ('app-image payload files: ' + $payloadFiles.Count)
$stage9ARelease = Join-Path $Stage9ADir 'runtime/release'
$jpackageRelease = Join-Path $JpackageAppImage 'runtime/release'
$stage9AReleaseHash = (Get-FileHash -LiteralPath $stage9ARelease -Algorithm SHA256).Hash
$jpackageReleaseHash = (Get-FileHash -LiteralPath $jpackageRelease -Algorithm SHA256).Hash
if ($stage9AReleaseHash -ne $jpackageReleaseHash) {
    Fail 'the jpackage runtime is not the Stage 9A private runtime (release files differ). jpackage must reuse --runtime-image, never generate its own.'
}
Write-Note 'private runtime: identical to the fresh Stage 9A runtime (release file hash match)'
$releaseModules = ''
foreach ($line in ([System.IO.File]::ReadAllLines($jpackageRelease))) {
    if ($line.StartsWith('MODULES="')) {
        $releaseModules = $line
    }
}
Write-Note ('private runtime modules: ' + $releaseModules)

# ---------------------------------------------------------------------------
# Step 11: input-free packaged app-image smokes
# ---------------------------------------------------------------------------
Write-Step 'Run input-free app-image smokes with the bundled runtime'
$appPayloadAbs = [System.IO.Path]::GetFullPath($appPayloadDir)
$appImageAbs = [System.IO.Path]::GetFullPath($JpackageAppImage)
$outsideCwd = $env:SystemRoot
$appRootArgument = '-D' + $AppRootProperty + '=' + $appPayloadAbs
Invoke-WithScrubbedJavaEnvironment {
    Write-Note ('JAVA_HOME forced to ' + $env:JAVA_HOME + ' and PATH to System32 only: the smokes cannot reach a system Java')
    $whereJava = Get-Command java.exe -ErrorAction SilentlyContinue
    $whereJavaText = 'not found'
    if ($whereJava) { $whereJavaText = $whereJava.Source }
    Write-Note ('java.exe on the scrubbed PATH: ' + $whereJavaText)
    Add-Log ('java.exe on the scrubbed PATH: ' + $whereJavaText)
    Invoke-SmokeTest -Name 'A-jpackage-java-version' -FilePath $bundledJava -Arguments @('-version') -WorkingDirectory $JpackageAppImage -Expect @('Temurin') | Out-Null
    $probeSettings = Invoke-SmokeTest -Name 'B-jpackage-java-home' -FilePath $bundledJava -Arguments @('-XshowSettings:properties', '-version') -WorkingDirectory $JpackageAppImage
    $bundledHome = Get-JavaSetting $probeSettings 'java.home'
    if (-not $bundledHome.StartsWith($appImageAbs, [StringComparison]::OrdinalIgnoreCase)) {
        Fail ('the smoke test used a Java runtime outside the jpackage image: java.home=' + $bundledHome)
    }
    Write-Note ('bundled java.home: ' + $bundledHome)
    $operatorMarkers = @("OPERATOR SMOKE: FlatLaf version: $FlatLafVersion", 'OPERATOR SMOKE: OpenCV health: OK', 'OPERATOR SMOKE: reference manifest: 20 crops', 'OPERATOR SMOKE: reference library: 20 crops normalized', 'OPERATOR SMOKE: gameplay layout: 2560x1440', 'OPERATOR SMOKE: monitors (', 'OPERATOR STATE: DISARMED', 'OPERATOR INPUT: none sent')
    Invoke-SmokeTest -Name 'C-operator-smoke' -FilePath $bundledJava -Arguments @($appRootArgument, '-cp', ($appPayloadAbs + '/*'), $MainClass, '--operator-smoke') -WorkingDirectory $outsideCwd -Expect $operatorMarkers | Out-Null
    Invoke-SmokeTest -Name 'D-cli-monitor-listing' -FilePath $bundledJava -Arguments @($appRootArgument, '-cp', ($appPayloadAbs + '/*'), $LiveMainClass, '--list-monitors') -WorkingDirectory $outsideCwd -Expect @('layout requires physical 2560x1440', 'no input sent') | Out-Null
    $guiLogFile = Join-Path $SmokeDir 'E-gui-launcher.log'
    $guiEntry = New-Object 'System.Collections.Generic.List[string]'
    $guiEntry.Add('# Stage 9B packaged smoke test: E-gui-launcher')
    $guiEntry.Add('command: ' + $guiExe + ' --operator-smoke')
    $guiEntry.Add('working directory: ' + $outsideCwd)
    $guiEntry.Add('expectation: GUI-subsystem launcher, no console window, exit 0, zero input')
    Push-Location -Path $outsideCwd
    $guiExit = -1
    try {
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $guiExe
        $startInfo.Arguments = '--operator-smoke'
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $guiProcess = [System.Diagnostics.Process]::Start($startInfo)
        if (-not $guiProcess.WaitForExit(120000)) {
            try { $guiProcess.Kill() } catch { }
            $guiEntry.Add('result: TIMEOUT after 120 seconds')
            [System.IO.File]::WriteAllLines($guiLogFile, $guiEntry)
            Fail 'the GUI launcher did not exit within 120 seconds for --operator-smoke'
        }
        $guiExit = $guiProcess.ExitCode
    } finally {
        Pop-Location
    }
    $guiEntry.Add('exit code: ' + $guiExit)
    [System.IO.File]::WriteAllLines($guiLogFile, $guiEntry)
    Add-Log ('smoke E-gui-launcher: exit=' + $guiExit + ' log=' + $guiLogFile)
    if ($guiExit -ne 0) {
        Fail ('the installed-style GUI launcher failed --operator-smoke with exit ' + $guiExit)
    }
    Write-Note 'smoke E-gui-launcher: OK (exit 0, no console window, zero input)'
    Remove-WithinTarget $RelocationRoot
    Copy-Item -LiteralPath $JpackageAppImage -Destination $RelocationImage -Recurse -Force
    $relocatedPayload = Join-Path $RelocationImage 'app'
    $relocatedRuntime = Join-Path $RelocationImage 'runtime/bin/java.exe'
    $relocatedRootArgument = '-D' + $AppRootProperty + '=' + [System.IO.Path]::GetFullPath($relocatedPayload)
    Invoke-SmokeTest -Name 'F-relocation-operator-smoke' -FilePath $relocatedRuntime -Arguments @($relocatedRootArgument, '-cp', ([System.IO.Path]::GetFullPath($relocatedPayload) + '/*'), $MainClass, '--operator-smoke') -WorkingDirectory $outsideCwd -Expect @('OPERATOR STATE: DISARMED', 'OPERATOR INPUT: none sent') | Out-Null
    Remove-WithinTarget $RelocationRoot
    Write-Note 'relocation smoke: path with spaces works from an outside working directory'
}
# ---------------------------------------------------------------------------
# Step 12: EXE installer from the validated jpackage app-image
# ---------------------------------------------------------------------------
Write-Step 'Build the EXE installer from the validated app-image'
Remove-WithinTarget $JpackageTempExe
Remove-WithinTarget $InstallerDir
$installerArgs = @('--type', 'exe', '--verbose', '--dest', $InstallerDir, '--name', $ProductName, '--app-version', $AppVersion, '--vendor', $Vendor, '--description', 'Casino fingerprint solver operator for GTA V Enhanced (2560x1440)', '--copyright', $Vendor, '--app-image', $JpackageAppImage, '--win-per-user-install', '--win-menu', '--win-menu-group', $MenuGroup, '--win-upgrade-uuid', $upgradeUuid, '--temp', $JpackageTempExe)
Invoke-Tool -Label 'jpackage exe installer' -FilePath $jpackageExe -Arguments $installerArgs -WorkingDirectory $Stage9BDir | Out-Null
$installerName = $ProductName + '-' + $AppVersion + '.exe'
$installerPath = Join-Path $InstallerDir $installerName
if (-not (Test-Path -LiteralPath $installerPath)) {
    Fail ('jpackage did not produce the installer: ' + $installerPath)
}
$installerBytes = (Get-Item -LiteralPath $installerPath).Length
$installerSha = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Note ('installer: ' + $installerName)
Write-Note ('installer bytes: ' + $installerBytes)
Write-Note ('installer SHA-256: ' + $installerSha)

# ---------------------------------------------------------------------------
# Step 13: static installer inspection (the installer is never executed here)
# ---------------------------------------------------------------------------
Write-Step 'Inspect the installer package statically'
$msiCandidates = @(Get-ChildItem -LiteralPath (Join-Path $JpackageTempExe 'images/win-exe.image') -File -Filter '*.msi')
if ($msiCandidates.Count -ne 1) {
    Fail ('expected exactly the installer MSI below jpackage-temp-exe/images/win-exe.image, found ' + $msiCandidates.Count)
}
$msiPath = $msiCandidates[0].FullName
Write-Note ('installer MSI (static inspection only, never executed): ' + $msiCandidates[0].Name)
$msiProperties = @(Invoke-MsiQuery -MsiPath $msiPath -Sql 'SELECT Property, Value FROM Property')
foreach ($line in $msiProperties) {
    Add-Log ('msi Property: ' + $line)
}
function Find-MsiProperty([string]$Name) {
    foreach ($line in $msiProperties) {
        $cells = @($line -split '[|]')
        if ($cells[0] -ceq $Name) {
            return $cells[1]
        }
    }
    return ''
}
$msiUpgradeCode = Find-MsiProperty 'UpgradeCode'
if ($msiUpgradeCode -ne ('{' + $upgradeUuid + '}')) {
    Fail ('the installer UpgradeCode is not the permanent UUID: ' + $msiUpgradeCode)
}
Write-Note ('installer UpgradeCode: ' + $msiUpgradeCode + ' (permanent)')
if ((Find-MsiProperty 'ProductVersion') -ne $AppVersion) {
    Fail 'the installer ProductVersion does not match -AppVersion'
}
if ((Find-MsiProperty 'ProductName') -ne $ProductName) {
    Fail 'the installer ProductName does not match'
}
if ((Find-MsiProperty 'Manufacturer') -ne $Vendor) {
    Fail 'the installer Manufacturer does not match'
}
if (Find-MsiProperty 'ALLUSERS') {
    Fail 'the installer sets ALLUSERS: per-user installation was requested, machine-wide was baked'
}
if (Find-MsiProperty 'MSIINSTALLPERUSER') {
    Add-Log 'msi Property MSIINSTALLPERUSER is present (per-user marker)'
} else {
    Write-Note 'no ALLUSERS/MSIINSTALLPERUSER machine-wide marker: per-user package'
}
$msiUpgradeRows = @(Invoke-MsiQuery -MsiPath $msiPath -Sql 'SELECT UpgradeCode, VersionMax FROM Upgrade')
$upgradeMatch = @($msiUpgradeRows | Where-Object { $_ -like ('*' + $upgradeUuid + '*') })
if ($upgradeMatch.Count -eq 0) {
    Fail 'the installer Upgrade table does not reference the permanent UUID'
}
Write-Note ('installer Upgrade table references the permanent UUID (' + $msiUpgradeRows.Count + ' rows)')
$msiShortcuts = @(Invoke-MsiQuery -MsiPath $msiPath -Sql 'SELECT Shortcut, Directory_, Name FROM Shortcut')
if ($msiShortcuts.Count -ne 1) {
    Fail ('expected exactly the Start Menu shortcut, found ' + $msiShortcuts.Count + ' shortcut rows')
}
$shortcutCells = @($msiShortcuts[0] -split '[|]')
if (-not ($shortcutCells -contains $ProductName)) {
    Fail ('the shortcut name is not the product name: ' + ($shortcutCells -join '|'))
}
$msiDirectories = @(Invoke-MsiQuery -MsiPath $msiPath -Sql 'SELECT Directory, Directory_Parent, DefaultDir FROM Directory')
foreach ($line in $msiDirectories) {
    Add-Log ('msi Directory: ' + $line)
}
$directoryParents = @{}
foreach ($line in $msiDirectories) {
    $cells = @($line -split '[|]')
    $directoryParents[$cells[0]] = $cells[1]
}
$walk = $shortcutCells[1]
$walkChain = New-Object 'System.Collections.Generic.List[string]'
while ($walk -and $walk -ne 'TARGETDIR') {
    $walkChain.Add($walk)
    if ($directoryParents.ContainsKey($walk)) {
        $walk = $directoryParents[$walk]
    } else {
        break
    }
}
Write-Note ('shortcut directory chain: ' + ($walkChain -join ' -> ') + ' -> TARGETDIR')
if (-not ($walkChain -contains 'ProgramMenuFolder')) {
    Fail 'the shortcut does not live below ProgramMenuFolder (Start Menu proof failed)'
}
$menuDirRow = @($msiDirectories | Where-Object { $_ -like ($shortcutCells[1] + '|*') })
if (-not ($menuDirRow -join ' ' -like ('*' + $MenuGroup + '*'))) {
    Fail ('the Start Menu group is not "' + $MenuGroup + '"')
}
Write-Note ('Start Menu group: ' + $MenuGroup + ' (exactly one shortcut, no desktop shortcut)')
$desktopRows = @($msiDirectories | Where-Object { $_ -like '*DesktopFolder*' })
if ($desktopRows.Count -gt 0) {
    Fail 'the installer references a desktop folder: no desktop shortcut was requested'
}
$installDirRow = @($msiDirectories | Where-Object { $_.StartsWith('INSTALLDIR|') })
if ($installDirRow.Count -ne 1) {
    Fail 'the installer directory layout is not the expected single INSTALLDIR'
}
$installDirParent = @($installDirRow[0] -split '[|]')[1]
if ($installDirParent -ne 'LocalAppDataFolder') {
    Fail ('per-user installation must root at LocalAppDataFolder, found: ' + $installDirParent)
}
Write-Note 'install location roots at LocalAppDataFolder (per-user, no administrator rights)'
$generatedWxs = Join-Path $JpackageTempExe 'config/main.wxs'
if (Test-Path -LiteralPath $generatedWxs) {
    Copy-Item -LiteralPath $generatedWxs -Destination (Join-Path $DiagnosticsDir 'main.wxs') -Force
    Write-Note 'generated WiX source retained: target/stage9b/main.wxs'
}

# ---------------------------------------------------------------------------
# Step 14: temporary work cleanup and final worktree assertion
# ---------------------------------------------------------------------------
Write-Step 'Clean temporary work and assert the worktree is unchanged'
if (-not $KeepJpackageTemp) {
    Remove-WithinTarget $JpackageTempAppImage
    Remove-WithinTarget $JpackageTempExe
    Write-Note 'jpackage temp directories removed (-KeepJpackageTemp keeps them)'
} else {
    Write-Note 'jpackage temp directories retained (-KeepJpackageTemp)'
}
if (Test-Path -LiteralPath $RelocationRoot) {
    Remove-WithinTarget $RelocationRoot
}
$gitStatusAfter = (Invoke-Tool -Label 'git status --porcelain' -FilePath 'git' -Arguments @('-C', $RepoRoot, 'status', '--porcelain')).Output.Trim()
if ($gitStatusAfter) {
    Fail 'the build modified tracked files; packaging must only write below the ignored target/ directory.'
}
Write-Note 'worktree is still clean: the build modified no tracked file'

# ---------------------------------------------------------------------------
# Step 15: provenance summary
# ---------------------------------------------------------------------------
Write-Step 'Write the provenance summary'
$summary = New-Object 'System.Collections.Generic.List[string]'
$summary.Add('Product:                   ' + $ProductName)
$summary.Add('Windows package version:   ' + $AppVersion)
$summary.Add('Maven project version:     0.1.0-SNAPSHOT')
$summary.Add('Git SHA:                   ' + $gitSha)
$summary.Add('Git branch:                ' + $gitBranch)
$summary.Add('Git worktree:              clean')
$summary.Add('Stable profile:            ' + $StableProfile)
$summary.Add('Input delivery:            SCANCODE_BATCH, strictly opt-in (startup sends no input)')
$summary.Add('Packaging JDK:             Eclipse Temurin ' + $RequiredJdkMajor + ' (' + $javaOsArch + ')')
$summary.Add('                           java.runtime.version = ' + $javaRuntimeVersion)
$summary.Add('jpackage version:          ' + $jpackageVersion)
$summary.Add('WiX version:               ' + $wixVersion + ' (build-time only, never bundled)')
$summary.Add('FlatLaf version:           ' + $FlatLafVersion + ' (core artifact only)')
$summary.Add('Private runtime modules:   ' + $releaseModules)
$summary.Add('Windows upgrade UUID:      ' + $upgradeUuid + ' (permanent)')
$summary.Add('jpackage app-image:        ' + $JpackageAppImage)
$summary.Add('Installer:                 ' + $installerPath)
$summary.Add('Installer bytes:           ' + $installerBytes)
$summary.Add('Installer SHA-256:         ' + $installerSha)
$summary.Add('Install mode:              per-user (LocalAppDataFolder, no administrator rights)')
$summary.Add('Start Menu group:          ' + $MenuGroup + ' (exactly one shortcut, no desktop shortcut)')
$summary.Add('Code signing:              NOT SIGNED - code signing is deferred (no Authenticode material is present)')
[System.IO.File]::WriteAllLines((Join-Path $DiagnosticsDir 'summary.txt'), $summary)
Write-Diagnostics
Write-Host ''
Write-Host 'Stage 9B Windows installer complete.' -ForegroundColor Green
foreach ($line in $summary) {
    Write-Host ('  ' + $line)
}
Write-Host ''
Write-Host 'Install/uninstall validation uses the generated MSI with documented msiexec switches;' -ForegroundColor Yellow
Write-Host 'the interactive EXE wizard itself remains unexercised (Stage 9D qualification).' -ForegroundColor Yellow
Write-Host '  2. Launch "GTA Casino Fingerprint Solver" from the Start Menu (no console window).' -ForegroundColor Yellow
Write-Host '  3. Never press ARM during validation: startup must stay DISARMED with zero input.' -ForegroundColor Yellow
