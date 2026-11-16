# Build the NeoForge bridge mod: Rust native library + Java classes + resources,
# then deploy the jar to <ServerDir>/mods. Server dirs are isolated per MC version
# (default ../server-1.21.11; -ServerDir points at another isolated server dir).
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1 [-SkipRust] [-RustTargetDir <path>] [-ServerDir <path>] [-ClientJar "<jar>[;<jar>...]"]
#   -ClientJar 可传多个 jar（分号分隔）：客户端 mojmap 包 + lwjgl-glfw（键位/输入需要）。
#   不传则只编服务端源集，src/client/java 整体跳过。
#
# The jar carries META-INF/nebula-build.json: SHA256 of every Rust/Java source file
# used for this build. regress.py compares it with the working tree, so a stale jar
# is detected by CONTENT, not by file timestamps (which change on a no-op save).
# NOTE: keep this file pure ASCII. PowerShell 5.1 reads BOM-less .ps1 as GBK.
param(
    [switch]$SkipRust,
    [string]$RustTargetDir = "",
    [string]$ServerDir = "",
    [string]$ClientJar = "",
    [switch]$AllowServerOnly
)

$ErrorActionPreference = 'Stop'

# javac writes notes/warnings to stderr (e.g. "uses or overrides a deprecated API") even
# with -nowarn. Under $ErrorActionPreference='Stop' that native-command stderr becomes a
# terminating error, so the build used to abort AFTER a perfectly clean compile and the
# jar silently stayed stale. Relax the preference around the native call and trust
# $LASTEXITCODE; real compiler errors are echoed before throwing.
function Invoke-Javac {
    param(
        [string]$JavacPath,
        [string]$Destination,
        [string]$ClassPath,
        [object[]]$Sources,
        [string]$Label
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = $null
    $code = 1
    try {
        $output = & $JavacPath -encoding UTF-8 -proc:none -nowarn -d $Destination -classpath $ClassPath $Sources 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        foreach ($line in $output) { Write-Host $line }
        throw "javac ($Label) failed ($code)"
    }
    if ($output) {
        foreach ($line in $output) { Write-Host $line }
    }
}
$modDir = $PSScriptRoot
$javamcDir = Split-Path -Parent $modDir
$serverDir = if ($ServerDir) { $ServerDir } else { Join-Path $javamcDir 'server-1.21.11' }
$bridgeDir = Join-Path $javamcDir 'bridge'
# Each version has its own .jdk; discover it instead of pinning one patch level.
$jdkRoot = Join-Path $serverDir '.jdk'
$jdkHome = Get-ChildItem -LiteralPath $jdkRoot -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdkHome) { throw "No JDK found under $jdkRoot" }
$jdkBin = Join-Path $jdkHome.FullName 'bin'
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
# Client classes are NOT optional: the mod registers a custom entity renderer for the
# word-library plugin's container creature, so a jar without client classes crashes clients
# on the missing renderer. bridge/pack.ps1 used to call this script without -ClientJar
# and silently ship such a jar.
# Resolution order: -ClientJar  ->  NebulaLoadMod/client-jar.txt  ->  hard error.
$clientSrcDir = Join-Path $modDir 'src\client\java'
$clientJarList = $ClientJar
if (-not $clientJarList) {
    $clientJarFile = Join-Path $modDir 'client-jar.txt'
    if (Test-Path -LiteralPath $clientJarFile) {
        $clientJarList = ((Get-Content -LiteralPath $clientJarFile -Encoding UTF8) |
            Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') } |
            ForEach-Object { $_.Trim() }) -join ';'
    }
}
$hasClientSources = (Test-Path $clientSrcDir) -and
    ((Get-ChildItem $clientSrcDir -Recurse -Filter *.java -ErrorAction SilentlyContinue).Count -gt 0)
