plugins {
    base
    alias(libs.plugins.spotless)
}

val libraryCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

allprojects {
    group = "dev.openoneblock"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    dependencies {
        add("testImplementation", platform(libraryCatalog.findLibrary("junit-bom").get()))
        add("testImplementation", libraryCatalog.findLibrary("junit-jupiter").get())
        add("testRuntimeOnly", libraryCatalog.findLibrary("junit-platform-launcher").get())
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
        withJavadocJar()
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

spotless {
    java {
        target("**/src/**/*.java")
        googleJavaFormat()
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        ktlint()
    }
    format("misc") {
        target("**/*.md", ".editorconfig", ".gitattributes", ".gitignore", ".github/**/*.yml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
