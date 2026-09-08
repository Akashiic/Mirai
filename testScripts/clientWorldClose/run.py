#!/usr/bin/env python3
"""Compile real client-world source with boundary fakes; kill a hung JVM after five seconds."""
from pathlib import Path
import subprocess
import tempfile
import os
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--world-source", type=Path, help="Use a prior DhClientWorld source for a negative control")
args = parser.parse_args()
script_root = Path(__file__).resolve().parent
repo_root = script_root.parent.parent
core = repo_root / "coreSubProjects/core/src/main/java"
world = core / "com/seibel/distanthorizons/core/world"
sources = [
    args.world_source if args.world_source else world / "DhClientWorld.java",
    world / "IDhClientWorld.java",
    world / "IDhWorld.java",
    world / "EWorldEnvironment.java",
    core / "com/seibel/distanthorizons/core/level/IServerKeyedClientLevel.java",
    script_root / "DhClientWorldRegressionTest.java",
    *sorted((script_root / "stubs").rglob("*.java")),
]
with tempfile.TemporaryDirectory(prefix="akashic-client-world-close-") as output:
    subprocess.run([os.environ.get("JAVAC", "javac"), "--release", "8", "-encoding", "UTF-8", "-d", output,
                    *map(str, sources)], check=True, timeout=30)
    subprocess.run([os.environ.get("JAVA", "java"), "-ea", "-cp", output,
                    "com.seibel.distanthorizons.core.world.DhClientWorldRegressionTest"],
                   check=True, timeout=5)
