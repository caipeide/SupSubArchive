plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.peide.supsub.notionsync"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // api 而非 implementation：同步引擎的公开签名里直接出现 ArchiveStore / ArticleRecord，
    // 用 implementation 的话 app 模块拿不到这些类型，构造引擎时会编译不过。
    api(project(":core-data"))
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coroutines.core)
}
