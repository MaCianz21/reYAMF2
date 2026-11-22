plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.rikka.tools.refine)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

// Gradle 9 removed Project.exec, so the git metadata is read through providers.exec.
val gitHash: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "(error)" } }.get()

val isDirty: Boolean = providers.exec {
    commandLine("git", "diff", "--stat")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.isNotBlank() }.get()

android {
    val buildTime = System.currentTimeMillis()
    val baseVersionName = "1.1.1"
    namespace = "com.mja.reyamf"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mja.reyamf"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "$baseVersionName-git.$gitHash${if (isDirty) "-dirty" else ""}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("long", "BUILD_TIME", buildTime.toString())
    }
    packaging {
        // Excluding all of META-INF/** would drop META-INF/xposed, which is the only
        // registration channel of the modern API, so only merge-prone entries are dropped.
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/NOTICE.md",
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
            "META-INF/*.SF",
            "META-INF/*.RSA",
            "META-INF/*.DSA",
            "META-INF/versions/**",
            "kotlin/**"
        )
    }
    flavorDimensions += "hook"
    productFlavors {
        // Classic de.robv.android.xposed module: assets/xposed_init + xposedminversion=82.
        create("legacy") {
            dimension = "hook"
            versionNameSuffix = "-legacy"
        }
        // Modern libxposed API 101 module for Vector 2.1+: META-INF/xposed/*.
        create("vector") {
            dimension = "hook"
            versionNameSuffix = "-vector"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.wear)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.preference.ktx)

    compileOnly(project(":android-stub"))
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.rikka.hidden.compat)

    // Flavour configurations are created by AGP at runtime, so they are not available as
    // typed Kotlin DSL accessors.
    //
    // The two hook stacks must never meet: EzXHelper 1.0.3 references de.robv.android.xposed, so it
    // is scoped to the legacy flavour and the vector flavour links the 3.x stack instead. Shared
    // code uses com.mja.reyamf.common.reflect, which depends on neither.
    //noinspection GradleDependency
    add("legacyImplementation", libs.ezxhelper)

    // Legacy Xposed API 82: only the legacy entry points import it.
    add("legacyCompileOnly", libs.xposed.api)

    // Modern libxposed API 101: only the vector entry points import it.
    add("vectorImplementation", libs.ezxhelper.core)
    add("vectorImplementation", libs.ezxhelper.xposed101)
    add("vectorImplementation", libs.ezxhelper.android.utils)
    add("vectorCompileOnly", libs.libxposed.api)

    //lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.service)

    //coroutines (used directly by the module and the manager UI)
    implementation(libs.kotlinx.coroutines.android)

    //flexbox
    implementation(libs.flexbox)

    //dynamicanimation
    implementation(libs.androidx.dynamicanimation.ktx)

    //gson
    implementation(libs.gson)

    //glide
    implementation(libs.glide)

    // byte buddy
    implementation(libs.byte.buddy.android)

    //room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    //hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)
}
