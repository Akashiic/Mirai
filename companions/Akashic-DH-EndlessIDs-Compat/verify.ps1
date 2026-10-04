$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Entries = (Get-Content (Join-Path $Root 'build\compile-classpath.txt') -Raw) -split ';'
function Find-Dependency([string]$Pattern) {
    $match = $Entries | Where-Object { $_ -match $Pattern } | Select-Object -First 1
    if (-not $match) { throw "Dependency not found: $Pattern" }
    return $match
}

$PatchJar = Join-Path $Root 'releases\Akashic-DH-EndlessIDs-Compat-1.0.1.jar'
$Asm = Find-Dependency 'asm-all-5\.0\.3\.jar$'
$Launch = Find-Dependency 'launchwrapper-1\.12\.jar$'
$LogApi = Find-Dependency 'log4j-api-2\.23\.1\.jar$'
$LogCore = Find-Dependency 'log4j-core-2\.23\.1\.jar$'
$TestClasses = Join-Path $Root 'build\test-classes'
$FullForgeCp = Get-Content (Join-Path $Root 'build\compile-classpath.txt') -Raw
$CompileCp = "$PatchJar;$FullForgeCp"
$RuntimeCp = "$TestClasses;$CompileCp"

Remove-Item $TestClasses -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $TestClasses | Out-Null

$Tests = @(
    (Join-Path $Root 'tests\PatchVerification.java'),
    (Join-Path $Root 'tests\RuntimeActivationVerification.java'),
    (Join-Path $Root 'tests\ModConstructionVerification.java')
)
& javac --release 8 -proc:none -encoding UTF-8 -cp $CompileCp -d $TestClasses $Tests
if ($LASTEXITCODE -ne 0) { throw "test javac failed with exit code $LASTEXITCODE" }

& java -cp $RuntimeCp ModConstructionVerification
if ($LASTEXITCODE -ne 0) { throw "FML mod construction verification failed" }

$Mirai16 = 'C:\Users\User\Desktop\Codex\Akashic-Mirai-0.1.16-dev-release\Akashic-Horizons-of-The-Mirai-forge17-0.1.16-dev-1.7.10.jar'
$Mirai17 = 'C:\Users\User\Desktop\Codex\Akashic-Mirai-0.1.17-dev-release\Akashic-Horizons-of-The-Mirai-forge17-0.1.17-dev-1.7.10.jar'

& java -cp $RuntimeCp PatchVerification $Mirai16 $Mirai17
if ($LASTEXITCODE -ne 0) { throw "patch verification failed with exit code $LASTEXITCODE" }

& java -cp $RuntimeCp RuntimeActivationVerification $Mirai16
if ($LASTEXITCODE -ne 0) { throw "runtime activation verification failed for 0.1.16" }
& java -cp $RuntimeCp RuntimeActivationVerification $Mirai17
if ($LASTEXITCODE -ne 0) { throw "runtime activation verification failed for 0.1.17" }

$Listing = & jar tf $PatchJar
if ($Listing -notcontains 'com/akashic/compat/dhendlessids/DHEndlessIDsCompatTransformer.class') {
    throw 'Transformer class missing from jar'
}
if ($Listing -notcontains 'mcmod.info') { throw 'mcmod.info missing from jar' }
if ($Listing -notcontains 'META-INF/MANIFEST.MF') { throw 'manifest missing from jar' }

Write-Host 'JAR_STRUCTURE_OK'
Get-FileHash $PatchJar -Algorithm SHA256 | Format-List
