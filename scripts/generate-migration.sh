#!/usr/bin/env bash
# Draft a Flyway migration from the Exposed Table definitions, and bring the draft back to the Mac.
#
# WHY A SCRIPT AND NOT A GRADLE TASK. `generateMigrations` needs Docker — it diffs against a
# throwaway Postgres — and Docker lives only on the Linux box. It also WRITES A FILE, and this
# repository is a one-way mutagen replica, so a file written on that side is deleted on the next
# sync. Either constraint alone is fine; together they mean the task cannot be run usefully from
# either machine on its own. So: run it there, read the result back here.
#
# WHAT YOU GET IS A DRAFT. A schema differ emits the shortest SQL that makes two schemas equal —
# DROP COLUMN, RENAME, ALTER TYPE — which is exactly the set that breaks a rolling deploy, because
# during a roll the old code is still reading the column the diff just dropped. Turning the draft
# into an expand/contract pair is yours; see docs/backlog/B-36 for the table of what each kind of
# change becomes.
#
# AND CHECK IT IS COMPLETE, AND RENUMBER IT. The generator names each file by a version stamped to
# the second plus a description taken from its FIRST statement — which, for a table with a foreign
# key, is the parent. So several tables produce one filename, the files overwrite each other, and a
# table goes missing with exit code 0 (JetBrains/Exposed#2897). Even when nothing is lost the shared
# version is a set Flyway refuses outright. Two gates catch this: MigrationFilesTest on the names,
# and KonektSchemaTest on what they produce. Run the tests before believing a draft.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out_dir="$repo_root/build/generated-migrations"
remote_dir="server/build/generated-migrations"

mkdir -p "$out_dir"
rm -f "$out_dir"/*.sql

"$HOME/.claude/bin/wsl-run" "rm -rf $remote_dir && ./gradlew :server:generateMigrations --console=plain"

# Read them back one by one rather than mounting anything: the list is short and the failure mode of
# a partial copy is a migration that looks complete.
names=$(ssh -o BatchMode=yes -p 2222 youndie@127.0.0.1 "ls \$HOME/konekt/$remote_dir 2>/dev/null || true")
if [ -z "$names" ]; then
    echo "generate-migration: the generator produced nothing — no schema change to draft?" >&2
    exit 1
fi

while IFS= read -r name; do
    [ -n "$name" ] || continue
    ssh -o BatchMode=yes -p 2222 youndie@127.0.0.1 "cat \$HOME/konekt/$remote_dir/$name" > "$out_dir/$name"
    echo "drafted: build/generated-migrations/$name"
done <<< "$names"

cat >&2 <<'NOTE'

Not a migration yet. Before anything goes into server/src/main/resources/db/migration:
  1. rewrite it as an expand/contract pair (B-36) — the generator's form breaks a rolling deploy;
  2. check that every changed table is in it (JetBrains/Exposed#2897 drops one silently);
  3. renumber it — the generator stamps versions to the second and collides with itself;
  4. run the tests: KonektSchemaTest is what actually proves the schema is complete.
NOTE
