plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.peide.supsub.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // V2 改用平台原生 SQLite（ArchiveDb/ArchiveStore），不再需要 Room + ksp；
    // SAF 也只剩「导出备份」一处，直接用 DocumentsContract，无需 documentfile。
    implementation(libs.datastore.preferences)
}
