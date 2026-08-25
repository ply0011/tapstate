#!/usr/bin/env bash
#
# Cases for catalog-age-days.sh. Each drives a throwaway repository whose commit dates are set
# outright, so the answers are exact rather than approximately today.

set -eu

script="$(cd "$(dirname "$0")" && pwd)/catalog-age-days.sh"
failures=0

check() {
    local name="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo "ok   - $name"
    else
        echo "FAIL - $name: expected '$expected', got '$actual'"
        failures=$((failures + 1))
    fi
}

# A repository whose catalog path was last committed a known number of days ago.
repo_committed_days_ago() {
    local days="$1" dir
    dir="$(mktemp -d)"
    git -C "$dir" init -q
    git -C "$dir" config user.email tapstate@example.com
    git -C "$dir" config user.name tapstate
    mkdir -p "$dir/catalog"
    echo '{}' > "$dir/catalog/index.json"
    git -C "$dir" add -A
    local when
    when="$(( $(date +%s) - days * 86400 ))"
    GIT_AUTHOR_DATE="$when" GIT_COMMITTER_DATE="$when" git -C "$dir" commit -q -m "catalog"
    echo "$dir"
}

repo="$(repo_committed_days_ago 9)"
check "nine days ago reads as nine" "9" "$(cd "$repo" && "$script" catalog)"
rm -rf "$repo"

repo="$(repo_committed_days_ago 0)"
check "committed today reads as zero" "0" "$(cd "$repo" && "$script" catalog)"
rm -rf "$repo"

# A path nothing has ever touched must stop the run, not answer zero: zero reads as "just
# refreshed", which holds drift back forever and looks exactly like a quiet upstream.
repo="$(repo_committed_days_ago 3)"
if (cd "$repo" && "$script" no-such-directory >/dev/null 2>&1); then
    echo "FAIL - an untouched path must refuse, not answer"
    failures=$((failures + 1))
else
    echo "ok   - an untouched path refuses"
fi
rm -rf "$repo"

if [ "$failures" -ne 0 ]; then
    echo "$failures case(s) failed" >&2
    exit 1
fi
echo "all cases passed"
