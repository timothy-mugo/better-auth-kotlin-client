#!/usr/bin/env bash
# Checks what `./gradlew publishToMavenLocal -Dmaven.repo.local=<dir>` produced, i.e. what consumers will resolve.
#
#   scripts/verify-artifacts.sh <maven-repo-dir> <group> <version> [group-path-override]
#
# - the three modules exist at the expected version, with their jar/aar and sources
# - dependency isolation: the Android artifact has no Redis, the Redis artifact has no AndroidX/Jetpack,
#   the core has neither
# - the core pins OkHttp (so apps are not forced to compileSdk 37)
set -euo pipefail

repo="${1:?maven repo dir}"; group="${2:?group}"; version="${3:?version}"
base="$repo/$(echo "$group" | tr . /)"

python3 - "$base" "$version" <<'PY'
import json, os, sys

base, version = sys.argv[1], sys.argv[2]
core, android, redis = "better-auth-kotlin-client", "better-auth-kotlin-client-android", "better-auth-kotlin-client-redis"
errors = []

def path(artifact, suffix):
    return os.path.join(base, artifact, version, f"{artifact}-{version}{suffix}")

expected = {
    core: [".jar", "-sources.jar", ".pom", ".module"],
    redis: [".jar", "-sources.jar", ".pom", ".module"],
    android: [".aar", "-sources.jar", ".pom", ".module"],
}
for artifact, suffixes in expected.items():
    for suffix in suffixes:
        if not os.path.isfile(path(artifact, suffix)):
            errors.append(f"missing {artifact}-{version}{suffix}")

def deps(artifact):
    f = path(artifact, ".module")
    if not os.path.isfile(f):
        return []
    out = []
    for variant in json.load(open(f))["variants"]:
        for d in variant.get("dependencies", []):
            out.append((d["group"], d["module"], (d.get("version") or {}).get("requires", "")))
    return out

def has(artifact, needles):
    return [f"{g}:{m}" for g, m, _ in deps(artifact) if any(n in f"{g}:{m}".lower() for n in needles)]

for needle_set, artifact, label in [
    (["lettuce", "redis"], android, "Redis/Lettuce"),
    (["androidx", "com.android"], redis, "AndroidX/Android"),
    (["lettuce", "redis", "androidx", "com.android"], core, "Redis or AndroidX"),
]:
    found = has(artifact, needle_set)
    if found:
        errors.append(f"{artifact} must not depend on {label}: {sorted(set(found))}")

okhttp = [(g, m, v) for g, m, v in deps(core) if g == "com.squareup.okhttp3" and m == "okhttp"]
if not okhttp or any(not v.startswith("5.3.") for _, _, v in okhttp):
    errors.append(f"core must pin okhttp 5.3.x, found {okhttp}")

for artifact in (core, android, redis):
    pom = path(artifact, ".pom")
    if os.path.isfile(pom) and f"<version>{version}</version>" not in open(pom).read():
        errors.append(f"{artifact} pom does not declare version {version}")

if errors:
    print("artifact verification FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print(f"artifact verification OK: 3 modules at {version}, dependencies isolated, okhttp pinned")
PY
