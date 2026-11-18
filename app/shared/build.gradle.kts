import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val appSecretsText = providers.fileContents(rootProject.layout.projectDirectory.file("secrets.properties")).asText.orElse("")

val generateAppSecrets by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/appSecrets/kotlin")
    val secrets = appSecretsText
    inputs.property("secrets", secrets)
    outputs.dir(outputDir)
    doLast {
        val properties = Properties().apply { load(secrets.get().reader()) }
        fun value(key: String) = properties.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"")
        val file = outputDir.get().file("com/ashish/allthedocs/config/AppSecrets.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.ashish.allthedocs.config
            |
            |object AppSecrets {
            |    const val GOOGLE_IOS_CLIENT_ID = "${value("GOOGLE_IOS_CLIENT_ID")}"
            |    const val MICROSOFT_CLIENT_ID = "${value("MICROSOFT_CLIENT_ID")}"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.ashish.allthedocs.app.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppSecrets)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.koin.android)
            implementation(libs.androidx.workRuntime)
            implementation(libs.mlkit.documentScanner)
            implementation(libs.playServicesAuth)
        }
        iosMain.dependencies {
            implementation(libs.ktor.clientDarwin)
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialIconsCore)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationKotlinxJson)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.clientMock)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}