#!/usr/bin/env python3
"""Compare the environment a chart renders against the environment a cluster is running.

Driven by `deploy-check.sh`, which is where the *why* is written. This file is the half that can be
pointed at files instead of at a cluster, which is what makes the check provable:

    python3 scripts/deploy_check.py <rendered.json> <live.json>

`rendered.json` is a JSON array of manifest documents; `live.json` is what
`kubectl get deployments,statefulsets -o json` answers. Exit status 1 when they disagree.
"""

import json
import sys


def envs(objects):
    """(workload, container, env-name) -> value, or None for a `valueFrom` reference.

    An env var whose value comes from a secret or a config map has no value here to compare — only
    its PRESENCE is checked, because the whole point of those references is that the manifest does
    not carry the number.
    """
    out = {}
    for obj in objects:
        kind = obj.get("kind", "?")
        name = obj.get("metadata", {}).get("name", "?")
        spec = obj.get("spec", {}).get("template", {}).get("spec", {})
        for group in ("initContainers", "containers"):
            for container in spec.get(group) or []:
                for env in container.get("env") or []:
                    key = (f"{kind}/{name}", container.get("name", "?"), env["name"])
                    out[key] = None if "valueFrom" in env else env.get("value", "")
    return out


def problems_between(rendered, running):
    problems = []

    # THE FLOOR, FIRST, AND IT IS NOT DECORATION. An empty value is the observable form of `B-106`:
    # the template still emits `- name: X` with `value: ""`, so the variable is present, spelled
    # right, and carries nothing. Asserted against the CLUSTER alone, so it fires even when the
    # working tree's chart has drifted from what was deployed and the comparison below is noisy for
    # reasons that have nothing to do with the flag.
    for (workload, container, name), value in sorted(running.items()):
        if value == "":
            problems.append("empty in the cluster: {0} {1} {2}=".format(workload, container, name))

    # AND THEN THE COMPARISON, which catches a variable the chart gained and the deploy dropped
    # ENTIRELY — a template that wraps one in `{{- if }}` emits nothing at all, and the floor above
    # would never see it.
    for key, value in sorted(rendered.items()):
        workload, container, name = key
        if key not in running:
            problems.append(
                "the chart declares it and the cluster has not got it: {0} {1} {2}"
                .format(workload, container, name))
        elif value is not None and running[key] != value:
            problems.append(
                "different: {0} {1} {2} - chart {3!r}, cluster {4!r}"
                .format(workload, container, name, value, running[key]))

    for key in sorted(running):
        if key not in rendered:
            workload, container, name = key
            problems.append(
                "the cluster has it and the chart does not declare it: {0} {1} {2}"
                .format(workload, container, name))

    # VACUITY LAST AND UNCONDITIONAL. Every statement above is about a pair of dictionaries, and two
    # empty ones satisfy all of them - which is what a wrong namespace, a renamed release or a
    # `helm template` that printed a warning to stdout would produce.
    if not rendered:
        problems.append("the chart rendered no environment at all; every comparison above was vacuous")
    if not running:
        problems.append("the cluster answered with no workload; every comparison above was vacuous")

    return problems


def main(argv):
    if len(argv) != 3:
        sys.exit("usage: deploy_check.py <rendered.json> <live.json>")

    with open(argv[1]) as f:
        rendered = envs(json.load(f))
    with open(argv[2]) as f:
        running = envs(json.load(f).get("items", []))

    problems = problems_between(rendered, running)
    if problems:
        print("FAIL  the cluster is not running what this chart says:")
        for p in problems:
            print("        " + p)
        print()
        print("        A deploy made with `--reuse-values` produces exactly this: helm reuses the")
        print("        previous release's user-supplied config INSTEAD of the new chart's defaults,")
        print("        so every value the chart gained since the last deploy arrives empty.")
        print("        Redeploy with `make deploy`, which is `--reset-then-reuse-values` written")
        print("        down where it cannot be forgotten.")
        return 1

    print("ok    {0} environment values, and the cluster agrees with the chart on all of them"
          .format(len(rendered)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
