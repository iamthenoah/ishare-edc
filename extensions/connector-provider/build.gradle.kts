import com.github.jengelman.gradle.plugins.shadow.transformers.AppendingTransformer

plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

configurations.all {
    exclude(group = "org.eclipse.edc", module = "data-plane-self-registration")
}

dependencies {
    implementation(libs.edc.boot)
    implementation(libs.edc.connector.core)
    implementation(libs.edc.http)
    implementation(libs.edc.configuration.filesystem)
    implementation(libs.edc.control.api.configuration)
    implementation(libs.edc.control.plane.core)
    implementation(libs.edc.control.plane.api)
    implementation(libs.edc.control.plane.api.client)
    implementation(libs.edc.management.api)
    implementation(libs.edc.dsp)
    implementation(libs.edc.data.plane.core)
    implementation(libs.edc.data.plane.http)
    implementation(libs.edc.data.plane.selector.core)
    implementation(libs.edc.data.plane.selector.control.api)
    implementation(libs.edc.edr.store.core)
    implementation(libs.edc.transfer.data.plane.signaling)

    implementation(project(":extensions:actor-extension"))
    implementation(project(":extensions:ishare-identity"))
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.shadowJar {
    mergeServiceFiles()
    transform(AppendingTransformer::class.java) {
        resource = "reference.conf"
    }
    archiveFileName.set("connector-provider.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
