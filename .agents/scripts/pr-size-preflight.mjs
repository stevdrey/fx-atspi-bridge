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
function publicationTargets(command, cwd, depth = 0) {
  // Avoid parsing arbitrary test scripts/heredocs unless publication is even plausible.
  if (!/\b(?:git\b[\s\S]*?\bpush|gh\b[\s\S]*?\bpr\s+create)\b/.test(command)) return new Set();
  if (depth > 3) throw new Error('Shell wrapper nesting exceeds preflight limit');
  const targets = new Set();
  let uncertainCwd = false;
  const addTarget = target => {
    if (uncertainCwd) throw new Error('Dynamic publication directory requires manual preflight');
    targets.add(target);
  };
  const staticPath = value => {
    if (!value || /[$`~*?{}<>]/.test(value)) throw new Error('Dynamic publication directory requires manual preflight');
    return value;
  };
  for (let words of shellWords(command)) {
    while (words.length && (/^[A-Za-z_][A-Za-z0-9_]*=/.test(words[0]) || ['command', 'exec', 'env', '--'].includes(words[0]))) words = words.slice(1);
    const executable = basename(words[0] || '');
    if (executable === 'cd') {
      try { cwd = resolve(cwd, staticPath(words[1])); } catch { uncertainCwd = true; }
      continue;
    }
    if (['sh', 'bash', 'zsh'].includes(executable) && /^-[a-z]*c[a-z]*$/.test(words[1] || '') && words[2]) {
      for (const target of publicationTargets(words[2], cwd, depth + 1)) addTarget(target);
    }
    if (executable === 'git') {
      let i = 1;
      const directories = [];
      for (; i < words.length; i++) {
        if (words[i] === '-C' && words[i + 1]) directories.push(words[++i]);
        else if (words[i].startsWith('-C') && words[i].length > 2) directories.push(words[i].slice(2));
        else if (words[i] === '-c' && words[i + 1]) i++;
        else if (words[i].startsWith('--git-dir') || words[i].startsWith('--work-tree')) {
          if (words.includes('push')) throw new Error('Git directory override requires manual preflight');
          break;
        } else if (words[i].startsWith('-')) continue;
        else break;
      }
      if (words[i] === 'push') {
        let target = cwd;
        for (const directory of directories) target = resolve(target, staticPath(directory));
        addTarget(target);
      }
    } else if (executable === 'gh') {
      let i = 1;
      while (['-R', '--repo'].includes(words[i]) && words[i + 1]) i += 2;
      if (words[i] === 'pr' && words[i + 1] === 'create') addTarget(cwd);
    }
  }
  return targets;
}
const decoder = new TextDecoder('utf-8', { fatal: true });
function git(cwd, ...args) {
  const result = spawnSync('git', args, { cwd, encoding: 'buffer', timeout: 3000,
    maxBuffer: 8 * 1024 * 1024, env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' } });
  if (result.error || result.status !== 0) throw new Error(`git ${args[0]} failed`);
  return decoder.decode(result.stdout);
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
    if (!entry.removed && !deleted.has(entry.filename) && policy.evidenceReason(entry.filename)) prohibited.add(entry.filename);
  }
  return { count: paths.size, prohibited };
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
  if (hook) {
    const payload = await hookInput();
    cwd = payload.cwd;
    const inputCwd = payload.tool_input.cwd ?? payload.tool_input.workdir;
    if (inputCwd !== undefined) {
      if (typeof inputCwd !== 'string' || !inputCwd) throw new Error('Invalid command working directory');
      cwd = resolve(cwd, inputCwd);
    }
    const targets = publicationTargets(payload.tool_input.command, cwd);
    for (const target of targets) inspect(realpathSync(target), base, true);
    return;
  }
  inspect(realpathSync(cwd), base, false);
}
function inspect(cwd, base, hook) {
  const { count, prohibited } = changedFiles(cwd, base);
  const level = policy.countLevel(count);
  const message = `PR preflight: ${count}/${policy.MAX_FILES} changed files relative to ${JSON.stringify(base)}.`;
  if (prohibited.size) {
    console.error('BLOCKED: Generated test/QA/benchmark outputs are prohibited at any file count: ' +
      [...prohibited].slice(0, 10).map(p => JSON.stringify(p)).join(', ') +
      '. Keep outputs outside Git; retain an authored summary. See docs/verification/pr-size-guard.md.');
    process.exitCode = 2;
  }
  if (level === 'BLOCKED') {
    console.error(`BLOCKED: ${message} Split into independently reviewable PRs; preserve required tests, security controls and documentation.`);
    process.exitCode = 2;
  } else if (level === 'WARNING') {
    console.error(`WARNING: ${message} Review scope before adding more changes.`);
    if (hook) console.log(JSON.stringify({ systemMessage: `WARNING: ${message}` }));
  } else if (!hook && !prohibited.size) console.log(`PASS: ${message}`);
}
try { await main(); }
catch (error) {
  const safe = /^(git \S+ failed|No verified merge base|Incomplete Git output|Invalid Git status|Incomplete Git filename|Hook input exceeds 1 MiB|Malformed hook JSON|Invalid PreToolUse Bash payload|Use \[--hook\] \[--base REF\]|Invalid base reference|Invalid command working directory|Malformed shell command|Shell wrapper nesting exceeds preflight limit|Dynamic publication directory requires manual preflight|Git directory override requires manual preflight)/.test(error.message);
  console.error(`BLOCKED: PR preflight could not verify scope: ${safe ? error.message : 'Git, path or input validation failed'}. Refresh the intended base (git fetch origin main), check --base/PR_PREFLIGHT_BASE and retry.`);
  process.exitCode = 2;
}
