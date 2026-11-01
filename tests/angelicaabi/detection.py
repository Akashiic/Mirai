#!/usr/bin/env python3
"""Gate: every Distant Horizons class name Angelica probes for must exist in the Mirai jar.

Angelica (Iris) enables its DH integration only if Class.forName("com.seibel.distanthorizons.…") succeeds
(Iris.onRenderSystemInit, DHCompat). This reads those string constants from the real Angelica jar's class
files and checks the names against the Mirai jar, so a rename on either side fails offline.
"""
import argparse
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit import ClassFile  # noqa: E402

PREFIX = "com.seibel.distanthorizons."


def probed_names(angelica_jar):
    names = {}
    with zipfile.ZipFile(angelica_jar) as archive:
        for entry in archive.namelist():
            if not (entry.endswith(".class") and entry.startswith("net/coderbot/iris/")):
                continue
            cf = ClassFile(archive.read(entry))
            for item in cf.pool:
                if item and item[0] == 8:  # CONSTANT_String
                    value = cf.utf8(item[1])
                    if value.startswith(PREFIX) and value.count(" ") == 0:
                        names.setdefault(value, set()).add(cf.name)
    return names


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("jar", type=Path)
    parser.add_argument("--angelica", action="append", required=True, type=Path)
    args = parser.parse_args()

    with zipfile.ZipFile(args.jar) as archive:
        present = set(archive.namelist())
    failed = False
    for angelica in args.angelica:
        names = probed_names(angelica)
        if not names:
            print("%s: no DH class probes found (unexpected Angelica layout)" % angelica.name)
            failed = True
        for name, callers in sorted(names.items()):
            entry = name.replace(".", "/") + ".class"
            ok = entry in present
            failed |= not ok
            print("%s: %s probed by %s -> %s" % (angelica.name, name, ", ".join(sorted(callers)), "present" if ok else "MISSING"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
