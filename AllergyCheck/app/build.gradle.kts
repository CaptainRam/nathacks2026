plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.allergycheck"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.allergycheck"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.fragment)
    implementation(libs.recyclerview)
    implementation(libs.material)
    implementation(libs.mlkit.text.recognition)
    // On-device LLM (Gemma) that reads the OCR text and lists the ingredients.
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    testImplementation(libs.junit)
    // org.json ships in the Android framework, so the app needs no dependency for it. On the JVM
    // unit-test classpath it is only a stub, so the real implementation is added for tests only.
    testImplementation(libs.json)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}