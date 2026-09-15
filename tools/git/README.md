# Translation guard

This fork ships **English (`app/src/main/res/values/`)** and
**Simplified Chinese (`app/src/main/res/values-zh-rCN/`)** only. Upstream keeps
adding/updating other locales, and every merge of `upstream/main` used to drag
them back in.

## Why a hook and not `.gitattributes`

When we deleted a locale folder while upstream edited the same file, Git saw a
`CONFLICT (modify/delete)` and left *their* version in the tree. A
`merge=<driver>` attribute does not help here: Git only calls merge drivers for
content merges, never for delete/modify conflicts (verified with
`git merge-tree --write-tree` against `merge=binary` and a custom
`merge=keepours` driver — both still produced the resurrected file).

So the guard lives in hooks, which always run.

## Files

| File | Purpose |
| --- | --- |
| `purge-translations.sh` | Removes every `app/src/main/res/values-*` folder except `values`, `values-v27` (API qualifier, not a language) and `values-zh-rCN`, from the index *and* the working tree. Safe to run by hand. |
| `hooks/post-merge` | Git LFS hook + purge → runs right after `git merge` / `git pull`. |
| `hooks/pre-commit` | Purge → nothing can ever be committed with the removed locales. Also covers cherry-pick, `git checkout upstream/main -- path` and `git add -A`. |
| `hooks/post-checkout`, `hooks/post-commit`, `hooks/pre-push` | Verbatim copies of the Git LFS hooks that used to live in `.git/hooks`, so switching `core.hooksPath` does not break LFS. |
| `setup-hooks.sh` | One-time installer. |

## Install (once per clone)

```sh
sh tools/git/setup-hooks.sh
# equivalent to: git config core.hooksPath tools/git/hooks
```

If a merge still stops on `CONFLICT (modify/delete)` for those folders, resolve
them by deleting the files — or just run:

```sh
sh tools/git/purge-translations.sh
```

`git status` must stay clean afterwards; committing is then enough.

> Adding another language later means keeping it out of `purge-translations.sh`
> and adding it to `resConfigs` in `app/build.gradle`,
> `app/src/main/res/xml/locales_config.xml` and
> `SUPPORTED_LANGUAGE_TAGS` in `app/src/main/shared/android/LocaleHelper.kt`.
