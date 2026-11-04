// JavaFX is put on the classpath directly (platform classifier, non-transitive) rather than via
// the openjfx Gradle plugin, so TestFX + Monocle run headless without module-path juggling.
val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch")
val fxPlatform = when {
    os.contains("win") -> "win"
    os.contains("mac") -> if (arch == "aarch64") "mac-aarch64" else "mac"
    else -> if (arch == "aarch64") "linux-aarch64" else "linux"
}
val fxVersion = libs.versions.javafx.get()

dependencies {
    api(project(":application"))
    for (module in listOf("base", "graphics", "controls")) {
        api("org.openjfx:javafx-$module:$fxVersion:$fxPlatform") { isTransitive = false }
    }
    implementation(libs.jna.platform)

    testImplementation(project(":adapters:crypto"))
    testImplementation(project(":adapters:persistence-sqlite"))
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
    testImplementation(libs.monocle)
    testImplementation(libs.hamcrest)
}

tasks.test {
    // Headless JavaFX (Monocle) so UI tests run without a display.
    systemProperty("testfx.robot", "glass")
    systemProperty("testfx.headless", "true")
    systemProperty("glass.platform", "Monocle")
    systemProperty("monocle.platform", "Headless")
    systemProperty("prism.order", "sw")
    systemProperty("java.awt.headless", "true")
}

tasks.test {
    useJUnitPlatform { excludeTags("windows-spike", "screenshots") }
}

// Decision Q9 spike: real Windows clipboard (no Monocle). Run manually on a Windows desktop.
val windowsClipboardSpike by tasks.registering(Test::class) {
    description = "Checks Windows Clipboard History exclusion formats on the real clipboard."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("windows-spike") }
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}

// README / portfolio screenshots with made-up demo data, rendered headless into docs/screenshots.
val screenshots by tasks.registering(Test::class) {
    description = "Renders showcase screenshots into docs/screenshots."
    group = "documentation"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("screenshots") }
    systemProperty("screenshots.dir", rootProject.file("docs/screenshots").path)
    for (p in listOf("testfx.robot" to "glass", "testfx.headless" to "true", "glass.platform" to "Monocle",
            "monocle.platform" to "Headless", "prism.order" to "sw", "java.awt.headless" to "true")) {
        systemProperty(p.first, p.second)
    }
    outputs.upToDateWhen { false }
}
