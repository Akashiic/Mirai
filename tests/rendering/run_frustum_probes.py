#!/usr/bin/env python3
"""Run real-JOML culling equivalence and allocation probes on Java 8 and 21."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT.parent
OUT = Path(os.environ.get("MIRAI_FRUSTUM_EVIDENCE", BASE / "homologation-0.1.5-evidence/rendering/frustum"))
GEN = OUT / "generated"
GEN.mkdir(parents=True, exist_ok=True)
REL = "core/src/main/java/com/seibel/distanthorizons/core/render/renderer/cullingFrustum/DhFrustumBounds.java"
SOURCE = ROOT / "coreSubProjects" / REL
TEST = ROOT / "coreSubProjects/core/src/test/java/com/seibel/distanthorizons/core/render/renderer/cullingFrustum/DhFrustumBoundsTest.java"
BUILD = Path(os.environ.get("MIRAI_FRUSTUM_BUILD", "/tmp/akashic-mirai-014-build"))
class_paths = [BUILD / "coreSubProjects" / module / "build/classes/java/main" for module in ("core", "api")]
if not all(path.is_dir() for path in class_paths):
    raise RuntimeError("Compile core and API first; MIRAI_FRUSTUM_BUILD must point to the build tree")
cache = BASE / "gradle-cache/caches/modules-2/files-2.1"
dependencies = [next(cache.glob(pattern)) for pattern in (
    "org.joml/joml/1.10.2/*/joml-1.10.2.jar",
    "junit/junit/4.13.2/*/junit-4.13.2.jar",
    "org.hamcrest/hamcrest-core/*/*/hamcrest-core-*.jar")]
classpath = os.pathsep.join(map(str, class_paths + dependencies))
baseline = subprocess.run(["git", "show", "954b57e6491ee77f9e3e45a602d31467f0033205:" + REL],
                          cwd=ROOT / "coreSubProjects", text=True, capture_output=True, check=True).stdout
baseline = baseline.replace("DhFrustumBounds", "PreviousDhFrustumBounds")
(GEN / "PreviousDhFrustumBounds.java").write_text(baseline)
probe = r'''
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.render.renderer.cullingFrustum.DhFrustumBounds;
import com.seibel.distanthorizons.core.render.renderer.cullingFrustum.PreviousDhFrustumBounds;
import java.lang.management.ManagementFactory;

public final class FrustumAllocationProbe {
    private static volatile long sink;
    private static final int ROUNDS = 200000;
    private static long work(IDhApiCullingFrustum f, DhApiMat4f matrix) {
        long count = 0;
        for (int i = 0; i < ROUNDS; i++) {
            f.update(-64, 320, matrix);
            for (int j = 0; j < 4; j++) {
                if (f.intersects(((i + j) & 1023) - 512, ((i * 3 + j) & 1023) - 512, 64, 6)) count++;
            }
        }
        sink = count;
        return count;
    }
    private static void measure(String name, IDhApiCullingFrustum f, DhApiMat4f matrix,
                                com.sun.management.ThreadMXBean bean) {
        for (int i = 0; i < 3; i++) work(f, matrix);
        long thread = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(thread);
        long checksum = work(f, matrix);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        System.out.println(name + " updates=" + ROUNDS + " intersections=" + (ROUNDS * 4)
                           + " allocatedBytes=" + bytes + " checksum=" + checksum);
        if (name.equals("current") && bytes > 1024) throw new AssertionError("Unexpected hot-path allocation: " + bytes);
    }
    public static void main(String[] args) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        DhApiMat4f matrix = new DhApiMat4f();
        matrix.m00 = 0.01f; matrix.m11 = 0.01f; matrix.m22 = 0.01f; matrix.m33 = 1f;
        PreviousDhFrustumBounds previous = new PreviousDhFrustumBounds();
        DhFrustumBounds current = new DhFrustumBounds();
        if (work(previous, matrix) != work(current, matrix)) throw new AssertionError("Visibility changed");
        measure("previous", previous, matrix, bean);
        measure("current", current, matrix, bean);
    }
}
'''
(GEN / "FrustumAllocationProbe.java").write_text(probe)
results = {"scope": "Exact production frustum and real JOML 1.10.2; no GPU or Minecraft boot.",
           "baseline_core_commit": "954b57e6491ee77f9e3e45a602d31467f0033205",
           "inputs_sha256": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in [SOURCE, TEST] + dependencies},
           "runs": []}
for jdk in ("jdk8", "jdk21"):
    java = BASE / "toolchains" / jdk / "bin"
    classes = GEN / jdk
    classes.mkdir(exist_ok=True)
    cmd = [str(java / "javac"), "-source", "8", "-target", "8", "-cp", classpath,
           "-d", str(classes), str(SOURCE), str(TEST), str(GEN / "PreviousDhFrustumBounds.java"), str(GEN / "FrustumAllocationProbe.java")]
    compiled = subprocess.run(cmd, text=True, capture_output=True)
    (OUT / (jdk + "-compile.log")).write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise RuntimeError(compiled.stderr)
    cp = str(classes) + os.pathsep + classpath
    for name, vm_args, main_args in (
        ("junit", [], ["org.junit.runner.JUnitCore", "com.seibel.distanthorizons.core.render.renderer.cullingFrustum.DhFrustumBoundsTest"]),
        ("allocation-default", [], ["FrustumAllocationProbe"]),
        ("allocation-no-escape-analysis", ["-XX:-DoEscapeAnalysis"], ["FrustumAllocationProbe"])):
        run = subprocess.run([str(java / "java"), "-Xverify:all"] + vm_args + ["-cp", cp] + main_args,
                             text=True, capture_output=True)
        (OUT / (jdk + "-" + name + ".log")).write_text(run.stdout + run.stderr)
        results["runs"].append({"jdk": jdk, "test": name, "exit_code": run.returncode, "output": run.stdout})
        if run.returncode:
            raise RuntimeError(run.stdout + run.stderr)
    bytecode = subprocess.run([str(java / "javap"), "-cp", cp, "-c", "-p",
                              "com.seibel.distanthorizons.core.render.renderer.cullingFrustum.DhFrustumBounds"],
                             text=True, capture_output=True, check=True).stdout
    (OUT / (jdk + "-bytecode.txt")).write_text(bytecode)
    hot_path = bytecode[bytecode.index("public void update("):bytecode.index("public int getPriority(")]
    if " new " in hot_path or "Matrix4f.invert" in hot_path or "frustumAabb" in hot_path:
        raise AssertionError("Unexpected allocation/inversion in hot path")
results["passed"] = True
(OUT / "RESULTS.json").write_text(json.dumps(results, indent=2) + "\n")
print(json.dumps(results, indent=2))
