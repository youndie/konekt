#!/usr/bin/env python3
"""The chart's version must move when the chart's shape moves.

`Chart.yaml` states the rule in its own comment — a chart version and an application version are
different lifetimes, and the shape of the deployment changes when a template does — and the number
sat at 0.1.0 through a template change that added a refusal (B-91). B-99 is why this exists.

What it costs when the number does not move: a release tag pins the binary and nothing pins the
templates that turn it into pods. There is no released chart version for anything to ask for, so a
deployment takes the chart from a branch, a rollback to an older image renders under today's
templates, and `helm --atomic` rolls back to a previous render that is not pinned either.

NOT A COMPARISON AGAINST THE BASE BRANCH, which is what B-99 first proposed. This repository pushes
to the default branch and opens no pull requests, so a `pull_request`-only check would never run —
the mirror image of the failure that checks only on main rot unseen. The comparison that works in any
flow is against the commit where the version last changed: the question is whether the shape has
moved since the number did, and that question has the same answer on a branch, on main, and in a
working tree.
"""
import argparse
import pathlib
import re
import subprocess
import sys

CHART = "charts/konekt"
SHAPE = [f"{CHART}/templates", f"{CHART}/values.yaml"]
VERSION = re.compile(r"^version:\s*(\S+)", re.MULTILINE)


# GIT FAILURES ARE ANSWERS HERE, not crashes. `git log` in a repository with no commits exits 128,
# and so does `git show HEAD:...` — both are states this check meets legitimately, in a fresh clone
# and in a shallow one. Raising through them gives a stack trace where a sentence belongs, and a
# guard that dies is read as a broken guard rather than as a refusal.
def git(*args: str) -> str:
    done = subprocess.run(["git", *args], capture_output=True, text=True)
    return done.stdout.strip() if done.returncode == 0 else ""


def version_of(text: str) -> str | None:
    found = VERSION.search(text)
    return found.group(1) if found else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chart", default=f"{CHART}/Chart.yaml")
    args = parser.parse_args()

    chart = pathlib.Path(args.chart)
    if not chart.exists():
        print(f"{chart} does not exist, so this check examined nothing", file=sys.stderr)
        return 2

    current = version_of(chart.read_text())
    if current is None:
        print(f"{chart} declares no version", file=sys.stderr)
        return 2

    # WHEN THE NUMBER LAST MOVED. Walked rather than searched: `git log -S` counts occurrences of a
    # string and would answer about a line that was reformatted, and what is wanted is the commit
    # where the VALUE became what it is now.
    history = git("log", "--format=%H", "--", str(chart)).splitlines()
    if not history:
        # A shallow clone has no history to walk, and a check that cannot see its input must say so
        # rather than pass. `fetch-depth: 0` is what CI needs.
        print(f"no history for {chart} — a shallow clone cannot answer this", file=sys.stderr)
        return 2

    # THE VERSION MOVING IN THE WORKING TREE IS THE WHOLE POINT OF THE RULE, so it is the first
    # thing asked. Without this the check refuses the correct move: a person editing a template and
    # bumping the number in the same change has no commit yet, and a naive walk finds no bump and
    # reports the template as unaccounted for. That is a guard failing the behaviour it exists to
    # produce, which is worse than not having it.
    at_head = version_of(git("show", f"HEAD:{chart}"))
    if at_head is not None and at_head != current:
        print(f"chart_version: {chart.name} moves {at_head} -> {current} in this change")
        return 0

    bumped_at = None
    for commit in history:
        blob = git("show", f"{commit}:{chart}")
        if version_of(blob) != current:
            break
        bumped_at = commit
    if bumped_at is None:
        # Every commit that touched the file carries this version, so the chart has never been
        # bumped: the shape is measured from the commit that introduced it.
        bumped_at = history[-1]

    moved = [
        path
        for path in git("diff", "--name-only", f"{bumped_at}..HEAD", "--", *SHAPE).splitlines()
        if path
    ]
    # The working tree too, so a person sees this before committing rather than after.
    # `line[3:]` is wrong on a line whose index and working tree both changed, and the symptom is a
    # path with its first character eaten — which is exactly what a person would read past. The
    # status letters are the first two columns and the rest is the path.
    moved += [
        path
        for path in (line[2:].strip() for line in git("status", "--porcelain", "--", *SHAPE).splitlines())
        if path and path not in moved
    ]

    print(f"chart_version: {chart.name} is {current}, set in {bumped_at[:7]}")
    sys.stdout.flush()

    if moved:
        print(
            f"\nthe chart's shape moved since its version did, and the version is still {current}:",
            file=sys.stderr,
        )
        for path in sorted(set(moved)):
            print(f"  {path}", file=sys.stderr)
        print(
            "\nA template or a value decides what a deployment renders. While this number stands "
            "still\nthere is no released chart version for anything to pin, so an image tag pins the "
            "binary\nand nothing pins the shape. Move `version:` in the chart. See B-99.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
