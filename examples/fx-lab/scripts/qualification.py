#!/usr/bin/env python3
"""SC-10: collect external-client acceptance, then combine attributable evidence and fixed budgets.

collect ROOT NEW_OUTPUT; report ROOT OUTPUT --performance SC01_QUICK_DIRECTORY
All commands use the wrapper-built fx-lab and independent published consumers.
"""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from qa_common import check, digest, environment, read_json, run_owned, stop

PREFIXES = ('src/', 'examples/fx-lab/', 'qa/', 'gradle/')
ACCEPTANCE_CASES = {
    'discovery-password-redaction-exclusion', 'unicode-code-point-write-read',
    'dynamic-events-stale-reference-new-incarnation', 'second-window-open-write-close',
    'scene-replacement-and-restoration', 'modal-read-denial-and-dialog-action',
    'bounded-overload-expiry-no-late-effect-recovery', 'text-children-state-events',
    'private-bus-loss-native-termination-fx-usable',
}
MIN_CASES = {'acceptance': 9, 'semantics': 22, 'geometry': 16, 'modular': 1, 'classpath': 1}
# Issue-1 initial budgets, not values derived from the candidate collection. Units as published.
LATENCY = {100: {1:42,10:41,100:29}, 1000:{1:42,10:29,100:190}, 10000:{1:631,10:307}}
ALLOCATION = {100:{1:34,10:116,100:403}, 1000:{1:205,10:299,100:2581}, 10000:{1:8506,10:8844}}
OTHER = {
    'idleCpuPercent': (1, {100:8,1000:14,10000:137}),
    'rssAfterIdleKb': (1024, {100:327,1000:434,10000:1590}),
    'installChurnHeapDeltaBytesAfterGc': (2**20, {100:1,1000:57,10000:1}),
    'installChurnProviderThreadsCheckpoint': (1, {100:2,1000:117,10000:2}),
    'installChurnProviderThreadsAfterDrain': (1, {100:2,1000:2,10000:2}),
    'structuralHeapDeltaBytesAfterGc': (2**20, {100:2,1000:6,10000:18}),
}


def git(root, *args):
    return subprocess.check_output(['git','-C',str(root),*args], text=True, timeout=10).strip()


# The launchers select JAVA_HOME before PATH; pin that same JDK for every child.
def java_launcher():
    home = os.environ.get('JAVA_HOME')
    binary = str(Path(home)/'bin/java') if home else shutil.which('java')
    check(bool(binary) and Path(binary).is_file(), 'java-launcher-missing')
    return Path(binary).resolve()


def version_lines(output):
    lines = [line.strip() for line in output.splitlines()]
    return [line for line in lines if '=' not in line and
            (re.match(r'^(?:openjdk|java) version "', line) or 'Runtime Environment' in line
             or re.match(r'^.* (?:Server |Client )?VM .*\(build ', line))]


def validate_java(value):
    lines = value.get('java')
    check(isinstance(lines, list) and len(lines) == 3 and
          all(isinstance(line, str) and len(line) <= 512 and not any(ord(c) < 32 for c in line)
              for line in lines), 'java-version-missing')
    match = re.fullmatch(r'(?:openjdk|java) version "(27(?:[.+-][^" ]+)?)"(?: [^\r\n]*)?', lines[0])
    check(match is not None and 'Runtime Environment' in lines[1] and ' VM ' in lines[2], 'jdk27-required')
    builds = [re.search(r'\(build ([^,) ]+)', line) for line in lines[1:]]
    check(all(build is not None for build in builds) and
          builds[0][1] == builds[1][1] and re.match(r'^27(?:[.+-]|$)', builds[0][1]), 'java-build-ambiguous')
    # Historical metadata has only the three version lines. Never default a missing/invalid version to 27.
    if 'javaSpecificationVersion' in value:
        check(value['javaSpecificationVersion'] == '27', 'jdk27-required')
    return tuple(lines)


def java_metadata(binary, env):
    result = subprocess.run([str(binary), '-XshowSettings:properties', '-version'], env=env,
                            check=True, capture_output=True, text=True, timeout=10)
    output = result.stderr + '\n' + result.stdout
    specs = re.findall(r'^ *java\.specification\.version = ([^\r\n]+)$', output, re.MULTILINE)
    check(specs == ['27'], 'jdk27-required')
    value = {'java':version_lines(output), 'javaSpecificationVersion':specs[0]}
    validate_java(value)
    return value


def java_version():
    return java_metadata(java_launcher(), environment())['java']


