from pathlib import Path

root = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat")
files = [
    root / "build.ps1",
    root / "verify.ps1",
    root / "README.md",
    root / "src/main/resources/mcmod.info",
    root / "src/main/resources/META-INF/MANIFEST.MF",
    root / "build/finalize_release.py",
    root / "build/package_sources.py",
    root / "build/inspect_jar.py",
]
for path in files:
    text = path.read_text(encoding="utf-8")
    path.write_text(text.replace("1.0.0", "1.0.1"), encoding="utf-8")
    print(path)
