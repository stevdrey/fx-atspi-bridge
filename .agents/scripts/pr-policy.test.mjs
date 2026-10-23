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
