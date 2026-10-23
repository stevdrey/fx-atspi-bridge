// Authored API stubs for exercising the actual generated workflow without network access.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
export const HEAD = 'a'.repeat(40);
export const BASE = 'b'.repeat(40);
export function embeddedWorkflow() {
  return readFileSync(root + '.github/workflows/trusted-pr-size-guard.yml', 'utf8');
}
export async function runCI(count, files = Array.from({ length: count ?? 0 }, (_, i) => ({ filename: `src/file-${i}.java`, status: 'modified' })), options = {}) {
  const workflow = embeddedWorkflow();
  const script = workflow.split('          script: |\n')[1].split('\n').map(l => l.replace(/^ {12}/, '')).join('\n');
  const ci = new AsyncFunction('github', 'context', 'core', script);
  const history = options.history ?? [{ sha: HEAD, parents: [{ sha: BASE }], files }];
  const head = options.head ?? history.at(-1)?.sha ?? HEAD;
  const events = { failures: [], warnings: [], info: [], tables: [], pages: [], commitPages: [] };
  let calls = 0;
  const github = { rest: {
    pulls: {
      async get() {
        calls++;
        if (options.apiFailure) throw Object.assign(new Error('private server detail'), { status: 502 });
        return { data: { changed_files: count, commits: options.commitCount ?? history.length,
          head: { sha: calls > 1 && options.stale ? 'other' : head }, base: { sha: BASE } } };
      },
      async listFiles({ page, per_page }) {
        events.pages.push(page);
        if (options.listFailure) throw new Error('private list error');
        return { data: files.slice((page - 1) * per_page, page * per_page) };
      },
      async listCommits({ page, per_page }) {
        if (options.historyFailure) throw new Error('private history error');
        return { data: (options.incompleteHistory ? history.slice(1) : history).slice((page - 1) * per_page, page * per_page) };
      },
    },
    repos: { async getCommit({ ref, page, per_page }) {
      events.commitPages.push([ref, page]);
      if (options.commitFailure) throw new Error('private commit error');
      const commit = history.find(c => c.sha === ref);
      if (!commit) throw new Error('missing stub commit');
      return { data: { sha: ref, parents: commit.parents ?? [{ sha: BASE }],
        files: commit.files.slice((page - 1) * per_page, page * per_page) } };
    } },
    git: {
      async getCommit({ commit_sha }) { return { data: { tree: { sha: options.parentRoots?.[commit_sha] } } }; },
      async getTree({ tree_sha }) { return { data: { truncated: !!options.truncatedTree, tree: options.trees?.[tree_sha] } }; },
    },
  } };
  const summary = { addHeading() { return this; }, addTable(table) { events.tables.push(table); return this; }, async write() {} };
  const core = { summary, setFailed: m => events.failures.push(m), warning: m => events.warnings.push(m), info: m => events.info.push(m) };
  await ci(github, { repo: { owner: 'example', repo: 'project' }, issue: { number: 43 } }, core);
  return events;
}
