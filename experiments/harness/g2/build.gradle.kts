plugins {
    `java-library`
}

dependencies {
    implementation(project(":connector:extensions:benefits-privacy"))
    implementation(project(":experiments:harness:e4"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
