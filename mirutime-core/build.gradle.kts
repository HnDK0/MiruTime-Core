import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

// Overridable for JitPack: it builds with -Pgroup=com.github.<owner>
// -Pversion=<tag>; without them the local defaults apply.
group = providers.gradleProperty("group").getOrElse("dev.mirutime")
version = providers.gradleProperty("version").getOrElse("0.1.0")

kotlin {
    // SDK levels aligned with the NoveLA consumer app.
    android {
        namespace = "dev.mirutime.core"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        // Consumer R8 rules shipped in the AAR (packed into proguard.txt,
        // consumed automatically by the app's R8). AGP 9's KMP DSL has no
        // consumerProguardFiles() — the equivalent is
        // optimization { consumerKeepRules { … } }; publishing is on by
        // default when a file is set.
        optimization {
            consumerKeepRules.file("consumer-rules.pro")
        }

        // NB: with com.android.kotlin.multiplatform.library the android
        // publication is auto-created by maven-publish; no singleVariant
        // publishing block (that DSL belongs to the old com.android.library).
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
            // Response decompression (gzip/deflate) — internal to PluginHttpClient.
            implementation(libs.ktor.client.encoding)
            implementation(libs.ksoup)
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        // Intermediate source set shared by the jvm and android compilations:
        // androidMain does not see jvmMain in this KMP configuration, but both
        // targets are JVM-compatible, so LuaJ code lives here once.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)

        jvmSharedMain.dependencies {
            // LuaJ is pure Java: shared by the jvm and android targets via
            // this intermediate source set (androidMain does not see jvmMain).
            implementation(libs.luaj.jse)
            // Brotli (br) response decoding — pure-Java decoder, no JNI, so the
            // same jar serves the jvm and android targets (ContentEncoder lives
            // in this source set).
            implementation(libs.brotli.dec)
            // YAML index parsing. The whole plugin runtime (LuaPluginSource,
            // PluginInstaller, IndexParser, ScriptMetadataParser, ScriptLoader,
            // LibraryLoader) lives in THIS source set so Android can run
            // plugins too — Android does not see jvmMain here — so snakeyaml
            // has to be on the shared classpath, not the jvm-only one.
            implementation(libs.snakeyaml)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}
