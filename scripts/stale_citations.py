#!/usr/bin/env python3
"""A document citing a backlog item beside a claim that the item is unfinished.

B-82 found one of these, B-98 found twelve more, and the newest were created by the items that
closed the same week. The class reproduces faster than reading finds it: a paragraph written
mid-item is true for the length of one commit, and then the item closes and nothing anywhere
disagrees with the paragraph. `code_anchors.py` cannot see it — every path in every instance is
valid, and only the claim about them is false.

WHAT IS FLAGGED IS THE COLLOCATION, NOT THE CITATION. Most references to closed work are correct and
load-bearing — "B-64 is why the index exists", "per B-36" — and flagging those would produce a check
switched off within a month. What is flagged is `B-NN` sitting beside a phrase that asserts the work
has not happened, when the item's own frontmatter says it has.

Excluded: docs/backlog/, where an item legitimately describes the state it was written in, and
docs/research/source-draft.md, which is preserved verbatim by rule.
"""
import argparse
import pathlib
import re
import sys

# The markers, and each is a phrase that ASSERTS ABSENCE rather than merely mentioning an item. They
# are matched case-insensitively against the sentence a citation sits in, not against the whole
# paragraph: a file that says "not built" three sections above a correct citation is not a defect.
UNFINISHED = [
    r"\bis not built\b",
    r"\bnot built yet\b",
    r"\buntil it closes\b",
    r"\buntil that closes\b",
    r"\bis the item\b",
    r"\bis unfinished\b",
    r"\bare unfinished\b",
    r"\bunfinished, not scoped out\b",
    r"\bnot yet\b",
    r"\bno .{0,40} yet\b",
    r"\bnothing .{0,30} yet\b",
    r"\bstill to do\b",
    r"\bis pending\b",
    r"\bis open\b",
    r"\bremains open\b",
    r"\bhas no .{0,40} yet\b",
    r"\bwaiting on\b",
    r"\bonce there is one\b",
]
MARKER = re.compile("|".join(UNFINISHED), re.IGNORECASE)
CITATION = re.compile(r"\bB-(\d+)\b")

# THE ESCAPE HATCH, and it is not a concession. Some of these phrases are correct English about
# finished work — "B-86 is the item that displayed them" is past tense and true — and a rule with no
# usable way out is a rule that gets deleted the first time it is wrong. A line carrying this marker
# is skipped, and the marker is visible in the source so the next reader can see a claim was made.
ALLOWED = re.compile(r"<!--\s*citation-ok\s*-->")

# The unit a marker has to share with a citation. A sentence, approximately — and approximately is
# the right precision here: a table row is one line and one claim, which is where most of these live.
def sentences(line: str):
    return [part for part in re.split(r"(?<=[.!?])\s+", line) if part]


def statuses(backlog: pathlib.Path) -> dict[str, str]:
    found = {}
    for path in sorted(backlog.glob("*.md")):
        head = path.read_text().split("---")
        if len(head) < 3:
            continue
        ident = re.search(r"^id:\s*(\S+)", head[1], re.MULTILINE)
        status = re.search(r"^status:\s*(\S+)", head[1], re.MULTILINE)
        if ident and status:
            found[ident.group(1)] = status.group(1)
    return found


def scanned(root: pathlib.Path, docs: pathlib.Path):
    for path in sorted(docs.rglob("*.md")):
        parts = {p.name for p in path.parents}
        if "backlog" in parts or path.name == "source-draft.md":
            continue
        yield path
    for name in ("README.md", "CLAUDE.md"):
        candidate = root / name
        if candidate.exists():
            yield candidate


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--docs", default="docs")
    args = parser.parse_args()

    docs = pathlib.Path(args.docs)
    root = docs.parent if docs.name == "docs" else pathlib.Path(".")
    known = statuses(docs / "backlog")
    if not known:
        print("no backlog items were parsed, so this check examined nothing", file=sys.stderr)
        return 2

    resolved = 0
    examined = 0
    offenders = []
    for path in scanned(root, docs):
        examined += 1
        for number, line in enumerate(path.read_text().splitlines(), start=1):
            if ALLOWED.search(line):
                continue
            for sentence in sentences(line):
                cited = CITATION.findall(sentence)
                if not cited:
                    continue
                marker = MARKER.search(sentence)
                for item in cited:
                    ident = f"B-{item}"
                    status = known.get(ident)
                    if status is None:
                        continue
                    resolved += 1
                    if marker and status in ("done", "dropped"):
                        offenders.append(
                            f"{path}:{number}: cites {ident}, which is {status}, "
                            f"beside \"{marker.group(0)}\""
                        )

    # A CHECKER THAT FOUND NOTHING PASSES SILENTLY, which is the failure B-24 and B-09 both exist
    # for. The count is reported and zero is a failure: it means the citation form changed, or the
    # scanned set went empty, and either way this check stopped being a check.
    print(f"stale_citations: {resolved} citations resolved in {examined} documents")
    sys.stdout.flush()
    if resolved == 0:
        print("no citation resolved to a known item, so nothing was checked", file=sys.stderr)
        return 2

    if offenders:
        print("\ndocuments describing finished work as still to do:", file=sys.stderr)
        for offender in offenders:
            print(f"  {offender}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
