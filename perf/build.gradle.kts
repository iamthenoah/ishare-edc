plugins {
    `java-library`
    id("application")
}

application {
    mainClass.set("eu.example.ishare.perf.PerfHarness")
}

fun JavaExec.forwardSystemProperties() {
    systemProperties(System.getProperties().entries.associate { (it.key as String) to it.value })
}

tasks.register<JavaExec>("perfRun") {
    group = "ishare"
    description = "Runs the closed-loop load harness against a running scenario " +
            "(-Dperf.scenario=<label> -Dperf.mode=ping,catalog,run -Dperf.concurrency=1,4,16 ...)."
    mainClass.set("eu.example.ishare.perf.PerfHarness")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    forwardSystemProperties()
}

tasks.register<JavaExec>("perfCompare") {
    group = "ishare"
    description = "Aggregates results/perf/scenario-*/summary.csv into results/perf/comparison.md."
    mainClass.set("eu.example.ishare.perf.PerfCompare")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    forwardSystemProperties()
}
