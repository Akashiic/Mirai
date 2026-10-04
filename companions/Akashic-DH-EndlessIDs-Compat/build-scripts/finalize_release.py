from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat")
release = root / "releases"
jar = release / "Akashic-DH-EndlessIDs-Compat-1.0.1.jar"
readme = root / "README.md"
release_readme = release / "README.md"
checksums = release / "SHA256SUMS.txt"
release_zip = release / "Akashic-DH-EndlessIDs-Compat-1.0.1-release.zip"
source_zip = release / "Akashic-DH-EndlessIDs-Compat-1.0.1-fontes-testes.zip"

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

shutil.copy2(readme, release_readme)
checksums.write_text(f"{sha256(jar)}  {jar.name}\n", encoding="ascii")

for target in (release_zip, source_zip):
    if target.exists():
        target.unlink()

with zipfile.ZipFile(release_zip, "w", zipfile.ZIP_DEFLATED) as z:
    for p in (jar, release_readme, checksums):
        z.write(p, p.name)

source_items = [root / "src", root / "tests", root / "build.ps1", root / "verify.ps1", readme]
with zipfile.ZipFile(source_zip, "w", zipfile.ZIP_DEFLATED) as z:
    for item in source_items:
        if item.is_dir():
            for p in item.rglob("*"):
                if p.is_file():
                    z.write(p, p.relative_to(root))
        else:
            z.write(item, item.relative_to(root))

print("JAR_SHA256=" + sha256(jar))
print("RELEASE_ZIP_SHA256=" + sha256(release_zip))
print("SOURCES_ZIP_SHA256=" + sha256(source_zip))
