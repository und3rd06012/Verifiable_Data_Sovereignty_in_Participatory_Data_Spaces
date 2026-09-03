plugins {
    `java-library`
}

dependencies {
    implementation(project(":connector:extensions:benefits-privacy"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
