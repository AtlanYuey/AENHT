# 发版前问题修复：交接与审核说明

本分支供另一位开发者审阅，**没有合入main/dev，没有发布软件**。

- 产品基线：`19c358ce15a68e8be938800e60c86b83216dea11`（0.2.0/build2）。
- 修复代码提交：`0702f19ec1b092666f25939cca37b831e4381f11`，直接以该main提交为父提交。
- 修复分支：`fix/pre-release-main-19c358c`。
- 完整原始报告：[audit/full-main-19c358c](https://github.com/AtlanYuey/AENHT/tree/audit/full-main-19c358c/docs/audits/main-19c358c)，报告提交`b62af88`。报告中的失败结果属于修复前基线，保留不覆盖。

**本地138项测试通过，Android构建及Lint通过；本次iOS修复尚未在Mac编译、运行测试或做实机验收。** 主分支19c358c的iOS CI成功记录只证明原始基线构建成功，不能拿来证明本修复分支通过。

## 建议审核顺序

先看[10项修复状态CSV](fix-status.csv)，再看[代码差异](https://github.com/AtlanYuey/AENHT/compare/19c358ce15a68e8be938800e60c86b83216dea11...fix/pre-release-main-19c358c)。[78个产品/测试变更文件清单](evidence/changed-files.csv)包含前后hash及改动规模；[验证输入清单](evidence/validated-product-files.csv)覆盖358个产品文件。报告与修复代码在两个独立分支，审查报告提交不需要随代码合入。

本批针对报告的M001–M010写修复。原报告35个行动项还包括设备验证、未实现的模式能力、无障碍/对比、受审golden进CI、依赖锁和维护建议；它们没有被全部标成完成。用户接受的底栏三段式、首页两行推荐、翻译软上限、首版整份设置同步、语言“稍后”和Android最低29的取舍继续保留。

## 逐项修复及审核重点

| 问题 | 本次改动 | 当前验证/审核重点 |
| --- | --- | --- |
| M001 会话A→B→A | `TokenSnapshot`把access、refresh、revision作为锁内快照；显式替换/退出使旧快照失效；续期保持同一会话代次；429重放不切成别的身份；更新账号书使用本次续期的refresh，避免锁释放后读到B | 8个受控认证用例通过；Android平台对接已编译；Swift协议对接需Mac。候选校验与同会话续期交错时，当前保守要求完整快照匹配，可能返回未保存让用户重试 |
| M002 AVIF误计完成 | 去掉SDK低于31直接视为有效的捷径，要求系统读到正尺寸；解码失败的AVIF在10/11保持失败，不计完成，下载页说明原因 | API29/30/31/36合成坏图拒绝用例通过。没有新增AVIF解码器，真实有效AVIF跨设备能力仍需测 |
| M003 删除半提交 | history delete/clear与tombstone同Room写事务；favorites removed同原则；Android等待成功再移除原条目，保留期间新读；SwiftData在共享删除成功后处理本地，保存失败可见并回滚本次删除 | 实际Room删除/清空/收藏删除拒写回滚3用例通过；SwiftData与shared是两个存储，桥接/进程中断仍需Mac/实机，未声称跨两个数据库原子化 |
| M004 连续Retry | RemoteImage增加正文按需重试，每次attempt重建加载；仅连续正文启用，保持原scroll目标/页号 | Swift源码已落实，iPhone首次/再次失败后恢复仍待验 |
| M005 Keychain混合凭据 | 每站点以一个JSON记录提交完整tuple；区分不存在/不可读取；旧键在成功写新记录后清理；退出写权威空记录防旧键复活；退出失败保留登录、显示错误 | 8项注入存储XCTest已附，未运行。优先审核迁移、清空、不可读取与实际Keychain错误 |
| M006 标题缓存 | key含UID、实际文本、宽度和字体大小，4096条上限；已存在卡片内容变化时使布局失效并重算frame | Swift源码已落实，短长标题往返和锚点/大字需UIKit运行 |
| M007 上传假成功 | 正式及dev-latest附件重试耗尽均显式exit1，成功才继续 | 12个提取真实YAML的fake-gh控制全部符合预期；没有执行真实上传/发布 |
| M008 随机静默 | 显示在途状态和失败/重试；保存请求UUID，离开/切站取消，迟到响应失效 | Swift源码已落实，iOS成功/失败/取消/重复点击待验 |
| M009 标题重叠 | NavBar测量左右工具，用剩余区域约束标题，在可用范围内尽量居中 | 360/393dp标题与Select边界不相交测试通过；当前图已回看；长语言/大字/RTL仍需扩展验收 |
| M010 取消后落盘 | run所有权随协程传递、每次独立part、锁内检查提交；取消传到OkHttp；旧删除目录移到独立回收路径；先登记Job再启动，立即失败可再次恢复；元数据await后检查run | 取消旧worker不覆盖新PNG，以及立即元数据失败后再次恢复2用例通过；真实暂停/重建/磁盘失败仍待设备 |

源码位置：[共享认证](../../../shared/src/commonMain/kotlin/com/atlanyuey/aenht/shared/nh/NhApi.kt)、[Android认证](../../../androidApp/src/main/java/com/atlanyuey/aenht/core/NHClient.kt)、[下载/历史](../../../androidApp/src/main/java/com/atlanyuey/aenht/core/Stores.kt)、[Room历史](../../../shared/src/commonMain/kotlin/com/atlanyuey/aenht/shared/db/HistoryRepository.kt)、[iOS凭据](../../../iosApp/AENHT/Core/AuthStore.swift)、[单记录存储](../../../iosApp/AENHT/Core/AtomicCredentials.swift)、[列表](../../../iosApp/AENHT/UI/GalleryList.swift)、[导航栏](../../../androidApp/src/main/java/com/atlanyuey/aenht/ui/components/Scaffold.kt)。

## 自动验证

最终JVM80、Android58，合计**138项，0失败、0错误、0跳过**。Android58包含原38个截图方法、4个图片方法及补充行为/SDK实例；并非把每张截图当独立通过用例。共享包含真实Room故障注入、原认证反例和3个额外会话交错、原同步/链接/备注反例以及正常收藏/历史/Spreads路径。

Android `assembleDebug` 成功；`lintDebug`为0 Error、49 Warning、9 Hint。警告尚未全部消除，分类仍可参考原报告。本次没有为了绿色结果跳过反例或降低断言。

```sh
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest \
  :androidApp:lintDebug :androidApp:assembleDebug -Pkotlin.incremental=false --continue
python3 docs/fixes/main-19c358c/evidence/run-release-controls.py
python3 docs/fixes/main-19c358c/evidence/run-static-checks.py
```

审查辅助已迁入正式测试源集，无需旧报告的opt-in init script。原报告的init script只用于原始基线，不应在此分支重复注入同名测试。

证据：[JVM汇总](evidence/shared/summary.json)/[日志](evidence/shared/gradle.log)、[Android汇总](evidence/android/summary.json)/[日志](evidence/android/gradle.log)、[Lint XML](evidence/lint-results-debug.xml)、[上传12控制](evidence/release-controls/results.json)、[资源/模型检查](evidence/static-results.json)、[变更后下载列表](evidence/screenshots/32_downloads_list.png)。关键截图9张保留在[evidence/screenshots](evidence/screenshots/)，测试构建目录另产出全部52图。

首次合并运行曾出现3个Android用例失败和3个测试Lint错误，记录保留在[initial-combined-run](evidence/initial-combined-run/)。原因是取消测试修改了共享Net客户端，以及3处把mutableState放在composition里创建；随后改为给下载worker注入独立客户端、把测试状态移出composition。原来的点击/旋转/失败反馈断言保留，整套再跑通过。未把测试环境干扰写成产品新bug。

资源生成在临时副本重跑，29输出一致；20个产品Python、2个JS及JSON/XML/SVG/YAML/shell语法检查通过；3个高质量模型hash匹配。它们不验证模型推理、自然翻译质量或iOS运行。

## Mac上应先执行的工作

`TokenStorage`的桥接接口有变化，新增Swift源文件与独立凭据测试target。请重新生成Xcode项目并重建Shared framework，不使用旧框架只替换Swift文件。

```sh
cd iosApp
xcodegen generate
# 用本机已有的模拟器UDID替换下面的占位符。
xcodebuild test -project AENHT.xcodeproj -scheme AENHTCredentialTests \
  -destination 'platform=iOS Simulator,id=<已有模拟器UDID>' CODE_SIGNING_ALLOWED=NO
xcodebuild -project AENHT.xcodeproj -scheme AENHT -configuration Debug \
  -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build
```

[AtomicCredentialsTests.swift](../../../iosApp/Tests/AtomicCredentialsTests.swift)的8项是hostless逻辑测试：旧键读取不写、完整tuple一次写、写失败保留旧tuple、读取不可用不迁移、损坏记录不回退旧秘密、清理失败后空记录仍权威、退出拒写保留登录等。它们通过后仍不代替真实Keychain/SwiftData、UI和设备验收。

接着检查：同UID短→长→短标题、连续图片失败两次后恢复、随机请求成功/失败/取消、新导航返回与触控恢复、MoreTab跨宽窄；账号切换/退出/刷新同时发生、迁移旧Keychain、共享删除与SwiftData保存失败、暂停/恢复/删除重建。原报告80项设备计划仍全部未执行。

## 需要明确保留的限制

- **iOS本次修复未编译/运行。** 8项测试只是已编写，不填写通过；Linux没有iPhone/Xcode。请将编译、测试和实机结果追加到本文件，再决定合入/发版。
- 凭据新记录是`nh_credentials_v1`/`eh_credentials_v1`；读旧键兼容升级，第一次成功提交后清理旧键。降级到不认识新记录的旧App可能需要重新登录；历史/下载schema未改。清理被系统拒绝时可残留旧Keychain条目，权威新/空记录防止它们被客户端重新采用；不承诺物理安全擦除。
- 不可读取/损坏凭据记录会拒绝不安全的迁移或更新；完整恢复入口仍需按系统错误设计，不能静默删除数据。本批未实现整个存储恢复系统。
- Android10/11未增加AVIF解码能力，不能解码的页保持失败并说明；真实有效样本/OEM能力仍需测。没有重新要求Android8/9兼容。
- 下载目录移动失败时，优先避免旧删除异步任务误删新路径，可能留下待清理目录；磁盘满、进程死亡、权限/rename故障与真实网络取消仍需设备验证。
- 软上限、整份同步、连续模式高级图像能力、无障碍/所有配色、大字体与所有语言、受审golden和其余维护行动仍按原报告状态保留。本批不声称整个软件已无错，也不代替独立审核。

当前本地构建为debug验证，未上传APK/IPA、未创建Release、未触发发布工作流。审核通过后由维护者选择合入；本分支推送不等于发布。
