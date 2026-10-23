import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import policy from './pr-policy.cjs';
import { BASE, HEAD, embeddedWorkflow, runCI } from './pr-policy-fixtures.mjs';

test('the single trusted workflow exactly matches canonical code and uses read-only metadata', () => {
  const workflow = embeddedWorkflow();
  assert.equal(workflow, policy.renderWorkflow());
  assert.match(workflow, /\n  pull_request_target:\n/);
  assert.doesNotMatch(workflow, /\n  pull_request:|branches:/);
  assert.match(workflow, /types: \[opened, reopened, synchronize, ready_for_review, edited\]/);
  assert.match(workflow, /permissions:\n  pull-requests: read\n  contents: read\n/);
  assert.match(workflow, /name: Trusted PR file count/);
  assert.match(workflow, /concurrency:\n  group: trusted-pr-size-guard-\$\{\{ github\.event\.pull_request\.number \}\}\n  cancel-in-progress: true\n/);
  assert.doesNotMatch(workflow, /^\s+if:/m);
  assert.doesNotMatch(workflow, /checkout|contents: write|secrets\.|github\.event\.pull_request\.head|download-artifact|restore.*cache/);
  assert.match(workflow, /actions\/github-script@[0-9a-f]{40}/);
  assert.throws(() => readFileSync(new URL('../../.github/workflows/pr-size-guard.yml', import.meta.url)), /ENOENT/);
});
for (const count of [0, 30, 31, 40, 41]) {
  test(`trusted CI boundary ${count}`, async () => {
    const e = await runCI(count);
    assert.equal(e.failures.length, count > 40 ? 1 : 0);
    assert.equal(e.warnings.length, count > 30 && count <= 40 ? 1 : 0);
    assert.equal(e.tables.length, 1);
  });
}
test('CI invalid counts, API failures, incomplete/duplicate metadata and stale revisions fail closed', async () => {
  for (const count of [undefined, null, -1, 1.5, '30', Number.MAX_SAFE_INTEGER + 1]) assert.equal((await runCI(count, [])).failures.length, 1);
  for (const options of [{ apiFailure: true }, { listFailure: true }, { stale: true }, { historyFailure: true }, { commitFailure: true }, { incompleteHistory: true }, { commitCount: 201 }]) {
    const e = await runCI(1, undefined, options); assert.equal(e.failures.length, 1);
    assert.doesNotMatch(e.failures[0], /private/); assert.doesNotMatch(e.info.join(''), /PASS/);
  }
  assert.equal((await runCI(2, [{ filename: 'one', status: 'added' }])).failures.length, 1);
  assert.equal((await runCI(2, Array(2).fill({ filename: 'same', status: 'added' }))).failures.length, 1);
  assert.equal((await runCI(1, [{ filename: 'bad', status: 'unknown' }])).failures.length, 1);
});
test('CI paginates all net paths and detects evidence after first page', async () => {
  const files = Array.from({ length: 101 }, (_, i) => ({ filename: `src/${i}.java`, status: 'modified' }));
  files[100] = { filename: 'qa-results/run.json', status: 'added' };
  const e = await runCI(101, files); assert.deepEqual(e.pages, [1, 2]); assert.equal(e.failures.length, 2);
});
test('introduced then deleted evidence fails even with a zero-file net PR diff', async () => {
  const file = { filename: 'qa-results/transient.json', status: 'added' };
  const e = await runCI(0, [], { history: [
    { sha: 'c'.repeat(40), files: [file] }, { sha: HEAD, files: [{ ...file, status: 'removed' }] },
  ] });
  assert.equal(e.failures.length, 1); assert.match(e.failures[0], /intermediate commits/); assert.doesNotMatch(e.info.join(''), /PASS/);
});
test('per-commit pagination detects transient captures after first page', async () => {
  const files = Array.from({ length: 101 }, (_, i) => ({ filename: `src/${i}.java`, status: 'added' }));
  files[100] = { filename: 'qa-results/transient.json', status: 'added' };
  const e = await runCI(0, [], { history: [{ sha: HEAD, files }] });
  assert.deepEqual(e.commitPages, [[HEAD, 1], [HEAD, 2]]); assert.equal(e.failures.length, 1);
});
test('API truncation boundary never passes', async () => {
  const files = Array.from({ length: 3000 }, (_, i) => ({ filename: `src/${i}.java`, status: 'modified' }));
  const e = await runCI(0, [], { history: [{ sha: HEAD, files }] });
  assert.equal(e.failures.length, 1); assert.match(e.failures[0], /complete PR metadata/); assert.equal(e.commitPages.length, 30);
});
test('merge-inherited baseline captures are allowed but merge-created captures fail', async () => {
  const parent = 'd'.repeat(40), root = 'e'.repeat(40), tree = 'f'.repeat(40), blob = '1'.repeat(40);
  const history = [{ sha: HEAD, parents: [{ sha: BASE }, { sha: parent }], files: [{ filename: 'qa-results/old.json', status: 'added', sha: blob }] }];
  const options = { history, parentRoots: { [parent]: root }, trees: {
    [root]: [{ path: 'qa-results', type: 'tree', sha: tree }], [tree]: [{ path: 'old.json', type: 'blob', sha: blob }],
  } };
  assert.equal((await runCI(0, [], options)).failures.length, 0);
  assert.equal((await runCI(0, [], { ...options, truncatedTree: true })).failures.length, 1);
  options.trees[tree][0].sha = '2'.repeat(40);
  assert.equal((await runCI(0, [], options)).failures.length, 1);
});
test('CI accepts a head commit that is not last in the date-ordered commit list', async () => {
  const first = { sha: HEAD, files: [] }, second = { sha: 'c'.repeat(40), files: [{ filename: 'src/a.java', status: 'added' }] };
  const e = await runCI(0, [], { history: [first, second], head: HEAD });
  assert.equal(e.failures.length, 0);
  assert.equal((await runCI(0, [], { history: [first, second], head: '9'.repeat(40) })).failures.length, 1);
});
test('CI failures name the policy reason but never raw API messages', async () => {
  const capacity = await runCI(1, undefined, { commitCount: 201 });
  assert.match(capacity.failures[0], /: Unverifiable PR commit count\./);
  const api = await runCI(1, undefined, { apiFailure: true });
  assert.match(api.failures[0], /: API or unexpected error \(HTTP 502\)\./); assert.doesNotMatch(api.failures[0], /private/);
});
test('CI lists each generated path once, with its introducing commit', async () => {
  const file = { filename: 'qa-results/run.json', status: 'added' };
  const e = await runCI(1, [file], { history: [{ sha: HEAD, files: [file] }] });
  assert.equal(e.failures.length, 1); assert.equal(e.failures[0].match(/qa-results\/run\.json/g).length, 1);
  assert.match(e.failures[0], new RegExp(`at ${HEAD.slice(0, 12)}`));
  assert.deepEqual(e.tables[0].at(-1), ['Recognizable generated outputs (including history)', '1']);
});


