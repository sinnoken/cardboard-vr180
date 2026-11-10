import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)   // AGP 9：Kotlin 已內建，不再套用 kotlin-android
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.vr180"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.vr180"
        minSdk = 31          // Android 12+；能硬解 8K 的手機都在這之上
        targetSdk = 37
        // CI 依 git tag 傳入（規則見 docs/development.md）；本機建置沒有傳就用開發版號
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.0.0-dev"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            // smbj 依賴的 BouncyCastle 會帶進重複的 META-INF 檔案
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
    // Media3 的 DataSource、解碼器選擇器屬於 @UnstableApi
    lint { disable += "UnsafeOptInUsageError" }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.media3.exoplayer)
    implementation(libs.smbj)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
