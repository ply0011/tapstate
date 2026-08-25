#!/usr/bin/env bash
#
# How many whole days it has been since the checked-in connector catalog last changed.
#
# The drift scan holds back a change that touches no supported connector, waiting for one that does
# to carry it along. That wait needs a ceiling, or an unsupported connector's specification can go
# stale for as long as the supported ones happen not to move. This is the number the ceiling is
# measured against.
#
# Read off the catalog path's own history rather than off the scan's previous pull requests: what
# matters is when upstream was last carried in, and a refresh somebody ran by hand counts exactly as
# much as one this lane opened.
#
# Needs the full history - under a shallow checkout the log of a path can only reach the tip commit,
# which reports every catalog as brand new and quietly disables the ceiling.
#
# Refuses rather than guessing. Zero would read as "just refreshed" and hold drift forever; a large
# number would open a pull request that has nothing in it. Neither announces itself, so an unreadable
# history stops the run instead.

set -eu

catalog_dir="${1:-core/core-catalog/src/main/resources/catalog}"

last_commit="$(git log -1 --format=%ct -- "$catalog_dir")"
if [ -z "$last_commit" ]; then
    echo "no commit touches $catalog_dir - is this a shallow checkout, or the wrong path?" >&2
    exit 1
fi

now="$(date +%s)"
if [ "$last_commit" -gt "$now" ]; then
    echo "$catalog_dir was last touched in the future ($last_commit > $now) - refusing to guess" >&2
    exit 1
fi

echo $(( (now - last_commit) / 86400 ))
