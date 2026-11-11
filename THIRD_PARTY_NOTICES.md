# Third-party notices / 第三方许可说明

PanScraper 自身按 **GPL-3.0-or-later** 授权，与原有应用内声明的“GPLv3 或后续版本”一致。LICENSE 保留 GNU GPL v3 标准全文；COPYRIGHT 标明项目版权。没有把第三方组件改授 GPL，也没有更改原许可证的权利人或年份。

本次逐项核对 **91 个正式发布运行时组件**的 Maven POM（包括父 POM 继承许可）及二进制内许可证/NOTICE。91 是解析依赖数量，并非 R8 后必然保留的组件数；测试和构建工具不计入运行时清单。各版本、原许可名称与来源地址见 [机器可读清单](app/src/main/assets/licenses/runtime-components.json)，保留的完整嵌入声明见 [第三方声明](app/src/main/assets/licenses/third-party-notices.txt)。

- AndroidX、Kotlin/kotlinx、OkHttp/Okio、Coil、Tink、Gson、Guava ListenableFuture、Accompanist、JetBrains Annotations 等解析组件：Apache-2.0（Guava 许可从父 POM 继承）。
- LZ4 Java：维护分支 `at.yawk.lz4:lz4-java:1.14.0`，核对上游 v1.14.0 的 Apache-2.0 LICENSE。包内原生 LZ4／xxHash 来自固定子模块 `c72c7c0ec132ad2ce2fe9fad7b0c728d9faee2fc`，另外保留 [BSD-2-Clause 声明](app/src/main/assets/licenses/bsd-lz4-xxhash.txt)，不将这些原生代码误记为仅 Apache-2.0。
- Bouncy Castle：`org.bouncycastle:bcprov-jdk18on:1.87`，保留实际 JAR 的 MIT 许可与 Copyright 2000–2026。
- Mona Sans、Geist Mono、Noto Sans CJK SC 字体：各自保留 SIL OFL-1.1；字体子集修改说明及保留字体名约束见对应 OFL 文件。没有把原字体版权年份统一改为当前年份。
- TMDB 数据、图片及服务条款独立于程序源代码许可，不能因本应用开源而再许可这些素材。

分发 APK 时应同时提供对应版本的完整源码、构建脚本、修改说明及许可证，版本发布页会附源码 ZIP。分发和再分发须保留各权利人的声明；修改本项目需明确标注变更。GPL 第6节涉及的安装信息要求应按实际分发方式判断，不能以一份声明替代对分发条件的核验。发布签名私钥不包含在公开源码包中。

应用内“设置 → 关于 → 开源许可”保留主许可证、组件分类及完整第三方声明入口。清单按实际解析版本生成；以后修改依赖或字体时必须重新核对。
