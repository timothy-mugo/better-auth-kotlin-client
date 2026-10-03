#!/usr/bin/env bash
# Normalizes a release version and prints key=value lines for GitHub Actions ($GITHUB_OUTPUT).
#
#   scripts/resolve-version.sh 1.2.3        -> version=1.2.3  tag=v1.2.3  prerelease=false
#   scripts/resolve-version.sh v1.2.3-rc.1  -> version=1.2.3-rc.1  tag=v1.2.3-rc.1  prerelease=true
#
# Accepts MAJOR.MINOR.PATCH with an optional -prerelease suffix, with or without a leading "v".
set -euo pipefail

raw="${1:-}"
version="${raw#v}"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$ ]]; then
  echo "error: '$raw' is not a valid version (expected 1.2.3 or 1.2.3-rc.1, optional leading v)" >&2
  exit 1
fi

prerelease=false
[[ "$version" == *-* ]] && prerelease=true

echo "version=$version"
echo "tag=v$version"
echo "prerelease=$prerelease"
