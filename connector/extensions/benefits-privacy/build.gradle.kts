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
    // jakarta.ws.rs-api (pulled in via edc.web.spi) has no RuntimeDelegate provider on
    // its own -- ReleaseRequestResource's use of jakarta.ws.rs.core.Response needs one
    // to run outside the full connector runtime, which supplies Jersey transitively via
    // org.eclipse.edc:jersey-core. Test-scoped only; production is unaffected.
    testRuntimeOnly("org.glassfish.jersey.core:jersey-common:4.0.2")
}
