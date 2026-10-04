$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ClasspathFile = Join-Path $Root 'build\compile-classpath.txt'
$ClassesDir = Join-Path $Root 'build\classes'
$ReleaseDir = Join-Path $Root 'releases'
$Manifest = Join-Path $Root 'src\main\resources\META-INF\MANIFEST.MF'
$Resources = Join-Path $Root 'src\main\resources'
$OutputJar = Join-Path $ReleaseDir 'Akashic-DH-EndlessIDs-Compat-1.0.1.jar'

if (-not (Test-Path $ClasspathFile)) {
    throw "Missing compile classpath: $ClasspathFile"
}

$Entries = (Get-Content $ClasspathFile -Raw) -split ';'
function Find-Dependency([string]$Pattern) {
    $match = $Entries | Where-Object { $_ -match $Pattern } | Select-Object -First 1
    if (-not $match) { throw "Dependency not found: $Pattern" }
    return $match
}

$Dependencies = @(
    (Find-Dependency 'forge-1\.7\.10-MinecraftForge-FG2-merged.*\.jar$'),
    (Find-Dependency 'launchwrapper-1\.12\.jar$'),
    (Find-Dependency 'asm-all-5\.0\.3\.jar$'),
    (Find-Dependency 'log4j-api-2\.0-beta9\.jar$')
)
$CompileClasspath = [string]::Join(';', $Dependencies)

Remove-Item $ClassesDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null

$Sources = Get-ChildItem (Join-Path $Root 'src\main\java') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName }

& javac --release 8 -encoding UTF-8 -cp $CompileClasspath -d $ClassesDir $Sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Remove-Item $OutputJar -Force -ErrorAction SilentlyContinue
& jar cfm $OutputJar $Manifest -C $ClassesDir . -C $Resources mcmod.info
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host "BUILT: $OutputJar"
Get-FileHash $OutputJar -Algorithm SHA256 | Format-List
