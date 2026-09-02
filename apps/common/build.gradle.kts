plugins {
    `java-library`
}

dependencies {
    api(project(":common"))
    api(libs.jackson.databind)
    api(libs.akka.actor.typed)
    api(libs.akka.cluster.typed)
    api(libs.akka.serialization.jackson)
}
