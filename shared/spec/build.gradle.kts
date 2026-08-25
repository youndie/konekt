plugins {
    id("konekt.jvm")
}

dependencies {
    // The dictionary whose descriptors the schema is generated from. `api`, because
    // konektSpecModule() hands out a SerializersModule built from these types.
    api(project(":shared:components"))

    implementation(platform(libs.kompot.bom))
    // kompot-spec depends on every protocol module of the toolkit, which is what lets
    // KompotToolkitSpec.modules exist at all.
    implementation(libs.kompot.spec)
    implementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    // SchemaFiles resolves `schema/` relative to the working directory, which for a Gradle Test task
    // is the module directory. Stated rather than assumed, because a change of Gradle default here
    // would move the goldens somewhere nobody looks and the test would happily record a new set.
    workingDir = projectDir

    // Recording is opt-in through the environment, and it must run on the Mac: this repository is a
    // one-way mutagen replica, so a file written on the Linux side is reverted on the next sync and
    // the run looks like it did nothing.
    //   LOCAL=1 KONEKT_SPEC_RECORD=true ./gradlew :shared:spec:test
    environment("KOMPOT_SPEC_RECORD", providers.environmentVariable("KONEKT_SPEC_RECORD").getOrElse("false"))
}
