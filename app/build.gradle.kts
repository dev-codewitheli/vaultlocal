// DESIGN.md §4.3 / §9: heap for Argon2 (256 MiB recovery), no attach (blocks jcmd/jmap heap dumps),
// no crash minidump or hs_err file (either could capture process memory).
val hardenedJvmArgs = listOf(
    "-Xmx768m",
    "-XX:+DisableAttachMechanism",
    "-XX:-CreateCoredumpOnCrash",
    "-XX:+SuppressFatalErrorMessage",
)

plugins {
    application
}

dependencies {
    implementation(project(":application"))
    implementation(project(":adapters:crypto"))
    implementation(project(":adapters:persistence-sqlite"))
    implementation(project(":adapters:ui-javafx"))
    implementation(libs.jna.platform)

    testImplementation(libs.archunit)
}

application {
    mainClass.set("com.codewitheli.vaultlocal.app.Main")
    applicationName = "VaultLocal"
    applicationDefaultJvmArgs = hardenedJvmArgs
}

tasks.named<JavaExec>("run") {
    // ./gradlew :app:run -Pvaultlocal.home=build/dev-vault  keeps a dev vault out of %LOCALAPPDATA%
    project.findProperty("vaultlocal.home")?.let { systemProperty("vaultlocal.home", it.toString()) }
}

// Collects the application JAR and its runtime dependencies for jdeps/jlink/jpackage.
val jpackageInput by tasks.registering(Sync::class) {
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("jpackage/input"))
}

// ---------------------------------------------------------------- jlink + jpackage (DESIGN.md §9)
val jdkHome: String = System.getProperty("java.home")
val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
// From jdeps, minus java.naming (only Bouncy Castle's unused JCE CRL/LDAP code needs it) and
// jdk.jfr (optional for JavaFX). No java.net.http, jdk.httpserver or javafx.web in the image.
val runtimeModules = "java.base,java.desktop,java.sql,jdk.unsupported"
val appVersion = project.version.toString()
val upgradeUuid = "7d4e3b0a-5f61-4c8e-9a2d-1b6f0c3e8a51" // fixed so new versions upgrade in place

val jlinkRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a minimal Java runtime image with jlink."
    val out = layout.buildDirectory.dir("jpackage/runtime").get().asFile
    outputs.dir(out)
    doFirst { out.deleteRecursively() }
    commandLine("$jdkHome/bin/jlink$exe", "--add-modules", runtimeModules, "--strip-debug", "--no-header-files",
        "--no-man-pages", "--compress=zip-6", "--output", out.path)
}

val appImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the VaultLocal application image (no installer)."
    dependsOn(jpackageInput, jlinkRuntime)
    val dest = layout.buildDirectory.dir("jpackage/image").get().asFile
    outputs.dir(dest)
    doFirst { dest.deleteRecursively() }
    val args = mutableListOf("$jdkHome/bin/jpackage$exe", "--type", "app-image", "--name", "VaultLocal",
        "--app-version", appVersion, "--vendor", "codewitheli",
        "--description", "Offline, local-only password manager",
        "--input", layout.buildDirectory.dir("jpackage/input").get().asFile.path,
        "--main-jar", "app-$appVersion.jar", "--main-class", application.mainClass.get(),
        "--runtime-image", layout.buildDirectory.dir("jpackage/runtime").get().asFile.path,
        "--dest", dest.path)
    if (exe.isNotEmpty()) {
        args += listOf("--icon", file("src/packaging/windows/icon.ico").path)
    }
    hardenedJvmArgs.forEach { args += listOf("--java-options", it) }
    commandLine(args)
}

val msi by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the Windows .msi installer (requires WiX Toolset 3.x on the PATH)."
    dependsOn(appImage)
    val dest = layout.buildDirectory.dir("dist").get().asFile
    // -Pwix.dir=C:\path\to\wix314 uses portable WiX binaries without touching the system PATH.
    project.findProperty("wix.dir")?.let { environment("PATH", "$it${File.pathSeparator}${System.getenv("PATH")}") }
    doFirst { dest.deleteRecursively() }
    commandLine("$jdkHome/bin/jpackage$exe", "--type", "msi", "--name", "VaultLocal", "--app-version", appVersion,
        "--vendor", "codewitheli", "--description", "Offline, local-only password manager",
        "--app-image", layout.buildDirectory.dir("jpackage/image/VaultLocal").get().asFile.path,
        // Per-user installs default to %LOCALAPPDATA%\VaultLocal, which is the vault's data dir.
        // Program files go to %LOCALAPPDATA%\VaultLocal App instead, so they never mix. (A nested
        // path like Programs\VaultLocal fails WiX validation ICE64 with jpackage 21.)
        "--install-dir", "VaultLocal App",
        "--dest", dest.path, "--win-per-user-install", "--win-dir-chooser", "--win-menu",
        "--win-menu-group", "VaultLocal", "--win-shortcut-prompt", "--win-upgrade-uuid", upgradeUuid)
}

// Phase 3: writes the classpath of the evidence driver (app test sources) for standalone runs.
val smokeClasspath by tasks.registering {
    dependsOn("testClasses")
    val out = layout.buildDirectory.file("phase3/classpath.txt")
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs(); writeText(sourceSets["test"].runtimeClasspath.asPath) } }
}