def source_provenance(root):
    files = git(root, 'ls-files').splitlines()
    relevant = [name for name in files if name.startswith(PREFIXES) or name in
                ('build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat')]
    return {'sourceHead':git(root,'rev-parse','HEAD'),
            'sourceSha256':{name:digest(root/name) for name in relevant}}


def provenance(root):
    lib = root/'examples/fx-lab/build/install/fx-lab/lib'
    jars = {p.name:digest(p) for p in sorted(lib.glob('*.jar'))}
    check(len(jars) == 5, 'installed-jar-count')
    # Include launchers, consumers, publication reports and JUnit results in the stability check.
    outputs = [root/'examples/fx-lab/build/install/fx-lab/bin/fx-lab',
               root/'build/consumer-qa/artifacts.json',
               *sorted((root/'build/libs').glob('*.jar')),
               *sorted((root/'build/test-results/test').glob('TEST-*.xml'))]
    for mode in ('modular','classpath'):
        outputs.extend([root/f'build/consumer-qa/{mode}/install/{mode}/bin/{mode}',
                        *sorted((root/f'build/consumer-qa/{mode}/install/{mode}/lib').glob('*.jar'))])
    return {**source_provenance(root), 'jars':jars,
            'outputsSha256':{str(p.relative_to(root)):digest(p) for p in outputs}}


def rebuild(root, out, env):
    command = ['./gradlew', 'check', ':examples:fx-lab:installDist', '--rerun-tasks',
               '--no-build-cache', '--no-daemon', '--console=plain']
    record = {'status':'Failed', 'command':command, 'timeoutSeconds':1200}
    try:
        with (out/'build.log').open('w') as log:
            code = run_owned([str(root/'gradlew'), *command[1:]], log=log, env=env,
                             timeout=1200, cwd=root)
        record['exitCode'] = code
        check(code == 0, 'qualification-build-failed')
        record['status'] = 'Passed'
    except Exception as error:
        record['errorCode'] = type(error).__name__
        raise
    finally:
        (out/'build.json').write_text(json.dumps(record,indent=2)+'\n')
    return record


def validate_suite(name, value, exit_code):
    check(exit_code == 0 and value.get('status') == 'Passed', 'suite-failed')
    cases = value.get('cases')
    check(isinstance(cases,list) and MIN_CASES[name] <= len(cases) <= 64, 'suite-incomplete')
    if name == 'geometry':
        check(all(case.get('status') == 'Passed' for case in cases), 'geometry-case-failed')
        check([case.get('step') for case in cases] == list(range(len(cases))) and
              len({case.get('case') for case in cases}) == len(cases), 'geometry-checkpoints')
        check(value.get('backend',{}).get('backend') in ('X11','XWayland','Wayland'), 'backend-missing')
    else:
        check(all(isinstance(case,str) and len(case) < 128 for case in cases) and len(set(cases)) == len(cases), 'case-identities')
    if name == 'acceptance':
        check(ACCEPTANCE_CASES == set(cases), 'adverse-cases-missing')
    result = {'status':'Passed', 'cases':cases, **({'backend':value['backend']} if name=='geometry' else {})}
    if name == 'acceptance':
        for key in ('retiredPathError','overloadError','eventCount','events'):
            if key in value: result[key] = value[key]
    return result


def resource_evidence(root):
    directory = root/'build/test-results/test'
    paths = sorted(directory.glob('TEST-*.xml'))
    expected = {'TEST-'+str(p.relative_to(root/'src/test/java')).removesuffix('.java').replace('/', '.')+'.xml'
                for p in (root/'src/test/java').rglob('*Test.java')}
    check(bool(expected) and {p.name for p in paths} == expected, 'unit-evidence-incomplete')
    total = 0
    records = []
    for path in paths:
        suite = ET.parse(path).getroot()
        check(all(int(suite.attrib.get(key,0)) == 0 for key in ('failures','errors','skipped')), 'unit-suite-not-passed')
        total += int(suite.attrib['tests'])
        for output in suite.findall('system-out'):
            for line in (output.text or '').splitlines():
                if line.startswith('SC10_RESOURCES '):
                    records.append(json.loads(line.removeprefix('SC10_RESOURCES ')))
    check(len(records) == 1 and records[0].get('cycles') == 10 and records[0].get('busLossCycles') == 1, 'resource-evidence-missing')
    record = records[0]
    check(all(record.get(key) == 0 for key in ('targetsAfterClose','pendingAfterClose','outstandingAfterClose')), 'resource-retention')
    check(all(isinstance(record.get(key),int) and record[key] > 0 for key in
              ('invocationsBalanced','exportsBalanced','definitionsBalanced')), 'resource-counters')
    return {'status':'Passed', 'javaTests':total, 'counters':record,
            'junitSha256':{p.name:digest(p) for p in paths}}


