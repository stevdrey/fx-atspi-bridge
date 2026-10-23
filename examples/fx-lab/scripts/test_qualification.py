"""Failure-oriented checks of publication, bounded cleanup and evidence gates (no desktop claims)."""
from pathlib import Path
import copy
import json
import subprocess
import os
import sys
import tempfile
import unittest
from unittest.mock import Mock, call, patch
import zipfile
import consumer_check
import qa_common
import qualification as qa
from test_budgets import run_json


JAVA = ['openjdk version "27" 2026-09-15',
        'OpenJDK Runtime Environment Zulu27.28+101-CA (build 27+35)',
        'OpenJDK 64-Bit Server VM Zulu27.28+101-CA (build 27+35, mixed mode, sharing)']
ROOT = Path(__file__).resolve().parents[3]


class ArtifactChecks(unittest.TestCase):
    def test_duplicate_or_unexpected_javafx_fails(self):
        names = ['fx-atspi-bridge-0.1.0-SNAPSHOT.jar', *(f'javafx-{p}-27-linux.jar' for p in ('base','graphics','controls'))]
        consumer_check.inspect_runtime(names,'0.1.0-SNAPSHOT','27')
        for invalid in (names+['javafx-base-27-linux.jar'],names+['javafx-base-27.jar'],names[:-1],names+['junit.jar']):
            with self.assertRaises(AssertionError):
                consumer_check.inspect_runtime(invalid,'0.1.0-SNAPSHOT','27')

    def test_javafx_version_comes_from_the_build(self):
        names = ['fx-atspi-bridge-0.1.0.jar', *(f'javafx-{p}-27.0.1-linux.jar' for p in ('base','graphics','controls'))]
        consumer_check.inspect_runtime(names,'0.1.0','27.0.1')
        with self.assertRaises(AssertionError): consumer_check.inspect_runtime(names,'0.1.0','27')

    def test_release_and_snapshot_publications_resolve_their_files(self):
        with tempfile.TemporaryDirectory() as directory:
            publication=Path(directory)
            self.assertEqual('fx-atspi-bridge-0.1.0',consumer_check.published_stem(publication,'0.1.0'))
            (publication/'maven-metadata.xml').write_text('<metadata><versioning><snapshotVersions><snapshotVersion>'
                '<extension>pom</extension><value>0.1.0-20261022.101010-1</value></snapshotVersion>'
                '</snapshotVersions></versioning></metadata>')
            self.assertEqual('fx-atspi-bridge-0.1.0-20261022.101010-1',
                             consumer_check.published_stem(publication,'0.1.0-SNAPSHOT'))

    def test_missing_notice_or_bundled_javafx_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'artifact.jar'
            for missing, extra in ((True,False),(False,True),(False,False)):
                with zipfile.ZipFile(path,'w') as jar:
                    for name in consumer_check.NOTICES:
                        if missing and name.endswith('NOTICE'):
                            continue
                        jar.writestr(name,'license')
                    jar.writestr('module-info.class','module')
                    if extra:
                        jar.writestr('javafx/scene/Node.class','bad')
                if missing or extra:
                    with self.assertRaises(AssertionError): consumer_check.inspect_jar(path,True)
                else:
                    self.assertEqual(64,len(consumer_check.inspect_jar(path,True)))


