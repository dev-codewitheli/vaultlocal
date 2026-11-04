dependencies {
    api(project(":domain"))

    // Integration tests run the use cases against the real adapters (no Gradle cycle: test scope only).
    testImplementation(project(":adapters:crypto"))
    testImplementation(project(":adapters:persistence-sqlite"))
}
