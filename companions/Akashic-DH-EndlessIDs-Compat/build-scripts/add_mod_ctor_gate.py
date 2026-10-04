from pathlib import Path

p = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat\verify.ps1")
s = p.read_text(encoding="utf-8")
s = s.replace(
    "    (Join-Path $Root 'tests\\RuntimeActivationVerification.java')\n)",
    "    (Join-Path $Root 'tests\\RuntimeActivationVerification.java'),\n"
    "    (Join-Path $Root 'tests\\ModConstructionVerification.java')\n)"
)
s = s.replace(
    "$Mirai16 = 'C:\\Users\\User\\Desktop\\Codex\\Akashic-Mirai-0.1.16-dev-release",
    "& java -cp $RuntimeCp ModConstructionVerification\n"
    "if ($LASTEXITCODE -ne 0) { throw \"FML mod construction verification failed\" }\n\n"
    "$Mirai16 = 'C:\\Users\\User\\Desktop\\Codex\\Akashic-Mirai-0.1.16-dev-release"
)
p.write_text(s, encoding="utf-8")
