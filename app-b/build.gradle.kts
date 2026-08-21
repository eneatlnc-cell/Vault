plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// 签名说明 (v3.18.0, 共享签名机制已撤销 —— 违背项目密钥原则):
// 两 App 的 IPC 入口由 signature 级权限互锁, 签名证书必须一致。
// 开发期: Engine 与 Vault 在同一台机器构建 (共用 ~/.android/debug.keystore),
// 签名天然一致, 无需任何配置。
// 换机/重装导致的身份找回: 使用本应用的「迁移」功能
// (指纹门 + 二维码光学通道转移绑定, 私钥全程不离用户授权)。

android {
    namespace = "com.vault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vault"
        minSdk = 26
        targetSdk = 34
        // v3.18.0: 绑定迁移 (换机/重装后经二维码光学通道转移身份, 私钥不出用户授权)
        versionCode = 4
        versionName = "3.18.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Kotlin 1.9.22 <-> Compose Compiler 1.5.8
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 共享模块
    implementation(project(":core:core-crypto"))
    implementation(project(":core:core-ipc"))

    // AndroidX Core / Lifecycle / Activity / Navigation
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Jetpack Compose (BOM 管理版本)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 安全存储: EncryptedSharedPreferences (Master key 由 AndroidKeystore 保护)
    implementation(libs.androidx.security.crypto)

    // 生物识别: VerifyActivity 指纹验证 (FragmentActivity 宿主)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // ZXing: 二维码解码 (拍照后从图片解码 QR, 纯本地无网络)
    implementation(libs.zxing.core)

    // 协程
    implementation(libs.kotlinx.coroutines.android)
    // 序列化运行时 (供共享模块 KeyPayloadSerializer 的 Json 解析使用)
    implementation(libs.kotlinx.serialization.json)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
