#Requires -Version 5.1
<#
.SYNOPSIS
  Bumps app + version.json, builds release APK, commits and pushes (optional skips).

.PARAMETER SkipBuild
  Skip gradlew assembleRelease --rerun-tasks

.PARAMETER SkipGit
  Skip git add / commit / push
#>
param(
    [switch] $SkipBuild,
    [switch] $SkipGit
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $RepoRoot

$DefaultApkUrl = 'https://github.com/Hoot1980/DirtheadIPTV/releases/latest/download/DirtheadIPTV.apk'

function Escape-JsonString([string] $Value) {
    if ($null -eq $Value) { return '' }
    return $Value.Replace('\', '\\').Replace('"', '\"')
}

function Write-VersionJson(
    [int] $VersionCode,
    [string] $VersionName,
    [string] $ApkUrl,
    [string] $Changelog
) {
    $path = Join-Path $RepoRoot 'version.json'
    $apkEsc = Escape-JsonString $ApkUrl
    $nameEsc = Escape-JsonString $VersionName
    $logEsc = Escape-JsonString $Changelog
    $content = @"
{
  "versionCode": $VersionCode,
  "versionName": "$nameEsc",
  "apkUrl": "$apkEsc",
  "changelog": "$logEsc"
}
"@
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($path, $content.TrimEnd() + "`n", $utf8NoBom)
}

# --- 1–3: bump app/build.gradle.kts ---
$gradlePath = Join-Path $RepoRoot 'app\build.gradle.kts'
if (-not (Test-Path $gradlePath)) { throw "Missing $gradlePath" }

$gradle = [System.IO.File]::ReadAllText($gradlePath)

if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') {
    throw 'Could not parse versionCode in app/build.gradle.kts'
}
$oldCode = [int] $Matches[1]
$newCode = $oldCode + 1

if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') {
    throw 'Could not parse versionName in app/build.gradle.kts'
}
$oldName = $Matches[1]
$culture = [Globalization.CultureInfo]::InvariantCulture
$newName = ([double]::Parse($oldName, $culture) + 0.1).ToString('0.0', $culture)

$gradleNew = [regex]::Replace($gradle, 'versionCode\s*=\s*\d+', "versionCode = $newCode", 1)
$gradleNew = [regex]::Replace($gradleNew, 'versionName\s*=\s*"[^"]*"', "versionName = `"$newName`"", 1)

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($gradlePath, $gradleNew.TrimEnd() + "`n", $utf8NoBom)

Write-Host "Bumped build.gradle.kts: versionCode $oldCode -> $newCode, versionName `"$oldName`" -> `"$newName`""

# --- 4–9: version.json (preserve apkUrl) ---
$versionJsonPath = Join-Path $RepoRoot 'version.json'
if (Test-Path $versionJsonPath) {
    $jsonRaw = [System.IO.File]::ReadAllText($versionJsonPath)
    if ($jsonRaw -match '"apkUrl"\s*:\s*"([^"]*)"') {
        $apkUrl = $Matches[1]
    }
    else {
        throw 'version.json exists but apkUrl could not be read; add "apkUrl": "..."'
    }
}
else {
    $apkUrl = $DefaultApkUrl
    Write-Warning "version.json missing; created apkUrl default. Edit version.json if needed."
}

Write-VersionJson -VersionCode $newCode -VersionName $newName -ApkUrl $apkUrl -Changelog 'Update'
Write-Host "Updated version.json (changelog = Update; apkUrl unchanged)."

# --- 10: release build ---
if (-not $SkipBuild) {
    $gradlew = Join-Path $RepoRoot 'gradlew.bat'
    if (-not (Test-Path $gradlew)) {
        $gradlew = Join-Path $RepoRoot 'gradlew'
    }
    if (-not (Test-Path $gradlew)) { throw 'gradlew / gradlew.bat not found at repo root' }
    & $gradlew assembleRelease --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease --rerun-tasks failed with exit code $LASTEXITCODE" }
    Write-Host 'assembleRelease --rerun-tasks completed.'
}
else {
    Write-Host 'Skipped assembleRelease --rerun-tasks (-SkipBuild).'
}

# --- 11–13: git ---
if (-not $SkipGit) {
    if (-not (Test-Path (Join-Path $RepoRoot '.git'))) {
        throw 'No .git directory; initialize git and add a remote before running, or use -SkipGit.'
    }
    git -C $RepoRoot add .
    git -C $RepoRoot commit -m "Update app"
    if ($LASTEXITCODE -ne 0) {
        throw 'git commit failed (nothing to commit or hook failure).'
    }
    git -C $RepoRoot push
    if ($LASTEXITCODE -ne 0) {
        throw 'git push failed; check remote and auth.'
    }
    Write-Host 'git add / commit / push completed.'
}
else {
    Write-Host 'Skipped git (-SkipGit).'
}

Write-Host 'Update-app workflow finished.'
