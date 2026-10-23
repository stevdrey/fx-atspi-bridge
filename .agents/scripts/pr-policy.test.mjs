import assert from 'node:assert/strict';
import test from 'node:test';
import policy from './pr-policy.cjs';
import { BASE, HEAD, embeddedWorkflow, runCI } from './pr-policy-fixtures.mjs';

test('both standalone workflows exactly match canonical code and use read-only metadata', () => {
  for (const trusted of [false, true]) {
    const workflow = embeddedWorkflow(trusted);
    assert.equal(workflow, policy.renderWorkflow(trusted));
    assert.match(workflow, new RegExp(`  ${trusted ? 'pull_request_target' : 'pull_request'}:`));
    assert.match(workflow, /branches: \[main\]/);
    assert.match(workflow, /types: \[opened, reopened, synchronize, ready_for_review, edited\]/);
    assert.match(workflow, /permissions:\n  pull-requests: read\n  contents: read\n/);
    assert.match(workflow, trusted ? /name: Trusted PR file count/ : /name: PR file count/);
    assert.doesNotMatch(workflow, /checkout|contents: write|secrets\.|github\.event\.pull_request\.head|download-artifact|restore.*cache/);
    assert.match(workflow, /actions\/github-script@[0-9a-f]{40}/);
  }
});
for (const trusted of [false, true]) for (const count of [0, 30, 31, 40, 41]) {
  test(`${trusted ? 'trusted' : 'bootstrap'} CI boundary ${count}`, async () => {
    const e = await runCI(count, undefined, { trusted });
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
  for (const trusted of [false, true]) {
    const file = { filename: 'qa-results/transient.json', status: 'added' };
    const e = await runCI(0, [], { trusted, history: [
      { sha: 'c'.repeat(40), files: [file] }, { sha: HEAD, files: [{ ...file, status: 'removed' }] },
    ] });
    assert.equal(e.failures.length, 1); assert.match(e.failures[0], /intermediate commits/); assert.doesNotMatch(e.info.join(''), /PASS/);
  }
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

const gradleReports = [
  'build/reports/tests/test/index.html',
  'build/reports/tests/test/com.example.SomeTest/index.html',
  'examples/fx-lab/build/reports/tests/test/index.html',
  'modules/library/build/reports/tests/integrationTest/index.html',
  'build/reports/tests/test/css/style.css',
  'examples/fx-lab/build/reports/tests/test/js/report.js',
];
const htmlFixtures = [
  'src/test/resources/fixtures/report.html',
  'src/test/resources/fixtures/reports/tests/index.html',
  'examples/fx-lab/src/test/resources/fixtures/index.html',
  'docs/examples/test-report.html',
];
for (const trusted of [false, true]) {
  const label = trusted ? 'trusted' : 'bootstrap';
  test(`${label} CI rejects root/subproject Gradle HTML reports and their assets`, async () => {
    for (const filename of gradleReports) for (const status of ['added', 'modified', 'renamed']) {
      const e = await runCI(1, [{ filename, status }], { trusted });
      assert.equal(e.failures.length, 1, filename + status);
      assert.match(e.failures[0], /Generated evidence is prohibited/);
      assert.doesNotMatch(e.info.join(''), /PASS/);
    }
  });
  test(`${label} CI rejects Gradle reports introduced then deleted from a zero-file net diff`, async () => {
    for (const filename of gradleReports) {
      const e = await runCI(0, [], { trusted, history: [
        { sha: 'c'.repeat(40), files: [{ filename, status: 'added' }] },
        { sha: HEAD, files: [{ filename, status: 'removed' }] },
      ] });
      assert.equal(e.failures.length, 1, filename);
      assert.match(e.failures[0], /intermediate commits/);
      assert.doesNotMatch(e.info.join(''), /PASS/);
    }
  });
  test(`${label} CI allows removal of baseline Gradle reports while counting those paths`, async () => {
    const e = await runCI(gradleReports.length,
      gradleReports.map(filename => ({ filename, status: 'removed' })), { trusted });
    assert.deepEqual(e.failures, []);
    assert.match(e.info.join(''), /PASS: 6\/40/);
    assert.equal(e.tables[0][1][1], '6');
  });
  test(`${label} CI preserves authored HTML fixtures in the net diff and intermediate history`, async () => {
    for (const filename of htmlFixtures) {
      const net = await runCI(1, [{ filename, status: 'added' }], { trusted });
      assert.deepEqual(net.failures, [], filename); assert.match(net.info.join(''), /PASS: 1\/40/);
      const transient = await runCI(0, [], { trusted, history: [
        { sha: 'c'.repeat(40), files: [{ filename, status: 'added' }] },
        { sha: HEAD, files: [{ filename, status: 'removed' }] },
      ] });
      assert.deepEqual(transient.failures, [], filename); assert.match(transient.info.join(''), /PASS: 0\/40/);
    }
  });
}
