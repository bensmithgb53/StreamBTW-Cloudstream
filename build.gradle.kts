import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.6.0")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/bensmithgb53/StreamBTW-Cloudstream/")
    }

    android {
        namespace = "ben.smith53"
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions {
                jvmTarget = "1.8"
                freeCompilerArgs = freeCompilerArgs +
                        "-Xno-call-assertions" +
                        "-Xno-param-assertions" +
                        "-Xno-receiver-assertions"
            }
        }
    }

    dependencies {
        val apk by configurations
        val implementation by configurations

        apk("com.lagradost:cloudstream3:pre-release") // Includes nicehttp for app.get
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11") // Already present, but not used here
        implementation("org.jsoup:jsoup:1.18.1") // For other providers, not needed here
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0") // For JSON parsing
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0") // For JSON parsing
        implementation("com.squareup.okhttp3:okhttp:4.12.0") // For fetchEncryptedData
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1") // For suspendCancellableCoroutine
    }
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}