<#
.SYNOPSIS
    Resolves the Stage 9C public tag policy to JSON; performs no Git or API operations.
.EXAMPLE
    ./scripts/resolve-release-version.ps1 -ReleaseTag v0.9.0-beta.1
.EXAMPLE
    ./scripts/resolve-release-version.ps1 -SelfTest
#>
[CmdletBinding(DefaultParameterSetName = 'Resolve')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Resolve')]
    [AllowEmptyString()][string]$ReleaseTag,
    [Parameter(Mandatory = $true, ParameterSetName = 'Test')][switch]$SelfTest
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-Version([string]$Tag) {
    # ASCII digits, case-sensitive channels, absolute anchors (even a final newline fails).
    $pattern = '\Av(?<major>0|[1-9][0-9]{0,2})\.(?<minor>0|[1-9][0-9]{0,2})\.(?<patch>0|[1-9][0-9]{0,3})(?:-(?<channel>alpha|beta|rc)\.(?<number>[1-9][0-9]{0,3}))?\z'
    $match = [regex]::Match($Tag, $pattern)
    if (-not $match.Success) { throw "Invalid release tag: '$Tag'. See docs/releases.md." }
    $major = [int]$match.Groups['major'].Value
    $minor = [int]$match.Groups['minor'].Value
    $patch = [int]$match.Groups['patch'].Value
    if ($major -gt 255 -or $minor -gt 255) { throw 'Release major/minor must be 0..255.' }
    $channel = $match.Groups['channel'].Value
    $prerelease = $channel -ne ''
    $number = $null
    $windows = "$major.$minor.$patch"
    if ($prerelease) {
        if ($patch -ne 0) { throw 'Prerelease target patch must be 0.' }
        if ($major -eq 0 -and $minor -eq 0) { throw 'v0.0.0 has no prerelease predecessor slot.' }
        $number = [int]$match.Groups['number'].Value
        $offset = @{ alpha = 10000; beta = 30000; rc = 50000 }[$channel]
        $build = $offset + $number
        if ($minor -gt 0) { $windows = "$major.$($minor - 1).$build" }
        else { $windows = "$($major - 1).255.$build" }
    }
    return [pscustomobject][ordered]@{
        tag = $Tag
        semanticVersion = $Tag.Substring(1)
        windowsPackageVersion = $windows
        isPrerelease = $prerelease
        channel = $channel
        prereleaseNumber = $number
    }
}

if ($SelfTest) {
    $valid = @(
        @('v0.9.0-beta.1', '0.8.30001'), @('v0.9.0-beta.2', '0.8.30002'),
        @('v1.0.0-rc.1', '0.255.50001'), @('v1.2.0-alpha.7', '1.1.10007'),
        @('v1.2.0-beta.7', '1.1.30007'), @('v1.2.0-rc.7', '1.1.50007'),
        @('v1.2.0', '1.2.0'), @('v1.2.3', '1.2.3'), @('v0.0.0', '0.0.0'),
        @('v255.255.9999', '255.255.9999'), @('v0.1.0-alpha.1', '0.0.10001'),
        @('v255.255.0-alpha.9999', '255.254.19999'),
        @('v255.255.0-beta.9999', '255.254.39999'),
        @('v255.255.0-rc.9999', '255.254.59999')
    )
    $invalid = @(
        'v0.0.0-beta.1', 'v1.2.3-beta.1', 'v256.0.0', 'v1.256.0', 'v1.2.10000',
        'v1.2.0-beta.0', 'v1.2.0-preview.1', '1.2.0', 'v01.2.0', 'v1.02.0',
        'v1.2.0+metadata', 'garbage', '', ' v1.2.0', "v1.2.0`n", 'v1.2.00',
        'v1.2.0-beta.01', 'v1.2.0-beta.10000', 'v1.2.0-BETA.1', 'V1.2.0',
        'v1.2.0-rc.1+build', 'v1.2.0.1', 'v999999999999999.0.0', 'v1.2.0-beta.-1',
        'v1.2.0-beta.1suffix', 'v-1.2.0', 'v1.2.0-alpha.0', 'v1.2.0-rc.0'
    )
    foreach ($row in $valid) {
        $result = Resolve-Version $row[0]
        if ($result.windowsPackageVersion -cne $row[1] -or
            $result.semanticVersion -cne $row[0].Substring(1) -or $result.tag -cne $row[0]) {
            throw ('Resolver self-test failed: ' + $row[0])
        }
        $expectedPrerelease = $row[0].Contains('-')
        if ($result.isPrerelease -ne $expectedPrerelease) { throw 'Incorrect prerelease flag.' }
        if ($expectedPrerelease) {
            $parts = $row[0].Split('-')[1].Split('.')
            if ($result.channel -cne $parts[0] -or $result.prereleaseNumber -ne [int]$parts[1]) {
                throw 'Incorrect prerelease channel/number.'
            }
        } elseif ($result.channel -cne '' -or $null -ne $result.prereleaseNumber) {
            throw 'Stable tag has prerelease metadata.'
        }
    }
    foreach ($tag in $invalid) {
        $rejected = $false
        try { Resolve-Version $tag | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw "Invalid tag was accepted: '$tag'" }
    }
    # Windows upgrade order across all channel boundaries and a major rollover.
    foreach ($sequence in @(
        @('v1.1.9999', 'v1.2.0-alpha.1', 'v1.2.0-alpha.9999', 'v1.2.0-beta.1',
          'v1.2.0-beta.9999', 'v1.2.0-rc.1', 'v1.2.0-rc.9999', 'v1.2.0', 'v1.2.1'),
        @('v0.255.9999', 'v1.0.0-alpha.1', 'v1.0.0-beta.1', 'v1.0.0-rc.9999', 'v1.0.0')
    )) {
        $previous = [version]'0.0.0'
        foreach ($tag in $sequence) {
            $current = [version](Resolve-Version $tag).windowsPackageVersion
            if ($current -le $previous) { throw "Windows upgrade ordering failed: $tag" }
            $previous = $current
        }
    }
    Write-Host "Release resolver self-test passed: $($valid.Count) valid, $($invalid.Count) invalid, 2 ordering tables."
} else {
    Resolve-Version $ReleaseTag | ConvertTo-Json -Compress
}
