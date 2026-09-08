#!/usr/bin/env python3
"""Verify release packaging, identity and root class-file floor (no game startup).

SPDX-License-Identifier: LGPL-3.0-only
Do not use this report as evidence of OpenGL/Forge runtime compatibility.
"""
import argparse
import collections
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile


def read_string_constants(data):
    """Read ConstantValue fields, not arbitrary strings elsewhere in the class."""
    stream = io.BytesIO(data)
    def take(size):
        value = stream.read(size)
        if len(value) != size:
            raise ValueError('truncated class')
        return value
    def u2():
        return struct.unpack('>H', take(2))[0]
    if take(4) != b'\xca\xfe\xba\xbe':
        raise ValueError('invalid class magic')
    take(4)
    pool = [None] * u2()
    index = 1
    while index < len(pool):
        tag = take(1)[0]
        if tag == 1:
            pool[index] = (tag, take(u2()).decode('utf-8', 'replace'))
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, u2())
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag == 15:
            take(3)
        else:
            raise ValueError('unknown constant pool tag ' + str(tag))
        index += 1
    take(6)
    take(2 * u2())
    constants = {}
    for _ in range(u2()):
        access, name_index, descriptor_index = u2(), u2(), u2()
        name, descriptor = pool[name_index][1], pool[descriptor_index][1]
        for _ in range(u2()):
            attribute = pool[u2()][1]
            size = struct.unpack('>I', take(4))[0]
            value = take(size)
            if attribute == 'ConstantValue' and descriptor == 'Ljava/lang/String;':
                if size != 2 or access & 0x0018 != 0x0018:
                    raise ValueError('invalid static final string constant')
                constant = pool[struct.unpack('>H', value)[0]]
                if constant[0] != 8:
                    raise ValueError('expected string ConstantValue')
                constants[name] = pool[constant[1]][1]
    return constants


def verify(path, expected_commit=None, expected_version='0.1.7-dev', expected_branch=None):
    failures = []
    versions = collections.Counter()
    versioned = collections.Counter()
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        duplicates = [k for k, v in collections.Counter(names).items() if v > 1]
        if duplicates:
            failures.append('duplicate ZIP entries: ' + ', '.join(duplicates[:10]))
        invalid = jar.testzip()
        if invalid:
            failures.append('bad ZIP CRC: ' + invalid)
        required = [
            'META-INF/MANIFEST.MF', 'mcmod.info', 'build_info.json',
            'com/seibel/distanthorizons/forge17/ForgeMain.class',
            'com/seibel/distanthorizons/forge17/DistantHorizonsLoadingPlugin.class',
            'com/seibel/distanthorizons/forge17/modAccessor/AngelicaAccessor.class',
            'com/seibel/distanthorizons/coreapi/ModInfo.class',
            'com/seibel/distanthorizons/lwjgl/lwjgl2/LWJGL2Service.class',
            'assets/distanthorizons/lang/en_US.lang',
            'assets/distanthorizons/lang/pt_BR.lang',
            'DistantHorizons/libraries/unimi/dsi/fastutil/objects/ObjectArrayList.class',
            'DistantHorizons/libraries/joml/Matrix4f.class',
        ]
        for entry in required:
            if entry not in names:
                failures.append('missing required entry: ' + entry)
        for name in names:
            if not name.endswith('.class'):
                continue
            with jar.open(name) as stream:
                header = stream.read(8)
            if len(header) != 8 or header[:4] != b'\xca\xfe\xba\xbe':
                failures.append('invalid class: ' + name)
                continue
            major = struct.unpack('>H', header[6:8])[0]
            if name.startswith('META-INF/versions/'):
                versioned[major] += 1
            else:
                versions[major] += 1
                if major > 52:
                    failures.append('root class newer than Java 8: ' + name)
        if 'mcmod.info' in names:
            metadata = json.loads(jar.read('mcmod.info'))[0]
            for key, expected in [('modid', 'distanthorizons'), ('name', 'Akashic: Horizons of The Mirai'), ('version', expected_version), ('mcversion', '1.7.10')]:
                if metadata.get(key) != expected:
                    failures.append('wrong mcmod.info ' + key)
        if 'META-INF/MANIFEST.MF' in names:
            manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '').replace('\r\n', '\n')
            for field in ['FMLCorePlugin: com.seibel.distanthorizons.forge17.DistantHorizonsLoadingPlugin', 'Implementation-Version: ' + expected_version]:
                if field not in manifest:
                    failures.append('missing manifest field: ' + field)
        mod_info = 'com/seibel/distanthorizons/coreapi/ModInfo.class'
        if mod_info in names:
            try:
                constants = read_string_constants(jar.read(mod_info))
                if constants.get('VERSION') != expected_version:
                    failures.append('ModInfo.VERSION does not match release version: ' + repr(constants.get('VERSION')))
            except (ValueError, IndexError, TypeError) as failure:
                failures.append('cannot read ModInfo constants: ' + str(failure))
        for notice in ['LICENSE.txt', 'LICENSE.LESSER.txt']:
            if notice not in names:
                failures.append('project license notice missing: ' + notice)
        if expected_commit and 'build_info.json' in names:
            build_info = json.loads(jar.read('build_info.json'))
            if build_info.get('info_git_commit') != expected_commit:
                failures.append('embedded source commit does not match expected commit')
        if expected_branch is not None and 'build_info.json' in names:
            build_info = json.loads(jar.read('build_info.json'))
            if build_info.get('info_git_branch') != expected_branch:
                failures.append('embedded source branch does not match expected branch')
        if any(n.startswith('net/minecraft/') for n in names):
            failures.append('Minecraft classes unexpectedly bundled')
        if any(n.endswith('.class') and n.startswith('it/unimi/dsi/fastutil/') for n in names):
            failures.append('unrelocated fastutil classes can override the modpack library')
        if any(n.endswith('.class') and n.startswith('org/joml/') for n in names):
            failures.append('unrelocated JOML classes can override the modpack library')
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {
        'file': path.name, 'size_bytes': path.stat().st_size,
        'sha256': digest,
        'root_class_major_counts': dict(versions),
        'multi_release_class_major_counts': dict(versioned),
        'checks_passed': not failures, 'failures': failures,
        'scope': 'ZIP integrity, metadata, required entries and class format only; no runtime certification',
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--expected-commit')
    parser.add_argument('--expected-version', default='0.1.7-dev')
    parser.add_argument('--expected-branch')
    args = parser.parse_args()
    report = verify(args.jar, args.expected_commit, args.expected_version, args.expected_branch)
    rendered = json.dumps(report, indent=2, ensure_ascii=False) + '\n'
    if args.report:
        args.report.write_text(rendered)
    print(rendered, end='')
    raise SystemExit(0 if report['checks_passed'] else 1)
