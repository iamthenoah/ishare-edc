plugins { `java-library` }

dependencies {
    api(libs.akka.actor.typed)
    api(libs.akka.cluster.typed)
    api(libs.jackson.databind)
}
