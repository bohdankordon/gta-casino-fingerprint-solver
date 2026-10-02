<#
.SYNOPSIS
    Builds and verifies the three public Stage 9C assets without GitHub API changes.
.EXAMPLE
    ./scripts/build-release-bundle.ps1 -ReleaseTag v0.9.0-beta.1 -PackagingJdk 'C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot' -WixBinDir C:\Dev\wix-3.14.1
.EXAMPLE
    ./scripts/build-release-bundle.ps1 -ReleaseTag v0.9.0-beta.1 -VerifyOnly
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReleaseTag,
    [string]$PackagingJdk = '',
    [string]$WixBinDir = '',
    [switch]$VerifyOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$BundleDir = Join-Path $RepoRoot 'target/release'
$version = & (Join-Path $PSScriptRoot 'resolve-release-version.ps1') -ReleaseTag $ReleaseTag | ConvertFrom-Json
$publicName = 'GTA-Casino-Fingerprint-Solver-' + $version.tag + '-windows-x64.exe'
$assetNames = @($publicName, 'SHA256SUMS.txt', 'release-provenance.txt')
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Invoke-Checked([string]$Tool, [string[]]$Arguments) {
    $output = & $Tool @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed ($LASTEXITCODE): $output" }
    return $output.Trim()
}
function Read-Field([string[]]$Lines, [string]$Name) {
    $matches = @($Lines | Where-Object { $_.StartsWith($Name + ':', [StringComparison]::Ordinal) })
    if ($matches.Count -ne 1) { throw "Expected exactly one provenance field: $Name" }
    return $matches[0].Substring($Name.Length + 1).Trim()
}
function Require-Equal([string]$Actual, [string]$Expected, [string]$Label) {
    if ($Actual -cne $Expected) { throw "${Label}: expected '$Expected', found '$Actual'." }
}
function Verify-Bundle {
    $entries = @(Get-ChildItem -LiteralPath $BundleDir -Force)
    if ($entries.Count -ne 3) { throw 'Release bundle must contain exactly three assets.' }
    foreach ($entry in $entries) {
        if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $assetNames -cnotcontains $entry.Name) { throw ('Unexpected release bundle entry: ' + $entry.Name) }
    }
    $lines = @([IO.File]::ReadAllLines((Join-Path $BundleDir 'SHA256SUMS.txt')))
    if ($lines.Count -ne 2) { throw 'SHA256SUMS must contain exactly two entries.' }
    $seen = @()
    foreach ($line in $lines) {
        if ($line -cnotmatch '\A([0-9a-f]{64})  (.+)\z') { throw 'Invalid SHA256SUMS format.' }
        $hash = $Matches[1]
        $name = $Matches[2]
        if (@($publicName, 'release-provenance.txt') -cnotcontains $name -or $seen -ccontains $name) {
            throw 'Unexpected or duplicate checksum filename.'
        }
        $seen += $name
        Require-Equal (Get-FileHash -LiteralPath (Join-Path $BundleDir $name) -Algorithm SHA256).Hash.ToLowerInvariant() $hash ('Checksum ' + $name)
    }
    $provenance = [IO.File]::ReadAllLines((Join-Path $BundleDir 'release-provenance.txt'), $utf8)
    Require-Equal (Read-Field $provenance 'Release tag') $version.tag 'Release tag'
    Require-Equal (Read-Field $provenance 'Semantic version') $version.semanticVersion 'Semantic version'
    Require-Equal (Read-Field $provenance 'Windows package version') $version.windowsPackageVersion 'Windows version'
    Require-Equal (Read-Field $provenance 'Prerelease') $version.isPrerelease.ToString().ToLowerInvariant() 'Prerelease'
    Require-Equal (Read-Field $provenance 'Installer public filename') $publicName 'Public filename'
    Require-Equal (Read-Field $provenance 'Installer bytes') (Get-Item -LiteralPath (Join-Path $BundleDir $publicName)).Length.ToString() 'Installer bytes'
    Require-Equal (Read-Field $provenance 'Installer SHA-256') (Get-FileHash -LiteralPath (Join-Path $BundleDir $publicName) -Algorithm SHA256).Hash.ToLowerInvariant() 'Installer SHA-256'
    Write-Host 'Release bundle verified: exact asset set, versions, sizes and SHA-256 checksums.'
}
if ($VerifyOnly) { Verify-Bundle; return }

