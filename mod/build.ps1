# Build the NeoForge bridge mod: Rust native library + Java classes + resources,
# then deploy the jar to ../server/mods (mod/ and server/ are siblings under javamc/).
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1 [-SkipRust] [-RustTargetDir <path>]
#
# The jar carries META-INF/nebula-build.json: SHA256 of every Rust/Java source file
# used for this build. regress.py compares it with the working tree, so a stale jar
# is detected by CONTENT, not by file timestamps (which change on a no-op save).
# NOTE: keep this file pure ASCII. PowerShell 5.1 reads BOM-less .ps1 as GBK.
param(
    [switch]$SkipRust,
    [string]$RustTargetDir = ""
)

$ErrorActionPreference = 'Stop'
$modDir = $PSScriptRoot
$javamcDir = Split-Path -Parent $modDir
$serverDir = Join-Path $javamcDir 'server'
$bridgeDir = Join-Path $javamcDir 'bridge'
$jdkBin = Join-Path $serverDir '.jdk\jdk-21.0.13+9\bin'
# Version has ONE source of truth: the mod metadata NeoForge itself reads. The jar name
# and the docs/version.txt shipped to <gameDir>/nebula/_docs are both derived from it,
# so bumping the mod version cannot leave a stale copy in either place.
$modsToml = Join-Path $modDir 'src\main\resources\META-INF\neoforge.mods.toml'
$versionLine = [regex]::Match(
    (Get-Content -LiteralPath $modsToml -Raw -Encoding UTF8), '(?m)^version="([^"]*)"')
if (-not $versionLine.Success) { throw "Cannot read version= from $modsToml" }
$version = $versionLine.Groups[1].Value
if (-not $version -or $version.Contains('$')) {
    throw "Unsupported mod version in ${modsToml}: $version"
}
$buildDir = Join-Path $modDir 'build'
$classesDir = Join-Path $buildDir 'classes'
$jarName = "nebula-neoforge-bridge-$version.jar"

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-SourceFingerprint {
    param([string]$Root, [string]$Pattern)
    $map = [ordered]@{}
    $files = Get-ChildItem -LiteralPath $Root -Recurse -File -Filter $Pattern |
        Sort-Object -Property FullName
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($Root.Length).TrimStart('\').Replace('\', '/')
        $map[$rel] = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash
    }
    return $map
}

function Get-ZipEntryHash {
    param($Zip, [string]$Name)
    $entry = $Zip.GetEntry($Name)
    if ($null -eq $entry) { return $null }
    $stream = $entry.Open()
    try {
        return (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash
    } finally {
        $stream.Dispose()
    }
}

function Get-EmbeddedSources {
    param([string]$DllPath)
    # build.rs burned the source fingerprint into .rodata at compile time.
    # Reading it back from the dll makes the dll itself the evidence, not a sidecar file.
    $bytes = [System.IO.File]::ReadAllBytes($DllPath)
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)
    $match = [regex]::Match($text, 'NEBULA_SOURCES=([^\x00]+)')
    if (-not $match.Success) {
        throw "No embedded source fingerprint in $DllPath (build with the current build.rs)"
    }
    return $match.Groups[1].Value
}

if (-not (Test-Path (Join-Path $jdkBin 'javac.exe'))) {
    throw "JDK not found: $jdkBin"
}