def consumer(launcher, directory):
    import gi
    gi.require_version('Atspi','2.0')
    from gi.repository import Atspi
    from qa_common import discover, tree, until
    Atspi.set_timeout(2000,5000)
    directory.mkdir()
    result = {'status':'Failed','cases':[]}
    process = None
    try:
        with (directory/'fixture.log').open('w') as log:
            process = subprocess.Popen([str(launcher)], stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                       text=True, env=environment())
        app = discover(process.pid,'SC10 Consumer')
        found = tree(app)
        check({'consumer-action','consumer-result'} <= found.keys(), 'consumer-tree')
        check(Atspi.Action.do_action(found['consumer-action'],0), 'consumer-action')
        until(lambda: tree(app)['consumer-result'].get_name() == '1')
        process.stdin.write('close\n'); process.stdin.flush()
        process.wait(timeout=15)
        check(process.returncode == 0 and 'CONSUMER_CLOSED' in (directory/'fixture.log').read_text(), 'consumer-native-close')
        result.update(status='Passed',cases=['published-consumer-install-action-close'])
    except Exception as error:
        result['errorCode'] = type(error).__name__
    finally:
        stop(process)
        (directory/'results.json').write_text(json.dumps(result,indent=2)+'\n')
    return 0 if result['status'] == 'Passed' else 1


def collect(root, out):
    check(__debug__, 'python-optimization-forbidden')
    check(not git(root,'status','--porcelain'), 'clean-commit-required')
    binary = java_launcher()
    env = environment(); env['JAVA_HOME'] = str(binary.parent.parent)
    jvm = java_metadata(binary, env)
    source_before = source_provenance(root)
    out.mkdir(parents=True,exist_ok=False)
    env['REQUIRE_FX_TOOLKIT'] = '1'; env['REQUIRE_NATIVE_BUS'] = '1'
    build = rebuild(root, out, env)
    check(not git(root,'status','--porcelain') and source_before == source_provenance(root),
          'build-source-changed')
    check(jvm == java_metadata(binary, env), 'build-java-changed')
    before = provenance(root)
    scripts = root/'examples/fx-lab/scripts'
    launcher = root/'examples/fx-lab/build/install/fx-lab/bin/fx-lab'
    metadata = {**before, 'collectedUtc':datetime.now(timezone.utc).isoformat(),
                'os':platform.freedesktop_os_release().get('PRETTY_NAME'), 'kernel':platform.release(),
                'session':os.environ.get('XDG_SESSION_TYPE'), 'desktop':os.environ.get('XDG_CURRENT_DESKTOP'),
                'python':platform.python_version(),
                **jvm, 'build':build,
                'packages':subprocess.run(['rpm','-q','gnome-shell','mutter','at-spi2-core','python3-gobject','xorg-x11-server-Xwayland'],
                                          capture_output=True,text=True,timeout=10).stdout.splitlines()}
    artifacts = read_json(root/'build/consumer-qa/artifacts.json')
    check(artifacts['status'] == 'Passed', 'artifact-gate')
    for name, value in artifacts['artifacts'].items():
        check(digest(root/'build/libs'/name) == value, 'artifact-changed')
        if name in before['jars']:
            check(value == before['jars'][name], 'installed-artifact-mismatch')
    (out/'environment.json').write_text(json.dumps(metadata,indent=2)+'\n')
    suites = {}
    for name, limit in (('acceptance',180),('semantics',240),('geometry',240),('modular',45),('classpath',45)):
        if name == 'acceptance':
            command = ['dbus-run-session','--',sys.executable,str(scripts/'acceptance_qa.py'),str(launcher),str(out/name)]
        elif name in ('semantics','geometry'):
            command = [sys.executable,str(scripts/(name+'_qa.py')),str(launcher),str(out/name)]
        else:
            consumer_binary = root/'build/consumer-qa'/name/'install'/name/'bin'/name
            command = [sys.executable,__file__,'consumer',str(consumer_binary),str(out/name)]
        suite_env = dict(env); suite_env['SC10_PRIVATE_SESSION'] = '1'
        try:
            with (out/(name+'.log')).open('w') as log:
                exit_code = run_owned(command,log=log,env=suite_env,timeout=limit)
            value = read_json(out/name/'results.json')
            suites[name] = validate_suite(name,value,exit_code)
        except Exception as error:
            suites[name] = {'status':'Failed','errorCode':type(error).__name__}
        print(name+': '+suites[name]['status'],flush=True)
    check(not git(root,'status','--porcelain') and before == provenance(root), 'collection-source-or-artifact-changed')
    check(jvm == java_metadata(binary, env), 'collection-java-changed')
    result = {'status':'Passed' if all(v['status']=='Passed' for v in suites.values()) else 'Failed',
              'sourceHead':before['sourceHead'],'jars':before['jars'],'suites':suites,'artifacts':artifacts,
              'resources':resource_evidence(root), 'build':build}
    (out/'functional.json').write_text(json.dumps(result,indent=2)+'\n')
    return 0 if result['status']=='Passed' else 1