test('CI bounds metadata requests with abort signals', async () => {
  const e = await runCI(1);
  assert.equal(e.signals.length, 2);
  for (const signal of e.signals) assert.ok(signal instanceof AbortSignal);
});
test('CI reports an over-limit count even when history inspection fails', async () => {
  const e = await runCI(45, undefined, { historyFailure: true });
  assert.equal(e.failures.length, 2);
  assert.match(e.failures[0], /^45\/40 changed files\. Split/);
  assert.match(e.failures[1], /Could not verify complete PR metadata/);
  assert.doesNotMatch(e.failures.join(''), /private/);
});

const gradleOutputs = [
  'build/reports/tests/test/index.html',
  'build/reports/tests/test/com.example.SomeTest/index.html',
  'examples/fx-lab/build/reports/tests/test/index.html',
  'modules/library/build/reports/tests/integrationTest/index.html',
  'build/reports/tests/test/css/style.css',
  'examples/fx-lab/build/reports/tests/test/js/report.js',
  'build/reports/problems/problems-report.html',
  'modules/library/build/reports/jacoco/test/html/index.html',
  'build/reports/checkstyle/main.xml',
  'build/tmp/test/output.txt',
];
const authoredFixtures = [
  'src/test/resources/fixtures/report.html',
  'src/test/resources/fixtures/reports/tests/index.html',
  'examples/fx-lab/src/test/resources/fixtures/index.html',
  'docs/examples/test-report.html',
  'src/main/java/example/build/reports/Report.java',
];
{
  const label = 'trusted';
  test(`${label} CI rejects root/subproject Gradle build outputs and report assets`, async () => {
    for (const filename of gradleOutputs) for (const status of ['added', 'modified', 'renamed']) {
      const e = await runCI(1, [{ filename, status }]);
      assert.equal(e.failures.length, 1, filename + status);
      assert.match(e.failures[0], /Generated evidence is prohibited/);
      assert.doesNotMatch(e.info.join(''), /PASS/);
    }
  });
  test(`${label} CI rejects Gradle outputs introduced then deleted from a zero-file net diff`, async () => {
    for (const filename of gradleOutputs) {
      const e = await runCI(0, [], { history: [
        { sha: 'c'.repeat(40), files: [{ filename, status: 'added' }] },
        { sha: HEAD, files: [{ filename, status: 'removed' }] },
      ] });
      assert.equal(e.failures.length, 1, filename);
      assert.match(e.failures[0], /intermediate commits/);
      assert.doesNotMatch(e.info.join(''), /PASS/);
    }
  });
  test(`${label} CI allows removal of baseline Gradle outputs while counting those paths`, async () => {
    const e = await runCI(gradleOutputs.length,
      gradleOutputs.map(filename => ({ filename, status: 'removed' })));
    assert.deepEqual(e.failures, []);
    assert.match(e.info.join(''), /PASS: 10\/40/);
    assert.equal(e.tables[0][1][1], '10');
  });
  test(`${label} CI preserves authored HTML fixtures and build-named sources in the net diff and intermediate history`, async () => {
    for (const filename of authoredFixtures) {
      const net = await runCI(1, [{ filename, status: 'added' }]);
      assert.deepEqual(net.failures, [], filename); assert.match(net.info.join(''), /PASS: 1\/40/);
      const transient = await runCI(0, [], { history: [
        { sha: 'c'.repeat(40), files: [{ filename, status: 'added' }] },
        { sha: HEAD, files: [{ filename, status: 'removed' }] },
      ] });
      assert.deepEqual(transient.failures, [], filename); assert.match(transient.info.join(''), /PASS: 0\/40/);
    }
  });
}
