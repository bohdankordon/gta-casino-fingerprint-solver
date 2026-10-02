<#
.SYNOPSIS
    Read-only, fail-closed inspection of the actual Stage 9B.1 MSI UI tables.
.DESCRIPTION
    Never calls InstallProduct, msiexec or an installer. -SelfTest also corrupts
    in-memory copies of the inspected rows to prove that regressions are rejected.
    Evidence is JSON with named columns, not decompiler guesses or source grep.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MsiPath,
    [Parameter(Mandatory = $true)][string]$ApplicationVersion,
    [Parameter(Mandatory = $true)][string]$EvidencePath,
    [switch]$SelfTest
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Release-Com($Object) {
    if ($null -ne $Object) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($Object) }
}

function Read-MsiTables([string]$Path) {
    $columns = [ordered]@{
        Dialog = @('Dialog', 'Attributes')
        Control = @('Dialog_', 'Control', 'Type', 'X', 'Y', 'Width', 'Height', 'Attributes', 'Property', 'Text')
        ControlEvent = @('Dialog_', 'Control_', 'Event', 'Argument', 'Condition', 'Ordering')
        ControlCondition = @('Dialog_', 'Control_', 'Action', 'Condition')
        CheckBox = @('Property', 'Value')
        EventMapping = @('Dialog_', 'Control_', 'Event', 'Attribute')
        Property = @('Property', 'Value')
        CustomAction = @('Action', 'Type', 'Source', 'Target')
        InstallUISequence = @('Action', 'Condition', 'Sequence')
        InstallExecuteSequence = @('Action', 'Condition', 'Sequence')
        AdminUISequence = @('Action', 'Condition', 'Sequence')
        AdminExecuteSequence = @('Action', 'Condition', 'Sequence')
        ActionText = @('Action', 'Description', 'Template')
        File = @('File', 'Component_', 'FileName')
        Component = @('Component', 'Directory_', 'Attributes')
        Directory = @('Directory', 'Directory_Parent', 'DefaultDir')
        Shortcut = @('Shortcut', 'Directory_', 'Name', 'Target')
        Upgrade = @('UpgradeCode', 'VersionMin', 'VersionMax', 'Attributes', 'ActionProperty')
    }
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $null
    $summary = $null
    $tables = @{}
    try {
        $database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($Path, 0))
        $summary = $database.GetType().InvokeMember('SummaryInformation', 'GetProperty', $null, $database, @(0))
        $tables['SummaryWordCount'] = [int]$summary.GetType().InvokeMember('Property', 'GetProperty', $null, $summary, @(15))
        foreach ($name in $columns.Keys) {
            $view = $null
            $rows = New-Object 'System.Collections.Generic.List[object]'
            try {
                $sql = 'SELECT ' + (($columns[$name] | ForEach-Object { '`' + $_ + '`' }) -join ', ') + ' FROM `' + $name + '`'
                $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @($sql))
                [void]$view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)
                while ($true) {
                    $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
                    if ($null -eq $record) { break }
                    try {
                        $row = [ordered]@{}
                        for ($i = 0; $i -lt $columns[$name].Count; $i++) {
                            $row[$columns[$name][$i]] = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @($i + 1))
                        }
                        $rows.Add([pscustomobject]$row)
                    } finally { Release-Com $record }
                }
                [void]$view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null)
            } catch { throw ('MSI table read failed (' + $name + '): ' + $_) }
            finally { Release-Com $view }
            $tables[$name] = @($rows.ToArray())
        }
    } finally {
        Release-Com $summary
        Release-Com $database
        Release-Com $installer
    }
    return $tables
}

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw ('MSI UI contract: ' + $Message) }
}

