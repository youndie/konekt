plugins {
    id("konekt.multiplatform")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, not implementation: every type here implements KompotComponent, so the interface
            // stands in the public signature of all nine and a consumer cannot name them without it.
            // `project.dependencies.platform(...)`, not a bare `platform(...)`: inside a
            // source-set dependency block the receiver is KotlinDependencyHandler, which has no
            // `platform` of its own, and the error names the function rather than the receiver.
            api(project.dependencies.platform(libs.kompot.bom))
            api(libs.kompot.core)
            implementation(libs.kompot.registryAnnotations)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            // The standard set is a test dependency rather than a main one: konekt's own components
            // never embed a toolkit component in their own fields, but the round-trip tests build a
            // realistic tree — a column of cards — which is the shape a screen actually has.
            implementation(libs.kompot.standard)
        }
    }
}

dependencies {
    // kspCommonMainMetadata, not one entry per target. The annotated types are all in commonMain and
    // per-target output lands in a PLATFORM source set, where the metadata a consumer's commonMain
    // compiles against can never see it. With a single target nothing notices; the second target
    // turns it into an unresolved reference.
    //
    // The platform has to be added HERE as well, and that is not belt-and-braces. A BOM constrains
    // the configuration it is declared in, and the processor classpath is a configuration of its
    // own — so without this line the coordinate below resolves with no version at all and the build
    // fails with "Could not find io.github.youndie:kompot-registry-processor:", trailing colon and
    // nothing after it. The alternative, writing the version once here, is the single literal that
    // B-01's rule exists to prevent.
    add("kspCommonMainMetadata", platform(libs.kompot.bom))
    add("kspCommonMainMetadata", libs.kompot.registryProcessor)
}

ksp {
    // Unique across every module that applies the processor, because generated files land in one
    // package: two modules sharing a tag generate objects of the same name and collide in the
    // consumer's build. "Konekt" is this repository's one dictionary.
    arg("kompotModuleTag", "Konekt")
}

kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// Everything that READS the generated directory has to wait for it, and that is not only the
// compilers. The per-target sources jars package commonMain too, and so does ktlint: excluding
// generated files from the CHECK does not remove the directory from the task's INPUTS, so ktlint
// still fails Gradle's undeclared-dependency validation. Matched on the consumers rather than listed
// by name, so a target added later is covered without anybody remembering to.
tasks
    .matching {
        (
            it.name.startsWith("compile") ||
                it.name.lowercase().endsWith("sourcesjar") ||
                it.name.startsWith("runKtlint") ||
                it.name.startsWith("ktlint")
        ) && it.name != "kspCommonMainKotlinMetadata"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

// The per-target ksp tasks are registered for every target and now have no processor of their own,
// but they still read the metadata output as a source directory — which Gradle reports as an
// undeclared task dependency. Generation happens once, so they are switched off.
//
// A DISABLED KSP TASK IS THE CLASSIC WAY TO GET A GREEN AND EMPTY BUILD, so the exit code proves
// nothing about this file. What does: KonektRegistrationTest round-trips every one of the nine
// components THROUGH generatedKonektSerializersModule, on every target. An empty registration fails
// it, which is the only reason that test exists in the shape it does.
tasks.matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }.configureEach {
    enabled = false
}
