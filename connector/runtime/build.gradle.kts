plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    runtimeOnly(libs.edc.bom.controlplane.base)
    runtimeOnly(libs.edc.iam.mock)
    implementation(project(":connector:extensions:benefits-policy"))
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("connector.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
