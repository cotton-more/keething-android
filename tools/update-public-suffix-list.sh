#!/bin/bash
# Regenerates core/search's copy of the Public Suffix List (https://publicsuffix.org), the same way as the
# Mac app's scripts/update-public-suffix-list.sh: one rule per line, comments and blank lines left out. The
# app has no network, so the list is built in; re-run it now and then (the list changes slowly).
#
#   tools/update-public-suffix-list.sh [path/to/public_suffix_list.dat]
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=core/search/src/main/resources/dev/keething/search/site/public_suffix_list.txt
TMP=$(mktemp)
trap '/bin/rm -f "$TMP"' EXIT

if [ $# -ge 1 ]; then
  /bin/cp "$1" "$TMP"
else
  curl -fsS https://publicsuffix.org/list/public_suffix_list.dat -o "$TMP"
fi

# The first whitespace-separated word of every non-empty, non-comment line.
RULES=$(sed -e 's#//.*##' -e 's/[[:space:]].*//' "$TMP" | grep -v '^$')
COUNT=$(printf '%s\n' "$RULES" | wc -l | tr -d ' ')
[ "$COUNT" -gt 5000 ] || { echo "only $COUNT rules, refusing to write" >&2; exit 1; }

{
  echo "// The Public Suffix List (https://publicsuffix.org, Mozilla Public License 2.0), ICANN and private"
  echo "// sections, written by tools/update-public-suffix-list.sh. Do not edit."
  echo "// $COUNT rules, fetched $(date -u +%Y-%m-%d)."
  printf '%s\n' "$RULES"
} > "$OUT"
echo "wrote $COUNT rules to $OUT"
