import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import policy from './pr-policy.cjs';

const script = fileURLToPath(new URL('./pr-size-preflight.mjs', import.meta.url));
const root = fileURLToPath(new URL('../../', import.meta.url));
const workflow = readFileSync(join(root, '.github/workflows/pr-size-guard.yml'), 'utf8');
const ciScript = workflow.split('          script: |\n')[1].split('\n').map(l => l.replace(/^ {12}/, '')).join('\n');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const ci = new AsyncFunction('github', 'context', 'core', ciScript);
function command(cmd, args, cwd, input, env = {}) {
  return spawnSync(cmd, args, { cwd, encoding: 'utf8', input, timeout: 30000,
    env: { ...process.env, PR_PREFLIGHT_BASE: '', ...env } });
}
function git(cwd, ...args) {
  const r = command('git', args, cwd);
  assert.equal(r.status, 0, r.stderr);
  return r.stdout.trim();
}
function put(cwd, file, content = 'test\n') {
  mkdirSync(dirname(join(cwd, file)), { recursive: true });
  writeFileSync(join(cwd, file), content);
}
function repository(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'fx-pr-preflight-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  git(cwd, 'init', '-q', '-b', 'main');
  git(cwd, 'config', 'user.name', 'Test Agent');
  git(cwd, 'config', 'user.email', 'agent@example.invalid');
  put(cwd, 'README.md', 'baseline\n');
  git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'baseline');
  git(cwd, 'update-ref', 'refs/remotes/origin/main', 'HEAD');
  git(cwd, 'switch', '-qc', 'feature/preflight');
  return cwd;
}
function createFiles(cwd, amount) {
  for (let i = 0; i < amount; i++) put(cwd, `samples/file-${i}.txt`);
}
function check(cwd, args = [], env = {}) { return command(process.execPath, [script, ...args], cwd, undefined, env); }
function payload(cwd, shellCommand, extra = {}) {
  return { session_id: 'test-session', hook_event_name: 'PreToolUse', tool_name: 'Bash',
    cwd, tool_input: { command: shellCommand }, ...extra };
}
function hook(cwd, shellCommand, extra = {}) {
  return command(process.execPath, [script, '--hook'], cwd, JSON.stringify(payload(cwd, shellCommand, extra)));
}
async function runCI(count, files = Array.from({ length: count ?? 0 }, (_, i) => ({ filename: `src/file-${i}.java`, status: 'modified' })), options = {}) {
  const events = { failures: [], warnings: [], info: [], tables: [], pages: [] };
  let calls = 0;
  const github = { rest: { pulls: {
    async get() {
      calls++;
      if (options.apiFailure) throw new Error('private server detail');
      return { data: { changed_files: count, head: { sha: calls > 1 && options.stale ? 'other' : 'head' }, base: { sha: 'base' } } };
    },
    async listFiles({ page, per_page }) {
      events.pages.push(page);
      if (options.listFailure) throw new Error('private list error');
      return { data: files.slice((page - 1) * per_page, page * per_page) };
    },
  } } };
  const summary = { addHeading() { return this; }, addTable(table) { events.tables.push(table); return this; }, async write() {} };
  const core = { summary, setFailed: m => events.failures.push(m), warning: m => events.warnings.push(m), info: m => events.info.push(m) };
  await ci(github, { repo: { owner: 'example', repo: 'project' }, issue: { number: 43 } }, core);
  return events;
}

