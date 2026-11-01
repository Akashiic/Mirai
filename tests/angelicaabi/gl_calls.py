#!/usr/bin/env python3
"""Gate: every raw LWJGL OpenGL reference in Mirai's classes must be in the reviewed allowlist.

Angelica's GLStateManager (GLSM) caches GL state and rewrites other mods' org/lwjgl/opengl calls to itself.
The 2026-11 audit verified that all of Mirai's current references have a GLSM redirect (or change no cached
state), so DH rendering never leaves GLSM's cache stale. A new reference fails this gate until it is checked
against Angelica's redirect maps and added to gl-calls.txt (python gl_calls.py <jar> --update).
Reads class files only; nothing is loaded or executed.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit import load_jar  # noqa: E402

ALLOWLIST = Path(__file__).resolve().parent / "gl-calls.txt"
MIRAI_PREFIX = "com/seibel/distanthorizons/"
# the optional LWJGL3 backend ships in the jar but is never selected on 1.7.10 / LWJGL2 (D-02)
EXCLUDED_PREFIXES = ("com/seibel/distanthorizons/lwjgl/lwjgl3/",)
GL_PREFIX = "org/lwjgl/opengl/"


def gl_references(jar):
    all_classes, _ = load_jar(jar)
    found = set()
    for cf in all_classes:
        if not cf.name.startswith(MIRAI_PREFIX) or cf.name.startswith(EXCLUDED_PREFIXES):
            continue
        for ref in cf.references():
            if ref["kind"] == "method" and ref["owner"].startswith(GL_PREFIX):
                found.add("%s -> %s.%s%s" % (cf.name, ref["owner"], ref["name"], ref["descriptor"]))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("jar", type=Path)
    parser.add_argument("--update", action="store_true", help="rewrite the allowlist after a reviewed change")
    args = parser.parse_args()

    found = gl_references(args.jar)
    if args.update:
        header = "# Reviewed against Angelica's GLSM redirect maps (2.2.20, 2.2.43). Regenerate only after review.\n"
        ALLOWLIST.write_text(header + "".join(line + "\n" for line in sorted(found)), encoding="utf-8")
        print("wrote %d references to %s" % (len(found), ALLOWLIST.name))
        return 0

    allowed = {line.strip() for line in ALLOWLIST.read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#")}
    added, removed = sorted(found - allowed), sorted(allowed - found)
    for line in removed:
        print("no longer referenced (run --update): " + line)
    for line in added:
        print("NOT REVIEWED against Angelica GLSM: " + line)
    print("%d GL references, %d unreviewed" % (len(found), len(added)))
    return 1 if added else 0


if __name__ == "__main__":
    sys.exit(main())
