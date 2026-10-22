#!/usr/bin/env node
// Adapted from stevdrey/dokene; local usability preflight, never an authorization boundary.
import { spawnSync } from 'node:child_process';
import { realpathSync } from 'node:fs';
import { basename, resolve } from 'node:path';
import policy from './pr-policy.cjs';

// A small shell lexer, never a shell evaluator. Wrappers/scripts/aliases are documented limitations.
function shellWords(command) {
  const segments = [[]];
  let word = '', active = false, quote = '';
  const flush = () => { if (active) segments.at(-1).push(word); word = ''; active = false; };
  for (let i = 0; i < command.length; i++) {
    const c = command[i];
    if (c === '\\' && quote !== "'") {
      if (++i >= command.length) throw new Error('Malformed shell command');
      if (command[i] !== '\n') { word += command[i]; active = true; }
    } else if (quote) {
      if (c === quote) quote = ''; else word += c;
    } else if (c === "'" || c === '"') { quote = c; active = true; }
    else if (';&|()\n'.includes(c)) { flush(); segments.push([]); }
    else if (/\s/.test(c)) flush();
    else if (c === '#' && !active) { while (i + 1 < command.length && command[i + 1] !== '\n') i++; }
    else { word += c; active = true; }
  }
  if (quote) throw new Error('Malformed shell command');
  flush(); return segments.filter(s => s.length);
}
function publicationTargets(command, cwd, depth = 0, state = { headChanged: false }, inheritedGitEnvironment = false) {
  // Avoid parsing arbitrary test scripts/heredocs unless publication is even plausible.
  if (depth === 0 && !/\b(?:git\b[\s\S]*?\bpush|gh\b[\s\S]*?\b(?:create|new))\b/.test(command)) return [];
  if (depth > 3) throw new Error('Shell wrapper nesting exceeds preflight limit');
  const targets = [];
  let uncertainCwd = false, gitEnvironment = inheritedGitEnvironment;
  const repositoryVariable = /^(?:GIT_DIR|GIT_WORK_TREE)(?:=|$)/;
  const appendTarget = target => {
    if (uncertainCwd) throw new Error('Dynamic publication directory requires manual preflight');
    if (targets.length >= 8) throw new Error('At most eight publication commands may be inspected');
    targets.push(target);
  };
  const addTarget = (target, environment) => {
    if (state.headChanged) throw new Error('Branch change before publication requires separate commands; switch or checkout first, then publish');
    if (environment) throw new Error('Git environment override requires manual preflight; use the target working directory or git -C instead');
    appendTarget(target);
  };
  const staticPath = value => {
    if (!value || /[$`~*?{}<>]/.test(value)) throw new Error('Dynamic publication directory requires manual preflight');
    return value;
  };
  for (let words of shellWords(command)) {
    let commandEnvironment = gitEnvironment, environmentWrapper = false;
    while (words.length) {
      if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(words[0])) {
        if (repositoryVariable.test(words[0])) commandEnvironment = true;
      } else if (basename(words[0]) === 'env') environmentWrapper = true;
      else if (!['command', 'exec', '--'].includes(words[0])) break;
      words = words.slice(1);
      // Do not silently miss a publication behind unparsed env options.
      if (environmentWrapper && words[0]?.startsWith('-') && words[0] !== '--') {
        throw new Error('Unverifiable environment wrapper; use the target working directory and publish separately');
      }
    }
    if (!words.length) {
      if (!environmentWrapper) gitEnvironment = commandEnvironment;
      continue;
    }
    const executable = basename(words[0]);
    if (executable === 'export') {
      if (words.slice(1).some(word => repositoryVariable.test(word))) gitEnvironment = true;
      continue;
    }
    if (executable === 'cd') {
      try { cwd = resolve(cwd, staticPath(words[1])); } catch { uncertainCwd = true; }
      continue;
    }
    if (['sh', 'bash', 'zsh'].includes(executable) && /^-[a-z]*c[a-z]*$/.test(words[1] || '') && words[2]) {
      // Filesystem/HEAD mutations survive child shells; exported variables do not.
      for (const target of publicationTargets(words[2], cwd, depth + 1, state, commandEnvironment)) appendTarget(target);
    }
    if (executable === 'git') {
      let i = 1;
      const directories = [], config = [];
      for (; i < words.length; i++) {
        if (words[i] === '-C' && words[i + 1]) directories.push(words[++i]);
        else if (words[i].startsWith('-C') && words[i].length > 2) directories.push(words[i].slice(2));
        else if (words[i] === '-c' && words[i + 1]) config.push('-c', words[++i]);
        else if (words[i].startsWith('--git-dir') || words[i].startsWith('--work-tree')) {
          if (words.includes('push')) throw new Error('Git directory override requires manual preflight');
          if (words.some(word => ['switch', 'checkout'].includes(word))) state.headChanged = true;
          break;
        } else if (words[i].startsWith('-')) continue;
        else break;
      }
      if (['switch', 'checkout'].includes(words[i])) state.headChanged = true;
      if (words[i] === 'push') {
        let target = cwd;
        for (const directory of directories) target = resolve(target, staticPath(directory));
        addTarget({ cwd: target, kind: 'push', args: words.slice(i + 1), config }, commandEnvironment);
      }
    } else if (executable === 'gh') {
      let i = 1;
      while (i < words.length) {
        if (['-R', '--repo'].includes(words[i]) && words[i + 1]) i += 2;
        else if (words[i].startsWith('--repo=') || /^-R.+/.test(words[i])) i++;
        else break;
      }
      if (words[i] === 'pr' && ['create', 'new'].includes(words[i + 1])) {
        addTarget({ cwd, kind: 'pr', args: words.slice(i + 2) }, commandEnvironment);
      }
    }
  }
  return targets;
}
const decoder = new TextDecoder('utf-8', { fatal: true });
const deadline = performance.now() + 20000;
function remaining() {
  const value = Math.floor(deadline - performance.now());
  if (value <= 0) throw new Error('Aggregate inspection deadline exceeded');
  return value;
}
function gitResult(cwd, args, allowed = [0]) {
  const result = spawnSync('git', args, { cwd, encoding: 'buffer', timeout: Math.min(3000, remaining()),
    maxBuffer: 8 * 1024 * 1024, env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' } });
  remaining();
  if (result.error || !allowed.includes(result.status)) throw new Error(`git ${args[0]} failed`);
  return decoder.decode(result.stdout);
}
function git(cwd, ...args) { return gitResult(cwd, args); }
function validatePush(target) {
  const args = target.args, positionals = [];
  let deletion = false, noFollowTags = false;
  const flags = new Set(['-u', '--set-upstream', '-f', '--force', '--force-with-lease', '--force-if-includes',
    '--atomic', '--no-verify', '--verify', '--dry-run', '-n', '--porcelain', '--quiet', '-q', '--verbose', '-v', '--progress', '--no-progress', '--signed', '--no-signed', '--no-thin', '--thin']);
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--no-follow-tags') noFollowTags = true;
    else if (['--delete', '-d'].includes(arg)) deletion = true;
    else if (arg === '--') { positionals.push(...args.slice(i + 1)); break; }
    else if (['-o', '--push-option', '--receive-pack', '--exec'].includes(arg)) {
      if (!args[++i]) throw new Error('Unverifiable push options');
    } else if (/^--(?:force-with-lease|push-option|receive-pack|exec|signed)=/.test(arg) || flags.has(arg)) continue;
    else if (arg.startsWith('-')) throw new Error('Push may publish other refs; switch to the target branch and push HEAD explicitly');
    else positionals.push(arg);
  }
  const refs = positionals.slice(1);
  const configured = gitResult(target.cwd, [...target.config, 'config', '--get-regexp',
    '^(remote\\..*\\.(push|mirror)|push\\.(default|followTags))$'], [0, 1]);
  for (const line of configured.split('\n').filter(Boolean)) {
    const separator = line.search(/\s/), key = line.slice(0, separator).toLowerCase(), value = line.slice(separator).trim();
    if (key.endsWith('.mirror') && value !== 'false') throw new Error('Push may publish other refs; disable mirror publication');
    if (key === 'push.followtags' && value !== 'false' && !noFollowTags) throw new Error('Push may publish other refs; use --no-follow-tags');
    if (!refs.length && ((key.startsWith('remote.') && key.endsWith('.push')) ||
        (key === 'push.default' && !['simple', 'current', 'upstream', 'nothing'].includes(value)))) {
      throw new Error('Push may publish other refs; push HEAD explicitly without matching or configured refspecs');
    }
  }
  if (deletion || (refs.length && refs.every(ref => !ref.replace(/^\+/, '').split(':')[0]))) return false;
  if (refs.length) {
    const branch = gitResult(target.cwd, ['symbolic-ref', '--quiet', '--short', 'HEAD'], [0, 1]).trim();
    for (const ref of refs) {
      const source = ref.replace(/^\+/, '').split(':')[0];
      if (!source) continue;
      if (!['HEAD', branch, `refs/heads/${branch}`].includes(source) || /[*?]/.test(ref)) {
        throw new Error('Push source is not checked-out HEAD; switch to that branch before publication');
      }
    }
  }
  return true;
}
function validatePrCreate(target) {
  const valueFlags = new Set(['--assignee', '--attach', '--base', '--body', '--body-file', '--head', '--label',
    '--milestone', '--project', '--recover', '--reviewer', '--template', '--title', '--repo']);
  const switchFlags = new Set(['--draft', '--dry-run', '--editor', '--fill', '--fill-first', '--fill-verbose',
    '--no-maintainer-edit', '--web', '--help']);
  const shortValues = new Set('aBbFHlmprTtR');
  const shortSwitches = new Set('defw');
  let headSelected = false, help = false;
  const value = (args, i, attached) => {
    if (attached !== undefined) return attached;
    if (i + 1 >= args.length) throw new Error('Unverifiable gh pr create options');
    return args[i + 1];
  };
  for (let i = 0; i < target.args.length; i++) {
    const arg = target.args[i];
    if (arg.startsWith('--')) {
      const equal = arg.indexOf('=');
      const name = equal === -1 ? arg : arg.slice(0, equal);
      if (switchFlags.has(name)) {
        if (name === '--help') {
          const setting = equal === -1 ? 'true' : arg.slice(equal + 1);
          if (!['true', 'false'].includes(setting)) throw new Error('Unverifiable gh pr create options');
          help = setting === 'true';
        }
        continue;
      }
      if (!valueFlags.has(name)) throw new Error('Unverifiable gh pr create options');
      const attached = equal === -1 ? undefined : arg.slice(equal + 1);
      value(target.args, i, attached);
      if (name === '--head') headSelected = true;
      if (attached === undefined) i++;
    } else if (arg.startsWith('-') && arg.length > 1) {
      for (let j = 1; j < arg.length; j++) {
        const flag = arg[j];
        if (shortSwitches.has(flag)) continue;
        if (!shortValues.has(flag)) throw new Error('Unverifiable gh pr create options');
        const attached = j + 1 < arg.length ? arg.slice(j + 1) : undefined;
        value(target.args, i, attached);
        if (flag === 'H') headSelected = true;
        if (attached === undefined) i++;
        break;
      }
    } else throw new Error('Unverifiable gh pr create options');
  }
  if (help) return false;
  if (headSelected) throw new Error('Explicit gh head selection is not verified; check out the intended branch and omit --head/-H');
  return true;
}
function nullFields(value) {
  if (!value) return [];
  if (!value.endsWith('\0')) throw new Error('Incomplete Git output');
  return value.slice(0, -1).split('\0');
}
function diffEntries(value) {
  const fields = nullFields(value);
  const entries = [];
  for (let i = 0; i < fields.length;) {
    const status = fields[i++];
    if (!/^(?:[ACDMRTUXB]|[RC]\d{1,3})$/.test(status)) throw new Error('Invalid Git status');
    const first = fields[i++];
    const filename = /^[RC]/.test(status) ? fields[i++] : first;
    if (!filename || !first) throw new Error('Incomplete Git filename');
    entries.push({ filename, removed: status === 'D' });
  }
  return entries;
}
function changedFiles(cwd, baseRef) {
  const root = git(cwd, 'rev-parse', '--show-toplevel').replace(/\n$/, '');
  const base = git(root, 'merge-base', '--', baseRef, 'HEAD').trim();
  if (!/^[0-9a-f]{40,64}$/.test(base)) throw new Error('No verified merge base');
  const options = ['--name-status', '-z', '--find-renames'];
  const groups = [
    diffEntries(git(root, 'diff', ...options, base, 'HEAD', '--')),
    diffEntries(git(root, 'diff', ...options, '--cached', 'HEAD', '--')),
    diffEntries(git(root, 'diff', ...options, '--')),
    nullFields(git(root, 'ls-files', '--others', '--exclude-standard', '-z')).map(filename => ({ filename, removed: false })),
  ];
  const paths = new Set();
  const prohibited = new Set();
  for (const entries of groups) for (const entry of entries) paths.add(entry.filename);
  // Check the final proposed diff for artifacts, allowing removal of old generated files.
  const final = diffEntries(git(root, 'diff', ...options, base, '--'));
  const deleted = new Set(final.filter(entry => entry.removed).map(entry => entry.filename));
  for (const entry of groups.flat()) {
    if (!entry.removed && !deleted.has(entry.filename) && policy.evidenceReason(entry.filename)) prohibited.add(JSON.stringify(entry.filename));
  }
  for (const entry of historyEvidence(root, base)) prohibited.add(entry);
  return { count: paths.size, prohibited };
}
function historyEvidence(root, base) {
  const commits = git(root, 'rev-list', '--reverse', `--max-count=${policy.MAX_HISTORY_COMMITS + 1}`, `${base}..HEAD`).trim().split('\n').filter(Boolean);
  if (commits.length > policy.MAX_HISTORY_COMMITS) throw new Error('Feature history exceeds inspection capacity');
  const result = new Set();
  let records = 0;
  const blob = (sha, file) => {
    const value = git(root, 'ls-tree', '-z', sha, '--', file);
    return value ? value.slice(0, value.indexOf('\t')).split(' ')[2] : null;
  };
  for (const commit of commits) {
    if (!/^[0-9a-f]{40,64}$/.test(commit)) throw new Error('Invalid commit metadata');
    const parents = git(root, 'show', '-s', '--format=%P', commit, '--').trim().split(' ').filter(Boolean);
    const options = ['--name-status', '-z', '--find-renames'];
    const entries = parents.length ? diffEntries(git(root, 'diff', ...options, parents[0], commit, '--')) :
      diffEntries(git(root, 'diff-tree', '--root', '--no-commit-id', '-r', ...options, commit, '--'));
    records += entries.length;
    if (records > policy.MAX_HISTORY_FILES || entries.length >= 3000) throw new Error('Feature history exceeds inspection capacity');
    for (const entry of entries) {
      if (entry.removed || !policy.evidenceReason(entry.filename)) continue;
      if (parents.length > 1) {
        const value = blob(commit, entry.filename);
        if (!value) throw new Error('Missing result blob');
        if (parents.slice(1).some(parent => blob(parent, entry.filename) === value)) continue;
      }
      result.add(`${JSON.stringify(entry.filename)} at ${commit.slice(0, 12)}`);
    }
  }
  return result;
}
async function hookInput() {
  const chunks = [];
  let size = 0;
  for await (const chunk of process.stdin) {
    size += chunk.length;
    if (size > 1024 * 1024) throw new Error('Hook input exceeds 1 MiB');
    chunks.push(chunk);
  }
  let value;
  try { value = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new Error('Malformed hook JSON'); }
  if (value?.hook_event_name !== 'PreToolUse' || value.tool_name !== 'Bash' ||
      typeof value.tool_input?.command !== 'string' || !value.tool_input.command.trim() ||
      typeof value.cwd !== 'string' || !value.cwd) throw new Error('Invalid PreToolUse Bash payload');
  return value;
}
function options(args) {
  let hook = false;
  let base = process.env.PR_PREFLIGHT_BASE || 'origin/main';
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--hook' && !hook) hook = true;
    else if (args[i] === '--base' && args[i + 1] && !args[i + 1].startsWith('-')) base = args[++i];
    else throw new Error('Use [--hook] [--base REF]');
  }
  if (!base.trim() || base.startsWith('-') || /[\0\r\n]/.test(base)) throw new Error('Invalid base reference');
  return { hook, base };
}
async function main() {
  const { hook, base } = options(process.argv.slice(2));
  let cwd = process.cwd();
  const results = [];
  if (hook) {
    const payload = await hookInput();
    cwd = payload.cwd;
    const inputCwd = payload.tool_input.cwd ?? payload.tool_input.workdir;
    if (inputCwd !== undefined) {
      if (typeof inputCwd !== 'string' || !inputCwd) throw new Error('Invalid command working directory');
      cwd = resolve(cwd, inputCwd);
    }
    const targets = publicationTargets(payload.tool_input.command, cwd);
    const inspected = new Set();
    for (const target of targets) {
      remaining();
      target.cwd = realpathSync(target.cwd);
      if (target.kind === 'push' && !validatePush(target)) continue;
      if (target.kind === 'pr' && !validatePrCreate(target)) continue;
      if (!inspected.has(target.cwd)) {
        results.push(inspect(target.cwd, base)); inspected.add(target.cwd);
      }
    }
  } else results.push(inspect(realpathSync(cwd), base));
  remaining();
  const warnings = [];
  for (const result of results) {
    if (result.prohibited.size) {
      console.error('BLOCKED: Generated evidence is prohibited, including intermediate commits: ' +
        [...result.prohibited].slice(0, 10).join(', ') +
        '. Remove captures from feature history; deleting later is insufficient. Keep an authored summary.');
      process.exitCode = 2;
    }
    if (result.level === 'BLOCKED') {
      console.error(`BLOCKED: ${result.message} Split into independently reviewable PRs; preserve required tests, security controls and documentation.`);
      process.exitCode = 2;
    } else if (result.level === 'WARNING') {
      const warning = `WARNING: ${result.message} Review scope before adding more changes.`;
      console.error(warning); warnings.push(warning);
    }
  }
  if (hook && !process.exitCode && warnings.length) console.log(JSON.stringify({ systemMessage: warnings.join('\n') }));
  if (!hook && !process.exitCode && !warnings.length) console.log(`PASS: ${results[0].message}`);
}
function inspect(cwd, base) {
  const { count, prohibited } = changedFiles(cwd, base);
  const level = policy.countLevel(count);
  const message = `PR preflight: ${count}/${policy.MAX_FILES} changed files relative to ${JSON.stringify(base)}.`;
  return { level, message, prohibited };
}
const timer = setTimeout(() => {
  console.error('BLOCKED: Aggregate inspection deadline exceeded before the provider timeout.');
  process.exit(2);
}, Math.max(1, deadline - performance.now()));
try { await main(); }
catch (error) {
  const safe = /^(git \S+ failed|No verified merge base|Incomplete Git output|Invalid Git status|Incomplete Git filename|Hook input exceeds 1 MiB|Malformed hook JSON|Invalid PreToolUse Bash payload|Use \[--hook\] \[--base REF\]|Invalid base reference|Invalid command working directory|Malformed shell command|Shell wrapper nesting exceeds preflight limit|Dynamic publication directory requires manual preflight|Git directory override requires manual preflight|Git environment override requires manual preflight|Branch change before publication requires separate commands|Unverifiable environment wrapper|At most eight publication commands may be inspected|Aggregate inspection deadline exceeded|Push |Unverifiable push options|Unverifiable gh pr create options|Explicit gh head selection is not verified|Feature history exceeds inspection capacity|Invalid commit metadata|Missing result blob)/.test(error.message);
  console.error(`BLOCKED: PR preflight could not verify scope: ${safe ? error.message : 'Git, path or input validation failed'}. Check the source ref and base, refresh origin/main and retry.`);
  process.exitCode = 2;
} finally { clearTimeout(timer); }
