#!/usr/bin/env python3
from pathlib import Path
import subprocess,json,hashlib
ROOT=Path(__file__).resolve().parents[2];WORK=ROOT.parent;OUT=WORK/'stabilization-0.1.3-evidence/rendering/compile';OUT.mkdir(parents=True,exist_ok=True)
files=['common/src/main/java/com/seibel/distanthorizons/common/render/openGl/glObject/GLState.java','common/src/main/java/com/seibel/distanthorizons/common/render/openGl/util/GlResourceCleanup.java','common/src/main/java/com/seibel/distanthorizons/common/render/openGl/terrain/GlBlockTextureAtlas.java','coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhMetaRenderer.java','coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/AbstractBlockTextureAtlas.java','coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/LodRenderer.java','tests/rendering/LodBoundaryProbe.java']
cp=':'.join(map(str,[WORK/'deliverables-0.1.2/Akashic-Horizons-of-The-Mirai-forge17-0.1.2-dev-1.7.10.jar',next((WORK/'gradle-cache').rglob('lwjgl-2.9.1.jar')),next((WORK/'gradle-cache').rglob('log4j-api-2.0-beta9.jar'))]))
p=subprocess.run([str(WORK/'toolchains/jdk8/bin/javac'),'-source','8','-target','8','-cp',cp,'-d',str(OUT)]+[str(ROOT/n) for n in files],text=True,capture_output=True)
(OUT/'compile.log').write_text(p.stdout+p.stderr)
if p.returncode:print(p.stderr);raise SystemExit(p.returncode)
for j in ['jdk8','jdk21']:
 p=subprocess.run([str(WORK/'toolchains'/j/'bin/java'),'-Xverify:all','-cp',str(OUT)+':'+cp,'LodBoundaryProbe'],text=True,capture_output=True);(OUT/(j+'-lod.log')).write_text(p.stdout+p.stderr);print(j,p.stdout,p.stderr)
 if p.returncode:raise SystemExit(p.returncode)
(OUT/'SOURCE-SHA256.json').write_text(json.dumps({n:hashlib.sha256((ROOT/n).read_bytes()).hexdigest() for n in files},indent=2)+'\n')
