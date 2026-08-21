import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// ---- v3.17.1: 可选共享签名 (Engine 与 Vault 必须同证书) ------------------
//
// 根因背景: 两 App 的全部 IPC 入口由 signature 级自定义权限互锁
// (com.vault.permission.VAULT_IPC / com.engine.permission.ENGINE_CALLBACK),
// 签名证书不一致时: ①Engine 唤起 Vault 直接 SecurityException;
// ②Vault 向 Engine 投递回调同样失败; ③无法覆盖安装 → 更新必须
// 卸载重装 → Vault 内绑定私钥全清 (身份永久丢失, 私钥不出 Vault 无备份)。
//
// 机制: 仓库根目录放一份 signing.properties (已被 .gitignore 排除,
// 密钥绝不入库), 与 Engine 工程填同一份密钥库信息 → 任何机器的
// 构建产物签名一致。未配置时回退构建机默认 debug keystore ——
// 此时两应用必须在同一台机器构建才能同签名。
//
// 配置方法见 signing.properties.example。
val signingPropsFile = rootProject.file("signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) {
        signingPropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.vault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vault"
        minSdk = 26
        targetSdk = 34
        // v3.17.1: 回调投递失败原因可见化 + 可选共享签名机制 (与 Engine v3.23.3 同批)
        versionCode = 3
        versionName = "3.17.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (signingPropsFile.exists()) {
            create("sharedIpc") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("sharedIpc")
            }
        }
        release {
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("sharedIpc")
            }
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
