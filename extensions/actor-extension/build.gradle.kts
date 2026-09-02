plugins { `java-library` }

dependencies {
    implementation(project(":common"))
    implementation(libs.edc.connector.core)
    implementation(libs.edc.control.plane.core)
    implementation(libs.edc.data.plane.http)

    implementation(libs.nimbus.jose.jwt)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    implementation(libs.edc.data.plane.selector.core)

    implementation(libs.akka.actor.typed)
    implementation(libs.akka.serialization.jackson)
}
