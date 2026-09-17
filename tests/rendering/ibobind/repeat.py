import json, statistics, subprocess, sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
E=HERE/'evidence'
runs=[]
for i in range(1,6):
    p=subprocess.run([sys.executable,str(HERE/'run.py')],cwd=HERE.parents[2],capture_output=True,text=True,timeout=120)
    if p.returncode: print(p.stdout); print(p.stderr,file=sys.stderr); raise SystemExit(p.returncode)
    data=json.loads((E/'shared-ibo.json').read_text(encoding='utf-8'))
    runs.append(data['result']['cases'])
summary={}
for group in (1,2,3,4,8,16):
    rows=[next(c for c in run if c['group']==group) for run in runs]
    ratios=[r['cpu_candidate_median_ms']/r['cpu_baseline_median_ms'] for r in rows]
    gratios=[r['gpu_candidate_median_ms']/r['gpu_baseline_median_ms'] for r in rows]
    summary[str(group)]={'cpu_ratio_median':statistics.median(ratios),'cpu_ratios':ratios,
                         'gpu_ratio_median':statistics.median(gratios),'gpu_ratios':gratios,
                         'all_pixels_exact':all(r['changed_rgba8_bytes']==0 for r in rows)}
(E/'shared-ibo-repeat-summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary,indent=2))
