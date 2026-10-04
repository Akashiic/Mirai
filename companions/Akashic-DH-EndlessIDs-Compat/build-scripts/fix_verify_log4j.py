from pathlib import Path

p = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat\verify.ps1")
s = p.read_text(encoding="utf-8")
s = s.replace("log4j-api-2\\.0-beta9\\.jar$", "log4j-api-2\\.23\\.1\\.jar$")
s = s.replace("log4j-core-2\\.0-beta9\\.jar$", "log4j-core-2\\.23\\.1\\.jar$")
p.write_text(s, encoding="utf-8")
