# One-key pack for javamc.
#   STEP 1: relink the JNI dll against the CURRENT engine crate.
#   STEP 2: rebuild + deploy the jar, then verify by content fingerprint.
#
# Why this script exists:
#   build.ps1 -SkipRust trusts target/release/nebula_jni.manifest.json, and the
#   fingerprint chain only covers bridge/src + Cargo.toml. The engine crate is a
#   path dependency OUTSIDE this repo, so it is not covered: after an engine update
#   a -SkipRust build silently ships the old dll while every check stays green.
#   This script ALWAYS relinks first and NEVER passes -SkipRust to build.ps1.
#   build.ps1 re-runs cargo build internally as a fast no-op; that is intentional,
#   because that run regenerates the native manifest bound to the new dll hash.
#
# Usage: powershell -ExecutionPolicy Bypass -File bridge/pack.ps1
#        [-Regression]          run the full isolated-server regression instead of --verify-only
#        [-NoVerify]            skip the post-build verification entirely
#        [-Force]               bypass the running-server guard and the stale-dll guard
#        [-RustTargetDir <path>] use a custom cargo target directory (passed to build.ps1 too)
#        [-ServerDir <path>]     target MC version's server dir (default javamc/server)
#
# NOTE: keep this file pure ASCII. PowerShell 5.1 reads BOM-less .ps1 as GBK.
param(
    [switch]$Regression,
    [switch]$NoVerify,
    [switch]$Force,
    [string]$RustTargetDir = "",
    [string]$ServerDir = ""
)

$ErrorActionPreference = 'Stop'

$bridgeDir = $PSScriptRoot
$javamcDir = Split-Path -Parent $bridgeDir
$buildPs1 = Join-Path $javamcDir 'NebulaLoadMod\build.ps1'
$cargoToml = Join-Path $bridgeDir 'Cargo.toml'
$regressPy = Join-Path $javamcDir 'NebulaLoadMod\selftest\regress.py'

# Honour an inherited CARGO_TARGET_DIR so this script and build.ps1 agree on paths.
if (-not $RustTargetDir -and $env:CARGO_TARGET_DIR) { $RustTargetDir = $env:CARGO_TARGET_DIR }
$targetRoot = if ($RustTargetDir) { $RustTargetDir } else { Join-Path $bridgeDir 'target' }
$releaseDir = Join-Path $targetRoot 'release'
$dllPath = Join-Path $releaseDir 'nebula_jni.dll'
$engineRecord = Join-Path $releaseDir 'nebula_engine.json'

function Get-FileSha {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Get-TreeFingerprint {
    param([string]$Root)
    $root = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    $files = @()
    foreach ($name in @('Cargo.toml', 'build.rs')) {
        $p = Join-Path $root $name
        if (Test-Path -LiteralPath $p) { $files += Get-Item -LiteralPath $p }
    }
    $srcDir = Join-Path $root 'src'
    if (Test-Path -LiteralPath $srcDir) {
        $files += @(Get-ChildItem -LiteralPath $srcDir -Recurse -File -Filter '*.rs')
    }
    if ($files.Count -eq 0) { throw "No source files found under $root" }
    $lines = @()
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($root.Length).TrimStart('\').Replace('\', '/')
        $lines += "$rel=$((Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash)"
    }
    [Array]::Sort($lines, [System.StringComparer]::Ordinal)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('X2') }) -join '')
    } finally {
        $sha.Dispose()
    }
}

# ---- preflight -------------------------------------------------------------
foreach ($p in @($buildPs1, $cargoToml, $regressPy)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "Missing required file: $p" }
}

# The engine path comes from Cargo.toml, never from a hardcoded guess.
$cargoText = Get-Content -LiteralPath $cargoToml -Raw -Encoding UTF8
$match = [regex]::Match($cargoText, '(?m)^\s*nebula\s*=\s*\{\s*path\s*=\s*"([^"]+)"')
if (-not $match.Success) { throw "Cannot read the nebula path dependency from $cargoToml" }
$engineRoot = [System.IO.Path]::GetFullPath((Join-Path $bridgeDir $match.Groups[1].Value))
if (-not (Test-Path -LiteralPath $engineRoot)) { throw "Engine crate not found: $engineRoot" }
$engineHash = Get-TreeFingerprint -Root $engineRoot

