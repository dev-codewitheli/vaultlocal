dependencies {
    implementation(project(":application"))
    implementation(libs.bcprov)
}

tasks.test {
    if (project.hasProperty("benchmark")) {
        systemProperty("vaultlocal.benchmark", "true")
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    }
}