if ($hasClientSources -and -not $clientJarList -and -not $AllowServerOnly) {
    throw ("src/client/java has sources but no client jar was given. Pass -ClientJar `"<client-patched>;<lwjgl-glfw>`", " +
        "or write the two paths into NebulaLoadMod/client-jar.txt (one per line). " +
        "Server-only builds must opt in with -AllowServerOnly and must NOT be shipped to clients.")
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
        # cargo writes progress to stderr, and PS 5.1 with $ErrorActionPreference='Stop'
        # treats native stderr as a terminating error. Start-Process also chokes on this
        # host's duplicated HTTPS_PROXY/https_proxy env keys. Use a plain synchronous
        # Process with redirected streams and trust only ExitCode.
        $cargoExe = (Get-Command cargo -ErrorAction SilentlyContinue | Select-Object -First 1).Path
        if (-not $cargoExe) { $cargoExe = Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe' }
        if (-not (Test-Path -LiteralPath $cargoExe)) { throw "cargo not found: $cargoExe" }
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $cargoExe
        $psi.Arguments = 'build --release'
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.WorkingDirectory = $bridgeDir
        $cargoProc = [System.Diagnostics.Process]::Start($psi)
        $cargoOut = $cargoProc.StandardOutput.ReadToEnd()
        $cargoErr = $cargoProc.StandardError.ReadToEnd()
        $cargoProc.WaitForExit()
        if ($cargoOut) { Write-Host $cargoOut.TrimEnd() }
        if ($cargoErr) { Write-Host $cargoErr.TrimEnd() }
        if ($cargoProc.ExitCode -ne 0) {
            throw "cargo build failed ($($cargoProc.ExitCode))"
        }
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
Invoke-Javac -JavacPath (Join-Path $jdkBin 'javac.exe') -Destination $classesDir `
    -ClassPath $classpath -Sources $sources -Label 'main'

# 2a. Client-side sources (src/client/java), compiled only when -ClientJar points at a
#     mojmap client jar. The client classes render entities / blocks / GUI / particles;
#     they are merged into the same jar. Server-only builds (no -ClientJar) still work.
if ($clientJarList -and (Test-Path $clientSrcDir)) {
    foreach ($oneJar in ($clientJarList -split ';')) {
        if ($oneJar -and -not (Test-Path -LiteralPath $oneJar)) { throw "Client jar not found: $oneJar" }
    }
    $clientSources = Get-ChildItem $clientSrcDir -Recurse -Filter *.java |
        ForEach-Object { $_.FullName }
    if ($clientSources) {
        $clientClasspath = "$clientJarList;$classpath;$classesDir"
        Invoke-Javac -JavacPath (Join-Path $jdkBin 'javac.exe') -Destination $classesDir `
            -ClassPath $clientClasspath -Sources $clientSources -Label 'client'
        Write-Host "Client classes: $($clientSources.Count) files"
    }
}

# 2b. Offline doc site: NebulaLoadMod/docs -> classes/docs (markdown + html only).
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
# World method specs are the single source of truth; the dll must not bake them in.
$specsSrc = Join-Path (Join-Path $modDir 'src\main\resources') 'nebula\world-specs.json'
if (-not (Test-Path -LiteralPath $specsSrc)) {
    throw "Missing world specs: $specsSrc"
}
$specsHash = (Get-FileHash -LiteralPath $specsSrc -Algorithm SHA256).Hash
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

# 5. Registry contract fingerprint.
# The client must share the server's registry entries (entity types / blocks / items /
# creative tabs). Those are the ONLY things that force a client jar swap; everything else
# (word libs, item definitions, textures) is pushed at runtime. Hash the sources that
# declare them, print whether it moved since the last build, and store it in the jar
# manifest so the answer is auditable instead of guessed.
# NOTE: add every new DeferredRegister holder file here. A missing file means the script
# happily prints "unchanged" while the registry really did change.
$registryFiles = @(
    'src\main\java\nebula\neoforge\content\NebulaEntities.java',
    'src\main\java\nebula\neoforge\content\NebulaBlocks.java',
    'src\main\java\nebula\neoforge\content\NebulaItems.java',
    'src\main\java\nebula\neoforge\content\NebulaCreativeTab.java'
)
$registryParts = @()
foreach ($rel in $registryFiles) {
    $full = Join-Path $modDir $rel
    if (Test-Path -LiteralPath $full) {
        $registryParts += "$rel=$((Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash)"
    }
}
$registrySha = ''
if ($registryParts.Count -gt 0) {
    $regHasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $regBytes = [System.Text.Encoding]::UTF8.GetBytes(($registryParts -join "`n"))
        $registrySha = -join ($regHasher.ComputeHash($regBytes) | ForEach-Object { $_.ToString('x2') })
    } finally {
        $regHasher.Dispose()
    }
}
$stampPath = Join-Path $modDir 'build\.registry-stamp'
$previousRegistry = ''
if (Test-Path -LiteralPath $stampPath) {
    $previousRegistry = (Get-Content -LiteralPath $stampPath -Raw).Trim()
}
if ($registrySha) {
    if ($previousRegistry -eq $registrySha) {
        Write-Host "Registry: unchanged ($($registrySha.Substring(0, 12))) - client jar NOT required"
    } else {
        Write-Host "Registry: CHANGED ($($registrySha.Substring(0, 12))) - both sides need the same jar"
        New-Item -ItemType Directory -Path (Split-Path -Parent $stampPath) -Force | Out-Null
        [System.IO.File]::WriteAllText($stampPath, $registrySha, (New-Object System.Text.UTF8Encoding($false)))
    }
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
    worldSpecs        = $specsHash
    fingerprintSource = 'nebula_jni.manifest.json'
    embeddedSources   = $embeddedSources
    registry          = $registrySha
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
# Generated by NebulaLoadMod/tools/gen_events.py: the NeoForge event catalog used by EventBridge.
$eventsSrc = Join-Path $resDir 'nebula\events.json'
if (-not (Test-Path -LiteralPath $eventsSrc)) {
    throw "Missing event catalog: $eventsSrc (run python NebulaLoadMod/tools/gen_events.py)"
}
New-Item -ItemType Directory -Path (Join-Path $classesDir 'nebula') -Force | Out-Null
Copy-Item $eventsSrc (Join-Path $classesDir 'nebula\events.json') -Force
Copy-Item $specsSrc (Join-Path $classesDir 'nebula\world-specs.json') -Force
# Client assets (lang files / block + item models). Without them, names show as raw translation keys.
$assetsSrc = Join-Path $resDir 'assets'
if (Test-Path -LiteralPath $assetsSrc) {
    Copy-Item -LiteralPath $assetsSrc -Destination $classesDir -Recurse -Force
    Write-Host "Assets: $((Get-ChildItem -LiteralPath $assetsSrc -Recurse -File).Count) files"
}
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
    throw "docs/index.html in jar does not match NebulaLoadMod/docs ($docsIndexHash vs $docsSrcHash)"
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
