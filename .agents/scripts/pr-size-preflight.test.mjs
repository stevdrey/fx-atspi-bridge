import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { runCI } from './pr-policy-fixtures.mjs';

const script = fileURLToPath(new URL('./pr-size-preflight.mjs', import.meta.url));
const root = fileURLToPath(new URL('../../', import.meta.url));
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

test('local history rejects generated evidence introduced and removed before HEAD', t => {
  const cwd = repository(t), file = 'qa-results/transient.json';
  put(cwd, file); git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'capture');
  unlinkSync(join(cwd, file)); git(cwd, 'add', '-A'); git(cwd, 'commit', '-qm', 'delete capture');
  const result = check(cwd); assert.equal(result.status, 2); assert.match(result.stderr, /intermediate commits/);
  assert.doesNotMatch(result.stdout, /PASS/);
});
test('authored fixtures can be added and removed across intermediate commits', t => {
  const cwd = repository(t), file = 'src/test/resources/fixtures/snapshot.json';
  put(cwd, file); git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'fixture');
  unlinkSync(join(cwd, file)); git(cwd, 'add', '-A'); git(cwd, 'commit', '-qm', 'remove fixture');
  assert.match(check(cwd).stdout, /0\/40/);
});
test('merge inheritance from the updated integration base is not new evidence', t => {
  const cwd = repository(t); git(cwd, 'branch', 'integration');
  put(cwd, 'feature.txt'); git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'feature');
  git(cwd, 'switch', '-q', 'integration'); put(cwd, 'qa-results/old.json');
  git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'historical integration capture');
  git(cwd, 'update-ref', 'refs/remotes/origin/main', 'HEAD');
  git(cwd, 'switch', '-q', 'feature/preflight'); git(cwd, 'merge', '--no-ff', '-qm', 'merge integration', 'integration');
  assert.match(check(cwd).stdout, /1\/40/);
});
test('a push of another branch, matching refs, wildcards or tags fails closed', t => {
  const cwd = repository(t); git(cwd, 'switch', '-qc', 'huge'); createFiles(cwd, 41);
  git(cwd, 'add', '.'); git(cwd, 'commit', '-qm', 'huge'); git(cwd, 'switch', '-q', 'feature/preflight');
  for (const cmd of ['git push origin huge', 'git push origin HEAD huge', 'git push --all origin',
    'git push --tags origin', 'git push --mirror origin', 'git push origin refs/heads/*:refs/heads/*']) {
    const r = hook(cwd, cmd); assert.equal(r.status, 2, cmd); assert.match(r.stderr, /BLOCKED/);
  }
  assert.equal(hook(cwd, 'git push origin HEAD:refs/heads/feature').status, 0);
  assert.equal(hook(cwd, 'git push origin feature/preflight').status, 0);
  git(cwd, 'config', 'push.default', 'matching');
  assert.equal(hook(cwd, 'git push origin').status, 2);
  assert.equal(hook(cwd, 'git push origin HEAD').status, 0);
  git(cwd, 'config', '--unset', 'push.default'); git(cwd, 'config', 'remote.origin.push', 'huge');
  assert.equal(hook(cwd, 'git push origin').status, 2);
  assert.equal(hook(cwd, 'git push origin HEAD').status, 0);
  git(cwd, 'checkout', '-q', '--detach'); assert.equal(hook(cwd, 'git push origin HEAD').status, 0);
});
test('all warning targets produce one valid JSON response', t => {
  const launch = repository(t), a = repository(t), b = repository(t);
  createFiles(a, 31); createFiles(b, 40);
  const r = hook(launch, `git -C "${a}" push origin HEAD; git -C "${b}" push origin HEAD`);
  assert.equal(r.status, 0, r.stderr);
  const response = JSON.parse(r.stdout); assert.match(response.systemMessage, /31\/40/); assert.match(response.systemMessage, /40\/40/);
  assert.equal(r.stdout.trim().split('\n').length, 1); assert.doesNotMatch(r.stdout, /permissionDecision/);
});
test('publication target capacity is denied before Git inspection', t => {
  const cwd = repository(t); git(cwd, 'update-ref', '-d', 'refs/remotes/origin/main');
  const result = hook(cwd, Array(9).fill('git push origin HEAD').join('; '));
  assert.equal(result.status, 2); assert.match(result.stderr, /At most eight publication commands/);
});
test('stalled input receives an explicit denial from a timer below both host timeouts', t => {
  const cwd = repository(t);
  const hostTimeout = Math.min(...['.claude/settings.json', '.codex/hooks.json'].map(file =>
    JSON.parse(readFileSync(join(root, file), 'utf8')).hooks.PreToolUse[0].hooks[0].timeout * 1000));
  // Exercise the production timer callback with stdin held open, accelerating only
  // timer scheduling in the child so CI load cannot turn this into a wall-clock race.
  const childCode = `const original=setTimeout; globalThis.setTimeout=(callback,delay,...args)=>{if(delay>20000||delay>=${hostTimeout})throw new Error('deadline exceeds host timeout');return original(callback,Math.min(delay,100),...args)}; process.argv=[process.execPath,${JSON.stringify(script)},'--hook']; import(${JSON.stringify(new URL('./pr-size-preflight.mjs', import.meta.url).href)});`;
  const runner = `const {spawn}=require('node:child_process'); const child=spawn(process.execPath,['-e',${JSON.stringify(childCode)}],{cwd:${JSON.stringify(cwd)},stdio:['pipe','pipe','pipe']}); child.stdin.write('{'); child.stdout.pipe(process.stdout); child.stderr.pipe(process.stderr); child.on('exit',code=>process.exit(code));`;
  const result = command(process.execPath, ['-e', runner], cwd);
  assert.equal(result.status, 2, result.stderr); assert.match(result.stderr, /deadline exceeded/);
  assert.doesNotMatch(result.stdout, /PASS/);
});