function Assert-MsiUi($Tables, [string]$Version) {
    $properties = @{}
    foreach ($row in $Tables.Property) { $properties[$row.Property] = $row.Value }
    Require ($properties['WIXUI_INSTALLDIR'] -ceq 'INSTALLDIR') 'directory chooser must target INSTALLDIR'
    Require ($properties['SOLVER_APPLICATION_VERSION'] -ceq $Version) 'full application version must match the packaged JAR version'
    Require ($properties['WixUI_Mode'] -ceq 'InstallDir') 'standard WixUI_InstallDir must be linked'
    Require ($properties['WIXUI_EXITDIALOGOPTIONALCHECKBOX'] -ceq '1') 'launch checkbox must default checked'
    Require ($properties['WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT'] -ceq 'Launch GTA Casino Fingerprint Solver') 'launch label missing'
    Require ($properties['WIXUI_EXITDIALOGOPTIONALTEXT'] -like '*was installed successfully*DISARMED*') 'success/startup text missing'
    Require (-not $properties.ContainsKey('ALLUSERS')) 'per-user package must not set ALLUSERS'
    Require (($Tables.SummaryWordCount -band 8) -eq 8) 'MSI must not require elevated privileges'
    $installDirectory = @($Tables.Directory | Where-Object Directory -ceq 'INSTALLDIR')
    Require ($installDirectory.Count -eq 1 -and $installDirectory[0].Directory_Parent -ceq 'LocalAppDataFolder' -and ($installDirectory[0].DefaultDir -split '[|]')[-1] -ceq 'GTA Casino Fingerprint Solver') 'default must remain LocalAppData/product name'
    foreach ($dialog in @('WelcomeDlg', 'InstallDirDlg', 'VerifyReadyDlg', 'ProgressDlg', 'ExitDialog')) {
        Require (@($Tables.Dialog | Where-Object { $_.Dialog -ceq $dialog }).Count -eq 1) ('missing dialog ' + $dialog)
    }
    $folder = @($Tables.Control | Where-Object { $_.Dialog_ -ceq 'InstallDirDlg' -and $_.Control -ceq 'Folder' })
    Require ($folder.Count -eq 1 -and $folder[0].Type -ceq 'PathEdit' -and $folder[0].Property -ceq 'WIXUI_INSTALLDIR' -and ([int]$folder[0].Attributes -band 8) -eq 8) 'indirect INSTALLDIR path edit missing'
    $events = $Tables.ControlEvent
    foreach ($edge in @(
        @('WelcomeDlg', 'Next', 'NewDialog', 'InstallDirDlg', 'NOT Installed', '6'),
        @('InstallDirDlg', 'Back', 'NewDialog', 'WelcomeDlg', 'NOT Installed', '6'),
        @('InstallDirDlg', 'Next', 'SetTargetPath', '[WIXUI_INSTALLDIR]', '1', '1'),
        @('InstallDirDlg', 'Next', 'NewDialog', 'VerifyReadyDlg', 'INSTALLDIR_VALID="1"', '5'),
        @('InstallDirDlg', 'ChangeFolder', 'SpawnDialog', 'BrowseDlg', '1', '2'),
        @('VerifyReadyDlg', 'Back', 'NewDialog', 'InstallDirDlg', 'NOT Installed', '1'),
        @('VerifyReadyDlg', 'InstallNoShield', 'EndDialog', 'Return', 'OutOfDiskSpace <> 1', '1'),
        @('ExitDialog', 'Finish', 'EndDialog', 'Return', '1', '999')
    )) {
        Require (@($events | Where-Object { $_.Dialog_ -ceq $edge[0] -and $_.Control_ -ceq $edge[1] -and $_.Event -ceq $edge[2] -and $_.Argument -ceq $edge[3] -and $_.Condition -ceq $edge[4] -and $_.Ordering -ceq $edge[5] }).Count -eq 1) ('navigation edge missing: ' + ($edge -join ' / '))
    }
    $welcome = @($Tables.Control | Where-Object { $_.Dialog_ -ceq 'WelcomeDlg' -and $_.Control -ceq 'Description' })
    Require ($welcome.Count -eq 1 -and $welcome[0].Text -like '*[[]SOLVER_APPLICATION_VERSION[]]*Temurin 21*OpenCV*Start Menu*No separate Java*' -and $welcome[0].Height -ceq '140') 'custom welcome text/space missing'
    $ready = @($Tables.Control | Where-Object { $_.Dialog_ -ceq 'VerifyReadyDlg' -and $_.Control -ceq 'InstallText' })
    Require ($ready.Count -eq 1 -and $ready[0].Text -like '*Version: [[]SOLVER_APPLICATION_VERSION[]]*current user*Java runtime: included*OpenCV/native dependencies: included*Start Menu shortcut: included*Additional downloads: none*' -and $ready[0].Height -ceq '140') 'custom package summary missing'
    $progress = @($Tables.Control | Where-Object { $_.Dialog_ -ceq 'ProgressDlg' -and $_.Control -ceq 'ProgressBar' })
    Require ($progress.Count -eq 1 -and $progress[0].Type -ceq 'ProgressBar' -and ([int]$progress[0].Attributes -band 1) -eq 1) 'visible real progress bar missing'
    foreach ($mapping in @(@('ProgressBar', 'SetProgress', 'Progress'), @('ActionText', 'ActionText', 'Text'))) {
        Require (@($Tables.EventMapping | Where-Object { $_.Dialog_ -ceq 'ProgressDlg' -and $_.Control_ -ceq $mapping[0] -and $_.Event -ceq $mapping[1] -and $_.Attribute -ceq $mapping[2] }).Count -eq 1) 'real MSI progress/status subscription missing'
    }
    foreach ($action in @('InstallFiles', 'CreateShortcuts', 'RegisterProduct')) {
        Require (@($Tables.ActionText | Where-Object { $_.Action -ceq $action }).Count -eq 1) ('MSI ActionText missing: ' + $action)
    }
    Require (@($Tables.InstallUISequence | Where-Object { $_.Action -ceq 'WelcomeDlg' -and $_.Condition -ceq 'NOT Installed OR PATCH' -and $_.Sequence -ceq '1298' }).Count -eq 1) 'welcome sequence missing'
    Require (@($Tables.InstallUISequence | Where-Object { $_.Action -ceq 'ProgressDlg' -and $_.Sequence -ceq '1299' }).Count -eq 1) 'real progress sequence missing'
    Require (@($Tables.InstallUISequence | Where-Object { $_.Action -ceq 'ExecuteAction' -and [int]$_.Sequence -gt 1299 }).Count -eq 1) 'progress must precede execution'
    $exits = @($Tables.InstallUISequence | Where-Object { $_.Action -ceq 'ExitDialog' })
    Require ($exits.Count -eq 1 -and $exits[0].Sequence -ceq '-1' -and -not $exits[0].Condition) 'ExitDialog must be success-only'
    Require (@($events | Where-Object { $_.Argument -ceq 'ExitDialog' }).Count -eq 0) 'no navigation may bypass success-only ExitDialog'
    $checkbox = @($Tables.Control | Where-Object { $_.Dialog_ -ceq 'ExitDialog' -and $_.Control -ceq 'OptionalCheckBox' })
    Require ($checkbox.Count -eq 1 -and $checkbox[0].Type -ceq 'CheckBox' -and $checkbox[0].Property -ceq 'WIXUI_EXITDIALOGOPTIONALCHECKBOX' -and ([int]$checkbox[0].Attributes -band 1) -eq 0) 'standard initially hidden launch checkbox missing'
    Require (@($Tables.CheckBox | Where-Object { $_.Property -ceq 'WIXUI_EXITDIALOGOPTIONALCHECKBOX' -and $_.Value -ceq '1' }).Count -eq 1) 'checkbox selected value must be 1'
    $shows = @($Tables.ControlCondition | Where-Object { $_.Dialog_ -ceq 'ExitDialog' -and $_.Control_ -ceq 'OptionalCheckBox' -and $_.Action -ceq 'Show' })
    Require ($shows.Count -eq 1 -and $shows[0].Condition -ceq 'WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT AND NOT Installed') 'maintenance must not offer launch'
    $launch = @($Tables.CustomAction | Where-Object { $_.Action -ceq 'SolverLaunchApplication' })
    # 34 (Directory EXE) + 64 (ignore return) + 128 (async). Neither InScript
    # (1024) nor NoImpersonate (2048): immediate client/user context.
    Require ($launch.Count -eq 1 -and $launch[0].Type -ceq '226' -and $launch[0].Source -ceq 'INSTALLDIR' -and $launch[0].Target -ceq '"[INSTALLDIR]GTA Casino Fingerprint Solver.exe"') 'launch must be the exact impersonated immediate directory EXE action'
    $exeActions = @($Tables.CustomAction | Where-Object { ([int]$_.Type -band 63) -in @(2, 18, 34, 50) })
    Require ($exeActions.Count -eq 1 -and $exeActions[0].Action -ceq 'SolverLaunchApplication') 'unexpected executable custom action'
    $launchEvents = @($events | Where-Object { $_.Argument -ceq 'SolverLaunchApplication' })
    $condition = 'WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 AND UILevel = 5 AND ACTION = "INSTALL" AND NOT Installed AND NOT REMOVE AND NOT UPGRADINGPRODUCTCODE AND NOT MsiRunningElevated'
    Require ($launchEvents.Count -eq 1 -and $launchEvents[0].Dialog_ -ceq 'ExitDialog' -and $launchEvents[0].Control_ -ceq 'Finish' -and $launchEvents[0].Event -ceq 'DoAction' -and $launchEvents[0].Condition -ceq $condition -and $launchEvents[0].Ordering -ceq '1') 'launch must be opt-in on successful interactive non-elevated Finish only'
    foreach ($sequence in @('InstallExecuteSequence', 'InstallUISequence', 'AdminExecuteSequence', 'AdminUISequence')) {
        Require (@($Tables[$sequence] | Where-Object { $_.Action -ceq 'SolverLaunchApplication' }).Count -eq 0) ('launch must not be scheduled in ' + $sequence)
    }
    $launcherFiles = @($Tables.File | Where-Object { ($_.FileName -split '[|]')[-1] -ceq 'GTA Casino Fingerprint Solver.exe' })
    Require ($launcherFiles.Count -eq 1) 'exact installed launcher must exist'
    Require (@($Tables.Component | Where-Object { $_.Component -ceq $launcherFiles[0].Component_ -and $_.Directory_ -ceq 'INSTALLDIR' }).Count -eq 1) 'launch target must be installed directly in INSTALLDIR'
    Require (@($Tables.Control | Where-Object { $_.Text -match '(?i)downloading (Java|OpenCV)' }).Count -eq 0) 'fake dependency download claim'
}

