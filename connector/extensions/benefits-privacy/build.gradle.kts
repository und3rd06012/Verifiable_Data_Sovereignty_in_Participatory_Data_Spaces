plugins {
    `java-library`
}

dependencies {
    api(libs.edc.control.plane.spi)
    api(libs.edc.policy.engine.spi)
    api(libs.edc.data.plane.spi)
    api(libs.edc.web.spi)
    implementation(libs.edc.control.plane.core)
    implementation(project(":connector:extensions:benefits-policy"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}
