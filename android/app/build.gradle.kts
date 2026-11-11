plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "ir.inourai.newapp"
    compileSdk = 34

    defaultConfig {
        // پکیج موقت؛ قبل از اولین آپلود بازار نهایی شود.
        applicationId = "ir.inourai.newapp"
        minSdk = 26
        targetSdk = 34
        // v14: دکمه‌ی ورود با بازار (جمله‌ی هدیه) + اعلان گالری در «تصاویر من».
        versionCode = 14
        versionName = "1.4"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../../chehre-release.keystore")
            storePassword = (project.findProperty("CHEHRE_STORE_PASSWORD") as String?) ?: System.getenv("CHEHRE_STORE_PASSWORD")
            keyAlias = "chehresaz"
            keyPassword = (project.findProperty("CHEHRE_KEY_PASSWORD") as String?) ?: System.getenv("CHEHRE_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    lint {
        // lintVital نسخه‌ی انتشاراتی برای دانلود ابزار lint به شبکه نیاز دارد؛ در بیلد آفلاین می‌شکند. این فقط بررسی ایستاست و روی خروجی اثری ندارد.
        checkReleaseBuilds = false
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
        // v13: CafeBazaarAuth 1.1.0 با متادیتای Kotlin 2.2 کامپایل شده
        // ولی پروژه روی Kotlin 1.9.24 است؛ بدون این پرچم، کامپایلر
        // همه‌ی ارجاع‌ها به کتابخانه را خطا می‌دهد. پرچم فقط بررسی
        // نسخه‌ی متادیتا را رد می‌کند (API کتابخانه ساده است)؛ جایگزین
        // آن ارتقای Kotlin به 2.2 است که تغییر زنجیره‌ی ابزار است.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

// v13: CafeBazaarAuth 1.1.0 وابستگی انتقالی androidx.core:core-ktx:1.17.0
// می‌آورد که compileSdk 36 و AGP 8.9.1 می‌خواهد؛ پروژه روی compileSdk 34
// و AGP 8.5.2 است. پس core را روی 1.13.1 (سازگار با 34) پین می‌کنیم.
// کتابخانه‌ی بازار فقط APIهای پایه‌ی core را استفاده می‌کند.
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.13.1")
        force("androidx.core:core-ktx:1.13.1")
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

    implementation("com.google.firebase:firebase-messaging:24.0.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("com.airbnb.android:lottie-compose:6.5.0")

    // v13: Bazaar native login (CafeBazaarAuth via JitPack).
    implementation("com.github.cafebazaar.CafeBazaarAuth:auth:1.1.0")

    // پین lifecycle روی 2.8.3 — فیکس کرش لانچ:
    // navigation-compose 2.8.0 در NavHost از viewModel()‎ با CreationExtras
    // استفاده می‌کند (از 2.7.0 اضافه شده) ولی resolution نسخه‌ی 2.6.2 را
    // می‌آورد که آن overload را ندارد → NoSuchMethodError موقع باز شدن اپ.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.3")

    // تست لانچ با Robolectric (بدون نیاز به گوشی/امیولاتور):
    // MainActivity را در JVM بالا می‌آورد تا کرش موقع باز شدن بازتولید شود.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("junit:junit:4.13.2")
}

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    // ورکر تست JVM جداست و Robolectric حافظه‌ی زیادی می‌خواهد؛
    // هیپ پیش‌فرض (۵۱۲ مگ) ورکر را می‌کشد.
    maxHeapSize = "2g"
}

// موقت برای اجرای دستی تست بیرون از ورکر Gradle (ورکر در این VM می‌میرد):
// classpath کامل تست را در فایل می‌نویسد.
tasks.register("printTestCp") {
    doLast {
        val cp = configurations["debugUnitTestRuntimeClasspath"].files
            .joinToString(":")
        file("build/test-cp.txt").writeText(cp)
        println("wrote build/test-cp.txt (${configurations["debugUnitTestRuntimeClasspath"].files.size} files)")
    }
}
