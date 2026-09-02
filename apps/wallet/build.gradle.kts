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
    mainClass.set("eu.example.ishare.apps.wallet.WalletApp")
}

tasks.shadowJar {
    archiveFileName.set("wallet.jar")
    mergeServiceFiles()
    append("reference.conf")
}
