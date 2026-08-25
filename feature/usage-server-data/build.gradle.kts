plugins {
    id("konekt.jvm")
}

dependencies {
    api(project(":feature:usage-server-domain"))
    implementation(project(":shared:db"))
    implementation(project(":shared:server-common"))
    implementation(project(":shared:components"))

    implementation(platform(libs.kompot.bom))
    implementation(libs.kompot.core)

    // The platform, again: a BOM constrains only the configuration it is declared in, so a module
    // that names koin-core without it resolves the coordinate with no version at all.
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":shared:db")))
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.logback.classic)
}
