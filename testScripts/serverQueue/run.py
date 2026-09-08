#!/usr/bin/env python3
"""Compile the real manager with boundary fakes; does not bootstrap Minecraft or use Gradle."""
from pathlib import Path
import subprocess
import tempfile

script_root = Path(__file__).resolve().parent
repo_root = script_root.parent.parent
core = repo_root / "coreSubProjects/core/src/main/java"
sources = [
    core / "com/seibel/distanthorizons/core/multiplayer/server/ServerPlayerStateManager.java",
    core / "com/seibel/distanthorizons/core/network/session/SessionClosedException.java",
    script_root / "ServerPlayerStateManagerRegressionTest.java",
    *sorted((script_root / "stubs").rglob("*.java")),
]
with tempfile.TemporaryDirectory(prefix="akashic-server-queue-") as output:
    subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", output,
                    *map(str, sources)], check=True)
    subprocess.run(["java", "-ea", "-cp", output,
                    "com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerStateManagerRegressionTest"], check=True)
