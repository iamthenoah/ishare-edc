plugins {
    `java-library`
}

allprojects {
    group = "eu.example.ishare.edc"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    }
}

subprojects {
    apply(plugin = "java-library")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }
}
