plugins {
    `java-library`
}

dependencies {
    api(libs.edc.control.plane.spi)
    api(libs.edc.policy.engine.spi)
    api(libs.edc.participant.context.config.spi)
    api(libs.edc.json.ld.spi)
    implementation(libs.edc.control.plane.core)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
}
