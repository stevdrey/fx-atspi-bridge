#!/usr/bin/env python3
"""Run the PR #41 high-review regression probe against installed JARs; needs a graphical session."""
import argparse
import json
import os
from pathlib import Path
import subprocess


def run(library, output):
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    bridges = list(library.glob('fx-atspi-bridge-*.jar'))
    assert len(bridges) == 1
    modules = os.pathsep.join(str(path.resolve()) for path in sorted(library.glob('javafx-*.jar')))
    source = Path(__file__).with_name('Pr41ReviewProbe.java').resolve()
    environment = dict(os.environ)
    for variable in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        environment.pop(variable, None)
    common = ['--module-path', modules, '--add-modules', 'javafx.controls']
    results = {'status': 'Failed'}
    try:
        subprocess.run(['javac', *common, '--class-path', str(bridges[0].resolve()), '-d', str(classes), str(source)],
                       env=environment, check=True, timeout=30, capture_output=True, text=True)
        process = subprocess.run(['java', '--enable-native-access=javafx.graphics,ALL-UNNAMED', *common,
                                  '--class-path', os.pathsep.join((str(classes), str(bridges[0].resolve()))),
                                  'dev.desktopcontrol.fxatspi.fx.Pr41ReviewProbe'],
                                 env=environment, check=True, timeout=30, capture_output=True, text=True)
        results = {'status': 'Passed', 'checks': process.stdout.splitlines()}
        (output / 'fixture-status.txt').write_text(process.stderr)
    finally:
        (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('installed_library', type=Path)
    parser.add_argument('new_output_directory', type=Path)
    args = parser.parse_args()
    run(args.installed_library.resolve(), args.new_output_directory.resolve())
