// What every module has regardless of its platform: a coordinate, a version, and one style.
//
// All of it comes from `ru.workinprogress.sborka` now, and the two plugins below are the whole file.
// What was here before — the group and the version read from a property, the ktlint plugin with its
// tool version pinned from the catalogue and generated sources excluded, and the check that every
// declared `@Test` actually ran — is in the shared conventions, several paragraphs of it word for
// word: this repository is where the `@Test` guard was written, after three tests were found that had
// never executed, one of them covering a counter that zeroed a subscriber's allowance.
//
// It stays a convention plugin of this build rather than each module naming the sborka ids directly,
// for the reason it was one to begin with: a module's build file should name what the module IS —
// `konekt.jvm`, `konekt.multiplatform` — and the answer to "what does that mean here" should live in
// one file. It also keeps the plugins on ONE classpath: applied both from this included build and
// from the main build's `plugins { }` block, the same plugin id arrives through two classloaders.

plugins {
    id("ru.workinprogress.sborka.base")
    // The test half is here rather than in `konekt.jvm` and `konekt.multiplatform` alone, because
    // `:client` and `:androidApp` take only this plugin — and `:client` is where the guard has most
    // to catch: its screenshot fixtures are generated, so a run that executed none of them looks
    // exactly like a run that passed them all.
    id("ru.workinprogress.sborka.test")
    id("ru.workinprogress.sborka.lint")
}
