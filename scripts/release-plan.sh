#!/usr/bin/env bash
# Decides whether the Release workflow has anything to release. Run from the repository root, with a full clone
# (fetch-depth: 0) and GH_TOKEN set. Prints key=value lines for $GITHUB_OUTPUT:
#
#   version=0.1.0        libraryVersion from gradle.properties
#   tag=v0.1.0
#   release=true|false   true when no GitHub Release exists for the tag yet
#   skip_tag=true|false  true when the tag already exists (a half-finished earlier run); JReleaser must not create it
#
# To release, bump libraryVersion in gradle.properties and merge it to main. Every other push to main finds the release
# already exists and does nothing, so re-running is always safe.
set -euo pipefail

version="$(sed -n 's/^libraryVersion=//p' gradle.properties | tr -d '[:space:]')"
if [[ -z "$version" ]]; then
  echo "error: libraryVersion is missing in gradle.properties" >&2
  exit 1
fi
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$ ]]; then
  echo "error: libraryVersion '$version' is not MAJOR.MINOR.PATCH (optionally -rc.1 style)" >&2
  exit 1
fi
tag="v$version"

echo "version=$version"
echo "tag=$tag"

if gh release view "$tag" >/dev/null 2>&1; then
  echo "release=false"
  echo "skip_tag=true"
  echo "$tag is already released; nothing to do." >&2
  exit 0
fi

# Peeled commit of the tag on the remote ("^{}" line for annotated tags, plain line for lightweight ones).
remote_tag="$(git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}" | awk '{print $1}' | tail -1)"
if [[ -n "$remote_tag" ]]; then
  head="$(git rev-parse HEAD)"
  if [[ "$remote_tag" != "$head" ]]; then
    echo "error: tag $tag exists at ${remote_tag:0:7} but has no GitHub Release, and main is at ${head:0:7}." >&2
    echo "Releasing now would attach artifacts from different code to that tag. Bump libraryVersion to a new version." >&2
    exit 1
  fi
  echo "release=true"
  echo "skip_tag=true"
  echo "$tag exists at HEAD without a GitHub Release (an earlier run was interrupted); finishing it." >&2
else
  echo "release=true"
  echo "skip_tag=false"
fi
