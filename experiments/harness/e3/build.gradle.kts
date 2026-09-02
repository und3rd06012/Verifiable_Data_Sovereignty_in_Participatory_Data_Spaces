plugins {
    `java-library`
}

dependencies {
    implementation(project(":connector:extensions:benefits-audit"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
