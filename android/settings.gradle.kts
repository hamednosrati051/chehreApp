pluginManagement {
    repositories {
        // مخزن لوکال (fetch-deps.py): چون سندباکس VM جلوی TCP مستقیم
        // Gradle را می‌گیرد، وابستگی‌ها با curl دانلود و اینجا گذاشته
        // شده‌اند؛ بیلد با --offline فقط از همین مخزن می‌خواند.
        maven { url = uri("local-repo") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("local-repo") }
        google()
        mavenCentral()
        // v13: CafeBazaarAuth (JitPack). Artifacts are pre-fetched into
        // local-repo by fetch-deps.py; the repo declaration is needed so
        // Gradle can resolve the module metadata offline.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "chehreApp"
include(":app")