class EvidenceGates(unittest.TestCase):
    def setUp(self):
        suites = {}
        for name,count in qa.MIN_CASES.items():
            if name=='geometry':
                cases=[{'status':'Passed','step':i,'case':f'geometry-{i}'} for i in range(count)]
            else:
                cases=[f'case-{i}' for i in range(count)]
            suites[name]={'status':'Passed','cases':cases}
        suites['acceptance']['cases']=sorted(qa.ACCEPTANCE_CASES)
        suites['geometry']['backend']={'backend':'XWayland'}
        self.metadata={'sourceHead':'a'*40,'jars':{'bridge.jar':'b'*64},'java':JAVA.copy()}
        self.functional={**self.metadata,'status':'Passed','suites':suites,
                         'resources':{'status':'Passed'},'artifacts':{'status':'Passed'}}
        self.perf={**self.metadata,'status':'Passed','profile':'quick', 'exceedances':[],
                   'comparisons':[{'run':run,'metric':metric,'observed':0,'limit':limit,'passed':True}
                                  for (run,metric),limit in qa.quick_comparisons().items()]}

    def combine(self):
        return qa.combine(self.functional,self.metadata,self.perf)

    def test_passed_campaign_keeps_known_release_gates_blocked(self):
        result=self.combine()
        self.assertEqual('Passed',result['status'])
        self.assertEqual('Blocked',result['stableRelease'])
        self.assertTrue(result['remainingReleaseGates'])

    def test_failed_or_missing_new_build_record_rejected(self):
        self.metadata['build']={'status':'Failed','exitCode':1}
        with self.assertRaisesRegex(AssertionError,'build-evidence-failed'):self.combine()
        self.metadata['build']={'status':'Passed','exitCode':0}
        with self.assertRaisesRegex(AssertionError,'build-evidence-failed'):self.combine()
        self.functional['build']=self.metadata['build'].copy()
        self.assertEqual('Passed',self.combine()['status'])

    def test_missing_or_failed_performance_blocks(self):
        saved=copy.deepcopy(self.perf)
        self.perf={'status':'Missing'}
        self.assertEqual('Blocked',self.combine()['status'])
        self.perf=saved; self.perf['status']='Failed'
        c=self.perf['comparisons'][0]; c.update(observed=c['limit']+1,passed=False)
        self.perf['exceedances']=[c]
        self.assertEqual('Blocked',self.combine()['status'])

    def test_old_commit_or_different_jar_rejected(self):
        for key,value in (('sourceHead','c'*40),('jars',{'other.jar':'d'*64})):
            with self.subTest(key=key):
                saved=self.functional[key]; self.functional[key]=value
                with self.assertRaises(AssertionError): self.combine()
                self.functional[key]=saved
                saved=self.perf[key]; self.perf[key]=value
                with self.assertRaises(AssertionError): self.combine()
                self.perf[key]=saved

    def test_missing_suite_and_missing_adverse_case_rejected(self):
        del self.functional['suites']['modular']
        with self.assertRaises(AssertionError): self.combine()
        self.setUp()
        self.functional['suites']['acceptance']['cases'][0]='different-case'
        with self.assertRaises(AssertionError): self.combine()

    def test_failed_or_partial_or_duplicate_case_rejected(self):
        for mutation in ('failed','partial','duplicate'):
            self.setUp()
            target=self.functional['suites']['semantics']
            if mutation=='failed': target['status']='Failed'
            elif mutation=='partial': target['cases'].pop()
            else: target['cases'][0]=target['cases'][1]
            with self.assertRaises(AssertionError): self.combine()

    def test_geometry_failure_or_missing_backend_rejected(self):
        self.functional['suites']['geometry']['cases'][0]['status']='Failed'
        with self.assertRaises(AssertionError): self.combine()
        self.setUp()
        self.functional['suites']['geometry']['cases'][1]['step']=0
        with self.assertRaises(AssertionError): self.combine()
        self.setUp()
        del self.functional['suites']['geometry']['backend']
        with self.assertRaises(AssertionError): self.combine()

    def test_performance_cannot_claim_pass_without_measurements(self):
        for comparisons in ([],[{'observed':3,'limit':2,'passed':True}],
                            [{'observed':float('nan'),'limit':2,'passed':True}]):
            self.perf['comparisons']=comparisons
            with self.assertRaises(AssertionError): self.combine()

    def test_nonzero_suite_exit_fails_even_with_passed_json(self):
        with self.assertRaises(AssertionError):
            qa.validate_suite('modular',self.functional['suites']['modular'],1)

    def test_skipped_resource_test_is_not_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); test=root/'build/test-results/test'; test.mkdir(parents=True)
            (test/'TEST-unit.xml').write_text('<testsuite tests="1" skipped="1" failures="0" errors="0"/>')
            with self.assertRaises(AssertionError): qa.resource_evidence(root)

    def test_unsupported_historical_or_different_performance_jvm_rejected(self):
        for major in ('26','28'):
            self.setUp()
            self.metadata['java']=[line.replace('27',major) for line in JAVA]
            with self.assertRaises(AssertionError): self.combine()
        self.setUp(); self.perf['java']=[line.replace('Zulu','OtherVendor') for line in JAVA]
        with self.assertRaises(AssertionError): self.combine()
        self.setUp(); del self.metadata['java']
        with self.assertRaises(AssertionError): self.combine()

    def test_duplicate_missing_unknown_or_retuned_comparison_rejected(self):
        for mutation in ('duplicate','missing','unknown','retuned'):
            self.setUp(); values=self.perf['comparisons']
            if mutation=='duplicate': values[-1]=values[0].copy()
            elif mutation=='missing': values.pop()
            elif mutation=='unknown': values[0]['run']='n100-d10-on-r0.json'
            else: values[0]['limit']+=1
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError): self.combine()


