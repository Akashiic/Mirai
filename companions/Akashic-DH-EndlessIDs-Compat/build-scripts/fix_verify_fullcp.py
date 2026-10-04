from pathlib import Path

p = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat\verify.ps1")
s = p.read_text(encoding="utf-8")
old = '''$TestClasses = Join-Path $Root 'build\\test-classes'
$CompileCp = [string]::Join(';', @($PatchJar, $Asm, $Launch))
$RuntimeCp = [string]::Join(';', @($TestClasses, $PatchJar, $Asm, $Launch, $LogApi, $LogCore))
'''
new = '''$TestClasses = Join-Path $Root 'build\\test-classes'
$FullForgeCp = Get-Content (Join-Path $Root 'build\\compile-classpath.txt') -Raw
$CompileCp = "$PatchJar;$FullForgeCp"
$RuntimeCp = "$TestClasses;$CompileCp"
'''
assert old in s, "verify classpath block not found"
p.write_text(s.replace(old, new), encoding="utf-8")
