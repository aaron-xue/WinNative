#!/bin/sh
# Only English (res/values) and Simplified Chinese (res/values-zh-rCN) are kept
# in this fork. Every other app/src/main/res/values-* folder is stripped from
# both the index and the working tree.
#
# Called from tools/git/hooks/{pre-commit,post-merge}; can also be run by hand:
#     sh tools/git/purge-translations.sh
#
# Note: res/values-v27 is an API-level qualifier, not a language, so it stays.
# app/build.gradle already declares resConfigs "en", "zh-rCN", so a stray folder
# would never reach the APK anyway -- this script just keeps the tree clean.

top=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$top" || exit 0

res_dir=app/src/main/res
[ -d "$res_dir" ] || exit 0

keep="values values-v27 values-zh-rCN"

for dir in "$res_dir"/values-*; do
    [ -d "$dir" ] || continue
    name=${dir##*/}
    for k in $keep; do
        [ "$name" = "$k" ] && continue 2
    done
    git rm -r -q -f --ignore-unmatch -- "$dir" >/dev/null 2>&1
    rm -rf -- "$dir"
    echo "[translations] removed $dir"
done

exit 0
