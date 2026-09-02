plugins {
    `java-library`
}

dependencies {
    implementation(project(":common"))

    implementation(libs.edc.connector.core)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    implementation(libs.akka.actor.typed)
    implementation(libs.akka.cluster.typed)
}
