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

1. core 模块如需改动, 须同步到 Engine 仓库保持契约一致。
2. 详见下方签名规范 —— 证书不一致是联调失败的最常见根因。

### 应用族签名规范 (与 Engine IPC 互信)

> 硬性工程规范。2026-08 曾因构建机证书不一致导致 "Engine 无法唤起 Vault / 更新即丢身份" 事故, 本节即为防再踩坑而定。

**为什么必须同一证书**: Vault 与 Engine 的全部 IPC 入口由 signature 级自定义权限互锁 (`com.vault.permission.VAULT_IPC` / `com.engine.permission.ENGINE_CALLBACK`), Android 仅向 "与权限声明方同证书" 的应用授予 signature 权限。证书不一致的后果:

1. Engine 跨应用唤起直接 `SecurityException` (表现: 无法唤起 Vault)
2. 无法覆盖安装 → 被迫卸载重装 → Vault 内绑定私钥全清 (私钥不出 Vault、无备份, 身份永久丢失)

**证书一致 ≠ 版本联动**: 权限按证书授予、与版本无关。两 App 更新节奏完全独立 —— Vault 装好可不频繁更新, Engine 任意频率迭代, 只要所有构建产物的**证书**不变, IPC 一直通, Vault 无需伴随升级。

**开发期 (debug)**: 统一各构建机的 debug keystore —— 从一台基准机复制 `~/.android/debug.keystore` 到所有开发机与 CI。debug keystore 密码为公开的 `android`, 本就不是秘密, 属官方设计; 同一台机器构建两 App 则天然一致。

**发布期 (release)**: 全应用族 (Vault + Engine + 未来接入应用) 共用一套 release keystore, 存放于 CI secrets 或团队密码管理器, 绝不提交进仓库。**必须离线备份** (如硬件加密盘): release 证书一旦丢失且无备份, signature 互信无法重建 (Android key rotation 仅支持单应用, 不能跨应用签名互认), 全部用户身份重置 —— 这是比任何功能 bug 都严重的事故。

**与身份密钥的区别**: APK 签名密钥是开发基础设施身份, 不接触用户身份数据、不进 Vault、无 "应用生成密钥对" 语义; 身份密钥 (EC P-256) 由应用生成、Vault 离线保管。两者生命周期与保护方式完全不同, 勿混为一谈。

**身份找回通道**: v3.18.0 起 Vault 提供「迁移」功能 —— 旧设备指纹门后导出绑定二维码, 新设备扫码导入, 私钥全程经光学通道、不触网。换机/重装/证书事故后由此找回身份。

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
