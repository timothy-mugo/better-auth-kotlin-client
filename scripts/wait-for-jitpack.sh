#!/usr/bin/env bash
# Triggers a JitPack build for a tag and waits for it to finish.
#
#   scripts/wait-for-jitpack.sh <group> <artifact> <tag> [timeout-seconds]
#   scripts/wait-for-jitpack.sh com.github.timothy-mugo better-auth-kotlin-client v0.1.0
#
# JitPack builds on first request. Requesting the build log starts the build; /api/builds/... reports the status.
# Exit 0 when the build is "ok", 1 on failure or timeout (the build log tail is printed).
set -euo pipefail

group="${1:?group, e.g. com.github.user}"; artifact="${2:?artifact (repo name)}"; tag="${3:?tag}"
timeout="${4:-1200}"
path="$(echo "$group" | tr . /)/$artifact/$tag"
log_url="https://jitpack.io/$path/build.log"
api_url="https://jitpack.io/api/builds/$group/$artifact/$tag"

echo "Triggering JitPack build: https://jitpack.io/#${group#com.github.}/$artifact/$tag"
curl -s -o /dev/null --max-time 60 "$log_url" || true

deadline=$(( $(date +%s) + timeout ))
status=""
while :; do
  body="$(curl -s --max-time 30 "$api_url" || true)"
  status="$(printf '%s' "$body" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("status",""))
except Exception: print("")')"
  echo "  status: ${status:-<not started yet>}"
  case "$status" in
    ok)
      echo "JitPack build OK"
      echo "Modules reported by JitPack (these are the coordinates consumers use):"
      printf '%s' "$body" | python3 -c 'import sys,json
try: print(json.dumps(json.load(sys.stdin).get("modules", []), indent=2))
except Exception: print("  (could not read modules)")'
      exit 0 ;;
    [Ee]rror) break ;;
  esac
  if [ "$(date +%s)" -ge "$deadline" ]; then echo "Timed out after ${timeout}s waiting for JitPack"; break; fi
  sleep 20
done

echo "JitPack build did not succeed. Last 60 lines of the log ($log_url):"
curl -s --max-time 60 "$log_url" | tail -60 || true
exit 1
