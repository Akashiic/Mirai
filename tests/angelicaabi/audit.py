#!/usr/bin/env python3
"""Audit final-JAR relocation boundaries and Angelica/GTNHLib/Iris symbols.

Reads real class files without initializing Minecraft, OpenGL or the mods.
This is a packaging/member-resolution gate, not a full JVM verifier.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

PRIVATE_PREFIXES = ("DistantHorizons/libraries/", "dh_sqlite/", "dhcomgithubluben/")
TARGET_PREFIXES = (
    "com/gtnewhorizons/angelica/", "com/gtnewhorizon/gtnhlib/",
    "net/coderbot/iris/", "me/jellysquid/mods/sodium/",
)
KNOWN_BAD_SYMBOL = (
    "com/gtnewhorizons/angelica/glsm/GLStateManager", "getFogColor",
    "()LDistantHorizons/libraries/joml/Vector3d;",
)
# Exact optional SQLite branch reviewed by bytecode inspection. The guard class
# hash must also match; if a dependency update changes its behavior, review it.
SQLITE_SLF4J_SYMBOLS = {
    ("DistantHorizons/libraries/slf4j/LoggerFactory", "getLogger", "(Ljava/lang/Class;)LDistantHorizons/libraries/slf4j/Logger;"),
    ("DistantHorizons/libraries/slf4j/Logger", "isTraceEnabled", "()Z"),
    ("DistantHorizons/libraries/slf4j/Logger", "trace", "(Ljava/lang/String;)V"),
    ("DistantHorizons/libraries/slf4j/Logger", "isInfoEnabled", "()Z"),
    ("DistantHorizons/libraries/slf4j/Logger", "info", "(Ljava/lang/String;)V"),
    ("DistantHorizons/libraries/slf4j/Logger", "isWarnEnabled", "()Z"),
    ("DistantHorizons/libraries/slf4j/Logger", "warn", "(Ljava/lang/String;)V"),
    ("DistantHorizons/libraries/slf4j/Logger", "isErrorEnabled", "()Z"),
    ("DistantHorizons/libraries/slf4j/Logger", "error", "(Ljava/lang/String;Ljava/lang/Throwable;)V"),
}


class ClassFile:
    def __init__(self, data):
        self.data, self.pos = data, 0
        if self.take(4) != b"\xca\xfe\xba\xbe":
            raise ValueError("Invalid class-file magic")
        self.minor, self.major = self.u2(), self.u2()
        self.pool = [None] * self.u2()
        i = 1
        while i < len(self.pool):
            tag = self.u1()
            if tag == 1:
                # Symbols/descriptors here are ASCII, identical in modified UTF-8.
                item = (tag, self.take(self.u2()).decode("utf-8", "replace"))
            elif tag in (3, 4):
                item = (tag, self.take(4))
            elif tag in (5, 6):
                item = (tag, self.take(8))
            elif tag in (7, 8, 16, 19, 20):
                item = (tag, self.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                item = (tag, self.u2(), self.u2())
            elif tag == 15:
                item = (tag, self.u1(), self.u2())
            else:
                raise ValueError("Unsupported constant-pool tag " + str(tag))
            self.pool[i] = item
            i += 2 if tag in (5, 6) else 1
        self.access = self.u2()
        self.name = self.class_name(self.u2())
        parent = self.u2()
        self.parent = self.class_name(parent) if parent else None
        self.interfaces = [self.class_name(self.u2()) for _ in range(self.u2())]
        self.fields = self.read_members()
        self.methods = self.read_members()
        self.attributes()
        if self.pos != len(self.data):
            raise ValueError("Unparsed trailing class-file bytes")

    def take(self, n):
        end = self.pos + n
        if end > len(self.data):
            raise ValueError("Truncated class file")
        result = self.data[self.pos:end]
        self.pos = end
        return result

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def u4(self):
        return struct.unpack(">I", self.take(4))[0]

    def utf8(self, index):
        if self.pool[index][0] != 1:
            raise ValueError("Expected CONSTANT_Utf8")
        return self.pool[index][1]

    def class_name(self, index):
        if self.pool[index][0] != 7:
            raise ValueError("Expected CONSTANT_Class")
        return self.utf8(self.pool[index][1])

    def attributes(self):
        for _ in range(self.u2()):
            self.utf8(self.u2())
            self.take(self.u4())

    def read_members(self):
        result = {}
        for _ in range(self.u2()):
            access, name, descriptor = self.u2(), self.utf8(self.u2()), self.utf8(self.u2())
            result[(name, descriptor)] = access
            self.attributes()
        return result

    def references(self):
        for item in self.pool:
            if item and item[0] in (9, 10, 11):
                nt = self.pool[item[2]]
                if nt[0] != 12:
                    raise ValueError("Expected CONSTANT_NameAndType")
                yield {
                    "caller": self.name,
                    "kind": "field" if item[0] == 9 else "method",
                    "constant_pool_tag": item[0],
                    "owner": self.class_name(item[1]),
                    "name": self.utf8(nt[1]), "descriptor": self.utf8(nt[2]),
                }


def jar_identity(path):
    data = path.read_bytes()
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}


def load_jar(path):
    all_classes, base_classes = [], {}
    with zipfile.ZipFile(path) as archive:
        duplicates = [n for n, c in Counter(archive.namelist()).items() if c > 1]
        if duplicates:
            raise ValueError("Duplicate archive entries: " + repr(duplicates[:5]))
        if archive.testzip():
            raise ValueError("Archive CRC failure")
        for entry in archive.namelist():
            if not entry.endswith(".class"):
                continue
            cf = ClassFile(archive.read(entry))
            expected_name = re.sub(r"^META-INF/versions/[0-9]+/", "", entry)[:-6]
            if cf.name != expected_name:
                raise ValueError("Class identity mismatch: " + entry)
            all_classes.append(cf)
            if not entry.startswith("META-INF/versions/"):
                base_classes[cf.name] = cf
    return all_classes, base_classes


def resolve_member(index, owner, name, descriptor, kind, seen=None):
    """Resolve exact descriptors through class/interface inheritance.

    Constructors are never inherited. This does not emulate access control,
    opcode/static compatibility, maximally-specific default methods, or mixins.
    Those are distinct runtime gates; the symbols checked here are public.
    """
    seen = set() if seen is None else seen
    if owner in seen or owner not in index:
        return None
    seen.add(owner)
    cls = index[owner]
    members = cls.fields if kind == "field" else cls.methods
    key = name, descriptor
    if key in members:
        return {"declaring_class": owner, "access_flags": members[key]}
    if name in ("<init>", "<clinit>"):
        return None
    parents = cls.interfaces + ([cls.parent] if cls.parent else []) if kind == "field" else ([cls.parent] if cls.parent else []) + cls.interfaces
    for parent in parents:
        resolved = resolve_member(index, parent, name, descriptor, kind, seen)
        if resolved:
            return resolved
    return None


def private_type(descriptor):
    return any("L" + prefix in descriptor for prefix in PRIVATE_PREFIXES)


def audit(path, dependency_paths):
    classes, contained = load_jar(path)
    sqlite_guard = contained.get("dh_sqlite/util/LoggerFactory")
    sqlite_guard_sha = hashlib.sha256(sqlite_guard.data).hexdigest() if sqlite_guard else None
    dependencies = {}
    for dependency in dependency_paths:
        _, indexed = load_jar(dependency)
        for name, cls in indexed.items():
            # Explicit first-JAR precedence, as in a normal class path.
            dependencies.setdefault(name, cls)
    failures, checked, private_external, optional_private = [], [], [], []
    for name in sorted(contained):
        if name.startswith(TARGET_PREFIXES):
            failures.append({"reason": "external_mod_class_bundled_in_candidate", "class": name})
    target_classes = set()
    references_seen = 0
    for cls in classes:
        for cp in cls.pool:
            if cp and cp[0] == 7:
                symbol = cls.utf8(cp[1])
                # Array class literals can also name external component classes.
                symbol = symbol.lstrip("[")
                symbol = symbol[1:-1] if symbol.startswith("L") and symbol.endswith(";") else symbol
                if symbol.startswith(TARGET_PREFIXES) and symbol not in contained:
                    target_classes.add(symbol)
        for ref in cls.references():
            references_seen += 1
            owner = ref["owner"]
            if owner in contained:
                continue
            if owner.startswith(PRIVATE_PREFIXES):
                optional_private.append(ref)
                known_optional = (
                    ref["caller"] == "dh_sqlite/util/LoggerFactory$SLF4JLogger" and
                    ref["kind"] == "method" and
                    (owner, ref["name"], ref["descriptor"]) in SQLITE_SLF4J_SYMBOLS and
                    "DistantHorizons/libraries/slf4j/Logger" not in contained and
                    "DistantHorizons/libraries/slf4j/LoggerFactory" not in contained and
                    sqlite_guard_sha == "c51f4750eacf45032c3287d536ef710b073e3ee255bf84c9b2f5513931c7d6a2"
                )
                if not known_optional:
                    failures.append({"reason": "unreviewed_missing_private_dependency", **ref})
            elif private_type(ref["descriptor"]):
                private_external.append(ref)
                failures.append({"reason": "private_type_in_external_descriptor", **ref})
            if not owner.startswith(TARGET_PREFIXES):
                continue
            resolved = resolve_member(dependencies, owner, ref["name"], ref["descriptor"], ref["kind"])
            checked.append({**ref, "resolved": resolved})
            if resolved is None:
                alternatives = []
                if owner in dependencies:
                    declarations = dependencies[owner].fields if ref["kind"] == "field" else dependencies[owner].methods
                    alternatives = sorted(d for (n, d) in declarations if n == ref["name"])
                failures.append({"reason": "missing_external_member", **ref, "declared_same_name_descriptors": alternatives})
            elif not resolved["access_flags"] & 0x0001:
                failures.append({"reason": "external_member_not_public", **ref, "resolved": resolved})
    missing_classes = sorted(target_classes - set(dependencies))
    for name in missing_classes:
        failures.append({"reason": "missing_external_class", "class": name})
    if not checked:
        failures.append({"reason": "empty_target_scope"})
    return {
        "artifact": jar_identity(path),
        "dependencies_in_precedence_order": [jar_identity(p) for p in dependency_paths],
        "scope": "All final-JAR member references for private-type leaks; direct Angelica/GTNHLib/Iris/Sodium class/member descriptors resolved against supplied original JARs.",
        "limitations": ["No Minecraft, OpenGL, class initialization, mixin application or network session executed.", "Does not audit unrelated external libraries, invocation opcodes or all JVM access/default-method rules.", "Dependency member resolution uses base classes (Java 8); every candidate class, including multi-release entries, is scanned."],
        "classes_scanned": len(classes), "member_references_scanned": references_seen,
        "external_target_classes": sorted(target_classes),
        "external_target_member_references": checked,
        "private_types_in_external_descriptors": private_external,
        "missing_private_dependency_references": optional_private,
        "sqlite_logger_guard_sha256": sqlite_guard_sha,
        "missing_private_dependency_note": "Listed separately, never silently discarded: SQLite has an optional SLF4J branch guarded by Class.forName(private Logger), with JDKLogger fallback when absent. This list is not evidence that an optional branch executes.",
        "failures": failures, "passed": not failures,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--dependency", action="append", required=True, type=Path)
    parser.add_argument("--negative-control", type=Path, help="The original 0.1.1-dev JAR; must fail on the exact reported fog symbol")
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = audit(args.jar, args.dependency)
        if args.negative_control:
            negative = audit(args.negative_control, args.dependency)
            expected = any(
                f.get("reason") == "missing_external_member" and
                (f.get("owner"), f.get("name"), f.get("descriptor")) == KNOWN_BAD_SYMBOL
                for f in negative["failures"]
            )
            result = {"candidate": result, "negative_control": negative, "negative_control_rejected_expected_symbol": expected, "passed": result["passed"] and expected}
    except (OSError, ValueError, IndexError, TypeError, struct.error, zipfile.BadZipFile) as error:
        result = {"passed": False, "audit_error": str(error)}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "report": str(args.report.resolve())}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
