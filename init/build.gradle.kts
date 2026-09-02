plugins {
    `java-library`
    id("application")
}

dependencies {
    implementation(project(":apps:apps-common"))
    implementation(libs.akka.slf4j)
    implementation(libs.logback.classic)
    implementation(libs.nimbus.jose.jwt)
}

application {
    mainClass.set("eu.example.ishare.init.InitCli")
}

fun JavaExec.forwardSystemProperties() {
    systemProperties(System.getProperties().entries.associate { (it.key as String) to it.value })
}

tasks.register<JavaExec>("initAr") {
    group = "ishare"
    description = "Seeds/inspects an AR policy (http or akka, per ar.transport)."
    mainClass.set("eu.example.ishare.init.InitCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    args = listOf("ar")
    forwardSystemProperties()
}

tasks.register<JavaExec>("initProvider") {
    group = "ishare"
    description = "Registers the provider's data plane and verifies assets/contract-definitions are queryable."
    mainClass.set("eu.example.ishare.init.InitCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    args = listOf("provider")
    forwardSystemProperties()
}

tasks.register<JavaExec>("consumerFlow") {
    group = "ishare"
    description = "Runs a full catalog -> negotiate -> transfer -> fetch-via-EDR demo flow " +
            "against the consumer EDC connector. Replaces the old scripts/consumer-flow.js."
    mainClass.set("eu.example.ishare.init.InitCli")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    args = listOf("consumer-flow")
    forwardSystemProperties()
}
