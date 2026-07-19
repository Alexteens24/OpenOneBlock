import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    alias(libs.plugins.shadow)
}

description = "Paper composition root and platform adapters for OpenOneBlock."

val pluginVersion = version.toString()

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

tasks.processResources {
    inputs.property("version", pluginVersion)
    filter<ReplaceTokens>("tokens" to mapOf("version" to pluginVersion))
}

tasks.jar {
    archiveClassifier = "plain"
}

val distributableJar =
    tasks.named<ShadowJar>("shadowJar") {
        archiveBaseName = "OpenOneBlock"
        archiveClassifier = ""
        archiveVersion = pluginVersion
    }

tasks.assemble {
    dependsOn(distributableJar)
}

tasks.test {
    dependsOn(distributableJar)
    systemProperty(
        "openoneblock.distributableJar",
        layout.buildDirectory
            .file("libs/OpenOneBlock-$pluginVersion.jar")
            .get()
            .asFile.absolutePath,
    )
}
