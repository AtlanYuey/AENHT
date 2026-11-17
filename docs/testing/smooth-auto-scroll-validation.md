# 自动平滑滚动：构建、测试与签名交付记录

日期：2026-11-17。开发分支 `dev`，基线为 `main@f313bafe4bafe5670be0535053acf97902700b2b`；没有把功能合并到 `main`，没有发布正式版本。

## 对应提交

- 功能实现：`bded65a`。
- 长图预加载、控件布局、暂停和末页检查：`66ef90a`。
- 本次 IPA 的完整运行代码：`f73115e743f3ce8cf74a7d151ae99361ac48cd53`。
- 追加失败恢复测试：`290b8913b65d990e58097bc256971f79db7301e3`。与 IPA 编译提交相比，只修改 Android 测试和说明文档，没有修改 iOS 或 Android 运行代码。

## 自动验证结果

| 项目 | 结果 |
| --- | --- |
| 共享 JVM 测试 | 88 项通过，0 失败、0 跳过 |
| Android 单元及界面测试 | 64 项通过，0 失败、0 跳过 |
| 合计 | **152 项通过** |
| Android Lint | 0 错误、51 条警告；这不代表整个项目没有其它问题 |
| Android Debug APK | 构建成功 |
| iOS Release / iphoneos | macOS 26 + Xcode 26 编译成功 |

验证命令：

```sh
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug -Pkotlin.incremental=false --continue
```

新增失败恢复用例后，另外重跑完整的 `:androidApp:testDebugUnitTest :androidApp:lintDebug`。界面用例实际运行 ReaderScreen、Coil 和 Compose 帧时钟，使用合成图片，不请求真实作品。

远端构建证据：

- [iOS 编译任务](https://github.com/AtlanYuey/AENHT/actions/runs/44084058486)，提交 `f73115e`，结果成功。
- [Android 构建任务](https://github.com/AtlanYuey/AENHT/actions/runs/44084058421)，相同运行代码，结果成功。
- [追加测试的 Android 任务](https://github.com/AtlanYuey/AENHT/actions/runs/44085142357)，含失败后暂停与重试场景；本地对应的完整 64 项已通过，远端结果可在该链接查看。

## 签名包

文件：`AENHT-dev-f73115e-signed.ipa`，9,055,222 字节。版本号沿用开发分支的 **0.2.0（2）**，请用文件名的提交编号识别这一测试包。

```text
SHA-256: e5cb2cb8a89de02dd244246d7774106c080d5a777a70c4f55c917b5005974fe6
Bundle ID: com.atlanyuey.ENHentai
最低系统: iOS / iPadOS 18.0
```

从上述 iOS 任务下载的未签名构建产物（artifact `14329875375`）进行本地签名，签名材料为本次用户提供的 P12 和描述文件。密钥、密码、描述文件不提交到 Git，也不上传到构建任务；交付包内嵌入描述文件是安装所必需的。

交付前已检查：

- P12 的私钥与描述文件允许的签名证书匹配；包内描述文件与用户提供文件完全一致。
- 描述文件 CMS 签名和 Apple 证书链有效；App 的 CMS 签名及 Apple 证书链有效。
- 验证 6,111 个可执行文件代码页哈希、28 个密封资源，以及 Info.plist、XML/DER 权限等签名槽位。
- 按 App 实际使用的功能配置签名权限，保留 iCloud **键值设置同步**，将描述文件允许的通配标识具体化到本 App。没有为未使用的 CloudKit / iCloud Drive 容器请求额外权限。
- 二进制包含新增自动滚动设置与界面，包中没有 P12、PEM 或私钥文件。
- 校验器的负例检查能拒绝一份修改了密封资源的测试 IPA。

开发描述文件登记了 **3 台设备**。需要开发者模式，并使用保留包内签名的安装方式。证书到期时间为 `2027-03-23T13:06:32+00:00`，描述文件到期时间为 `2027-11-14T00:02:14`。

## 实机验收范围

这个环境没有连接 iPhone 或 iPad。上述结果是代码自动测试、真实 macOS 编译、签名与包完整性验证；**没有声称完成实机安装、滚动流畅度或 iCloud 联网同步验收**。

入口、已知长图解码限制和详细实机项目见 [功能与实机测试说明](smooth-auto-scroll.md)。请重点测试长条漫画、末页长图、慢网重试、手动拖动后续读、后台返回及横竖屏/分屏。
