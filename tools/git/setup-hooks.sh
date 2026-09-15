#!/bin/sh
# One-time installer for the translation guard hooks.
#
#   sh tools/git/setup-hooks.sh
#
# It points core.hooksPath at tools/git/hooks (the versioned copy of the Git LFS
# hooks plus the guard) and immediately purges any stray locale folder.
set -e

top=$(git rev-parse --show-toplevel)
cd "$top"

git config core.hooksPath tools/git/hooks

# .git/hooks now shadows nothing: keep a note so nobody wonders why the old
# location is unused.
if [ -f .git/hooks/post-merge ]; then
    echo "[setup] core.hooksPath -> tools/git/hooks (.git/hooks is no longer used)"
fi

sh tools/git/purge-translations.sh

echo "[setup] done: only values/ (English) and values-zh-rCN/ (Simplified Chinese) are kept."