$prev = $null
if (Test-Path -LiteralPath $engineRecord) {
    try { $prev = (Get-Content -LiteralPath $engineRecord -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { $prev = $null }
}
$engineChanged = $false
if ($prev -and $prev.engineSha256 -eq $engineHash) {
    Write-Host "[engine] fingerprint unchanged $($engineHash.Substring(0, 12))..."
} elseif ($prev) {
    $engineChanged = $true
    Write-Host "[engine] fingerprint CHANGED $($prev.engineSha256.Substring(0, 12))... -> $($engineHash.Substring(0, 12))... (dll must be relinked)"
} else {
    Write-Host "[engine] fingerprint $($engineHash.Substring(0, 12))... (first recorded pack)"
}
Write-Host "[engine] $engineRoot"

# Overwriting server-1.21.11/mods while a server is up corrupts ModLauncher's lazy class
# loading (NoClassDefFoundError). Block that unless explicitly overridden.
if (-not $Force) {
    $servers = @(Get-CimInstance Win32_Process -Filter "name='java.exe'" |
        Where-Object { $_.CommandLine -match 'win_args\.txt|@libraries[\\/]net[\\/]neoforged' })
    if ($servers.Count -gt 0) {
        $pids = ($servers | ForEach-Object { $_.ProcessId }) -join ', '
        throw "A Minecraft server process is running (PID $pids). Stop it first, or use -Force if this is a false positive."
    }
}

# ---- STEP 1: update the dll ------------------------------------------------
Write-Host "=== [1/2] Update dll: cargo build --release ==="
$dllBefore = Get-FileSha $dllPath
Push-Location $bridgeDir
try {
    if ($RustTargetDir) { $env:CARGO_TARGET_DIR = $RustTargetDir }
    & cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build --release failed ($LASTEXITCODE). The jar was NOT built." }
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $dllPath)) { throw "Native library missing after build: $dllPath" }
$dllAfter = Get-FileSha $dllPath
if ($dllBefore -and $dllBefore -eq $dllAfter) {
    Write-Host "[dll] up to date $($dllAfter.Substring(0, 12))..."
    if ($engineChanged -and -not $Force) {
        # The dll may already have been relinked by a full build.ps1 run, which
        # does not update nebula_engine.json. Only block when the dll still
        # matches the one recorded for the OLD engine fingerprint.
        $recordedDll = if ($prev) { $prev.dllSha256 } else { $null }
        if (-not $recordedDll -or $recordedDll -eq $dllAfter) {
            throw "Engine sources changed but the dll still matches the recorded one. Cargo may have reused a stale artifact; run 'cargo clean -p nebula' and retry (or -Force to override)."
        }
        Write-Host "[engine] engine changed, but dll was relinked since the last record ($($recordedDll.Substring(0, 12))... -> $($dllAfter.Substring(0, 12))...); continuing"
    }
} else {
    $beforeLabel = if ($dllBefore) { $dllBefore.Substring(0, 12) } else { '(none)' }
    Write-Host "[dll] updated $beforeLabel... -> $($dllAfter.Substring(0, 12))..."
}

# ---- STEP 2: build + deploy the jar ---------------------------------------
# Deliberately NOT -SkipRust: build.ps1 must rewrite nebula_jni.manifest.json so
# the native fingerprint stays bound to the dll we just produced.
Write-Host "=== [2/2] Build jar + deploy (full build.ps1, never -SkipRust) ==="
$buildArgs = @{}
if ($RustTargetDir) { $buildArgs['RustTargetDir'] = $RustTargetDir }
if ($ServerDir) { $buildArgs['ServerDir'] = $ServerDir }
& $buildPs1 @buildArgs

$finalDll = Get-FileSha $dllPath
if (-not $finalDll) { throw "Native library disappeared after build.ps1: $dllPath" }

# The engine is outside the jar fingerprint chain, so record the binding here:
# which engine tree produced which dll, as first-hand evidence for the next pack.
$record = [ordered]@{
    builtAt      = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    engineRoot   = $engineRoot
    engineSha256 = $engineHash
    dllSha256    = $finalDll
}
[System.IO.File]::WriteAllText(
    $engineRecord,
    ($record | ConvertTo-Json -Depth 4),
    (New-Object System.Text.UTF8Encoding($false)))
Write-Host "[engine] recorded $engineRecord"

# ---- verify ----------------------------------------------------------------
if (-not $NoVerify) {
    Write-Host "=== Verify: content fingerprint (and regression if -Regression) ==="
    Push-Location $javamcDir
    try {
        $regressArgs = @()
        if ($ServerDir) { $regressArgs += @('--server-dir', $ServerDir) }
        if ($Regression) {
            & python $regressPy @regressArgs
        } else {
            & python $regressPy --verify-only @regressArgs
        }
        if ($LASTEXITCODE -ne 0) { throw "regress.py failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

Write-Host "=== Pack complete: dll $($finalDll.Substring(0, 12))... -> jar deployed to server-1.21.11/mods ==="
