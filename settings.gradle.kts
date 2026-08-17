pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vault"

// Vault 仓库范围：
// - app-b          : Vault 离线签名服务应用（保管私钥，对已授权应用提供 ECDSA 签名；无任何网络权限）
// - core:core-crypto : 密码学原语（与 Engine 仓库的 core-crypto 保持同步）
// - core:core-ipc    : IPC 契约（与 Engine 仓库的 core-ipc 保持同步）
// 注意：Vault 不联网，不包含 core-protocol 与 relay-server（它们归属 Engine 仓库）。
include(":core:core-crypto")
include(":core:core-ipc")
include(":app-b")
