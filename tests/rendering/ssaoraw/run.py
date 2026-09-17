import hashlib, json, shutil, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
JDK = Path(r"C:\Users\User\.gradle\jdks\temurin-8-amd64-windows\jdk8u362-b09")
LWJGL = Path(r"C:\Users\User\AppData\Roaming\.technic\libraries\org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209\lwjgl-2.9.4-nightly-20150209.jar")
NATIVES = Path(r"C:\Users\User\AppData\Roaming\.technic\modpacks\forbidden-series\bin\natives")
VERT = ROOT / r"coreSubProjects\core\src\main\resources\assets\distanthorizons\shaders\shared\gl\quad_apply.vert"
RAW = ROOT / r"coreSubProjects\core\src\main\resources\assets\distanthorizons\shaders\ssao\gl\ao.frag"
OUT = HERE / "out"
VAR = OUT / "variants"
OUT.mkdir(exist_ok=True); VAR.mkdir(exist_ok=True)

for required in (JDK / "bin" / "javac.exe", JDK / "bin" / "java.exe", LWJGL, NATIVES, VERT, RAW):
    if not required.exists(): raise SystemExit(f"missing dependency: {required}")
shutil.copy2(VERT, VAR / "quad.vert")
base = RAW.read_text(encoding="utf-8")
(VAR / "raw-base.frag").write_text(base, encoding="utf-8", newline="\n")
variants = {}
specialized = base
old_step = "    float rStep = uRadius / uSampleCount;"
old_loop = "    for (int i = 0; i < clamp(uSampleCount, 1, SAMPLE_MAX); i++) {"
if specialized.count(old_step) != 1 or specialized.count(old_loop) != 1:
    raise SystemExit("production SSAO RAW structure changed; candidate generator refused")
specialized = specialized.replace(old_step, "    float rStep = uRadius / 6.0;", 1)
specialized = specialized.replace(old_loop, "    for (int i = 0; i < 6; i++) {", 1)
variants["raw-specialized6"] = specialized
variants["raw-loop6"] = base.replace(old_loop, "    for (int i = 0; i < 6; i++) {", 1)
variants["raw-step6"] = base.replace(old_step, "    float rStep = uRadius / 6.0;", 1)
recurrence = base
old_phase = "    float rotatePhase = dither * TAU;"
new_phase = "    float rotatePhase = dither * TAU;\n    float sinPhase = sin(rotatePhase);\n    float cosPhase = cos(rotatePhase);"
old_offset = '        vec2 offset = vec2(\n            sin(rotatePhase),\n            cos(rotatePhase)\n        ) * radius;\n        \n        radius += rStep;\n        rotatePhase += GOLDEN_ANGLE;'
new_offset = '        vec2 offset = vec2(sinPhase, cosPhase) * radius;\n        float nextSin = sinPhase * -0.7373688817 + cosPhase * 0.6754903197;\n        float nextCos = cosPhase * -0.7373688817 - sinPhase * 0.6754903197;\n        sinPhase = nextSin;\n        cosPhase = nextCos;\n        radius += rStep;'
if recurrence.count(old_phase) != 1 or recurrence.count(old_offset) != 1:
    raise SystemExit("recurrence source markers changed")
recurrence = recurrence.replace(old_phase, new_phase, 1).replace(old_offset, new_offset, 1)
variants["raw-recurrence"] = recurrence

for name, text in variants.items():
    (VAR / f"{name}.frag").write_text(text, encoding="utf-8", newline="\n")

compile_cmd = [str(JDK / "bin" / "javac.exe"), "-cp", str(LWJGL), "-d", str(OUT), str(HERE / "RawSsaoProbe.java")]
compile_run = subprocess.run(compile_cmd, capture_output=True, text=True, timeout=30)
(OUT / "compile.log").write_text(compile_run.stdout + compile_run.stderr, encoding="utf-8")
if compile_run.returncode:
    raise SystemExit(f"probe compilation failed; see {OUT / 'compile.log'}")
selected = sys.argv[1:] or list(variants)
for name in selected:
    if name not in variants: raise SystemExit(f"unknown variant: {name}")
    cmd = [str(JDK / "bin" / "java.exe"), "-Dorg.lwjgl.librarypath=" + str(NATIVES),
           "-cp", str(OUT) + ";" + str(LWJGL), "RawSsaoProbe", str(VAR), name]
    started = time.monotonic()
    run = subprocess.run(cmd, cwd=OUT, capture_output=True, text=True, timeout=180)
    (OUT / f"{name}.log").write_text(run.stdout + run.stderr, encoding="utf-8")
    parsed = [json.loads(line.split("=", 1)[1]) for line in run.stdout.splitlines() if line.startswith("RESULT=")]
    report = {
        "variant": name, "exit_code": run.returncode, "seconds": round(time.monotonic() - started, 3),
        "production_raw_sha256": hashlib.sha256(RAW.read_bytes()).hexdigest(),
        "probe_sha256": hashlib.sha256((HERE / "RawSsaoProbe.java").read_bytes()).hexdigest(),
        "candidate_sha256": hashlib.sha256((VAR / f"{name}.frag").read_bytes()).hexdigest(),
        "scope": "Native Intel-driver offscreen R16F exact comparison and asynchronous GPU timestamp batches; not Minecraft FPS certification",
        "result": parsed[-1] if parsed else None, "stderr": run.stderr,
    }
    (OUT / f"{name}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report), flush=True)
    if run.returncode: raise SystemExit(run.returncode)