QUICK_PLAN = {100:(3,(1,10,100),100), 1000:(3,(1,10,100),100), 10000:(2,(1,10),30)}
# Budgets are measured with an unoccluded, focused benchmark window (best case); see issue-10-performance.md.
HISTORICAL_WINDOW = 'unrecorded-historical'
WINDOWS = ('foreground', HISTORICAL_WINDOW)
QUICK_WORKLOAD = {'warmup':50, 'samples':200, 'idle':10, 'installChurn':100}


def quick_files():
    return {f'n{n}-d{d}-{bridge}-r{rep}.json':(n,d,bridge)
            for n,(reps,dirties,_) in QUICK_PLAN.items() for d in dirties
            for bridge in ('off','on') for rep in range(1,reps+1)}


def quick_comparisons():
    expected = {}
    for name,(n,d,bridge) in quick_files().items():
        if bridge == 'off': continue
        expected[name,'latencyP99Ms'] = LATENCY[n][d]
        expected[name,'allocatedBytesPerSample'] = ALLOCATION[n][d]
        if d == 10:
            expected.update({(name,metric):limits[n] for metric,(_,limits) in OTHER.items()})
    return expected


def validate_comparisons(perf):
    expected = quick_comparisons()
    comparisons = perf.get('comparisons')
    check(isinstance(comparisons,list) and len(comparisons) == len(expected), 'performance-incomplete')
    seen = set()
    for c in comparisons:
        key = c.get('run'),c.get('metric')
        check(key in expected and key not in seen and c.get('limit') == expected[key], 'performance-comparisons')
        seen.add(key)
        observed = c.get('observed')
        check(type(observed) in (int,float) and math.isfinite(observed) and type(c.get('passed')) is bool
              and c['passed'] == (observed <= expected[key]), 'performance-inconsistent')
    check(perf.get('exceedances') == [c for c in comparisons if not c['passed']], 'performance-exceedances')
    check((perf.get('status') == 'Passed') == all(c['passed'] for c in comparisons), 'performance-status')


