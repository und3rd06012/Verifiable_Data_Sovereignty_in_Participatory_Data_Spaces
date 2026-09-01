plugins {
    `java-library`
}

dependencies {
    api(libs.edc.control.plane.spi)
    api(libs.edc.web.spi)
    implementation(libs.edc.control.plane.core)
    implementation(project(":connector:extensions:benefits-policy"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
}
