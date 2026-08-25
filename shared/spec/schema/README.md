# The wire schema of this build

`konekt-components.schema.json` is generated from the `SerialDescriptor`s of `:shared:components` —
the same descriptors kotlinx.serialization encodes a response with — so it cannot fall quietly behind
the Kotlin types. It is committed because it is the artefact another implementation reads, and
because a wire change is only ever noticed by a person in the diff of a pull request.

**This file is not self-contained, on purpose.** Its `$ref`s point at `kompot-core.schema.json` and
the toolkit's other twelve schema files, and those are *not* committed here. They come out
byte-identical to the ones kompot commits in its own repository, so a copy here would be a second
source of truth that churns on every version bump and produces a diff saying nothing about this
product. The set a reader needs is:

- the toolkit's thirteen files, from `kompot-spec/schema` at the version this build pins —
  `kompot = ` in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml);
- this one.

To get all fourteen locally, generated against the pinned version:

```bash
LOCAL=1 KONEKT_SPEC_RECORD=true ./gradlew :shared:spec:test
```

That records the toolkit's files beside this one; only this one is tracked by git, so the rest appear
as untracked and are meant to be thrown away. `LOCAL=1` because the task writes files and this
repository is a one-way mutagen replica — a file written on the Linux side is reverted on the next
sync, and the run looks like it did nothing.

## What the profile is for

`KonektSpec.profile()` is the closed list — "exactly these types may travel on this wire" — assembled
from the `x-kompot-contributes` of every module. The module schemas describe a polymorphic base as
*open*, because that is the runtime contract; the profile is the strict view, and it is what a second
implementation is held to. `KonektSchemaGoldenTest` asserts every one of konekt's nine names appears
in it, by name rather than by count: a count passes on a profile holding nine of the toolkit's own
types and none of ours, which is exactly what an empty generated registration produces.
