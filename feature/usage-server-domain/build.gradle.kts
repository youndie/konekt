plugins {
    id("konekt.jvm")
}

dependencies {
    api(project(":shared:domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
