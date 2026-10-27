// Top-level build file.
buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin; this upgrades the Kotlin Gradle Plugin it uses.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
