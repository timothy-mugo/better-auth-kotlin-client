#!/usr/bin/env python3
"""Extracts `METHOD /path` pairs from a Better Auth OpenAPI document into src/test/resources/spec-endpoints.txt.

Usage: scripts/extract-endpoints.py path/to/openapi.json
The CoverageTest diffs the SDK's endpoint paths against this list.
"""
import json
import sys

spec = json.load(open(sys.argv[1]))
rows = sorted(
    f"{method.upper()} {path}"
    for path, ops in spec["paths"].items()
    for method in ops
    if method in ("get", "post", "put", "patch", "delete")
)
out = "src/test/resources/spec-endpoints.txt"
open(out, "w").write("\n".join(rows) + "\n")
print(f"wrote {len(rows)} endpoints to {out}")
