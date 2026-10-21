import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.antomys"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Rider 2026.2 bundles Kotlin stdlib 2.4.0: don't call newer stdlib APIs.
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        // Use JVM default methods of platform interfaces instead of generating bridges that the verifier
        // reports as overrides of deprecated or experimental members.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val platformLocalPath = providers.gradleProperty("platformLocalPath").orNull

dependencies {
    intellijPlatform {
        if (platformLocalPath != null) local(platformLocalPath) else rider(providers.gradleProperty("platformVersion"))
        pluginVerifier()
    }
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    pluginVerification {
        ides {
            if (platformLocalPath != null) local(file(platformLocalPath)) else recommended()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
