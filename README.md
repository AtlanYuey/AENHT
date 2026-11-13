<h1 align="center">AENHT</h1>

<p align="center">
  All E-Hentai + nhentai HenTai：同时支持 nhentai 和 E-Hentai / ExHentai 的非官方客户端。<br>
  原生 iPhone / iPad（SwiftUI）与 Android（Kotlin、Jetpack Compose）App，逻辑层由 Kotlin Multiplatform 共享。
</p>

> ⚠️ 实验性项目，正在开发中，尚未发布正式版本。正式发布前请继续使用 [AEros-NHT](https://github.com/AtlanYuey/AEros-NHT)。

## 目录

| 目录 | 内容 |
| --- | --- |
| `shared/` | Kotlin Multiplatform 共享层：网络、站点抽象、解析、数据库、同步、下载、限速 |
| `androidApp/` | Android App（Kotlin、Jetpack Compose） |
| `iosApp/` | iOS / iPadOS App（SwiftUI），Xcode 工程由 XcodeGen 生成 |
| `docs/` | 设计文档与需求记录 |

## 许可与致谢

本项目是 [honjow/eros_n](https://github.com/honjow/eros_n) 的修改版（经由 [AEros-NHT](https://github.com/AtlanYuey/AEros-NHT)），基于 Apache License 2.0（见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)），由 [AtlanYuey](https://github.com/AtlanYuey) 维护，与原作者、nhentai 及 E-Hentai 均无关联。部分功能的行为参考了 [NextE](https://github.com/erosTeam/NextE) 与 [NextN](https://github.com/erosTeam/NextN)（MIT），未复制其源码。标签数据来自 [nhentai_tags_fetcher](https://github.com/honjow/nhentai_tags_fetcher)，中文翻译来自 [EhTagTranslation](https://github.com/EhTagTranslation/Database)。

仅供 18 岁以上用户使用。
