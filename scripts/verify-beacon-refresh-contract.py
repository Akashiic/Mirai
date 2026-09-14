#!/usr/bin/env python3
"""Source/packaging scope gate, NOT proof of in-game visual equivalence."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--jar',type=Path);p.add_argument('--baseline-jar',type=Path)
p.add_argument('--out',type=Path,required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[1];base='716ba89d4eb35ca7c82de6b01d25b0655e13fa41'
def git(*v):return subprocess.check_output(['git',*v],cwd=r,text=True).strip()
def baseline(rel):return subprocess.check_output(['git','show',base+':'+rel],cwd=r).decode('utf-8')
def method(s,sig):
    start=s.index(sig);i=s.index('{',start);depth=1;j=i+1
    while depth:
        depth+=(s[j]=='{')-(s[j]=='}');j+=1
    return s[start:j]
rel='coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.java'
old=baseline(rel);new=(r/rel).read_text(encoding='utf-8')
sig='private void refreshRenderingBeacons(DhBlockPos2D playerPos)'
body=method(new,sig)
normalized=body.replace('this.closing.get() || this.beaconBeamRepo','this.beaconBeamRepo').replace('\t\t\t\tif (this.closing.get()) { return; }\n','').replace('replaceBeacons && !this.closing.get()','replaceBeacons')
helper=rel.replace('LodQuadTree.java','BeaconRefreshScheduler.java')
allowed={rel,helper,'coreSubProjects/api/src/main/java/com/seibel/distanthorizons/coreapi/ModInfo.java'}
modified=git('diff','--name-only',base,'--','*src/main/*').splitlines()
added=git('ls-files','--others','--exclude-standard','--','*src/main/*').splitlines()
checks={'beacon_query_and_geometry_unchanged_except_closing_guards':normalized==method(old,sig),
        'tree_close_unchanged':method(old,'public void close()')==method(new,'public void close()'),
        'only_allowed_production_files':not(set(modified+added)-allowed)}
h=(r/helper).read_text(encoding='utf-8')
checks['no_sleep_or_new_timer_in_refresh']='Thread.sleep' not in method(new,'private void tryRefreshRenderingBeaconsAsync(DhBlockPos2D playerPos)') and all(s not in h for s in ['Thread.sleep','new Thread(','ScheduledExecutor','TimerTask','Thread.stop'])
checks['same_two_second_delay']='TimeUnit.SECONDS.toNanos(2)' in h
checks['no_old_future_consumers']='beaconUpdateFuture' not in new
result={'baseline_commit':base,'candidate_commit':git('rev-parse','HEAD'),'scope':__doc__,'checks':checks,'modified_production':modified,'new_production':added,
        'source_hashes':{f:hashlib.sha256((r/f).read_bytes()).hexdigest() for f in sorted(allowed)}}
if a.jar:
    if not a.baseline_jar:raise SystemExit('--baseline-jar required with --jar')
    sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
    with zipfile.ZipFile(a.baseline_jar) as x,zipfile.ZipFile(a.jar) as y:
        xs=set(x.namelist());ys=set(y.namelist())
        shaders=lambda ns:{n for n in ns if n.startswith('assets/distanthorizons/shaders/') and not n.endswith('/')}
        sx=shaders(xs);sy=shaders(ys);different=sorted(n for n in sx&sy if x.read(n)!=y.read(n))
        checks['baseline_jar_identity']=sha(a.baseline_jar)=='bb0748c9da9ce145a7f9e7bc066202856852875af8be9784650f6887e397442c'
        checks['all_45_shaders_byte_equal']=len(sx)==45 and sx==sy and not different
        changed=sorted(n for n in xs&ys if n.endswith('.class') and x.read(n)!=y.read(n))
        new_classes=sorted(n for n in ys-xs if n.endswith('.class'))
        removed_classes=sorted(n for n in xs-ys if n.endswith('.class'))
        permitted={'com/seibel/distanthorizons/coreapi/ModInfo.class','com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.class'}
        version_only=[]; unexpected=[]
        old_version=b'0.1.16-dev'; new_version=b'0.1.17-dev'
        for name in changed:
            if name in permitted: continue
            before=x.read(name); after=y.read(name)
            if before.count(old_version)==1 and after.count(new_version)==1 and before.replace(old_version,new_version)==after:
                version_only.append(name)
            else: unexpected.append(name)
        checks['expected_functional_classes_changed']=permitted.issubset(set(changed))
        checks['only_version_inlining_outside_functional_classes']=not unexpected
        checks['only_scheduler_classes_added']=len(new_classes)==2 and all(n.startswith('com/seibel/distanthorizons/core/render/QuadTree/BeaconRefreshScheduler') for n in new_classes)
        checks['no_class_removed']=not removed_classes
        result['jar']={'sha256':sha(a.jar),'baseline_sha256':sha(a.baseline_jar),'shader_count':len(sy),'shader_differences':different,'changed_classes':changed,'version_only_changed_classes':version_only,'unexpected_changed_classes':unexpected,'added_classes':new_classes,'removed_classes':removed_classes}
result['passed']=all(checks.values());a.out.parent.mkdir(parents=True,exist_ok=True)
a.out.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':result['passed'],'failed_checks':[k for k,v in checks.items() if not v],'report':str(a.out)}))
raise SystemExit(0 if result['passed'] else 1)
