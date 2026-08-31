plugins {
    `java-library`
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    dependencies {
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testImplementation", rootProject.libs.assertj.core)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

val Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = extensions.getByType()
