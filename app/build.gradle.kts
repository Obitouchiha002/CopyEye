import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.copyeye.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.copyeye.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    /**
     * Optional local signing, so a release build can be installed on a phone for testing.
     *
     * The keystore is never committed. A clone without one still builds a release APK — it just
     * comes out unsigned, which is the honest outcome: nobody else's build should be signed with a
     * key they did not make. To sign your own, put a keystore at the repository root and its
     * credentials in `keystore.properties` (also uncommitted):
     *
     *     storeFile=copyeye-test.jks
     *     storePassword=...
     *     keyAlias=...
     *     keyPassword=...
     */
    val keystoreProperties: Properties? =
        rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
            Properties().also { properties -> file.inputStream().use(properties::load) }
        }

    if (keystoreProperties != null) {
        signingConfigs {
            create("local") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed only when a local keystore is configured; otherwise deliberately unsigned.
            signingConfig = signingConfigs.findByName("local")
        }
    }

    buildFeatures {
        compose = true
        // The Shizuku capture path runs a small service inside the shell process, which needs an
        // AIDL boundary.
        aidl = true
        // RemoteAdmin reports the running version at check-in.
        buildConfig = true
    }

    /**
     * The bundled OCR models ship as a ~11 MB native library per ABI, which is nearly the whole
     * APK. Without splitting, every user downloads all four architectures.
     *
     * For Play, prefer `./gradlew :app:bundleRelease` — the App Bundle does this and more. These
     * splits exist for direct APK distribution, where there is no store to do it for you. The
     * universal APK is still produced for "just give me one file that works" cases.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        val target = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = target
        targetCompatibility = target
    }

    kotlin {
        jvmToolchain(libs.versions.javaToolchain.get().toInt())
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()),
            )
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += "GradleDependency"

        /**
         * `InlinedApi` has to be an error, not a warning.
         *
         * A constant that is too new for minSdk gets inlined by the compiler, so lint reports it
         * under `InlinedApi` rather than `NewApi` — as a warning. That is exactly how
         * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (API 34) reached a build with minSdk 29 and broke
         * the service on every Android 10 to 13 device, while the build stayed green.
         */
        error += "InlinedApi"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // Optional capture path: shell-privileged screenshots with no consent dialog and no
    // screen-recording indicator, in exchange for a one-time pairing the user performs themselves.
    // The app degrades to MediaProjection whenever Shizuku is absent, which is the common case.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // On-device OCR. Both models are bundled into the APK, so recognition works with no network
    // and no Play Services dependency.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.devanagari)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
