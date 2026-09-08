#!/usr/bin/env python3
"""Exercise real extracted tick methods with controlled Java collaborators.

The production ClientLevelModule methods and DhClientLevel caller are extracted
without rewriting their bodies. This is a caller-control-flow test, not a
Minecraft/GL runtime or memory-model test. The negative control changes only the
caller's new guard back to its previous unconditional void call.

python tests/diagnostics/run_client_tick_guard.py --jdk /path/to/jdk8 --jdk /path/to/jdk21 --report report.json
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


def method(source, signature):
    """Strict unique signature plus balanced braces, ignoring comments/literals."""
    if source.count(signature) != 1:
        raise ValueError("Expected exactly one production signature: " + signature)
    start = source.index(signature)
    brace = source.index("{", start)
    tokens = re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', source[brace:])
    depth = 0
    for token in tokens:
        text = token.group()
        if text == "{":
            depth += 1
        elif text == "}":
            depth -= 1
            if depth == 0:
                return source[start:brace + token.end()]
    raise ValueError("Unbalanced production method")


FIXTURE = r'''
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientTickGuardProbe {
    static final List<String> events = new ArrayList<String>();
    static final Client MC_CLIENT = new Client();
    static final Renderer MC_RENDER = new Renderer();
    static final Logger LOGGER = new Logger();
    static int failures, checks;
    static class DhVec3d { final double x, z; DhVec3d(double x, double z) { this.x=x;this.z=z; } }
    static class DhBlockPos2D {
        final int x,z;
        DhBlockPos2D(int x,int z) { this.x=x;this.z=z; }
        DhBlockPos2D(DhBlockPos2D p) { this(p.x,p.z); }
        public String toString() { return x+","+z; }
    }
    static class IClientLevelWrapper {
        Level level;
        Object getDhLevel() { return level; }
    }
    static class Client {
        boolean player;
        IClientLevelWrapper wrapper;
        boolean playerExists() { return player; }
        IClientLevelWrapper getWrappedClientLevel() { return wrapper; }
        DhBlockPos2D getPlayerBlockPos() {
            events.add("playerPos");
            return player ? new DhBlockPos2D(55,-33) : new DhBlockPos2D(0,0);
        }
    }
    static class Renderer {
        DhVec3d camera;
        DhVec3d getCameraPositionForLodTick(IClientLevelWrapper expected) {
            events.add("camera"); return camera;
        }
    }
    static class Option { boolean enabled=true; boolean get() { return enabled; } }
    static class Config { static class Client { static class Advanced { static class Graphics {
        static class Quality { static final Option useCameraPositionForQualityDropOff=new Option(); }
    }}}}
    static class QuadTree { void tryTick(DhBlockPos2D pos) { events.add("quadtree:"+pos); } }
    static class ClientRenderState {
        int viewDiameterInBlocks=256;
        final QuadTree quadtree=new QuadTree();
        ClientRenderState(Level level,Object provider) { }
        static int getViewDiameterInBlocks() { return 256; }
        void close() { events.add("closeRenderer"); }
    }
    static class Module {
        final Level clientLevel;
        final AtomicReference<ClientRenderState> ClientRenderStateRef=new AtomicReference<ClientRenderState>();
        Module(Level level) { clientLevel=level; }
        /* PRODUCTION_MODULE_METHODS */
    }
    static class Queue { void tick(DhBlockPos2D pos) { events.add("sync:"+pos); } }
    static class Provider { void tickDeferredSynchronization() { events.add("retry"); } }
    static class Logger { void error(String text,Exception failure) { events.add("caught:"+failure.getClass().getSimpleName()); } }
    static class Level {
        final IClientLevelWrapper wrapper=new IClientLevelWrapper();
        final Module clientside=new Module(this);
        final Queue syncOnLoadRequestQueue=new Queue();
        final Provider remoteDataSourceProvider=new Provider();
        Level() { wrapper.level=this; }
        IClientLevelWrapper getClientLevelWrapper() { return wrapper; }
        Object getFullDataProvider() { return remoteDataSourceProvider; }
        /* PRODUCTION_LEVEL_METHOD */
    }
    static Level reset(boolean hasRenderer) {
        events.clear();
        MC_CLIENT.player=true;
        MC_RENDER.camera=new DhVec3d(7.75,-11.2);
        Config.Client.Advanced.Graphics.Quality.useCameraPositionForQualityDropOff.enabled=true;
        Level level=new Level(); MC_CLIENT.wrapper=level.wrapper;
        if (hasRenderer) level.clientside.ClientRenderStateRef.set(new ClientRenderState(level,level.remoteDataSourceProvider));
        return level;
    }
    static void expect(String name,String... expected) {
        checks++;
        if (!events.equals(Arrays.asList(expected))) { failures++;System.out.println("FAIL "+name+" expected="+Arrays.toString(expected)+" actual="+events); }
        else System.out.println("PASS "+name);
    }
    public static void main(String[] args) {
        Level l=reset(true); MC_CLIENT.player=false; l.clientTick(); expect("player_absent");
        l=reset(true); MC_RENDER.camera=null; l.clientTick(); expect("camera_absent","camera");
        l=reset(true); l.clientTick(); expect("valid_order","camera","quadtree:7,-11","playerPos","sync:55,-33","retry");
        l=reset(false); MC_RENDER.camera=null; l.clientTick(); expect("renderer_absent_keeps_network","playerPos","sync:55,-33","retry");
        l=reset(true); l.clientside.clientTick(); expect("legacy_void_delegate","camera","quadtree:7,-11");
        l=reset(true); MC_CLIENT.wrapper=null; l.clientTick(); expect("wrong_level");
        l=reset(true); MC_RENDER.camera=null;
        Config.Client.Advanced.Graphics.Quality.useCameraPositionForQualityDropOff.enabled=false;
        l.clientTick(); expect("player_position_mode","playerPos","quadtree:55,-33","playerPos","sync:55,-33","retry");
        System.out.println("RESULT checks="+checks+" failures="+failures);
        if (failures!=0) System.exit(1);
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jdk", type=Path, action="append", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    base = root / "coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/level"
    module_path, level_path = base / "ClientLevelModule.java", base / "DhClientLevel.java"
    module_source, level_source = module_path.read_text(encoding="utf-8"), level_path.read_text(encoding="utf-8")
    void_method = method(module_source, "public void clientTick()")
    boolean_method = method(module_source, "public boolean clientTickForCurrentWorld()")
    caller = method(level_source, "public void clientTick()")
    guard = "if (!this.clientside.clientTickForCurrentWorld()) { return; }"
    if caller.count(guard) != 1:
        raise ValueError("Caller guard changed; update this strict negative control intentionally")
    positive = FIXTURE.replace("/* PRODUCTION_MODULE_METHODS */", void_method + "\n" + boolean_method).replace("/* PRODUCTION_LEVEL_METHOD */", caller)
    negative = positive.replace(guard, "this.clientside.clientTick();", 1)
    report = {"scope": "Exact extracted production tick methods with controlled collaborators; no Minecraft, GL, scheduler or memory-model validation.",
              "sources": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in [module_path, level_path]},
              "method_sha256": {name: hashlib.sha256(text.encode()).hexdigest() for name, text in [("legacy_void",void_method),("guarded_tick",boolean_method),("caller",caller)]},
              "negative_control": "Only the caller guard is reverted to the previous unconditional void call; module remains corrected.", "runs": []}
    with tempfile.TemporaryDirectory(prefix="mirai-client-tick-guard-") as temp:
        for label, source in [("positive", positive), ("negative", negative)]:
            directory = Path(temp) / label
            directory.mkdir()
            java_source = directory / "ClientTickGuardProbe.java"
            java_source.write_text(source, encoding="utf-8")
            compilation = subprocess.run([str(args.jdk[0] / "bin/javac"), "-source", "8", "-target", "8", "-d", str(directory), str(java_source)], capture_output=True, text=True, timeout=60)
            if compilation.returncode:
                raise RuntimeError("Fixture compilation failed: " + compilation.stderr)
            for index, jdk in enumerate(args.jdk, 1):
                version = subprocess.run([str(jdk / "bin/java"), "-version"], capture_output=True, text=True, timeout=20)
                run = subprocess.run([str(jdk / "bin/java"), "-Xverify:all", "-cp", str(directory), "ClientTickGuardProbe"], capture_output=True, text=True, timeout=30)
                if label == "positive":
                    passed = run.returncode == 0 and "RESULT checks=7 failures=0" in run.stdout
                else:
                    failed_cases = [line.split()[1] for line in run.stdout.splitlines() if line.startswith("FAIL ")]
                    passed = run.returncode == 1 and failed_cases == ["player_absent", "camera_absent"] and "RESULT checks=7 failures=2" in run.stdout
                report["runs"].append({"runtime_index": index, "runtime_version": version.stderr.strip(), "control": label,
                                       "passed": passed, "exit_code": run.returncode, "stdout": run.stdout, "stderr": run.stderr})
    report["passed"] = all(r["passed"] for r in report["runs"])
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "runs": len(report["runs"]), "positive_cases": 7, "expected_negative_failures": 2}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
