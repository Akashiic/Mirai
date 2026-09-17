import hashlib, json, subprocess, sys, time
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
JDK=Path(r'C:\Users\User\.gradle\jdks\temurin-8-amd64-windows\jdk8u362-b09')
LWJGL=Path(r'C:\Users\User\AppData\Roaming\.technic\libraries\org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209\lwjgl-2.9.4-nightly-20150209.jar')
NATIVES=Path(r'C:\Users\User\AppData\Roaming\.technic\modpacks\forbidden-series\bin\natives')
OUT=HERE/'out'; OUT.mkdir(exist_ok=True)
EVIDENCE=HERE/'evidence'; EVIDENCE.mkdir(exist_ok=True)
SRC=HERE/'SharedIboBindProbe.java'
for p in (JDK/'bin'/'javac.exe',JDK/'bin'/'java.exe',LWJGL,NATIVES,SRC):
    if not p.exists(): raise SystemExit('missing dependency: '+str(p))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT)
compile_cmd=[str(JDK/'bin'/'javac.exe'),'-cp',str(LWJGL),'-d',str(OUT),str(SRC)]
c=subprocess.run(compile_cmd,capture_output=True,text=True,timeout=30)
(OUT/'compile.log').write_text(c.stdout+c.stderr,encoding='utf-8')
if c.returncode: raise SystemExit(c.returncode)
run_cmd=[str(JDK/'bin'/'java.exe'),'-Dorg.lwjgl.librarypath='+str(NATIVES),'-cp',str(OUT)+';'+str(LWJGL),'SharedIboBindProbe']
started=time.monotonic()
r=subprocess.run(run_cmd,cwd=OUT,capture_output=True,text=True,timeout=120)
(OUT/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8')
parsed=[json.loads(line.split('=',1)[1]) for line in r.stdout.splitlines() if line.startswith('RESULT=')]
diff=git('diff','--','common/src/main/java/com/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram.java')
report={
    'exit_code':r.returncode,
    'seconds':round(time.monotonic()-started,3),
    'head':git('rev-parse','HEAD').decode().strip(),
    'source_sha256':sha(SRC),
    'lwjgl_sha256':sha(LWJGL),
    'candidate_diff_sha256':hashlib.sha256(diff).hexdigest(),
    'scope':'Offscreen LWJGL2 driver A/B of redundant ELEMENT_ARRAY_BUFFER binds; exact RGBA8 readback plus CPU submission and GPU timestamp medians; no Minecraft FPS claim',
    'result':parsed[-1] if parsed else None,
    'stderr':r.stderr,
}
(EVIDENCE/'shared-ibo.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report),flush=True)
raise SystemExit(r.returncode)
