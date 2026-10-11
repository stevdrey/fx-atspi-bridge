# Wiki publication

`docs/wiki/` is canonical. Publication runs on changes merged into main, including workflow/script changes, or manual dispatch on main. It mirrors only Markdown files and tracks owned files in a manifest; unrelated pages are preserved. Previously managed removed pages are deleted. No empty-source publication and no force-push. Direct edits to managed wiki pages will be overwritten.

## First setup

Enable Wiki in repository settings and create/save an initial Home page through GitHub UI to initialize the separate `.wiki.git` repository. A clone failure can also mean permissions, availability or authentication problems; the workflow reports it rather than assuming one cause. This editing session could not confirm wiki initialization.

The workflow first uses `GITHUB_TOKEN` with `contents: write`. If the repository's Wiki rejects that credential, configure `WIKI_SYNC_TOKEN` with only the permissions required to write this repository's Wiki under your account/organization policy. Do not paste credentials into source or logs; remote URLs contain no token. Do not add a personal token unless the default token actually fails.

Merge the setup PR, then run Publish Wiki manually once. Verify Home/Roadmap and a second no-op run. Publication is not verified until the actual workflow succeeds. Pull requests never execute Wiki writes. Failed push is not retried with force; concurrency serializes publications.

Local check: `bash scripts/publish-wiki.sh docs/wiki /path/to/local/wiki-checkout` using a disposable initialized git repository and `WIKI_SYNC_NO_PUSH=1`. Run once, again for no-op, then remove a managed source page and verify deletion while an unmanaged page survives.
