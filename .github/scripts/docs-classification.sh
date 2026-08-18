#!/usr/bin/env bash
# Decides whether a pull request's documentation pages say where they are going. A reader-facing
# Markdown page under docs/ is either a draft this repository is handing to documentation
# engineering, or a pointer to the published page that replaced it - and it says which in its own
# header, not in a reviewer's memory.
#
# The header is one of exactly two shapes:
#
#   status: engineering-draft     the page is written alongside the implementation and has not been
#   publication: handoff          reviewed for publication. It names where it is headed.
#   target: <url>
#
#   status: canonical-pointer     the published page is canonical and lives at the URL. What stays
#   canonical_url: <url>          here is a short pointer plus anything executable.
#
# In scope: any docs/**/*.md the pull request adds or modifies and that still exists. Out of scope:
# everything else under docs/ - sample data, workspace files, scripts, diagrams - because an
# executable asset is not a page and has no destination.
#
# A page nobody touches is left alone, so the rule arrives page by page rather than as one migration.
# Touching a page does bring it in scope, which is deliberate and is the whole migration plan: the
# person editing a page has just read it, and is the cheapest person to say where it is going. The
# alternative considered was a size threshold - only "substantial" edits in scope - and it was
# dropped because no number distinguishes a rewrite from a typo well enough to defend later, while
# a threshold quietly admits the rewrite that sits just under it.
#
# Run inside the repository being judged. Reads one environment variable:
#   BASE_REF   the branch the pull request targets; its merge base with HEAD is what "changed" means
# Exits 0 admitted, 1 refused.
set -euo pipefail

base="origin/${BASE_REF}"
git rev-parse --verify --quiet "$base" >/dev/null 2>&1 \
  || git fetch --quiet --no-tags origin "${BASE_REF}"
merge_base="$(git merge-base "$base" HEAD)"

# Deleting a page is not authoring one, so removals are excluded here rather than being judged and
# then found missing.
pages="$(git diff --name-only --diff-filter=d "$merge_base" HEAD \
  | grep -E '^docs/.*\.md$' || true)"

if [ -z "$pages" ]; then
  echo "not in scope: this pull request adds or modifies no documentation page."
  exit 0
fi

# The header is only a header where it opens the file. Read from anywhere else, the same words are
# prose - a page discussing the rule would classify itself by talking about it.
front_matter() {
  awk 'NR == 1 && $0 != "---" { exit } NR == 1 { next } $0 == "---" { exit } { print }' "$1"
}

value_of() {
  printf '%s\n' "$2" | sed -n "s/^${1}:[[:space:]]*//p" | head -n 1
}

refused=""
refuse() { refused="${refused}$1"$'\n'; }

for page in $pages; do
  header="$(front_matter "$page")"
  status="$(value_of status "$header")"
  publication="$(value_of publication "$header")"
  target="$(value_of target "$header")"
  canonical="$(value_of canonical_url "$header")"

  if [ -z "$status" ]; then
    refuse "$page carries no classification. Its header names no status."
    continue
  fi

  case "$status" in
    engineering-draft)
      if [ -n "$canonical" ]; then
        refuse "$page mixes the two shapes: a draft that also names a canonical_url. A page is on \
one side of the handoff or the other."
        continue
      fi
      [ "$publication" = handoff ] \
        || refuse "$page is a draft and must carry publication: handoff."
      [ -n "$target" ] \
        || refuse "$page is a draft and names no target: the public page it is headed for."
      ;;
    canonical-pointer)
      if [ -n "$target" ] || [ -n "$publication" ]; then
        refuse "$page mixes the two shapes: a pointer that also carries a draft's handoff fields."
        continue
      fi
      [ -n "$canonical" ] \
        || refuse "$page is a pointer and names no canonical_url: the published page it points to."
      ;;
    *)
      refuse "$page declares status: $status, which is not a status this repository knows."
      ;;
  esac
done

if [ -z "$refused" ]; then
  echo "every documentation page this pull request touches is classified:"
  printf '%s\n' "$pages"
  exit 0
fi

echo "::error::a documentation page this pull request touches does not say where it is going."
printf '%s' "$refused"
cat <<'EOF'

Give each page named above one of two headers, at the very top of the file.

  ---
  status: engineering-draft
  publication: handoff
  target: https://tapstate.dev/docs/<where-this-is-headed>
  ---

      The page is written alongside the implementation and has not been reviewed for
      publication. Say where it is headed even if that page does not exist yet.

  ---
  status: canonical-pointer
  canonical_url: https://tapstate.dev/docs/<the-published-page>
  ---

      The published page is canonical. What stays here is a short pointer to it, plus
      anything executable - sample data, workspace files, scripts - which belongs with
      the code and is never in scope for this check.

An existing page you did not touch needs nothing. This one is in scope because your
change touches it, and you have just read it.

See CONTRIBUTING.md, "Documentation".
EOF
exit 1
