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
    implementation(libs.material)
    implementation(libs.mlkit.text.recognition)
    // On-device LLM (Gemma) that reads the OCR text and lists the ingredients.
    implementation(libs.mediapipe.tasks.genai)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}