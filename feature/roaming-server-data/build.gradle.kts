plugins {
    id("konekt.jvm")
}

dependencies {
    api(project(":feature:roaming-server-domain"))
    implementation(project(":shared:db"))
    implementation(project(":shared:server-common"))

    // The platform, again: a BOM constrains only the configuration it is declared in, so a module that
    // names koin-core without it resolves the coordinate with no version at all.
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    testImplementation(testFixtures(project(":shared:db")))
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.logback.classic)
}
