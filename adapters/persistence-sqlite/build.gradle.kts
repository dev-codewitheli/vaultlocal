dependencies {
    implementation(project(":application"))
    implementation(libs.sqlite.jdbc)
    testImplementation(project(":adapters:crypto"))
}
