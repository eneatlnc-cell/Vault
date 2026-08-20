# Vault — 离线私钥保险箱 (App B)

物理隔离的密钥安全存储与签名服务。本仓库仅包含 Vault 自身代码与共享基础库；
聊天端应用见 **Engine 仓库** (`com.engine`)。

> **定位声明 (v3.17 明确)**: Vault 是通用离线保险箱, 不是 Engine 的附属组件。
> 它以 "来源应用包名" 为主键管理多把应用专属密钥 —— Engine 只是第一个接入方,
> 后续任何需要离线密钥保管 + 生物识别签名门禁的应用 (钱包/邮箱/工作台等)
> 都可经同一 IPC 契约接入, 各应用密钥相互隔离、可独立替换/删除。
> "单一 Engine 保险箱" 的理解已过时。

> v2 安全架构: Vault 永不联网 (Manifest 无任何网络权限),
> 密钥对由 Engine 生成, 经二维码光学通道或受保护的 Intent Extra 单向导入,
> 此后一切身份签名 (中继挑战应答 / ECDH 信令 / IPC 回调) 均由 Vault 完成。

## 项目结构

```
vault/
├── settings.gradle.kts              # 多模块注册
├── build.gradle.kts                 # 根构建配置
├── gradle/libs.versions.toml        # 统一版本目录
│
├── core/                            # 共享基础库层 (与 Engine 仓库保持字节一致)
│   ├── core-crypto/                 # ECDH/ECDSA/AES-GCM 密码学工具 (纯 Kotlin/JVM)
│   └── core-ipc/                    # URI Scheme IPC 契约 (v2: 签名回调 + 显式包名)
│
└── app-b/                           # Vault: 离线保险箱 (无 INTERNET, Keystore 存储)
```

## v2 安全模型摘要

| 项 | 说明 |
|----|------|
| 网络隔离 | Manifest 零网络权限, 物理断网; 零第三方 SDK |
| IPC 入口保护 | import/verify/sign 三个入口全部拆分为独立 Activity, 受 `com.vault.permission.VAULT_IPC` (signature 级) 保护, 仅同证书的 Engine 可唤起; 无 BROWSABLE, 网页不可达 |
| 签名回调 | 所有回调用绑定私钥对 (sessionId‖status‖ts) 签名后显式投递给 `com.engine` |
| 私钥存储 | Android Keystore (TEE) AES-256-GCM 加密 + EncryptedSharedPreferences 双层加密, 导入即替换 (单活动密钥) |
| 内存卫生 | 签名后明文零字节覆写; 全局 FLAG_SECURE 防截屏 |
| 签名服务 | `SignActivity`: 指纹验证 (BIOMETRIC_STRONG) 后对 Engine 载荷做 ECDSA P-256 签名, 载荷上限 4KB |

## IPC 契约 (myvault://)

| 入口 | 用途 |
|------|------|
| `myvault://import?session=..` (+ Extra payload) | 应用移交密钥对, Vault 加密导入 |
| `myvault://verify?session=..` | 应用登录验证 (BiometricPrompt) |
| `myvault://sign?session=..` (+ Extra payload) | 请求身份签名 (中继挑战 / ECDH 信令) |
| `myvault://callback?..&ts=..` (+ Extra sig/result) | Vault → 发起应用的签名回调 |

完整定义见 `core/core-ipc/src/main/kotlin/com/securesocial/core/ipc/IpcContract.kt`。

## 构建与运行

### 前置条件
- JDK 17+
- Android SDK (compileSdk 34, minSdk 26)

```bash
./gradlew :app-b:assembleDebug
```

### 与 Engine 联调注意

1. **签名证书必须一致**: 两 App 的 signature 级权限要求 Engine 与 Vault 使用同一签名证书
   (开发期共用本机 debug keystore; 发布期统一 release 签名后再安装)。
2. **安装顺序**: 先装 Vault 再装 Engine 均可, 但升级任一 App 时需保持证书不变, 否则权限失效。
3. core 模块如需改动, 须同步到 Engine 仓库保持契约一致。

## 安全设计验证

- [x] AndroidManifest 无 INTERNET / ACCESS_NETWORK_STATE 等任何网络权限
- [x] IPC 入口组件全部 permission (signature) 保护, 无 BROWSABLE
- [x] 私钥仅经二维码光学通道或受保护 Intent Extra 传递, URI 永不含私钥
- [x] Keystore (TEE) AES-256-GCM 加密存储, 永不存明文
- [x] 签名后零字节覆写内存明文
- [x] 全局 FLAG_SECURE 防截屏 (Main/Import/Sign/Restore; Verify 因指纹层兼容豁免, 页面无敏感信息)

## v3.17 变更记录

| 项 | 内容 |
|------|------|
| FLAG_SECURE 全覆盖 | SignActivity / RestoreActivity 补防截屏 (展示待签摘要/恢复上下文); VerifyActivity 保留原有指纹层兼容豁免 |
| IPC 日志泄露修复 | sign 请求 payload 与 callback 的 sig/result 移出 URI 改走 Intent Extra, 不再随 ActivityTaskManager 的 START 日志进 logcat (与 Engine 同批, 旧 URI 参数保留解析兼容) |
| 废弃 API 迁移 | `MasterKeys`(旧) → `MasterKey.Builder`; `create(fileName, alias, ..)`(废弃) → `create(context, fileName, masterKey)`。默认别名一致, 老用户密文无缝过渡 |
| 构建可复现 | 补齐真 Gradle Wrapper (8.5), `gradlew` 不再是转发系统 gradle 的假脚本 |
| 版本 | versionName 3.17.0 / versionCode 2 (与 Engine 对齐) |
