# 轻账（QingZhang）

一款本地优先的 Android 记账应用。账目数据全部保存在设备本地，不依赖服务端；网络权限仅用于应用内更新检查与安装包下载。采用 Kotlin + Jetpack Compose 构建，当前版本 **0.1.8（versionCode 11）**。

- 包名：`com.example.qingzhang`
- 最低 / 目标 SDK：26（Android 8.0）/ 35
- 仓库：https://github.com/termux31/qingzhang

## 功能

| 模块 | 说明 |
| --- | --- |
| 记账 | 手动记录收入与支出，支持分类、金额、时间、商户、备注，可编辑与删除；首页提供快捷记一笔 |
| 账单 | 按日期区间、收支类型、分类、关键词筛选 |
| 统计 | 收支汇总、分类占比与趋势图表 |
| 账单导入 | 导入微信、支付宝官方导出的 CSV / XLSX 账单，自动识别来源与表头别名、推荐分类；导入前逐条勾选复核 |
| 账单导出 | 将当前筛选结果导出为 UTF-8 BOM 编码的 CSV，便于 Excel 直接打开 |
| 预算 | 每日限额与超限提示、周期生活费计划及结算、储蓄目标 |
| 提醒 | 每日记账、超预算、周期结算、目标达成提醒；开机、时间或时区变更后自动重排 |
| 设置 | 分类管理、深浅主题、每日限额、提醒偏好、软件更新 |
| 应用内更新 | 从 HTTPS 版本清单检查更新，下载并校验后调用系统安装器 |

导入环节的约束与行为：单文件不超过 10 MiB、单次最多解析 5000 条账目；自动跳过「不计收支」及支付失败记录；金额或状态存在冲突的条目标记为待复核且不默认勾选。去重优先使用平台交易单号，无单号时以来源、时间、金额、方向、商户、备注生成指纹并附出现序号，最终由 `transactions.importKey` 唯一索引兜底。

## 技术栈

| 项 | 版本 / 选型 |
| --- | --- |
| 语言与构建 | Kotlin 2.0.21、AGP 8.7.3、Gradle 8.9（Wrapper 使用腾讯云镜像）、JDK 17 |
| UI | Jetpack Compose + Material 3（BOM 2024.12.01）、Navigation Compose |
| 本地存储 | Room 2.6.1（KSP）、DataStore Preferences |
| 异步 | Kotlin Coroutines 1.9.0 |
| 测试 | JUnit 4、Compose / AndroidX Test |

金额统一以 `Long` 类型的「分」存储与计算，避免浮点误差。应用配置 `android:allowBackup="false"`，账目不参与系统备份；全新安装不含预置账目，每日限额与提醒默认关闭。

## 环境要求

- JDK 17
- Android SDK Platform 35、Build Tools（发布脚本需要 `aapt2`）
- 首次构建由 Gradle Wrapper 自动下载 Gradle 8.9，无需单独安装

## 构建与安装

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows PowerShell
.\gradlew.bat assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`，可通过 `adb install -r <apk>` 安装。

发布构建（`assembleRelease`）需要自行配置签名。仓库不收录密钥文件（`*.jks`、`*.keystore`、`keystore.properties` 均在 `.gitignore` 中），请另行备份签名密钥。

## 测试

```bash
# 单元测试 + 静态检查
.\gradlew.bat testDebugUnitTest lintDebug

# 插桩测试（需连接设备或启动模拟器）
.\gradlew.bat connectedDebugAndroidTest

# 完整验证
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug connectedDebugAndroidTest
```

## 应用内更新

更新源可在「我的 → 软件更新」中填写，或在构建时通过 Gradle 属性注入默认值：

```bash
.\gradlew.bat assembleDebug -Pqingzhang.updateManifestUrl=https://example.com/qingzhang/latest.json
```

清单地址必须为 HTTPS；仅 debug 构建允许 `localhost`、`127.0.0.1`、`10.0.2.2` 的 HTTP 地址用于本地联调。APK 大小上限 100 MiB，清单不超过 64 KiB。

`latest.json` 示例：

```json
{
  "packageName": "com.example.qingzhang",
  "versionCode": 12,
  "versionName": "0.1.9",
  "apkUrl": "https://example.com/qingzhang/qingzhang-0.1.9.apk",
  "sha256": "<64 位小写十六进制摘要>",
  "size": 12345678,
  "releaseNotes": "修复账单导入问题",
  "force": false
}
```

客户端在安装前会校验：包名一致、`versionCode` 与 APK 实际值一致且高于已安装版本、文件实际大小与 SHA-256 摘要匹配、待安装 APK 与已安装应用的签名证书一致。任一校验失败即中止安装。最终安装由系统安装器完成，需要用户授予「安装未知应用」权限。

发布脚本会根据 APK 自动生成 `update-server/public/latest.json` 与规范化命名的安装包：

```powershell
.\update-server\publish-update.ps1 `
  -ApkPath .\app\build\outputs\apk\debug\app-debug.apk `
  -PublicBaseUrl https://example.com/qingzhang `
  -VersionName 0.1.8 -VersionCode 11 `
  -ReleaseNotes "说明文本"
```

```bash
./update-server/publish-update.sh <apk> <public-base-url> <version-name> <version-code> [release-notes]
```

脚本会用 `aapt2 dump badging` 核对 APK 的包名、版本名与版本号是否与参数一致，并要求 `PublicBaseUrl` 为不含凭据、查询串与片段的 HTTPS 地址。将 `public/` 目录内容部署到静态站点即可。

> **发布约束**：后续版本必须保持包名与签名不变，且 `versionCode` 严格递增，否则已安装用户无法覆盖升级。当前分发的 APK 为 debug 签名，正式发布前需完成签名迁移并在实体机上做覆盖安装回归。`update-server/public/` 与所有 `*.apk` 均不纳入版本控制。

## 目录结构

```
app/src/main/java/com/example/qingzhang/
├── data/           Room 实体、DAO、Repository、默认分类与设置
├── domain/         金额与日期工具、统计、CSV 导出、生活费预算
├── importer/       CSV / XLSX 解析与账单导入
├── ui/             Compose 页面、组件、主题与 ViewModel
├── notifications/  提醒规则、调度与广播接收
└── update/         版本清单解析、下载校验与安装
update-server/      版本清单与 APK 发布脚本（PowerShell / Bash）
```

`.gitignore` 排除了构建产物（`**/build/`）、APK、签名材料、`local.properties` 及本地工作资料，仓库仅保留源码与构建配置。
