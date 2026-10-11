#!/usr/bin/env python3
"""Publish reviewed starter issues through an authenticated GitHub CLI."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile

REPO = 'stevdrey/fx-atspi-bridge'
ROOT = Path(__file__).resolve().parent.parent

def gh(*args):
    return subprocess.check_output(['gh', *args], text=True).strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    entries = json.loads((ROOT / 'docs/specs/initial-issues.json').read_text())
    if not args.apply:
        for entry in entries:
            print(entry['title'])
        return
    existing = json.loads(gh('issue', 'list', '--repo', REPO, '--state', 'all', '--limit', '1000', '--json', 'number,title,url'))
    by_id = {}
    for issue in existing:
        match = re.search(r'\[(SC-\d{2})\]', issue['title'])
        if match:
            key = match.group(1)
            if key in by_id:
                raise RuntimeError(f'Duplicate planning ID {key}; resolve before publishing')
            by_id[key] = issue
    for entry in entries:
        key = entry['planning_id']
        if key in by_id:
            print(f"Reuse {key}: {by_id[key]['url']}")
            continue
        deps = [by_id[f'SC-{number:02d}']['url'] for number in entry['dependencies']]
        body = entry['body'].replace('DEPENDENCY_LINKS', 'Prerequisites: ' + ', '.join(deps) if deps else 'No prerequisite issue.')
        with tempfile.TemporaryDirectory() as directory:
            body_file = Path(directory) / 'body.md'
            body_file.write_text(body)
            command = ['issue', 'create', '--repo', REPO, '--title', entry['title'], '--body-file', str(body_file)]
            for label in entry['labels']:
                command.extend(['--label', label])
            url = gh(*command)
        match = re.fullmatch(r'https://github\.com/stevdrey/fx-atspi-bridge/issues/(\d+)', url)
        if not match:
            raise RuntimeError('Unexpected response; inspect GitHub before retrying')
        by_id[key] = {'number': int(match.group(1)), 'url': url, 'title': entry['title']}
        print(f'Created {key}: {url}')

if __name__ == '__main__':
    main()