if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem -or
    $env:PROCESSOR_ARCHITECTURE -notin @('AMD64', 'X64')) { throw 'Release packaging requires Windows x64.' }
Push-Location -LiteralPath $RepoRoot
try {
    $status = Invoke-Checked 'git' @('status', '--porcelain', '--untracked-files=all')
    if ($status) { throw "Release packaging requires a completely clean worktree: $status" }
    $gitSha = Invoke-Checked 'git' @('rev-parse', 'HEAD')
    $gitTree = Invoke-Checked 'git' @('rev-parse', 'HEAD^{tree}')
    $gitBranch = Invoke-Checked 'git' @('rev-parse', '--abbrev-ref', 'HEAD')
    if (-not $PackagingJdk -or -not $WixBinDir) { throw 'Specify -PackagingJdk and -WixBinDir explicitly; no toolchain is downloaded by this script.' }
    $javaSettings = Invoke-Checked (Join-Path $PackagingJdk 'bin/java.exe') @('-XshowSettings:properties', '-version')
    function Java-Setting([string]$Name) {
        $match = [regex]::Match($javaSettings, ('(?m)^\s*' + [regex]::Escape($Name) + ' = (.+)\r?$'))
        if (-not $match.Success) { throw "Missing Java setting: $Name" }
        return $match.Groups[1].Value.Trim()
    }
    foreach ($name in @('java.version', 'java.vendor', 'java.runtime.version', 'os.arch')) {
        Write-Host ($name + ': ' + (Java-Setting $name))
    }
    Require-Equal (Java-Setting 'java.version') '21.0.6' 'Release java.version'
    Require-Equal (Java-Setting 'java.vendor') 'Eclipse Adoptium' 'Release java.vendor'
    Require-Equal (Java-Setting 'java.runtime.version') '21.0.6+7-LTS' 'Release java.runtime.version'
    Require-Equal (Java-Setting 'os.arch') 'amd64' 'Release os.arch'
    $jpackageVersion = Invoke-Checked (Join-Path $PackagingJdk 'bin/jpackage.exe') @('--version')
    Require-Equal $jpackageVersion '21.0.6' 'Release jpackage version'
    foreach ($tool in @('candle.exe', 'light.exe')) {
        $banner = Invoke-Checked (Join-Path $WixBinDir $tool) @('-?')
        if ($banner -notmatch 'version 3[.]14[.]1[.]8722(?:\s|$)') {
            throw "Expected preinstalled WiX 3.14.1.8722 ($tool). Select that directory explicitly; do not install a substitute."
        }
    }
    $hostExe = (Get-Process -Id $PID).Path
    & $hostExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-windows-installer.ps1') -ProjectVersion $version.semanticVersion -AppVersion $version.windowsPackageVersion -PackagingJdk $PackagingJdk -WixBinDir $WixBinDir
    if ($LASTEXITCODE -ne 0) { throw 'Full Stage 9B installer build failed; no release bundle is produced.' }

    $buildInfo = [IO.File]::ReadAllLines((Join-Path $RepoRoot 'target/dist/app-image/BUILD-INFO.txt'))
    $summary = [IO.File]::ReadAllLines((Join-Path $RepoRoot 'target/stage9b/summary.txt'))
    Require-Equal (Read-Field $buildInfo 'Git SHA') $gitSha 'Stage 9A Git SHA'
    Require-Equal (Read-Field $buildInfo 'Project version') $version.semanticVersion 'Stage 9A project version'
    Require-Equal (Read-Field $summary 'Git SHA') $gitSha 'Stage 9B Git SHA'
    Require-Equal (Read-Field $summary 'Release/Maven project version') $version.semanticVersion 'Stage 9B project version'
    Require-Equal (Read-Field $summary 'Windows package version') $version.windowsPackageVersion 'Stage 9B Windows version'
    Require-Equal (Read-Field $summary 'jpackage version') $jpackageVersion 'Stage 9B jpackage'
    Require-Equal (Read-Field $summary 'WiX version') '3.14.1.8722 (build-time only, never bundled)' 'Stage 9B WiX'
    Require-Equal (Read-Field $summary 'FlatLaf version') '3.7.2 (core artifact only)' 'Stage 9B FlatLaf'
    Require-Equal (Read-Field $summary 'Windows upgrade UUID') '855C5415-0A97-4FEA-AE54-3B308FE8D1E4 (permanent)' 'Upgrade UUID'
    $tests = Read-Field $summary 'Maven tests'
    if ($tests -notmatch 'Tests run: ([0-9]+), Failures: 0, Errors: 0, Skipped: 0(?:\s|$)' -or [int]$Matches[1] -lt 915) {
        throw ('Maven test baseline regressed or tests failed/skipped: ' + $tests)
    }
    $testCount = [int]$Matches[1]
    $expectedSmokes = @{
        stage9a = @('A-bundled-java-version', 'B-application-classpath', 'C-opencv-health-check', 'D-monitor-listing', 'E-packaged-reference-data', 'F-bundled-java-home', 'G-relocation-opencv-health-check', 'H-relocation-monitor-listing', 'I-relocation-reference-data', 'J-relocation-launcher-from-outside-cwd')
        stage9b = @('A-jpackage-java-version', 'B-jpackage-java-home', 'C-operator-smoke', 'D-cli-monitor-listing', 'E-gui-launcher', 'F-relocation-operator-smoke')
    }
    foreach ($stage in $expectedSmokes.Keys) {
        foreach ($smoke in $expectedSmokes[$stage]) {
            $lines = [IO.File]::ReadAllLines((Join-Path $RepoRoot "target/$stage/smoke/$smoke.log"))
            Require-Equal (Read-Field $lines 'exit code') '0' ('Packaged smoke ' + $smoke)
        }
    }
    foreach ($payload in @('target/dist/app-image/app', 'target/stage9b/GTA Casino Fingerprint Solver/app')) {
        $jars = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot $payload) -Filter 'gta-casino-fingerprint-solver-*.jar' -File)
        if ($jars.Count -ne 1) { throw 'Expected exactly one project JAR in each image.' }
        Require-Equal $jars[0].Name ('gta-casino-fingerprint-solver-' + $version.semanticVersion + '.jar') 'Release JAR name'
        if (Get-ChildItem -LiteralPath (Join-Path $RepoRoot $payload) -Recurse -Force | Where-Object { $_.Name -like '*1080*' }) {
            throw 'Experimental 1080p content in release payload.'
        }
    }
    $installer = Read-Field $summary 'Installer'
    $installerHash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    $installerBytes = (Get-Item -LiteralPath $installer).Length
    Require-Equal $installerHash (Read-Field $summary 'Installer SHA-256') 'Stage 9B installer hash'
    Require-Equal $installerBytes.ToString() (Read-Field $summary 'Installer bytes') 'Stage 9B installer size'
    # Stage 9A Maven clean removes old bundles. Delete only this verified target child.
    $fullBundle = [IO.Path]::GetFullPath($BundleDir)
    $targetPrefix = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'target')) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullBundle.StartsWith($targetPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe release output path.' }
    if (Test-Path -LiteralPath $fullBundle) { Remove-Item -LiteralPath $fullBundle -Recurse -Force }
    New-Item -ItemType Directory -Path $fullBundle | Out-Null
    Copy-Item -LiteralPath $installer -Destination (Join-Path $BundleDir $publicName)
    Require-Equal (Get-FileHash -LiteralPath (Join-Path $BundleDir $publicName) -Algorithm SHA256).Hash.ToLowerInvariant() $installerHash 'Byte-identical installer copy'
    $provenance = @(
        'Product: GTA Casino Fingerprint Solver', ('Release tag: ' + $version.tag),
        ('Semantic version: ' + $version.semanticVersion), ('Windows package version: ' + $version.windowsPackageVersion),
        ('Prerelease: ' + $version.isPrerelease.ToString().ToLowerInvariant()),
        ('Git SHA: ' + $gitSha), ('Git tree SHA: ' + $gitTree), ('Git branch/ref: ' + $gitBranch + ' / ' + $env:GITHUB_REF),
        ('GitHub run ID: ' + $env:GITHUB_RUN_ID), ('GitHub run attempt: ' + $env:GITHUB_RUN_ATTEMPT),
        ('GitHub repository: ' + $(if ($env:GITHUB_REPOSITORY) { $env:GITHUB_REPOSITORY } else { Invoke-Checked 'git' @('remote', 'get-url', 'origin') })),
        'Runner OS: Windows', ('Runner image name: ' + $env:ImageOS), ('Runner image version: ' + $env:ImageVersion),
        ('Temurin version: ' + (Java-Setting 'java.runtime.version')), ('Selected Java os.arch: ' + (Java-Setting 'os.arch')),
        ('jpackage version: ' + (Read-Field $summary 'jpackage version')), ('WiX version: ' + (Read-Field $summary 'WiX version')),
        ('FlatLaf version: ' + (Read-Field $summary 'FlatLaf version')), ('Windows upgrade UUID: ' + (Read-Field $summary 'Windows upgrade UUID')),
        ('Maven test count: ' + $testCount), ('Maven test result: ' + $tests),
        ('Installer public filename: ' + $publicName), ('Installer bytes: ' + $installerBytes), ('Installer SHA-256: ' + $installerHash),
        'Installer copy: SHA-256 identical to authoritative Stage 9B installer', 'Packaged smokes: 10 Stage 9A + 6 Stage 9B passed; zero gameplay input',
        ('Stage 9B stable profile: ' + (Read-Field $summary 'Stable profile')), 'Stable profile: physical 2560x1440',
        ('Stage 9B input delivery: ' + (Read-Field $summary 'Input delivery')), 'Input delivery: SCANCODE_BATCH',
        'Startup: DISARMED / zero input', 'Code signing: NOT SIGNED',
        ('Stage 9B signing: ' + (Read-Field $summary 'Code signing')),
        ('Install mode: ' + (Read-Field $summary 'Install mode')), ('Start Menu group: ' + (Read-Field $summary 'Start Menu group'))
    )
    [IO.File]::WriteAllLines((Join-Path $BundleDir 'release-provenance.txt'), $provenance, $utf8)
    $checksums = @($publicName, 'release-provenance.txt') | ForEach-Object {
        (Get-FileHash -LiteralPath (Join-Path $BundleDir $_) -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_
    }
    [IO.File]::WriteAllLines((Join-Path $BundleDir 'SHA256SUMS.txt'), $checksums, $utf8)
    Verify-Bundle
    Require-Equal (Invoke-Checked 'git' @('rev-parse', 'HEAD')) $gitSha 'Final Git SHA'
    Require-Equal (Invoke-Checked 'git' @('rev-parse', 'HEAD^{tree}')) $gitTree 'Final Git tree'
    if (Invoke-Checked 'git' @('status', '--porcelain', '--untracked-files=all')) { throw 'Release build changed the worktree.' }
    Write-Host ('Release bundle complete: ' + $BundleDir)
} finally { Pop-Location }
