#!/usr/bin/env python3
import hashlib,json,os,pathlib,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[3];BASE=ROOT.parent
OUT=pathlib.Path(os.environ.get('MIRAI_S05_CALLERS_EVIDENCE',BASE/'homologation-0.1.4-evidence/storage/s05-callers'));OUT.mkdir(parents=True,exist_ok=True)
SRC=ROOT/'coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core'
paths={
'prop':SRC/'file/fullDatafile/V2/FullDataUpdatePropagatorV2.java',
'remote':SRC/'file/fullDatafile/RemoteFullDataSourceProvider.java',
'generated':SRC/'file/fullDatafile/GeneratedFullDataSourceProvider.java',
'server':SRC/'level/AbstractDhServerLevel.java'}
texts={k:v.read_text() for k,v in paths.items()}
def extract(text,signature):
 start=text.index(signature);op=text.index('{',start);depth=1;end=op+1
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
methods={
'// PROPAGATOR_METHODS':'\n'.join(extract(texts['prop'],s) for s in ['private void propagateChildrenToParent(','private void propagateParentToChildren(','private void runParentUpdates(','private void runChildUpdates(']),
'// REMOTE_METHODS':'\n'.join(extract(texts['remote'],s) for s in ['private void saveRemoteUpdate(','private void remoteUpdateFailed(']),
'// GENERATED_METHOD':extract(texts['generated'],'private void onWorldGenTaskComplete('),
'// SERVER_METHODS':'\n'.join(extract(texts['server'],s) for s in ['public CompletableFuture<Void> updateDataSourcesAsync(','private void broadcastCommittedData('])}
template=(ROOT/'tests/storage/s05/S05CallersProbe.template').read_text()
current=template
for key,value in methods.items():current=current.replace(key,value)
source=OUT/'S05CallersProbe.java';source.write_text(current)
results={'boundary':'Exact production caller methods; source/provider/network/SQL are declared deterministic doubles. Real SQLite acknowledgement tested separately.','source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths.values()},'runs':[]}
for version in ['jdk8','jdk21']:
 classes=OUT/version;classes.mkdir(exist_ok=True)
 java=BASE/'toolchains'/version/'bin'
 compile=subprocess.run([str(java/'javac'),'-source','8','-target','8','-d',str(classes),str(source)],text=True,capture_output=True)
 (OUT/(version+'-compile.log')).write_text(compile.stdout+compile.stderr)
 if compile.returncode:raise RuntimeError(compile.stderr)
 run=subprocess.run([str(java/'java'),'-Xverify:all','-cp',str(classes),'S05CallersProbe'],text=True,capture_output=True,timeout=30)
 (OUT/(version+'.log')).write_text(run.stdout+run.stderr)
 results['runs'].append({'java':version,'exit':run.returncode,'stdout':run.stdout.strip()})
 if run.returncode:raise RuntimeError(run.stdout+run.stderr)
# A legacy generated callback drops ownership on an unacknowledged synchronous write.
old=subprocess.run(['git','show','43eab6cb293197bddbd0ace4622864e7594252a1:core/src/main/java/com/seibel/distanthorizons/core/file/fullDatafile/GeneratedFullDataSourceProvider.java'],cwd=ROOT/'coreSubProjects',text=True,capture_output=True,check=True).stdout
baseline=current.replace(methods['// GENERATED_METHOD'],extract(old,'private void onWorldGenTaskComplete('))
baseline=baseline.replace('propagateTests();remoteTests();generatedTests();serverTests();','generatedTests();')
neg=OUT/'baseline';neg.mkdir(exist_ok=True);(neg/'S05CallersProbe.java').write_text(baseline)
for version in ['jdk8','jdk21']:
 classes=neg/version;classes.mkdir(exist_ok=True);java=BASE/'toolchains'/version/'bin'
 compile=subprocess.run([str(java/'javac'),'-source','8','-target','8','-d',str(classes),str(neg/'S05CallersProbe.java')],text=True,capture_output=True)
 if compile.returncode:raise RuntimeError(compile.stderr)
 run=subprocess.run([str(java/'java'),'-Xverify:all','-cp',str(classes),'S05CallersProbe'],text=True,capture_output=True,timeout=30)
 (OUT/(version+'-baseline-generated-negative.log')).write_text(run.stdout+run.stderr)
 expected=run.returncode!=0 and 'failed generation write closes result expected=1 actual=0' in run.stderr
 results['runs'].append({'java':version,'test':'0.1.3-generated-negative','detected_expected_failure':expected,'exit':run.returncode})
 if not expected:raise RuntimeError('Unexpected baseline result '+run.stdout+run.stderr)
# Negative controls for premature propagation acknowledgement and broadcast ownership.
oldprop=subprocess.run(['git','show','43eab6cb293197bddbd0ace4622864e7594252a1:core/src/main/java/com/seibel/distanthorizons/core/file/fullDatafile/V2/FullDataUpdatePropagatorV2.java'],cwd=ROOT/'coreSubProjects',text=True,capture_output=True,check=True).stdout
idx=oldprop.index('executor.execute(() ->',oldprop.index('private void runParentUpdates'))
body=extract(oldprop[idx:], 'executor.execute(() ->')
body=body[body.index('{')+1:-1]
oldmethod='private void propagateChildrenToParent(long parentPos, Set<Long> childPositions) { long parentUpdatePos=parentPos; HashMap<Long,HashSet<Long>> updatePosByParentPos=new HashMap<>(); updatePosByParentPos.put(parentPos,new HashSet<>(childPositions)); try {'+body+'} catch (Exception expectedFailure) {} }'
oldserver=subprocess.run(['git','show','43eab6cb293197bddbd0ace4622864e7594252a1:core/src/main/java/com/seibel/distanthorizons/core/level/AbstractDhServerLevel.java'],cwd=ROOT/'coreSubProjects',text=True,capture_output=True,check=True).stdout
negative_cases=[
 ('propagation',current.replace(extract(texts['prop'],'private void propagateChildrenToParent('),oldmethod).replace('propagateTests();remoteTests();generatedTests();serverTests();','Propagator p=new Propagator();p.dataUpdater.fail=100;p.propagateChildrenToParent(100,children());eq(4,p.provider.repo.up.size(),"parent failure must keep dirty flags");'),'parent failure must keep dirty flags expected=4 actual=0'),
 ('broadcast',current.replace(extract(texts['server'],'public CompletableFuture<Void> updateDataSourcesAsync('),extract(oldserver,'public CompletableFuture<Void> updateDataSourcesAsync(')).replace('propagateTests();remoteTests();generatedTests();serverTests();','serverTests();'),'zero-recipient creator released expected=0 actual=1')]
for case,content,expected_message in negative_cases:
    folder=OUT/('baseline-'+case);folder.mkdir(exist_ok=True);(folder/'S05CallersProbe.java').write_text(content)
    for version in ['jdk8','jdk21']:
        java=BASE/'toolchains'/version/'bin';classes=folder/version;classes.mkdir(exist_ok=True)
        compile=subprocess.run([str(java/'javac'),'-source','8','-target','8','-d',str(classes),str(folder/'S05CallersProbe.java')],text=True,capture_output=True)
        if compile.returncode:raise RuntimeError(compile.stderr)
        run=subprocess.run([str(java/'java'),'-Xverify:all','-cp',str(classes),'S05CallersProbe'],text=True,capture_output=True,timeout=30)
        (OUT/(version+'-baseline-'+case+'-negative.log')).write_text(run.stdout+run.stderr)
        detected=run.returncode!=0 and expected_message in run.stderr
        results['runs'].append({'java':version,'test':'0.1.3-'+case+'-negative','detected_expected_failure':detected,'exit':run.returncode})
        if not detected:raise RuntimeError('Unexpected negative '+case+' '+run.stdout+run.stderr)
results['passed']=True;(OUT/'RESULTS.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results,indent=2))
