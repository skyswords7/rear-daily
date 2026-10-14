import pathlib,subprocess,zipfile,shutil,sys,os,re,json,hashlib,urllib.request
release='--release' in sys.argv
with_tests='--with-tests' in sys.argv
if release and with_tests: raise SystemExit('Release builds cannot include instrumentation')
if release:
 for name in ('REAR_RELEASE_KEYSTORE','REAR_RELEASE_KEY_ALIAS','REAR_RELEASE_STORE_PASSWORD','REAR_RELEASE_KEY_PASSWORD'):
  if not os.environ.get(name): raise SystemExit('Missing release signing environment variable: '+name)
 if not pathlib.Path(os.environ['REAR_RELEASE_KEYSTORE']).is_file(): raise SystemExit('Release keystore not found')
r=pathlib.Path(__file__).resolve().parent
subprocess.run([sys.executable, str(r/'package-template.py')], check=True)
sdk=pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or r.parent/'tools/sdk')
def newest(directory, pattern):
 candidates=list(directory.glob(pattern)) if directory.is_dir() else []
 if not candidates: raise SystemExit('Missing Android SDK component: '+str(directory))
 return max(candidates, key=lambda p: tuple(map(int,re.findall(r'\d+',p.name))))
t=pathlib.Path(os.environ['REAR_BUILD_TOOLS']) if 'REAR_BUILD_TOOLS' in os.environ else newest(sdk/'build-tools','*')
a=pathlib.Path(os.environ['REAR_ANDROID_JAR']) if 'REAR_ANDROID_JAR' in os.environ else newest(sdk/'platforms','android-*')/'android.jar'
ext='.exe' if os.name=='nt' else ''
for required in (a,t/('aapt2'+ext),t/('zipalign'+ext),t/'lib/d8.jar',t/'lib/apksigner.jar'):
 if not required.is_file(): raise SystemExit('Missing build tool: '+str(required))
b=r/'build'; b.mkdir(exist_ok=True)
java_home=subprocess.run(['java','-XshowSettings:properties','-version'],capture_output=True,text=True).stderr
java_home=next(line.split('=',1)[1].strip() for line in java_home.splitlines() if 'java.home =' in line)
keytool=pathlib.Path(java_home)/('bin/keytool'+ext)
classes=b/'classes'
assert classes.resolve().parent == b.resolve() and b.resolve().parent == r.resolve()
if classes.exists(): shutil.rmtree(classes)
classes.mkdir()
# A previous multidex test build must never leak instrumentation into a release.
for stale_dex in b.glob('classes*.dex'): stale_dex.unlink()
with_tests='--with-tests' in sys.argv
manifest=r/'AndroidManifest.xml'
if with_tests:
 text=manifest.read_text(encoding='utf8').replace('android:debuggable="false"','android:debuggable="true"').replace('android:usesCleartextTraffic="false"','android:usesCleartextTraffic="true"').replace('</application>','<activity android:name=".UiTestActivity" android:exported="true"/></application>').replace('</manifest>','<instrumentation android:name="com.reardaily.app.SmokeInstrumentation" android:targetPackage="com.reardaily.app"/><instrumentation android:name="com.reardaily.app.DailyInstrumentation" android:targetPackage="com.reardaily.app"/></manifest>')
 manifest=b/'test-manifest.xml';manifest.write_text(text,encoding='utf8')
# Pinned precompiled AARs have no resources; provider manifest entries are explicit.
deps=b/'deps'; deps.mkdir(exist_ok=True)
jars=[]
for dependency in json.loads((r/'dependencies.json').read_text(encoding='utf8')):
 artifact=deps/dependency['file']
 if not artifact.is_file():
  print('Downloading',dependency['file'],flush=True)
  with urllib.request.urlopen(dependency['url'],timeout=45) as response:
   data=response.read(4*1024*1024+1)
  if len(data)>4*1024*1024 or hashlib.sha256(data).hexdigest()!=dependency['sha256']:
   raise SystemExit('Dependency checksum mismatch: '+dependency['file'])
  artifact.write_bytes(data)
 if hashlib.sha256(artifact.read_bytes()).hexdigest()!=dependency['sha256']:
  raise SystemExit('Dependency checksum mismatch: '+str(artifact))
 jar=artifact.with_suffix('.jar')
 if artifact.suffix=='.aar':
  with zipfile.ZipFile(artifact) as archive: jar.write_bytes(archive.read('classes.jar'))
 jars.append(jar)
sources=sorted((r/'src').rglob('*.java'))+(sorted((r/'tests').rglob('*.java')) if with_tests else [])
def run(*args):
 print('RUN',str(args[0]),flush=True);subprocess.run([str(x) for x in args],check=True,cwd=r)
run('javac','-encoding','UTF-8','-source','8','-target','8','-classpath',os.pathsep.join(map(str,[a]+jars)),'-d',classes,*sources)
run('java','-cp',t/'lib/d8.jar','com.android.tools.r8.D8','--min-api','31','--lib',a,'--output',b,*sorted(classes.rglob('*.class')),*jars)
run(t/('aapt2'+ext),'compile','--dir',r/'res','-o',b/'resources.zip')
run(t/('aapt2'+ext),'link',b/'resources.zip','-I',a,'--manifest',manifest,'-A',r/'assets','-o',b/'base.apk')
with zipfile.ZipFile(b/'base.apk','a',zipfile.ZIP_DEFLATED) as z:
 for dex in b.glob('classes*.dex'):z.write(dex,dex.name)
run(t/('zipalign'+ext),'-f','4',b/'base.apk',b/'aligned.apk')
if release:
 k=pathlib.Path(os.environ['REAR_RELEASE_KEYSTORE'])
 output=b/'rear-daily-release.apk'
 signing=['--ks',k,'--ks-key-alias',os.environ['REAR_RELEASE_KEY_ALIAS'],
          '--ks-pass','env:REAR_RELEASE_STORE_PASSWORD','--key-pass','env:REAR_RELEASE_KEY_PASSWORD']
else:
 k=r/'debug.keystore'
 if not k.exists():run(keytool,'-genkeypair','-keystore',k,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=RearDaily Development')
 output=b/'rear-daily.apk'
 signing=['--ks',k,'--ks-pass','pass:android']
run('java','-jar',t/'lib/apksigner.jar','sign',*signing,'--out',output,b/'aligned.apk')
run('java','-jar',t/'lib/apksigner.jar','verify',output)
print('BUILT',output)
