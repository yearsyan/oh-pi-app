# 发布与签名

推送 `v<major.minor.patch>` tag 会触发 `.github/workflows/release.yml`。发布流程构建 Android APK、Windows x64 桌面 ZIP、Linux / Windows 的 amd64 与 arm64 网关，以及经过 Developer ID 签名和 Apple notarization 的 macOS 网关。

`.github/workflows/testflight.yml` 不随 tag 自动触发，只能在 GitHub UI、`gh workflow run` 或 API 手工运行：在 GitHub macOS runner 上归档 iOS App，并使用专用 Apple Distribution 证书与 App Store provisioning profile 签名后上传到 TestFlight。版本号留空时默认取最近一个 `v*` tag；也可显式填写 `major.minor.patch`，在正式打 tag 前验证上传链路。

## Windows 桌面产物

GitHub 的 `windows-latest` runner 使用 Compose Multiplatform 的 `createDistributable` 任务生成带裁剪 JRE、Windows 原生 SSH DLL 和应用图标的 app-image，再发布为 `oh-pi-app-<version>-windows-x64.zip`。ZIP 是免安装便携包，不构建或发布 MSI；解压后运行目录内的 `io.github.yearsyan.ohpi.exe`。

## macOS 产物

Darwin 二进制必须在 GitHub 的 macOS runner 上构建。CI 使用 `Developer ID Application` 证书为两个架构分别执行带 Hardened Runtime 和可信时间戳的签名，再将它们放入 ZIP 并通过 `xcrun notarytool` 提交 Apple 公证。

相关平台要求见 Apple 的 [Developer ID 证书说明](https://developer.apple.com/help/account/certificates/create-developer-id-certificates/)与[自定义公证流程](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow)，Secret 存储方式见 GitHub 的 [Actions Secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

Release 同时包含：

- `ohpi-gateway-<version>-darwin-amd64` 与 `ohpi-gateway-<version>-darwin-arm64`：已签名的裸二进制，供 SSH 自动安装按架构下载；
- `ohpi-gateway-<version>-darwin.zip`：包含两个已签名二进制并已提交 Apple 公证，供直接下载或人工分发。

命令行可执行文件不能像 `.app`、`.pkg` 或 `.dmg` 那样附加 stapled ticket。公证成功后 Apple 会在线发布与代码签名对应的 ticket；因此 CI 会在公证后用 `codesign --check-notarization` 强制在线查询并验证两个裸二进制。`spctl --type execute` 会把裸 CLI 判为“代码有效但不是 app”，不适合作为这里的验收命令。

## GitHub Actions Secrets

仓库必须配置以下 Secrets，缺少任意一项都会终止发布，workflow 不会退化为未签名 macOS 产物：

| Secret | 内容 |
|---|---|
| `MACOS_CERTIFICATE_P12_BASE64` | 含私钥、Developer ID Application 证书及其 Apple Developer ID G2 中间证书的 `.p12` 文件，单行 Base64 |
| `MACOS_CERTIFICATE_PASSWORD` | 导出 `.p12` 时设置的强密码 |
| `APPLE_NOTARY_KEY_P8_BASE64` | App Store Connect Team API Key `.p8` 文件，单行 Base64 |
| `APPLE_NOTARY_KEY_ID` | API Key 的 Key ID |
| `APPLE_NOTARY_ISSUER_ID` | App Store Connect Team API 的 Issuer ID |
| `IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64` | 含专用 Apple Distribution 私钥、证书和 WWDR 中间证书的 `.p12` 文件，单行 Base64 |
| `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD` | 导出 iOS `.p12` 时设置的强密码 |
| `IOS_APP_STORE_PROFILE_BASE64` | 绑定 OhPiApp Bundle ID 与专用证书的 App Store provisioning profile，单行 Base64 |

macOS certificate Secret 必须包含 `Developer ID Application` identity，不能使用 Apple Development、Mac Distribution 或 ad-hoc identity；iOS certificate Secret 必须包含与 profile 匹配的 `Apple Distribution` identity。公证与上传 Key 建议使用独立的 Team API Key，并限制为完成发布所需的最低角色。

## iOS 与 TestFlight

iOS App Store Connect 记录使用以下固定身份：

- App 名称：`OhPiApp`
- Bundle ID：`io.github.yearsyan.ohpi.OhPiApp`
- Apple Team ID：`2XX5KZ6X3G`

TestFlight workflow 使用解析出的版本（手工输入或最近 `v*` tag）作为 `MARKETING_VERSION`，使用 `<workflow run number>.<run attempt>` 作为唯一的 `CURRENT_PROJECT_VERSION`。

归档阶段关闭代码签名；上传阶段在临时 keychain 中导入专用 Apple Distribution `.p12`，安装仅绑定 OhPiApp 的 App Store provisioning profile，再由 Xcode 手工签名并使用 App Store Connect API Key 上传。API Key 保持 Developer 角色即可，不需要给 CI Admin 权限。临时 keychain、证书、profile 和 API Key 在成功或失败后都会删除；缺少任一 Secret 时 workflow 会直接失败。

`app/iosApp/iosApp/Info.plist` 已声明 `ITSAppUsesNonExemptEncryption=false`，`app/iosApp/iosApp/PrivacyInfo.xcprivacy` 包含当前 required-reason API 声明。上传成功只代表 Apple 接受交付；构建仍需经过 App Store Connect 后台处理才会出现在 TestFlight。

在可信 Mac 上准备 Secrets 时，可使用：

```bash
base64 < DeveloperIDApplication.p12 | tr -d '\n' | gh secret set MACOS_CERTIFICATE_P12_BASE64
gh secret set MACOS_CERTIFICATE_PASSWORD
base64 < AuthKey_XXXXXXXXXX.p8 | tr -d '\n' | gh secret set APPLE_NOTARY_KEY_P8_BASE64
gh secret set APPLE_NOTARY_KEY_ID
gh secret set APPLE_NOTARY_ISSUER_ID
base64 < AppleDistribution.p12 | tr -d '\n' | gh secret set IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64
gh secret set IOS_DISTRIBUTION_CERTIFICATE_PASSWORD
base64 < OhPiApp.mobileprovision | tr -d '\n' | gh secret set IOS_APP_STORE_PROFILE_BASE64
```

`.p12`、`.p8`、`.mobileprovision`、密码和临时 keychain 不得提交到 Git。CI 脚本只在单个 step 内解码这些文件，结束或失败时都会删除临时证书、profile、API Key 和 keychain。

## 轮换与恢复

- `.p8` 只能在 App Store Connect 创建后下载一次。安全保存离线备份；如果丢失，应撤销旧 Key 并创建新 Key。
- Developer ID 私钥无法从 Apple 重新下载。安全保存 `.p12` 和密码；证书到期时创建新证书并更新两个 certificate Secrets。
- Apple Distribution 私钥同样无法从 Apple 重新下载。轮换证书时必须重新生成绑定新证书的 App Store provisioning profile，并同时更新三个 iOS signing Secrets。
- 不要为日常调试撤销仍在发布使用的 Developer ID 证书。证书被撤销后，已经用它签名的软件也可能无法通过 Gatekeeper。
- Secret 轮换后先用一个新的预发布 tag 验证签名、公证、校验和与 Release 附件，再发布正式版本。
