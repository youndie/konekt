#!/usr/bin/env python3
"""A document calling an upstream issue open that the other repository has closed.

The sibling of `stale_citations.py`, pointed the other way. That one catches a claim this tree makes
about itself; this one catches a claim it makes about SOMEBODY ELSE'S repository, which is the half
no edit here can invalidate and no test can notice. The whole class is invisible from inside: the
sentence stays true-looking for ever, and the only thing that changed is a state on a server.

It reproduces on its own schedule. On 2026-09-02 three rows of `research-upstream-proposals` said
"open" — kompot#95, kompot#99 and Exposed#2897 — of issues closed on the 27th, the 30th and the
26th; one of them had a workaround still carried in the tree for a gap that no longer existed. On
2026-09-03 a fourth was found, and it was worse: the row had been REWRITTEN to say "closed" by an
edit whose replacement matched nothing, so the prose and the table of one document disagreed.

WHAT IS FLAGGED IS THE COLLOCATION, on one line. A reference sitting beside a word that asserts the
issue is still open, when the repository says it is closed — and the reverse, which is rarer and
worse, since a reopened issue reads as settled. Most references are neither: "filed as X", "closed
in 0.6.41, X" — those are correct however the issue ends, and flagging them would produce a check
switched off within a month.

NON-BLOCKING, and it is in `report` rather than `gate` for the reason written above the target: it
needs the network and it depends on repositories nobody here controls. Without `gh`, or offline, it
says so and exits 0 — a check that cannot run must not be mistaken for a check that passed.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys

REFERENCE = re.compile(
    r"(?:github\.com/(?P<owner_url>[\w.-]+)/(?P<repo_url>[\w.-]+)/(?:issues|pull)/(?P<n_url>\d+))"
    r"|(?:\b(?P<owner>[\w.-]+)/(?P<repo>[\w.-]+)#(?P<n>\d+))"
)

# Words that ASSERT the issue is still open, not words that merely mention one.
OPEN_CLAIM = re.compile(
    r"\|\s*open\b|\bstill open\b|\bis open\b|\bremains open\b|\bnot yet closed\b"
    r"|\bno workaround\b|\bwaiting on\b|\bwaits on\b|\bblocked on\b|\buntil it is fixed\b"
    r"|\buntil that is fixed\b|\bnot fixed\b|\bopen;",
    re.I,
)
CLOSED_CLAIM = re.compile(r"\|\s*closed\b|\bclosed\b(?!\s+wider)|\bfixed in\b|\breleased in\b", re.I)


def state_of(ref, cache):
    if ref in cache:
        return cache[ref]
    repo, number = ref.rsplit("#", 1)
    try:
        out = subprocess.run(
            ["gh", "api", f"repos/{repo}/issues/{number}", "--jq", ".state"],
            capture_output=True, text=True, timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        cache[ref] = ("unreadable", str(exc))
        return cache[ref]
    if out.returncode != 0:
        cache[ref] = ("unreadable", out.stderr.strip()[:120])
    else:
        cache[ref] = (out.stdout.strip().lower(), "")
    return cache[ref]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--docs", default="docs")
    ap.add_argument("--also", nargs="*", default=["README.md", "CLAUDE.md"])
    args = ap.parse_args()

    files = sorted(pathlib.Path(args.docs).rglob("*.md"))
    files += [pathlib.Path(p) for p in args.also if pathlib.Path(p).exists()]

    cache, findings, seen = {}, [], set()
    for path in files:
        for lineno, line in enumerate(path.read_text().splitlines(), 1):
            refs = set()
            for m in REFERENCE.finditer(line):
                if m.group("n_url"):
                    refs.add(f"{m.group('owner_url')}/{m.group('repo_url')}#{m.group('n_url')}")
                else:
                    refs.add(f"{m.group('owner')}/{m.group('repo')}#{m.group('n')}")
            if not refs:
                continue
            says_open = bool(OPEN_CLAIM.search(line))
            says_closed = bool(CLOSED_CLAIM.search(line))
            for ref in sorted(refs):
                seen.add(ref)
                state, note = state_of(ref, cache)
                if state == "unreadable":
                    continue
                if says_open and state == "closed":
                    findings.append((path, lineno, ref, "is closed, and this line calls it open"))
                elif says_closed and state == "open" and not says_open:
                    findings.append((path, lineno, ref, "is OPEN, and this line calls it closed"))

    unreadable = sorted(r for r, (s, _) in cache.items() if s == "unreadable")
    if unreadable and len(unreadable) == len(cache):
        print(f"upstream_state: could not reach any of {len(unreadable)} references — "
              f"is `gh` installed and authenticated? Nothing was checked.")
        return 0

    for path, lineno, ref, what in findings:
        print(f"{path}:{lineno}: {ref} {what}")
    if unreadable:
        print(f"upstream_state: {len(unreadable)} reference(s) unreadable: {', '.join(unreadable)}")
    print(f"upstream_state: {len(seen)} references in {len(files)} documents, "
          f"{len(findings)} disagree with the repository")
    return 0


if __name__ == "__main__":
    sys.exit(main())