# 1. Rust native library
if (-not $SkipRust) {
    Push-Location $bridgeDir
    try {
        if ($RustTargetDir) { $env:CARGO_TARGET_DIR = $RustTargetDir }
        cargo build --release
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}
$targetRoot = if ($RustTargetDir) { $RustTargetDir } else { Join-Path $bridgeDir 'target' }
$nativeSrc = Join-Path $targetRoot 'release\nebula_jni.dll'
if (-not (Test-Path $nativeSrc)) {
    throw "Native library not found: $nativeSrc (run without -SkipRust)"
}
$srcHash = (Get-FileHash -LiteralPath $nativeSrc -Algorithm SHA256).Hash
$embeddedSources = Get-EmbeddedSources -DllPath $nativeSrc

# 2. Java compile
Remove-Item $classesDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
$sources = Get-ChildItem (Join-Path $modDir 'src\main\java') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
# -slim / -unpacked jars carry SRG method names; keep only officially mapped jars.
$libraries = Get-ChildItem (Join-Path $serverDir 'libraries') -Recurse -Filter *.jar |
    Where-Object { $_.Name -notmatch '-(slim|unpacked)\.jar$' } |
    ForEach-Object { $_.FullName }
$classpath = $libraries -join ';'
& (Join-Path $jdkBin 'javac.exe') -encoding UTF-8 -proc:none -nowarn -d $classesDir -classpath $classpath $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

# 2b. Offline doc site: mod/docs -> classes/docs (markdown + html only).
# At runtime the mod copies this tree to <gameDir>/nebula/_docs, a folder the engine
# skips (leading underscore), so docs are never compiled as a word library.
# .stamp is a content hash of the tree; the mod compares it on start and only
# rebuilds the folder when the jar actually carries different docs.
$docsSrc = Join-Path $modDir 'docs'
$docsOut = Join-Path $classesDir 'docs'
New-Item -ItemType Directory -Path $docsOut -Force | Out-Null
$docsMap = [ordered]@{}
$docsFiles = Get-ChildItem -LiteralPath $docsSrc -Recurse -File |
    Where-Object { $_.Extension -eq '.md' -or $_.Extension -eq '.html' } |
    Sort-Object -Property FullName
foreach ($f in $docsFiles) {
    $rel = $f.FullName.Substring($docsSrc.Length).TrimStart('\').Replace('\', '/')
    $dest = Join-Path $docsOut ($rel.Replace('/', '\'))
    New-Item -ItemType Directory -Path (Split-Path -Parent $dest) -Force | Out-Null
    Copy-Item -LiteralPath $f.FullName -Destination $dest -Force
    $docsMap[$rel] = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash
}
if ($docsMap.Count -eq 0) { throw "No docs found under $docsSrc" }
# version.txt records which mod version produced this copy of the site.
# It is folded into the stamp below: a version bump alone (same markdown, same html)
# must still rebuild <gameDir>/nebula/_docs, otherwise the file would go stale silently.
[System.IO.File]::WriteAllText(
    (Join-Path $docsOut 'version.txt'), "$version`n",
    (New-Object System.Text.UTF8Encoding($false)))
$stampLines = @($docsMap.Keys | ForEach-Object { "$_=$($docsMap[$_])" })
$stampLines += "version.txt=$version"
[Array]::Sort($stampLines, [System.StringComparer]::Ordinal)
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $docHash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes(($stampLines -join "`n")))
} finally {
    $sha.Dispose()
}
$docsStamp = ($docHash | ForEach-Object { $_.ToString('x2') }) -join ''
[System.IO.File]::WriteAllText(
    (Join-Path $docsOut '.stamp'), $docsStamp, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Docs: $($docsMap.Count) files + version.txt ($version), stamp $($docsStamp.Substring(0, 12))"

# 3. Source fingerprints -> native manifest + jar manifest.
# A FULL build writes nebula_jni.manifest.json next to the dll: a first-hand record
# of which Rust tree produced THAT dll, bound to the dll SHA256.
# -SkipRust reads only this file, never the previous jar's self-report, so a dll
# swapped in from elsewhere cannot inherit a believable fingerprint.
$javaMap = Get-SourceFingerprint -Root (Join-Path $modDir 'src\main\java') -Pattern '*.java'
$currentRustMap = Get-SourceFingerprint -Root (Join-Path $bridgeDir 'src') -Pattern '*.rs'
$currentCargoHash = (Get-FileHash -LiteralPath (Join-Path $bridgeDir 'Cargo.toml') -Algorithm SHA256).Hash
$nativeManifestPath = Join-Path $targetRoot 'release\nebula_jni.manifest.json'

if (-not $SkipRust) {
    $nativeManifest = [ordered]@{
        builtAt         = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
        dllSha256       = $srcHash
        cargoToml       = $currentCargoHash
        rust            = $currentRustMap
        embeddedSources = $embeddedSources
    }
    [System.IO.File]::WriteAllText(
        $nativeManifestPath,
        ($nativeManifest | ConvertTo-Json -Depth 6),
        (New-Object System.Text.UTF8Encoding($false)))
    $rustMap = $currentRustMap
    $cargoHash = $currentCargoHash
} else {
    if (-not (Test-Path $nativeManifestPath)) {
        throw "Native manifest not found: $nativeManifestPath (run a full build without -SkipRust)"
    }
    $native = Get-Content -LiteralPath $nativeManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($native.dllSha256 -ne $srcHash) {
        throw "Native manifest dllSha256 does not match $nativeSrc (dll replaced or rebuilt outside build.ps1). Run a full build."
    }
    if ($native.embeddedSources -ne $embeddedSources) {
        throw "Embedded source fingerprint in $nativeSrc does not match the native manifest (dll was replaced). Run a full build."
    }
    $rustMap = $native.rust
    $cargoHash = $native.cargoToml
    Write-Host "Native fingerprint read from $nativeManifestPath"
}

$manifest = [ordered]@{
    builtAt           = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    version           = $version
    skipRust          = [bool]$SkipRust
    dllSha256         = $srcHash
    cargoToml         = $cargoHash
    rust              = $rustMap
    java              = $javaMap
    docs              = $docsMap
    docsStamp         = $docsStamp
    fingerprintSource = 'nebula_jni.manifest.json'
    embeddedSources   = $embeddedSources
}
New-Item -ItemType Directory -Path (Join-Path $classesDir 'META-INF') -Force | Out-Null
$manifestPath = Join-Path $classesDir 'META-INF\nebula-build.json'
$manifestJson = $manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText(
    $manifestPath, $manifestJson, (New-Object System.Text.UTF8Encoding($false)))

# 4. Resources and native library
$resDir = Join-Path $modDir 'src\main\resources'
New-Item -ItemType Directory -Path (Join-Path $classesDir 'META-INF') -Force | Out-Null
Copy-Item (Join-Path $resDir 'META-INF\neoforge.mods.toml') (Join-Path $classesDir 'META-INF\neoforge.mods.toml') -Force
# Generated by mod/tools/gen_events.py: the NeoForge event catalog used by EventBridge.
$eventsSrc = Join-Path $resDir 'nebula\events.json'
if (-not (Test-Path -LiteralPath $eventsSrc)) {
    throw "Missing event catalog: $eventsSrc (run python mod/tools/gen_events.py)"
}
New-Item -ItemType Directory -Path (Join-Path $classesDir 'nebula') -Force | Out-Null
Copy-Item $eventsSrc (Join-Path $classesDir 'nebula\events.json') -Force
$nativeOut = Join-Path $classesDir 'META-INF\native\windows-x86_64'
New-Item -ItemType Directory -Path $nativeOut -Force | Out-Null
Copy-Item $nativeSrc (Join-Path $nativeOut 'nebula_jni.dll') -Force

# 5. Package and deploy
$jarPath = Join-Path $buildDir $jarName
Remove-Item $jarPath -Force -ErrorAction SilentlyContinue
& (Join-Path $jdkBin 'jar.exe') --create --file $jarPath -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }
New-Item -ItemType Directory -Path (Join-Path $serverDir 'mods') -Force | Out-Null
Copy-Item $jarPath (Join-Path $serverDir 'mods') -Force

# 6. Verify the deployed jar really carries what we just built.
# "Deployed" only proves the copy succeeded; a stale dll or stale docs would still
# look fine, so compare content hashes of the entries against the sources.
$entryName = 'META-INF/native/windows-x86_64/nebula_jni.dll'
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $innerHash = Get-ZipEntryHash $zip $entryName
    if ($null -eq $innerHash) { throw "Missing native library in jar: $entryName" }
    $docsIndexHash = Get-ZipEntryHash $zip 'docs/index.html'
    if ($null -eq $docsIndexHash) { throw "Missing docs/index.html in jar" }
    $stampEntry = $zip.GetEntry('docs/.stamp')
    if ($null -eq $stampEntry) { throw "Missing docs/.stamp in jar" }
    $reader = New-Object System.IO.StreamReader($stampEntry.Open())
    try {
        $jarStamp = $reader.ReadToEnd().Trim()
    } finally {
        $reader.Dispose()
    }
    $versionEntry = $zip.GetEntry('docs/version.txt')
    if ($null -eq $versionEntry) { throw "Missing docs/version.txt in jar" }
    $versionReader = New-Object System.IO.StreamReader($versionEntry.Open())
    try {
        $jarVersion = $versionReader.ReadToEnd().Trim()
    } finally {
        $versionReader.Dispose()
    }
} finally {
    $zip.Dispose()
}
if ($innerHash -ne $srcHash) {
    throw "Native library SHA256 mismatch between jar and $nativeSrc ($innerHash vs $srcHash)"
}
$docsSrcHash = (Get-FileHash -LiteralPath (Join-Path $docsSrc 'index.html') -Algorithm SHA256).Hash
if ($docsIndexHash -ne $docsSrcHash) {
    throw "docs/index.html in jar does not match mod/docs ($docsIndexHash vs $docsSrcHash)"
}
if ($jarStamp -ne $docsStamp) {
    throw "docs stamp in jar does not match the built stamp ($jarStamp vs $docsStamp)"
}
if ($jarVersion -ne $version) {
    throw "docs/version.txt in jar says '$jarVersion', expected '$version'"
}
Write-Host "Verified native library SHA256 $innerHash"
Write-Host "Verified docs/index.html SHA256 $docsIndexHash (stamp $docsStamp, version $jarVersion)"
Write-Host "Build manifest: META-INF/nebula-build.json"

Write-Host "Built $jarPath"
Write-Host "Deployed to $(Join-Path $serverDir 'mods')"
