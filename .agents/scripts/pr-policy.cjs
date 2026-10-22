// Adapted from stevdrey/dokene's PR size guard. Canonical policy for local and CI use.
const fs = require('node:fs');
const path = require('node:path');
const MAX_FILES = 40;
const WARNING_FILES = 30;

function countLevel(count) {
  if (!Number.isSafeInteger(count) || count < 0) throw new Error('Invalid changed-file count');
  return count > MAX_FILES ? 'BLOCKED' : count > WARNING_FILES ? 'WARNING' : 'PASS';
}

function evidenceReason(filename) {
  // Context matters: SnapshotTest.java, parser fixtures and authored summaries are valid.
  const p = filename.toLowerCase();
  const outputDirectory = /(^|\/)(test-results|test-reports|junit-reports|benchmark-results|qa-results|raw-evidence|evidence-bundle|evidence-bundles)(\/|$)/;
  const verificationData = /^docs\/verification\//;
  const rootCapture = /^(snapshots|screenshots|qa-output|benchmark-output)\//;
  const qaCapture = /(^|\/)(qa|benchmarks?|verification)\/(screenshots|snapshots|runs|logs|dumps)\//;
  const generatedExtension = /\.(jsonl?|ndjson|csv|tsv|txt|log|xml|html?|png|jpe?g|webp|gif|snap|dump|hprof|jfr|bin|zip|gz|sha256)$/;
  if (outputDirectory.test(p)) return 'generated test/QA/benchmark output directory';
  if ((verificationData.test(p) || qaCapture.test(p) || rootCapture.test(p)) &&
      (generatedExtension.test(p) || /(^|\/)(manifest|receipt)([.-]|$)/.test(p))) {
    return 'raw verification capture or evidence index';
  }
  if (/^(test-[^/]+\.xml|hs_err_pid\d+\.log|replay_pid\d+\.log)$/.test(p) ||
      /(^|\/)(heapdump[^/]*\.hprof|core\.\d+)$/.test(p)) return 'generated runtime/test report';
  return null;
}

async function ciCheck({ github, context, core }) {
  try {
    const params = { ...context.repo, pull_number: context.issue.number };
    const { data: before } = await github.rest.pulls.get(params);
    const count = before.changed_files;
    const level = countLevel(count);
    const files = [];
    // listFiles is capped by GitHub at 3,000; oversized/incomplete PRs never pass.
    for (let page = 1; page <= 30; page++) {
      const { data } = await github.rest.pulls.listFiles({ ...params, per_page: 100, page });
      if (!Array.isArray(data) || data.length > 100) throw new Error('Invalid PR file list');
      files.push(...data);
      if (data.length < 100) break;
    }
    if (files.length !== count || new Set(files.map(f => f.filename)).size !== count ||
        files.some(f => typeof f.filename !== 'string' || !f.filename ||
          !['added', 'removed', 'modified', 'renamed', 'copied', 'changed', 'unchanged'].includes(f.status))) {
      throw new Error('Incomplete or invalid PR file metadata');
    }
    const { data: after } = await github.rest.pulls.get(params);
    if (!before.head?.sha || !before.base?.sha || before.head.sha !== after.head?.sha ||
        before.base.sha !== after.base?.sha || after.changed_files !== count) {
      throw new Error('PR changed during inspection; rerun the check');
    }
    const prohibited = files.filter(f => f.status !== 'removed' && evidenceReason(f.filename));
    await core.summary.addHeading('Pull request publication policy').addTable([
      [{ data: 'Metric', header: true }, { data: 'Value', header: true }],
      ['Changed files (entire PR)', String(count)],
      ['Warning range', `${WARNING_FILES + 1}–${MAX_FILES}`],
      ['Maximum allowed (inclusive)', String(MAX_FILES)],
      ['Recognizable generated outputs', String(prohibited.length)],
    ]).write();
    if (level === 'BLOCKED') core.setFailed(`${count}/${MAX_FILES} changed files. Split into independently reviewable PRs; preserve required tests, security checks and documentation.`);
    else if (level === 'WARNING') core.warning(`${count}/${MAX_FILES} changed files. Review scope before adding more changes.`);
    else if (!prohibited.length) core.info(`PASS: ${count}/${MAX_FILES} changed files.`);
    if (prohibited.length) {
      // JSON escaping prevents control characters in filenames becoming log directives.
      const examples = prohibited.slice(0, 10).map(f => JSON.stringify(f.filename)).join(', ');
      core.setFailed(`Generated test/QA/benchmark evidence is prohibited at any file count: ${examples}. Keep outputs outside Git and publish a concise authored summary. See docs/verification/pr-size-guard.md.`);
    }
  } catch {
    // Do not echo API response bodies, tokens or arbitrary exception text.
    core.setFailed('Could not verify PR metadata/count/file list consistently. Check API availability and rerun; unknown scope never passes.');
  }
}

function renderWorkflow() {
  const script = [
    '// Generated executable source from .agents/scripts/pr-policy.cjs; do not edit inline.',
    `const MAX_FILES = ${MAX_FILES};`, `const WARNING_FILES = ${WARNING_FILES};`,
    countLevel.toString(), evidenceReason.toString(), ciCheck.toString(),
    'await ciCheck({ github, context, core });',
  ].join('\n\n');
  return `name: Pull Request Size Guard

on:
  pull_request:
    branches: [main]
    types: [opened, reopened, synchronize, ready_for_review, edited]

permissions:
  pull-requests: read

jobs:
  file-count:
    name: PR file count
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - name: Enforce changed-file and generated-evidence policy
        uses: actions/github-script@373c709c69115d41ff229c7e5df9f8788daa9553 # v9
        with:
          script: |
${script.split('\n').map(line => line ? '            ' + line : '').join('\n')}
`;
}

module.exports = { MAX_FILES, WARNING_FILES, countLevel, evidenceReason, renderWorkflow };
if (require.main === module) {
  if (process.argv.slice(2).join(' ') !== '--write-workflow') throw new Error('Use --write-workflow');
  fs.writeFileSync(path.join(__dirname, '../../.github/workflows/pr-size-guard.yml'), renderWorkflow());
}
