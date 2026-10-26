plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.inourai.newapp"
    compileSdk = 34

    defaultConfig {
        // پکیج موقت؛ قبل از اولین آپلود بازار نهایی شود.
        applicationId = "ir.inourai.newapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
    // jarهای common مولتی‌پلتفرم (بدون متادیتای ‎.module) روی classpath
    // اندروید می‌آیند و فایل‌های متادیتای تکراری دارند؛ این متادیتا در
    // ران‌تایم اندروید خوانده نمی‌شود (نسخه‌ی jvm/android همان کتابخانه
    // هم در classpath هست).
    packaging {
        resources {
            excludes += "commonMain/**"
            excludes += "nonJvmMain/**"
            excludes += "nativeMain/**"
            excludes += "linuxMain/**"
            excludes += "zlibMain/**"
            excludes += "unixMain/**"
            excludes += "systemFileSystemMain/**"
            excludes += "nonAppleMain/**"
            excludes += "hashFunctions/**"
            excludes += "ios*/**"
            excludes += "js/**"
            excludes += "wasmJs/**"
            excludes += "mingw*/**"
            excludes += "linux*/**"
            excludes += "macos*/**"
            excludes += "tvos*/**"
            excludes += "watchos*/**"
            excludes += "androidNative*/**"
            // متادیتای ساخت KMP که در همه‌ی jarهای common یکسان است.
            excludes += "META-INF/kotlin-project-structure-metadata.json"
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // نسخه‌ی اندرویدی آیکون‌ها؛ common فقط jar خالی است و بدون
    // متادیتای Gradle (‎.module) به -android نمی‌رسد.
    implementation("androidx.compose.material:material-icons-extended-android")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // اسپلش اول اپ (انیمیشن Lottie با نهایی‌شدن برند اضافه می‌شود).
    implementation("com.airbnb.android:lottie-compose:6.5.0")
}
