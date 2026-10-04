import hashlib
import zipfile
from pathlib import Path

p = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat\releases\Akashic-DH-EndlessIDs-Compat-1.0.1.jar")
data = p.read_bytes()
print("sha256=" + hashlib.sha256(data).hexdigest().upper())
with zipfile.ZipFile(p) as z:
    print("entries=" + str(len(z.namelist())))
    print(z.read("META-INF/MANIFEST.MF").decode("utf-8"))
