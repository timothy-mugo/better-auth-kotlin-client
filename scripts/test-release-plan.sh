#!/usr/bin/env bash
# Tests for release-plan.sh using throwaway git repos and a fake `gh`. Run: scripts/test-release-plan.sh
set -uo pipefail

script="$(cd "$(dirname "$0")" && pwd)/release-plan.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
failures=0

# Fake gh: `gh release view <tag>` succeeds only when FAKE_RELEASES lists the tag.
mkdir -p "$tmp/bin"
cat > "$tmp/bin/gh" <<'GH'
#!/usr/bin/env bash
[[ "$1 $2" == "release view" ]] && grep -qx "$3" <<<"${FAKE_RELEASES:-}"
GH
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH"

new_repo() { # $1 = libraryVersion line value
  rm -rf "$tmp/origin.git" "$tmp/work"
  git init -q --bare "$tmp/origin.git"
  git clone -q "$tmp/origin.git" "$tmp/work" 2>/dev/null
  cd "$tmp/work"
  git config user.email t@t; git config user.name t
  git checkout -q -b main
  printf 'x=1\nlibraryVersion=%s\n' "$1" > gradle.properties
  git add -A; git commit -q -m "init"; git push -q origin main
}

check() { # name, expected exit, expected stdout (one line, comma-joined), actual command
  local name="$1" want_code="$2" want_out="$3"; shift 3
  local out code
  out="$("$script" 2>/dev/null | paste -sd, -)"; code=${PIPESTATUS[0]}
  if [[ "$code" == "$want_code" && "$out" == "$want_out" ]]; then echo "ok   $name"
  else echo "FAIL $name: exit $code (want $want_code), out '$out' (want '$want_out')"; failures=$((failures+1)); fi
}

FAKE_RELEASES=""; export FAKE_RELEASES

new_repo 0.1.0
check "new version: release, create the tag" 0 "version=0.1.0,tag=v0.1.0,release=true,skip_tag=false"

FAKE_RELEASES="v0.1.0"
check "already released: nothing to do" 0 "version=0.1.0,tag=v0.1.0,release=false,skip_tag=true"
FAKE_RELEASES=""

new_repo 0.1.0
git tag -a v0.1.0 -m r && git push -q origin v0.1.0
check "tag at HEAD without a release: finish it, skip the tag" 0 "version=0.1.0,tag=v0.1.0,release=true,skip_tag=true"

new_repo 0.1.0
git tag v0.1.0 && git push -q origin v0.1.0   # lightweight tag
check "lightweight tag at HEAD is recognized" 0 "version=0.1.0,tag=v0.1.0,release=true,skip_tag=true"

new_repo 0.1.0
git tag -a v0.1.0 -m r && git push -q origin v0.1.0
echo "more" > f.txt && git add -A && git commit -q -m "later change" && git push -q origin main
check "tag on an older commit, no release: refuse" 1 "version=0.1.0,tag=v0.1.0"

new_repo 0.2.0-rc.1
check "prerelease version is accepted" 0 "version=0.2.0-rc.1,tag=v0.2.0-rc.1,release=true,skip_tag=false"

new_repo 1.2
check "invalid version is rejected" 1 ""
new_repo "v1.2.3"
check "leading v in libraryVersion is rejected" 1 ""

new_repo 0.1.0
printf 'x=1\n' > gradle.properties
check "missing libraryVersion is rejected" 1 ""

exit "$failures"
