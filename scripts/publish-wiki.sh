#!/usr/bin/env bash
# Mirror owned Markdown pages; preserve unmanaged pages. Source is trusted main.
set -euo pipefail
source_dir="$(realpath "${1:?source directory required}")"
wiki_dir="$(realpath "${2:?wiki checkout required}")"
[[ -d "$wiki_dir/.git" ]] || { echo 'Expected a separate Wiki git checkout' >&2; exit 1; }
[[ "$source_dir" != "$wiki_dir" && "$source_dir" != "$wiki_dir/"* && "$wiki_dir" != "$source_dir/"* ]] || exit 1
[[ -f "$source_dir/Home.md" && ! -L "$source_dir/Home.md" ]] || { echo 'Home.md is required' >&2; exit 1; }
manifest='.fx-atspi-managed-pages'
task_manifest="$(mktemp)"
trap 'rm -f "$task_manifest"' EXIT
# Flat safe filenames deliberately constrain the publication boundary.
while IFS= read -r -d '' page; do
  name="$(basename "$page")"
  [[ "$name" =~ ^[A-Za-z0-9_-]+\.md$ && ! -L "$page" ]] || { echo 'Unsupported Wiki filename or symlink' >&2; exit 1; }
  printf '%s\n' "$name" >> "$task_manifest"
done < <(find "$source_dir" -maxdepth 1 -type f -name '*.md' -print0)
[[ -s "$task_manifest" ]] || exit 1
# Reject nested Markdown and symlinks rather than silently omitting them.
if find "$source_dir" -type l -o -mindepth 2 -type f -name '*.md' | read -r _; then
  echo 'Wiki source must be flat Markdown without symlinks' >&2; exit 1
fi
sort -o "$task_manifest" "$task_manifest"
# Validate previous manifest fully before applying deletions.
if [[ -e "$wiki_dir/$manifest" ]]; then
  [[ -f "$wiki_dir/$manifest" && ! -L "$wiki_dir/$manifest" ]] || exit 1
  while IFS= read -r name; do
    [[ "$name" =~ ^[A-Za-z0-9_-]+\.md$ ]] || { echo 'Invalid ownership manifest' >&2; exit 1; }
  done < "$wiki_dir/$manifest"
  while IFS= read -r name; do
    if ! grep -Fxq "$name" "$task_manifest"; then rm -f -- "$wiki_dir/$name"; fi
  done < "$wiki_dir/$manifest"
fi
while IFS= read -r name; do
  [[ ! -L "$wiki_dir/$name" ]] || { echo 'Refusing symlink destination' >&2; exit 1; }
  cp -- "$source_dir/$name" "$wiki_dir/$name"
done < "$task_manifest"
cp -- "$task_manifest" "$wiki_dir/$manifest"
git -C "$wiki_dir" add --all
if git -C "$wiki_dir" diff --cached --quiet; then echo 'Wiki already up to date'; exit 0; fi
git -C "$wiki_dir" -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit -m 'docs: synchronize managed Wiki pages'
if [[ "${WIKI_SYNC_NO_PUSH:-0}" != 1 ]]; then git -C "$wiki_dir" push origin HEAD; fi