$resolvedMsi = (Resolve-Path -LiteralPath $MsiPath).Path
$tables = Read-MsiTables $resolvedMsi
# Write evidence before assertions so a failed gate remains diagnosable.
$tables | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
Assert-MsiUi $tables $ApplicationVersion
Write-Output 'MSI UI contract: PASS (actual read-only MSI tables)'

if ($SelfTest) {
    # Each corruption is independent and stays in memory; the MSI is read-only.
    $mutations = [ordered]@{
        MissingWelcome = { param($t) $t.Dialog = @($t.Dialog | Where-Object { $_.Dialog -cne 'WelcomeDlg' }) }
        WrongInstallDir = { param($t) ($t.Property | Where-Object Property -ceq 'WIXUI_INSTALLDIR').Value = 'ProgramFilesFolder' }
        WrongVersion = { param($t) ($t.Property | Where-Object Property -ceq 'SOLVER_APPLICATION_VERSION').Value = '0.8.30002' }
        MissingSummary = { param($t) ($t.Control | Where-Object { $_.Dialog_ -ceq 'VerifyReadyDlg' -and $_.Control -ceq 'InstallText' }).Text = 'Install now' }
        FakeProgress = { param($t) ($t.Control | Where-Object { $_.Dialog_ -ceq 'ProgressDlg' -and $_.Control -ceq 'ProgressBar' }).Type = 'Text' }
        MissingProgressEvents = { param($t) $t.EventMapping = @() }
        FailureExit = { param($t) ($t.InstallUISequence | Where-Object Action -ceq 'ExitDialog').Sequence = '-3' }
        UnconditionalLaunch = { param($t) ($t.ControlEvent | Where-Object Argument -ceq 'SolverLaunchApplication').Condition = '1' }
        LaunchOnCancel = { param($t) ($t.ControlEvent | Where-Object Argument -ceq 'SolverLaunchApplication').Control_ = 'Cancel' }
        ElevatedLaunch = { param($t) ($t.CustomAction | Where-Object Action -ceq 'SolverLaunchApplication').Type = '3298' }
        RandomFileLaunch = { param($t) ($t.CustomAction | Where-Object Action -ceq 'SolverLaunchApplication').Source = 'fileRandom' }
        ExecuteLaunch = { param($t) $t.InstallExecuteSequence += [pscustomobject]@{ Action = 'SolverLaunchApplication'; Condition = '1'; Sequence = '6601' } }
        MaintenanceLaunch = { param($t) ($t.ControlCondition | Where-Object { $_.Dialog_ -ceq 'ExitDialog' -and $_.Control_ -ceq 'OptionalCheckBox' -and $_.Action -ceq 'Show' }).Condition = '1' }
        LaunchOutsideInstallDir = { param($t) $f = $t.File | Where-Object { ($_.FileName -split '[|]')[-1] -ceq 'GTA Casino Fingerprint Solver.exe' }; ($t.Component | Where-Object Component -ceq $f.Component_).Directory_ = 'ProgramFilesFolder' }
        MachineWide = { param($t) $t.Property += [pscustomobject]@{ Property = 'ALLUSERS'; Value = '1' } }
        RequiresElevation = { param($t) $t.SummaryWordCount = $t.SummaryWordCount -band (-bnot 8) }
    }
    $json = $tables | ConvertTo-Json -Depth 8
    foreach ($name in $mutations.Keys) {
        $copy = @{}
        $object = $json | ConvertFrom-Json
        foreach ($property in $object.PSObject.Properties) { $copy[$property.Name] = $property.Value }
        & $mutations[$name] $copy
        $rejected = $false
        try { Assert-MsiUi $copy $ApplicationVersion } catch {
            if ($_.Exception.Message -notlike 'MSI UI contract:*') { throw }
            $rejected = $true
        }
        Require $rejected ('negative test failed to reject ' + $name)
    }
    Write-Output ('MSI UI negative tests: PASS (' + $mutations.Count + ' in-memory corruptions rejected)')
}
