#!/usr/bin/env python3
"""Build independent published consumers and check package contents (no desktop needed)."""
import argparse
import json
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET
from qa_common import check, digest, environment, run_owned

NOTICES = {'META-INF/LICENSE', 'META-INF/NOTICE', 'META-INF/THIRD_PARTY_NOTICES.md'}


def inspect_jar(path, main=False):
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        check(NOTICES <= names, 'missing-notices')
        for name in NOTICES:
            check(len(archive.read(name)) > 0, 'empty-notice')
        if main:
            check('module-info.class' in names, 'missing-module')
            check(not any(name.startswith(('javafx/', 'qa/', 'org/junit/', 'dev/desktopcontrol/fxlab/'))
                          for name in names), 'bundled-runtime-or-qa')
    return digest(path)


def inspect_runtime(names, version, javafx):
    expected = {f'fx-atspi-bridge-{version}.jar', *(f'javafx-{part}-{javafx}-linux.jar' for part in ('base', 'graphics', 'controls'))}
    check(len(names) == len(set(names)) and set(names) == expected, 'unexpected-runtime')


def published_stem(publication, version):
    # Only SNAPSHOT publications carry per-version metadata naming the timestamped files.
    if not version.endswith('-SNAPSHOT'):
        return f'fx-atspi-bridge-{version}'
    snapshot = ET.parse(publication/'maven-metadata.xml').findtext('versioning/snapshotVersions/snapshotVersion/value')
    check(snapshot is not None, 'snapshot-metadata')
    return f'fx-atspi-bridge-{snapshot}'


def run(root, version, javafx):
    output = root / 'build/consumer-qa'
    command = [str(root/'gradlew'), '-p', str(root/'qa/consumers'),
               '-PqaRepository=' + str(root/'build/qa-repository'), '-PqaOutput=' + str(output),
               '-PbridgeVersion=' + version, ':modular:recordRuntime', ':classpath:recordRuntime', '--no-daemon', '--rerun-tasks', '--no-build-cache']
    check(run_owned(command, log=None, env=environment(), timeout=240) == 0, 'consumer-build-failed')
    result = {'status': 'Failed', 'artifacts': {}, 'consumers': {}}
    for suffix in ('', '-sources', '-javadoc'):
        path = root/'build/libs'/f'fx-atspi-bridge-{version}{suffix}.jar'
        result['artifacts'][path.name] = inspect_jar(path, main=not suffix)
    publication = root/'build/qa-repository/io/github/stevdrey/fx-atspi-bridge'/version
    stem = published_stem(publication, version)
    pom = ET.parse(publication/f'{stem}.pom')
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    deps = pom.findall('m:dependencies/m:dependency', ns)
    actual = [(dep.findtext('m:groupId', namespaces=ns), dep.findtext('m:artifactId', namespaces=ns),
               dep.findtext('m:version', namespaces=ns), dep.findtext('m:classifier', namespaces=ns)) for dep in deps]
    check(len(actual) == 3 and set(actual) == {('org.openjfx', 'javafx-'+part, javafx, 'linux')
                                              for part in ('base', 'graphics', 'controls')}, 'pom-dependencies')
    check(pom.findtext('m:licenses/m:license/m:name', namespaces=ns) == 'Apache License, Version 2.0', 'pom-license')
    metadata = json.loads((publication/f'{stem}.module').read_text())
    check(metadata['component']['group'] == 'io.github.stevdrey', 'module-metadata')
    for mode in ('modular', 'classpath'):
        names = (output/mode/'runtime.txt').read_text().splitlines()
        inspect_runtime(names, version, javafx)
        launcher = output/mode/'install'/mode/'bin'/mode
        check(launcher.is_file(), 'consumer-launcher')
        flags = '--enable-native-access=' + ('dev.desktopcontrol.fxatspi,javafx.graphics' if mode == 'modular' else 'ALL-UNNAMED')
        check(flags in launcher.read_text(), 'consumer-native-flags')
        installed = output/mode/'install'/mode/'lib'
        bridge = installed/f'fx-atspi-bridge-{version}.jar'
        check(digest(bridge) == result['artifacts'][bridge.name], 'consumer-stale-bridge')
        result['consumers'][mode] = {'status': 'Passed', 'runtime': names,
                                     'jars': {p.name:digest(p) for p in sorted(installed.glob('*.jar'))}}
    result['status'] = 'Passed'
    (output/'artifacts.json').write_text(json.dumps(result, indent=2)+'\n')
    print('Passed: publication, notices and two independent consumers')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('version')
    parser.add_argument('javafx')
    args = parser.parse_args()
    run(args.root.resolve(), args.version, args.javafx)