class PerformancePlanChecks(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.out=Path(self.temp.name)/'quick'; self.out.mkdir()
        self.root=ROOT
        self.metadata={'sourceHead':'a'*40, 'jars':{'fixture.jar':'b'*64}, 'java':JAVA.copy()}
        # Authored synthetic fixture, independent of historical captures and canonical-plan helpers.
        plans=((100,3,(1,10,100),100),(1000,3,(1,10,100),100),(10000,2,(1,10),30))
        lines=['git='+self.metadata['sourceHead'], 'tree=clean', 'profile=quick',
               'workload=warmup:50,samples:200,idle:10,installChurn:100',
               'limits_sha256='+qa.digest(ROOT/'examples/fx-lab/scripts/baseline.conf'),
               'b'*64+'  fixture.jar', '    java.specification.version = 27', *JAVA]
        for nodes,reps,dirties,cycles in plans:
            lines.append(f'plan={nodes}:{reps}:{",".join(map(str,dirties))}:{cycles}')
            for dirty in dirties:
                for bridge in ('off','on'):
                    for rep in range(1,reps+1):
                        run=run_json(bridge,10,0)
                        run.update(nodes=nodes,dirty=dirty,idleSeconds=10 if dirty==10 else 0)
                        if dirty==10:
                            run.update(structuralCycles=cycles,structuralElapsedSeconds=cycles)
                        else:
                            run.pop('structuralCycles',None);run.pop('installChurnCycles',None)
                        (self.out/f'n{nodes}-d{dirty}-{bridge}-r{rep}.json').write_text(json.dumps(run))
        (self.out/'environment.txt').write_text('\n'.join(lines)+'\n')

    def performance(self):
        return qa.performance(self.root,self.out,self.metadata)

    def edit_environment(self, change):
        p=self.out/'environment.txt';p.write_text(change(p.read_text()))

    def test_synthetic_canonical_matrix_has_44_runs_and_92_passes(self):
        result=self.performance()
        self.assertEqual(44,len(result['filesSha256']))
        self.assertEqual(92,len(result['comparisons']))
        self.assertEqual('Passed',result['status'])

    def test_self_consistent_two_run_subset_cannot_claim_quick(self):
        for path in self.out.glob('n*.json'):
            if path.name not in ('n100-d10-off-r1.json','n100-d10-on-r1.json'): path.unlink()
        self.edit_environment(lambda text:'\n'.join(l for l in text.splitlines() if not l.startswith('plan='))
                              +'\nplan=100:1:10:100\n')
        with self.assertRaisesRegex(AssertionError,'performance-quick-plan'): self.performance()

    def test_duplicate_or_missing_plan_and_changed_workload_rejected(self):
        original=(self.out/'environment.txt').read_text()
        for value in (original+'plan=100:3:1,10,100:100\n',
                      original.replace('plan=10000:2:1,10:30\n',''),
                      original.replace('samples:200','samples:30'),
                      original.replace('java.specification.version = 27','java.specification.version = 28'),
                      original+'    java.specification.version = 27\n',
                      original.replace('    java.specification.version = 27\n',''),
                      original.replace('Zulu','OtherVendor')):
            (self.out/'environment.txt').write_text(value)
            with self.assertRaises(AssertionError): self.performance()

    def test_window_condition_is_recorded_and_restricted(self):
        self.assertEqual('unrecorded-historical',self.performance()['window'])
        original=(self.out/'environment.txt').read_text()
        (self.out/'environment.txt').write_text(original+'window=foreground\n')
        self.assertEqual('foreground',self.performance()['window'])
        for value in (original+'window=default\n',original+'window=foreground\nwindow=foreground\n'):
            (self.out/'environment.txt').write_text(value)
            with self.assertRaisesRegex(AssertionError,'performance-window'): self.performance()

    def test_missing_or_renamed_run_rejected(self):
        path=self.out/'n100-d1-on-r1.json';data=path.read_bytes();path.unlink()
        with self.assertRaises(AssertionError): self.performance()
        (self.out/'n100-d1-on-r01.json').write_bytes(data)
        with self.assertRaises(AssertionError): self.performance()

    def test_changed_validity_limits_rejected(self):
        self.edit_environment(lambda text:text.replace('limits_sha256=','limits_sha256=0'))
        with self.assertRaisesRegex(AssertionError,'performance-limits'): self.performance()

    def test_valid_budget_excess_remains_a_failed_comparison(self):
        p=self.out/'n100-d1-on-r1.json';value=json.loads(p.read_text())
        value['allocatedBytesPerSample']=35*1024;p.write_text(json.dumps(value))
        result=self.performance()
        self.assertEqual('Failed',result['status'])
        self.assertEqual(92,len(result['comparisons']))
        self.assertEqual(35,result['exceedances'][0]['observed'])


class CollectionBuildChecks(unittest.TestCase):
    def test_build_failure_stops_before_stale_hashes_or_suites(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);out=root/'output'
            with patch.object(qa,'git',return_value=''), patch.object(qa,'java_launcher',return_value=Path('/jdk/bin/java')), \
                 patch.object(qa,'java_metadata',return_value={'java':JAVA}), \
                 patch.object(qa,'source_provenance',return_value={}), \
                 patch.object(qa,'rebuild',side_effect=AssertionError('qualification-build-failed')), \
                 patch.object(qa,'provenance') as hashes, patch.object(qa,'run_owned') as suites:
                with self.assertRaises(AssertionError): qa.collect(root,out)
                hashes.assert_not_called();suites.assert_not_called()
                self.assertFalse((out/'functional.json').exists())

    def test_unsupported_jvm_stops_before_build_or_output_creation(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);out=root/'output'
            with patch.object(qa,'git',return_value=''), patch.object(qa,'java_launcher',return_value=Path('/jdk/bin/java')), \
                 patch.object(qa,'java_metadata',side_effect=AssertionError('jdk27-required')), \
                 patch.object(qa,'rebuild') as build:
                with self.assertRaisesRegex(AssertionError,'jdk27-required'):qa.collect(root,out)
                build.assert_not_called();self.assertFalse(out.exists())

    def test_rebuild_failure_or_timeout_is_recorded(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for failure in (1,subprocess.TimeoutExpired('wrapper',1200)):
                with patch.object(qa,'run_owned',side_effect=failure if isinstance(failure,Exception) else None,
                                  return_value=failure):
                    with self.assertRaises((AssertionError,subprocess.TimeoutExpired)):
                        qa.rebuild(root,root,{})
                self.assertEqual('Failed',json.loads((root/'build.json').read_text())['status'])

    def test_forced_build_precedes_provenance_and_all_suites(self):
        fixtures=EvidenceGates(); fixtures.setUp()
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);out=root/'output';stale=root/'stale.jar';stale.write_text('old')
            calls=[]
            def run(command,**kwargs):
                calls.append(command)
                if len(calls)==1:
                    self.assertIn('--rerun-tasks',command);self.assertIn('--no-build-cache',command)
                    self.assertEqual('1',kwargs['env']['REQUIRE_NATIVE_BUS'])
                    self.assertEqual('/jdk',kwargs['env']['JAVA_HOME'])
                    stale.write_text('fresh')
                return 0
            def hashes(_):
                self.assertEqual('fresh',stale.read_text())
                return fixtures.metadata
            def read(path):
                if path.name=='artifacts.json':return {'status':'Passed','artifacts':{}}
                return fixtures.functional['suites'][path.parent.name]
            with patch.object(qa,'git',return_value=''), patch.object(qa,'java_launcher',return_value=Path('/jdk/bin/java')), \
                 patch.object(qa,'java_metadata',side_effect=lambda binary,env: {'java':JAVA} if binary == Path('/jdk/bin/java') else self.fail('JDK launcher changed')), \
                 patch.object(qa,'source_provenance',return_value={}), \
                 patch.object(qa,'run_owned',side_effect=run),patch.object(qa,'provenance',side_effect=hashes), \
                 patch.object(qa,'read_json',side_effect=read),patch.object(qa,'resource_evidence',return_value={'status':'Passed'}), \
                 patch.object(qa.subprocess,'run',return_value=Mock(stdout='package-version')):
                self.assertEqual(0,qa.collect(root,out))
            self.assertEqual(6,len(calls))
            self.assertEqual('Passed',json.loads((out/'build.json').read_text())['status'])
            self.assertEqual('Passed',json.loads((out/'functional.json').read_text())['build']['status'])

    def test_independent_consumer_build_also_forces_execution(self):
        with patch.object(consumer_check,'run_owned',return_value=1) as run:
            with self.assertRaisesRegex(AssertionError,'consumer-build-failed'):
                consumer_check.run(Path('/project'),'0.1.0-SNAPSHOT','27')
        command=run.call_args.args[0]
        self.assertIn('--rerun-tasks',command);self.assertIn('--no-build-cache',command)


class JavaRuntimeChecks(unittest.TestCase):
    def test_java_home_wins_over_path_and_missing_home_does_not_fall_back(self):
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp)/'jdk';(home/'bin').mkdir(parents=True);(home/'bin/java').touch()
            with patch.dict(os.environ,{'JAVA_HOME':str(home),'PATH':'/does-not-exist'}):
                self.assertEqual((home/'bin/java').resolve(),qa.java_launcher())
                (home/'bin/java').unlink()
                with self.assertRaises(AssertionError):qa.java_launcher()
        with patch.dict(os.environ,{'JAVA_HOME':''}),patch.object(qa.shutil,'which',return_value=sys.executable):
            self.assertEqual(Path(sys.executable).resolve(),qa.java_launcher())

    def test_non27_missing_duplicate_spec_and_mismatched_lines_rejected(self):
        for specs,lines in ((['26'],JAVA),(['28'],JAVA),([],JAVA),(['27','27'],JAVA),
                            (['27'],[line.replace('27','28') for line in JAVA]),
                            (['27'],JAVA[:2]),(['27'],JAVA[:2]+[JAVA[2].replace('27+35','27+36')])):
            output='\n'.join(['    java.specification.version = '+v for v in specs]+lines)
            with patch.object(qa.subprocess,'run',return_value=Mock(stderr=output,stdout='')):
                with self.subTest(specs=specs,lines=lines),self.assertRaises(AssertionError):
                    qa.java_metadata(Path('/jdk/bin/java'),{})

    def test_only_allowlisted_java_metadata_retained(self):
        output='\n'.join(['    java.specification.version = 27','    private.value = secret',
                          '    java.runtime.name = OpenJDK Runtime Environment',
                          '    java.vm.name = OpenJDK 64-Bit Server VM']+JAVA)
        with patch.object(qa.subprocess,'run',return_value=Mock(stderr=output,stdout='')):
            value=qa.java_metadata(Path('/jdk/bin/java'),{})
        self.assertEqual({'java':JAVA,'javaSpecificationVersion':'27'},value)