def performance(root, directory, expected):
    env = (directory/'environment.txt').read_text()
    def field(key):
        entries = [line.split('=',1)[1] for line in env.splitlines() if line.startswith(key+'=')]
        check(len(entries)==1,'performance-environment')
        return entries[0]
    check(field('profile')=='quick' and field('git')==expected['sourceHead'],'performance-source-or-profile')
    check(field('tree') == 'clean', 'performance-dirty-source')
    spec = re.findall(r'^ *java\.specification\.version = ([^\r\n]+)$', env, re.MULTILINE)
    check(spec == ['27'], 'performance-jdk27-required')
    jvm = {'java':version_lines(env), 'javaSpecificationVersion':spec[0]}
    check(validate_java(jvm) == validate_java(expected), 'performance-java-mismatch')
    plans = [line.split('=',1)[1] for line in env.splitlines() if line.startswith('plan=')]
    canonical = {f'{n}:{reps}:{",".join(map(str,dirties))}:{cycles}'
                 for n,(reps,dirties,cycles) in QUICK_PLAN.items()}
    check(len(plans) == len(canonical) and set(plans) == canonical, 'performance-quick-plan')
    workload = field('workload').split(',')
    check(len(workload) == len(QUICK_WORKLOAD) and set(workload) ==
          {f'{k}:{v}' for k,v in QUICK_WORKLOAD.items()}, 'performance-quick-workload')
    windows = [line.split('=',1)[1] for line in env.splitlines() if line.startswith('window=')]
    check(windows in ([], ['foreground']), 'performance-window')
    # Earlier collections applied the foreground hint unconditionally; their recorded source proves it.
    window = windows[0] if windows else HISTORICAL_WINDOW
    check(field('limits_sha256') == digest(root/'examples/fx-lab/scripts/baseline.conf'), 'performance-limits')
    check({p.name for p in directory.glob('n*.json')} == set(quick_files()), 'performance-quick-files')
    for name, sha in expected['jars'].items():
        check(env.splitlines().count(sha+'  '+name) == 1,'performance-jar-mismatch')
    # Reuse canonical SC-01 validation of plan, repetitions, workload, readiness, pulse guards and retry rules.
    subprocess.run([sys.executable,str(root/'examples/fx-lab/scripts/budgets.py'),str(directory)],
                   check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
    comparisons=[]
    for path in sorted(directory.glob('n*-d*-on-r*.json')):
        run=read_json(path); n=run['nodes']; d=run['dirty']
        checks=[('latencyP99Ms',1,LATENCY[n][d]),('allocatedBytesPerSample',1024,ALLOCATION[n][d])]
        if d==10:
            checks.extend((metric,divisor,limits[n]) for metric,(divisor,limits) in OTHER.items())
        for metric,divisor,limit in checks:
            value=run[metric]/divisor
            check(isinstance(value,(int,float)) and math.isfinite(value),'invalid-metric')
            comparisons.append({'run':path.name,'metric':metric,'observed':value,'limit':limit,'passed':value<=limit})
    result = {'status':'Passed' if all(c['passed'] for c in comparisons) else 'Failed',
            'sourceHead':expected['sourceHead'],'jars':expected['jars'], **jvm, 'profile':'quick','window':window,'budgetSource':'docs/verification/issue-1.md#initial-budgets-provisional-until-15-derived-from-the-tables-above',
            'comparisons':comparisons,'exceedances':[c for c in comparisons if not c['passed']],
            'filesSha256':{p.name:digest(p) for p in sorted(directory.glob('*.json'))}}
    validate_comparisons(result)
    return result


def combine(functional, metadata, perf):
    validate_java(metadata)
    if 'build' in metadata or 'build' in functional:
        build = metadata.get('build', {})
        check(build == functional.get('build') and build.get('status') == 'Passed'
              and build.get('exitCode') == 0, 'build-evidence-failed')
    check(functional['sourceHead']==metadata['sourceHead'] and functional['jars']==metadata['jars'],'provenance-mismatch')
    check(len(functional['suites'])==len(MIN_CASES) and set(functional['suites'])==set(MIN_CASES),'missing-suite')
    for name,value in functional['suites'].items():
        validate_suite(name,value,0)
    check(functional['status']=='Passed' and functional['resources']['status']=='Passed'
          and functional['artifacts']['status']=='Passed','functional-gate')
    if perf.get('status') != 'Missing':
        check(perf.get('sourceHead')==metadata['sourceHead'] and perf.get('jars')==metadata['jars'], 'performance-provenance')
        check(perf.get('profile')=='quick', 'performance-incomplete')
        check(perf.get('window', HISTORICAL_WINDOW) in WINDOWS, 'performance-window')
        check(validate_java(perf) == validate_java(metadata), 'performance-java-mismatch')
        validate_comparisons(perf)
    passed = perf.get('status')=='Passed'
    return {'status':'Passed' if passed else 'Blocked','sourceHead':metadata['sourceHead'],'jars':metadata['jars'],
            'gates':{'functional':'Passed','artifacts':'Passed','resources':'Passed','performance':perf.get('status','Missing')},
            'performance':perf,'stableRelease':'Blocked','remainingReleaseGates':[
                'Full SC-01 matrix tracked in #15', 'Orca compatibility tracked in #32',
                'Only the measured JavaFX window backend is qualified; native Wayland is not established']}


def report(root,out,perf_dir):
    functional=read_json(out/'functional.json'); metadata=read_json(out/'environment.json')
    try:
        perf=performance(root,perf_dir,metadata) if perf_dir else {'status':'Missing'}
        value=combine(functional,metadata,perf)
    except Exception as error:
        value={'status':'Blocked','stableRelease':'Blocked','errorCode':type(error).__name__}
    (out/'qualification.json').write_text(json.dumps(value,indent=2)+'\n')
    print(value['status']+'; stable release: Blocked')
    return 0 if value['status']=='Passed' else 1


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=('collect','report','consumer'))
    parser.add_argument('root',type=Path)
    parser.add_argument('out',type=Path)
    parser.add_argument('--performance',type=Path)
    args=parser.parse_args()
    try:
        if args.mode=='consumer': code=consumer(args.root.resolve(),args.out.resolve())
        elif args.mode=='collect': code=collect(args.root.resolve(),args.out.resolve())
        else: code=report(args.root.resolve(),args.out.resolve(),args.performance.resolve() if args.performance else None)
    except Exception as error:
        print('Failed: '+type(error).__name__,file=sys.stderr); code=1
    raise SystemExit(code)
