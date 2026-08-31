plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    runtimeOnly(libs.edc.bom.controlplane.base) {
        exclude(group = "org.eclipse.edc", module = "data-plane-signaling-core")
        exclude(group = "org.eclipse.edc", module = "data-plane-signaling-oauth2")
    }
    implementation(libs.edc.iam.mock)
    implementation(libs.edc.control.plane.api.client)
    implementation(libs.edc.transfer.data.plane.signaling)
    implementation(libs.edc.validator.data.address.http.data)
    implementation(libs.edc.data.plane.self.registration)
    implementation(libs.edc.data.plane.signaling.api)
    implementation(libs.edc.data.plane.signaling.client)
    implementation(libs.edc.data.plane.core)
    implementation(libs.edc.data.plane.http)
    implementation(libs.edc.data.plane.iam)

    implementation(project(":connector:extensions:benefits-policy"))
    implementation(project(":connector:extensions:benefits-privacy"))
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("connector.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
