description = "Paper composition root and platform adapters for OpenOneBlock."

dependencies {
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)

    implementation(project(":openoneblock-admin-tools"))
    implementation(project(":openoneblock-api"))
    implementation(project(":openoneblock-core"))
    implementation(project(":openoneblock-integrations"))
    implementation(project(":openoneblock-persistence-sql"))
    implementation(project(":openoneblock-protection"))
    implementation(project(":openoneblock-scripting"))
    implementation(project(":openoneblock-structures-worldedit"))
}