test('CI executable is generated exactly from canonical policy and never checks out code', () => {
  assert.equal(workflow, policy.renderWorkflow());
  assert.match(workflow, /branches: \[main\]/);
  assert.match(workflow, /types: \[opened, reopened, synchronize, ready_for_review, edited\]/);
  assert.match(workflow, /permissions:\n  pull-requests: read\n/);
  assert.match(workflow, /name: PR file count/);
  assert.doesNotMatch(workflow, /checkout|pull_request_target|contents: write|secrets\./);
  assert.match(workflow, /actions\/github-script@[0-9a-f]{40}/);
});
for (const count of [0, 30, 31, 40, 41]) {
  test(`local and actual CI boundaries at ${count}`, async t => {
    const cwd = repository(t); createFiles(cwd, count);
    const local = check(cwd);
    const events = await runCI(count);
    assert.equal(local.status, count > 40 ? 2 : 0, local.stderr);
    assert.match(local.stdout + local.stderr, new RegExp(`${count}/40`));
    assert.equal(events.failures.length, count > 40 ? 1 : 0);
    assert.equal(events.warnings.length, count > 30 && count <= 40 ? 1 : 0);
    assert.equal(events.tables.length, 1);
  });
}
test('all commits, index, worktree and untracked paths union without duplicates', t => {
  const cwd = repository(t);
  put(cwd, 'committed.txt'); git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'one');
  put(cwd, 'second.txt'); git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'two');
  put(cwd, 'committed.txt', 'staged\n'); put(cwd, 'staged.txt'); git(cwd, 'add', '.');
  put(cwd, 'committed.txt', 'unstaged\n'); unlinkSync(join(cwd, 'README.md'));
  put(cwd, 'untracked.txt');
  assert.match(check(cwd).stdout, /5\/40/);
});
test('index changes canceled in worktree still count conservatively', t => {
  const cwd = repository(t); put(cwd, 'README.md', 'staged\n'); git(cwd, 'add', '.');
  put(cwd, 'README.md', 'baseline\n');
  assert.match(check(cwd).stdout, /1\/40/);
});
test('rename destination counts once, ignored output excluded', t => {
  const cwd = repository(t);
  renameSync(join(cwd, 'README.md'), join(cwd, 'RENAMED.md')); git(cwd, 'add', '-A');
  assert.match(check(cwd).stdout, /1\/40/);
  put(cwd, '.gitignore', '*.generated\n'); put(cwd, 'temp.generated');
  assert.match(check(cwd).stdout, /2\/40/);
});
test('spaces, tabs, newlines, Unicode and leading dashes are separate paths', t => {
  const cwd = repository(t);
  for (const name of ['space name.txt', 'tab\tname.txt', 'line\nname.txt', 'áé😀.txt', '-option.txt']) put(cwd, name);
  git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'unusual paths');
  assert.match(check(cwd).stdout, /5\/40/);
});
test('explicit and environment bases support stacked PRs', t => {
  const cwd = repository(t); createFiles(cwd, 41);
  git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'first layer');
  git(cwd, 'branch', 'stack-base'); put(cwd, 'next-layer.txt');
  assert.equal(check(cwd).status, 2);
  assert.match(check(cwd, ['--base', 'stack-base']).stdout, /1\/40/);
  assert.match(check(cwd, [], { PR_PREFLIGHT_BASE: 'stack-base' }).stdout, /1\/40/);
});
test('missing/unrelated bases and Git errors fail closed', t => {
  const cwd = repository(t);
  assert.equal(check(cwd, ['--base', 'missing']).status, 2);
  git(cwd, 'checkout', '--orphan', 'unrelated'); git(cwd, 'commit', '-qm', 'unrelated');
  assert.equal(check(cwd).status, 2);
  const bad = check(tmpdir()); assert.equal(bad.status, 2); assert.doesNotMatch(bad.stdout, /PASS/);
});
test('ordinary commands skip Git validation', t => {
  const cwd = repository(t); git(cwd, 'update-ref', '-d', 'refs/remotes/origin/main');
  for (const cmd of ['npm test', './gradlew check', 'git status', 'gh pr view', 'echo "git push"', 'printf \'gh pr create\'', 'cd $BUILD_DIR && npm test', 'git -C $DIR status']) {
    const r = hook(cwd, cmd); assert.equal(r.status, 0, r.stderr); assert.equal(r.stdout + r.stderr, '');
  }
});
test('publication hooks block every update above limit and permit smaller changes', t => {
  const cwd = repository(t);
  assert.equal(hook(cwd, 'git push origin HEAD').status, 0);
  createFiles(cwd, 41);
  for (const cmd of ['git push origin HEAD', 'gh pr create --fill', 'git -c push.default=current push', 'git -C . push', 'npm test && git push']) {
    const r = hook(cwd, cmd); assert.equal(r.status, 2, cmd); assert.match(r.stderr, /BLOCKED/);
  }
});
test('hook warnings are visible as JSON without granting permission', t => {
  const cwd = repository(t); createFiles(cwd, 31);
  const r = hook(cwd, 'git push'); assert.equal(r.status, 0);
  assert.match(JSON.parse(r.stdout).systemMessage, /31\/40/);
  assert.doesNotMatch(r.stdout, /permissionDecision/);
});
test('malformed, missing, wrong and oversized hook payloads fail closed', t => {
  const cwd = repository(t);
  for (const input of ['{bad json', '{}', JSON.stringify(payload(cwd, '', {})), JSON.stringify(payload(cwd, 'git push', { tool_input: { cmd: 'git push' } })), JSON.stringify(payload(cwd, 'git push', { hook_event_name: 'PostToolUse' })), ' '.repeat(1024 * 1024 + 1)]) {
    const r = command(process.execPath, [script, '--hook'], cwd, input);
    assert.equal(r.status, 2, r.stderr); assert.match(r.stderr, /BLOCKED/); assert.doesNotMatch(r.stdout, /PASS/);
  }
});
test('bad CLI options fail closed', t => {
  const cwd = repository(t);
  for (const args of [['--base'], ['--base', '--help'], ['--unknown'], ['--base', '-bad']]) assert.equal(check(cwd, args).status, 2);
});
const prohibited = [
  'docs/verification/data/issue-43/snapshot.json', 'docs/verification/issue-43/snapshot.json', 'snapshots/tree.json', 'screenshots/dialog.png', 'docs/verification/data/issue-43/results.csv',
  'docs/verification/screenshots/dialog.png', 'docs/verification/data/issue-43/nmt.txt',
  'docs/verification/data/issue-43/smaps_rollup.txt', 'docs/verification/data/issue-43/MANIFEST.sha256',
  'test-results/unit/TEST-sample.xml', 'qa-results/run.json', 'benchmark-results/run.csv',
  'qa/screenshots/dialog.png', 'evidence-bundles/receipt.md', 'TEST-run.xml', 'heapdump-1.hprof',
];
const permitted = [
  'src/test/java/SnapshotTest.java', 'src/test/resources/fixtures/snapshot.json',
  'src/test/resources/fixtures/TEST-parser.xml', 'src/test/resources/images/icon.png',
  'examples/fx-lab/scripts/benchmark.py', 'docs/verification/issue-43.md',
  'docs/verification/data/issue-43/SmokeApp.java', 'docs/verification/data/issue-43/probe.py',
];
test('local and CI reject recognizable evidence below file limit, allow authored fixtures/summaries', async t => {
  const cwd = repository(t);
  for (const filename of [...prohibited, ...permitted]) {
    const blocked = prohibited.includes(filename);
    put(cwd, filename);
    const r = check(cwd); assert.equal(r.status, blocked ? 2 : 0, filename + r.stderr);
    const e = await runCI(1, [{ filename, status: 'added' }]);
    assert.equal(e.failures.length, blocked ? 1 : 0, filename);
    if (blocked) assert.doesNotMatch(e.info.join(' '), /PASS/);
    unlinkSync(join(cwd, filename));
  }
});
test('tracked forced output cannot hide behind ignore rules and rename into output is rejected', t => {
  const cwd = repository(t); put(cwd, '.gitignore', '*.log\n');
  put(cwd, 'docs/verification/data/issue-43/run.log');
  assert.equal(check(cwd).status, 0);
  git(cwd, 'add', '-f', 'docs/verification/data/issue-43/run.log');
  assert.equal(check(cwd).status, 2);
  git(cwd, 'reset', '-q'); unlinkSync(join(cwd, 'docs/verification/data/issue-43/run.log'));
  renameSync(join(cwd, 'README.md'), join(cwd, 'docs/verification/data/issue-43/results.json'));
  git(cwd, 'add', '-A'); assert.equal(check(cwd).status, 2);
});
test('deleting historical output is permitted while counting removal', async t => {
  const cwd = repository(t); const filename = 'test-results/old.json';
  put(cwd, filename); git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'historical');
  git(cwd, 'update-ref', 'refs/remotes/origin/main', 'HEAD');
  unlinkSync(join(cwd, filename));
  assert.match(check(cwd).stdout, /1\/40/);
  assert.equal((await runCI(1, [{ filename, status: 'removed' }])).failures.length, 0);
});
test('artifact diagnostics escape control characters without reading contents', t => {
  const cwd = repository(t); put(cwd, 'qa-results/x\n::error::injected.json', 'SECRET-CONTENT');
  const r = check(cwd); assert.equal(r.status, 2);
  assert.match(r.stderr, /x\\n::error/); assert.doesNotMatch(r.stderr, /\n::error|SECRET-CONTENT/);
});
test('CI invalid counts, API failures, incomplete/duplicate metadata and stale revisions fail closed', async () => {
  for (const count of [undefined, null, -1, 1.5, '30', Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal((await runCI(count, [])).failures.length, 1);
  }
  for (const options of [{ apiFailure: true }, { listFailure: true }, { stale: true }]) {
    const e = await runCI(1, undefined, options); assert.equal(e.failures.length, 1);
    assert.doesNotMatch(e.failures[0], /private/);
  }
  assert.equal((await runCI(2, [{ filename: 'one', status: 'added' }])).failures.length, 1);
  assert.equal((await runCI(2, Array(2).fill({ filename: 'same', status: 'added' }))).failures.length, 1);
  assert.equal((await runCI(1, [{ filename: 'bad', status: 'unknown' }])).failures.length, 1);
});
test('CI paginates all paths and still detects evidence after first page', async () => {
  const files = Array.from({ length: 101 }, (_, i) => ({ filename: `src/${i}.java`, status: 'modified' }));
  files[100] = { filename: 'qa-results/run.json', status: 'added' };
  const e = await runCI(101, files); assert.deepEqual(e.pages, [1, 2]); assert.equal(e.failures.length, 2);
});
test('both provider configs execute the canonical hook with documented Bash input', t => {
  const cwd = repository(t);
  // Exercise config commands from a subdirectory in this actual worktree, no publication occurs.
  for (const file of ['.claude/settings.json', '.codex/hooks.json']) {
    const config = JSON.parse(readFileSync(join(root, file), 'utf8'));
    const group = config.hooks.PreToolUse[0]; assert.equal(group.matcher, 'Bash');
    const handler = group.hooks[0]; assert.equal(handler.type, 'command');
    assert.ok(handler.timeout >= 30); assert.match(handler.command, /\.agents\/scripts\/pr-size-preflight\.mjs/);
    const r = command('sh', ['-c', handler.command], join(root, 'docs'),
      JSON.stringify(payload(cwd, 'git push')), { CLAUDE_PROJECT_DIR: root });
    assert.equal(r.status, 0, r.stderr);
    createFiles(cwd, 41);
    const blocked = command('sh', ['-c', handler.command], join(root, 'docs'),
      JSON.stringify(payload(cwd, 'gh pr create --fill')), { CLAUDE_PROJECT_DIR: root });
    assert.equal(blocked.status, 2); assert.match(blocked.stderr, /41\/40/);
    rmSync(join(cwd, 'samples'), { recursive: true });
  }
});

test('publication inspects command directories and shell wrappers, not the launch repository', t => {
  const launch = repository(t), target = repository(t); createFiles(target, 41);
  for (const cmd of [`git -C "${target}" push`, `cd "${target}" && git push`, `bash -lc 'git -C "${target}" push'`, `env FLAG=1 command git -C "${target}" push`]) {
    const r = hook(launch, cmd); assert.equal(r.status, 2, cmd); assert.match(r.stderr, /41\/40/);
  }
  assert.equal(hook(launch, 'git push', { tool_input: { command: 'git push', workdir: target } }).status, 2);
  assert.equal(hook(launch, 'git -C $TARGET push').status, 2);
  assert.equal(hook(launch, 'cd $TARGET && git push').status, 2);
  assert.equal(hook(launch, 'git -C . push').status, 0);
});

test('a generated staged output cannot be hidden by restoring the worktree', t => {
  const cwd = repository(t); const file = 'docs/verification/data/issue-43/run.json';
  put(cwd, file); git(cwd, 'add', '.'); unlinkSync(join(cwd, file));
  assert.equal(check(cwd).status, 2);
});

test('ordinary multiline scripts and heredocs do not undergo shell or Git validation', t => {
  const cwd = repository(t); git(cwd, 'update-ref', '-d', 'refs/remotes/origin/main');
  const cmd = "python3 - <<'PY'\nprint(\"it's a test\")\nPY";
  const r = hook(cwd, cmd); assert.equal(r.status, 0, r.stderr); assert.equal(r.stdout + r.stderr, '');
});
