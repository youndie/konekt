#!/usr/bin/env python3
"""A service document stating a version the catalogue no longer pins.

A version lives in more places than one. `gradle/libs.versions.toml` is where it is DECIDED, and a
service document repeats it in the two lines a reader trusts for "what this deployment is": the
`tech_stack:` of its frontmatter and its `- **Image:**` line. A bump moves the catalogue and leaves
both behind, and nothing fails — the build does not read prose, and the prose is plausible for ever.

Found on 2026-09-03, hours after the bump that caused it: `konekt-broker` said `booblik 0.3.0` three
times while the chart, the compose file and the catalogue all said `0.3.1`, and `konekt-server` said
`Exposed 1.4.0` against a catalogue at `1.5.0`. Both were written the same morning by the person who
moved the catalogue.

WHAT IS READ IS DELIBERATELY NARROW — those two lines and nothing else. Every other version in these
documents is HISTORY and is supposed to stay put: "closed in `0.34.0.97`", "katcher published no
Apple target until `client:0.6.2`". A check that flagged those would be switched off in a week, and
would be right to be. The two lines here make a claim in the present tense about what runs, which is
the only kind of version statement a bump can falsify.

BLOCKING, for the reason the Makefile gives above `stale_citations`: this cannot be caused by a
rename in somebody else's repository. Every instance is a claim made in this tree about this tree.
"""
import argparse
import pathlib
import re
import sys

VERSIONS = re.compile(r"^\[versions\]\s*$(.*?)(?=^\[|\Z)", re.M | re.S)
ENTRY = re.compile(r"^([A-Za-z][\w-]*)\s*=\s*\"([^\"]+)\"", re.M)
# `name 1.2.3`, `name:1.2.3` and `owner/name:1.2.3` — the three shapes these two lines use.
STATED = re.compile(r"([A-Za-z][\w.-]*?)[\s:]+v?(\d+(?:\.\d+)+)\b")
LINES = re.compile(r"^(?:tech_stack:.*|- \*\*Image:\*\*.*)$", re.M)


def catalogue(path):
    body = VERSIONS.search(pathlib.Path(path).read_text())
    if not body:
        return {}
    return {k.lower(): v for k, v in ENTRY.findall(body.group(1))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--docs", default="docs")
    ap.add_argument("--catalogue", default="gradle/libs.versions.toml")
    args = ap.parse_args()

    pinned = catalogue(args.catalogue)
    if not pinned:
        print(f"stated_versions: no [versions] block in {args.catalogue}")
        return 1

    findings, checked = [], 0
    for path in sorted(pathlib.Path(args.docs, "services").glob("*.md")):
        text = path.read_text()
        for lineno, line in enumerate(text.splitlines(), 1):
            if not LINES.match(line):
                continue
            for name, stated in STATED.findall(line):
                key = name.rsplit("/", 1)[-1].lower()
                if key not in pinned:
                    continue
                checked += 1
                if pinned[key] != stated:
                    findings.append((path, lineno, key, stated, pinned[key]))

    for path, lineno, key, stated, want in findings:
        print(f"{path}:{lineno}: says {key} {stated}, the catalogue pins {want}")
    print(f"stated_versions: {checked} version(s) stated in what runs, {len(findings)} behind the catalogue")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
