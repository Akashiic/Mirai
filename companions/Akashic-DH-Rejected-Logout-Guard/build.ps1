$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ClasspathFile = Join-Path $Root 'build\compile-classpath.txt'
$ClassesDir = Join-Path $Root 'build\classes'
$TestClassesDir = Join-Path $Root 'build\test-classes'
$ReleaseDir = Join-Path $Root 'releases'
$Manifest = Join-Path $Root 'src\main\resources\META-INF\MANIFEST.MF'
$Resources = Join-Path $Root 'src\main\resources'
$OutputJar = Join-Path $ReleaseDir 'Akashic-DH-Rejected-Logout-Guard-1.0.0-serveronly.jar'
$TargetMiraiJar = 'C:\Users\User\Desktop\Codex\Akashic-Mirai-0.1.19-dev-diagnostic\Akashic-Horizons-of-The-Mirai-forge17-0.1.19-dev-1.7.10.jar'

if (-not (Test-Path $ClasspathFile)) { throw "Missing compile classpath: $ClasspathFile" }
if (-not (Test-Path $TargetMiraiJar)) { throw "Missing Mirai 0.1.19 target: $TargetMiraiJar" }

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
Remove-Item $TestClassesDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Force -Path $TestClassesDir | Out-Null
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null

$Sources = Get-ChildItem (Join-Path $Root 'src\main\java') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName }

& javac --release 8 -encoding UTF-8 -Xlint:all,-options,-path,-classfile -Werror -cp $CompileClasspath -d $ClassesDir $Sources
if ($LASTEXITCODE -ne 0) { throw "javac production failed with exit code $LASTEXITCODE" }

Remove-Item $OutputJar -Force -ErrorAction SilentlyContinue
& jar cfm $OutputJar $Manifest -C $ClassesDir . -C $Resources mcmod.info
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
$TestSources = Get-ChildItem (Join-Path $Root 'tests') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName }
$TestClasspath = "$CompileClasspath;$ClassesDir"

& javac --release 8 -encoding UTF-8 -Xlint:all,-options,-path,-classfile -Werror -cp $TestClasspath -d $TestClassesDir $TestSources
if ($LASTEXITCODE -ne 0) { throw "javac tests failed with exit code $LASTEXITCODE" }

$RuntimeClasspath = "$CompileClasspath;$ClassesDir;$TestClassesDir"
& java -ea -cp $RuntimeClasspath GuardLogicVerification
if ($LASTEXITCODE -ne 0) { throw "GuardLogicVerification failed" }

& java -ea -cp $RuntimeClasspath TransformerVerification $TargetMiraiJar
if ($LASTEXITCODE -ne 0) { throw "TransformerVerification failed" }

Write-Host "BUILT: $OutputJar"
Write-Host "TARGET-MIRAI-SHA256: $((Get-FileHash $TargetMiraiJar -Algorithm SHA256).Hash)"
Get-FileHash $OutputJar -Algorithm SHA256 | Format-List


