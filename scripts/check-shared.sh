#!/usr/bin/env bash
# contract/ and docs/ are shared with jizizr/mhub-ios and must stay identical:
# change both repositories the same way, or queue and lyric behaviour drifts.
set -euo pipefail
other=$(mktemp -d)
trap 'rm -rf "$other"' EXIT
git clone --quiet --depth 1 "https://github.com/jizizr/mhub-ios.git" "$other"
diff -ru "$other/contract" contract
diff -ru "$other/docs" docs
echo "contract/ and docs/ match jizizr/mhub-ios"
