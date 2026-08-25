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

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":shared:db")))
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.logback.classic)
}
