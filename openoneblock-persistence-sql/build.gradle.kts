description = "SQL persistence, migrations, write-behind, and crash recovery."

dependencies {
    implementation(project(":openoneblock-api"))
    implementation(project(":openoneblock-core"))
    implementation(libs.sqlite.jdbc)
}
