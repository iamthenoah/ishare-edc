plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":apps:apps-common"))
    implementation(libs.akka.slf4j)
    implementation(libs.logback.classic)
}

application {
    mainClass.set("eu.example.ishare.apps.ar.ArApp")
}

tasks.shadowJar {
    archiveFileName.set("ar.jar")
    mergeServiceFiles()
    append("reference.conf")
}