class BoundsAndCleanup(unittest.TestCase):
    def test_oversized_report_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'report.json'; path.write_bytes(b' '*(1024*1024+1))
            with self.assertRaises(AssertionError): qa_common.read_json(path)

    def test_child_terminated_then_killed_on_timeout(self):
        child=Mock(); child.poll.return_value=None
        child.wait.side_effect=[subprocess.TimeoutExpired('child',5),0]
        qa_common.stop(child)
        child.terminate.assert_called_once(); child.kill.assert_called_once()
        self.assertEqual(2,child.wait.call_count)

    def test_timed_out_suite_group_is_reaped(self):
        with patch('qa_common.subprocess.Popen') as spawn, patch('qa_common.os.killpg') as kill, \
             patch('qa_common._capture_owned'):
            child=spawn.return_value; child.pid=123; child.returncode=None
            child.wait.side_effect=[subprocess.TimeoutExpired('suite',1),0]
            with self.assertRaises(subprocess.TimeoutExpired):
                qa_common.run_owned(['suite'],log=None,env={},timeout=1)
            self.assertTrue(spawn.call_args.kwargs['start_new_session'])
            self.assertEqual([call(123,qa_common.signal.SIGTERM),call(123,qa_common.signal.SIGKILL)],kill.call_args_list)
            self.assertEqual(2,child.wait.call_count,'the root is reaped once, after the group kill')

    def test_group_that_ignores_term_is_killed_and_reaped(self):
        def capture(process,handles,stack): handles.extend([(7,123),(8,456)])
        with patch('qa_common.subprocess.Popen') as spawn, patch('qa_common.os.killpg') as kill, \
             patch('qa_common._capture_owned',side_effect=capture), \
             patch('qa_common._await_handles',side_effect=[[(8,456)],[]]), \
             patch('qa_common.signal.pidfd_send_signal') as send:
            child=spawn.return_value; child.pid=123; child.returncode=None
            child.wait.side_effect=[subprocess.TimeoutExpired('suite',1),0]
            with self.assertRaises(subprocess.TimeoutExpired):
                qa_common.run_owned(['suite'],log=None,env={},timeout=1)
            self.assertEqual([call(123,qa_common.signal.SIGTERM),call(123,qa_common.signal.SIGKILL)],kill.call_args_list)
            # Only the descendant still running after the grace period is killed.
            self.assertEqual([call(8,qa_common.signal.SIGTERM),call(8,qa_common.signal.SIGKILL)],send.call_args_list)
            self.assertEqual(2,child.wait.call_count)

    def test_reaped_root_group_id_is_never_signalled(self):
        with patch('qa_common.subprocess.Popen') as spawn, patch('qa_common.os.killpg') as kill, \
             patch('qa_common._capture_owned'):
            child=spawn.return_value; child.pid=123; child.returncode=0
            child.wait.side_effect=[KeyboardInterrupt(),0]
            with self.assertRaises(KeyboardInterrupt):
                qa_common.run_owned(['suite'],log=None,env={},timeout=1)
            kill.assert_not_called()

    def test_java_metadata_never_echoes_private_vm_options(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);(home/'bin').mkdir();java=home/'bin/java'
            output='\n'.join(['    java.specification.version = 27']+JAVA)
            java.write_text('#!'+sys.executable+'\nimport os,sys\nprint(os.environ.get("JAVA_TOOL_OPTIONS", ""),file=sys.stderr)\n'
                            +'print('+repr(output)+',file=sys.stderr)\n')
            java.chmod(0o700)
            with patch.dict(os.environ,{'JAVA_HOME':directory,'JAVA_TOOL_OPTIONS':'private-token-must-not-be-recorded'}):
                self.assertEqual(JAVA,qa.java_version())

    def test_injected_vm_or_python_optimization_options_removed(self):
        with patch.dict('os.environ',{'JAVA_TOOL_OPTIONS':'private','PYTHONOPTIMIZE':'1','MODULAR_OPTS':'private'}):
            env=qa_common.environment()
        self.assertFalse({'JAVA_TOOL_OPTIONS','PYTHONOPTIMIZE','MODULAR_OPTS'} & env.keys())


if __name__=='__main__': unittest.main()