test('slow successful operations share a deadline across repositories', t => {
  const cwd = repository(t), a = repository(t), b = repository(t);
  const shellCommand = `git -C "${a}" push origin HEAD; git -C "${b}" push origin HEAD`;
  // Advance only the child process's monotonic clock at each budget check. Real Git
  // commands still execute, but two individually small inspections exhaust one budget.
  const runner = `let elapsed=0; Object.defineProperty(globalThis,'performance',{value:{now:()=>elapsed+=500}}); process.argv=[process.execPath,${JSON.stringify(script)},'--hook']; import(${JSON.stringify(new URL('./pr-size-preflight.mjs', import.meta.url).href)});`;
  const result = command(process.execPath, ['-e', runner], cwd, JSON.stringify(payload(cwd, shellCommand)));
  assert.equal(result.status, 2, result.stderr); assert.match(result.stderr, /Aggregate inspection deadline exceeded/);
  assert.doesNotMatch(result.stdout, /PASS/);
});

test('artifact path diagnostics remain escaped when a path begins with a quote', t => {
  const cwd = repository(t); put(cwd, '"prefix/qa-results/x\n::error::injected.json');
  const result = check(cwd); assert.equal(result.status, 2);
  assert.match(result.stderr, /x\\n::error/); assert.doesNotMatch(result.stderr, /\n::error/);
});
test('deletions do not inspect an unrelated HEAD but follow-tag publication stays blocked', t => {
  const cwd = repository(t); createFiles(cwd, 41);
  assert.equal(hook(cwd, 'git push --delete origin old-branch').status, 0);
  assert.equal(hook(cwd, 'git push origin :old-branch').status, 0);
  git(cwd, 'config', 'push.followTags', 'true');
  assert.equal(hook(cwd, 'git push --delete origin old-branch').status, 2);
  assert.equal(hook(cwd, 'git push --no-follow-tags --delete origin old-branch').status, 0);
});
