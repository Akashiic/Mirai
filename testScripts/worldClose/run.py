#!/usr/bin/env python3
"""Compile real world orchestration with boundary fakes; kill hung JVM after five seconds."""
from pathlib import Path
import subprocess
import tempfile

script_root = Path(__file__).resolve().parent
repo_root = script_root.parent.parent
core = repo_root / "coreSubProjects/core/src/main/java"
world = core / "com/seibel/distanthorizons/core/world"
sources = [
    world / "AbstractDhServerWorld.java",
    world / "IDhServerWorld.java",
    world / "IDhWorld.java",
    world / "EWorldEnvironment.java",
    script_root / "AbstractDhServerWorldRegressionTest.java",
    *sorted((script_root / "stubs").rglob("*.java")),
]
with tempfile.TemporaryDirectory(prefix="akashic-world-close-") as output:
    subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", output,
                    *map(str, sources)], check=True, timeout=30)
    subprocess.run(["java", "-ea", "-cp", output,
                    "com.seibel.distanthorizons.core.world.AbstractDhServerWorldRegressionTest"],
                   check=True, timeout=5)
