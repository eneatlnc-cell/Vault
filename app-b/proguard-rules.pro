# App B (Vault 离线安全保险箱) ProGuard 规则
#
# 关键约束:
# - 本应用绝不申请 CAMERA / INTERNET 权限, 不含任何第三方分析/埋点 SDK。
# - 扫码通过系统相机 Intent 拍照 + ZXing 本地解码, 无需 CameraX / ML Kit。
# - release 构建当前未开启 minify (isMinifyEnabled = false), 此文件预留扩展位。
#
# ZXing core 仅含纯 Java 解码逻辑, 无需额外 ProGuard 规则。
