from pathlib import Path
import zipfile

root = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat")
out = root / "releases" / "Akashic-DH-EndlessIDs-Compat-1.0.1-fontes-testes.zip"
if out.exists():
    out.unlink()
items = [root / "src", root / "tests", root / "build.ps1", root / "verify.ps1", root / "README.md"]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for item in items:
        if item.is_dir():
            for p in item.rglob("*"):
                if p.is_file():
                    z.write(p, p.relative_to(root))
        else:
            z.write(item, item.relative_to(root))
print(out)
