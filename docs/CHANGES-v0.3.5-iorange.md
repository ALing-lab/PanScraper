# PanScraper v0.3.5（方案 I-orange）改动说明

仅本地提交，未推送、未发布。基于 v0.3.4（含本地提交 6465f6f / 4ed6395）。

## 界面（按设计稿 I-orange）
- 配色：按钮、开关、勾选框、选中的底栏图标统一用应用图标的橙 #FF8B37（浅色 / 深色相同）；
  开关圆钮和勾是 #0F0F0F；浅色模式按钮没有深色描边或阴影。
- 勾选框改为 22dp 圆角方块，勾更大、更粗（2.1dp 圆头描边）；部分选中显示横线。
- 大按钮改为 56dp 高的胶囊；卡片圆角 24dp + 发丝描边；分段控件改为胶囊形。
- 文字链接：深色模式用 #FF8B37；浅色模式用 #BB4E00（橙色文字在白底上只有 2.3:1，读不清）。
- 底栏：选中图标橙色 + 背后胶囊，文字用正文色加粗；平板侧栏选中为橙底黑字。
- 设置页：「账号与网络」（资源中心 + TMDB 网络）放在「刮削 · 命名」上面。
- 刮削页「最近一次计划」的数字带单位：分组「N 部」，操作项 / 未匹配「N 项」。
- 字体：内置 Mona Sans（可变字体）、Geist Mono（数字 / 文件名计数）、Noto Sans SC 子集（常用 4364 字），
  均为 SIL OFL 1.1，许可全文在 assets/licenses/，并列入「开源许可」页。

## 预刮削预览
- 上排状态筛选：全部 / 已匹配 / 未匹配 / 跳过（总有一项选中，带数量）。
- 下排操作格子：命名 / 新建 / 移动 / 海报 / 清理（计划里有删除时再加「删除」），显示「已选/总数」；
  点一下筛选，再点一次取消；与上排一起作用（且）；选未匹配 / 跳过时下排变灰、不可点。
- 有筛选时显示「正在筛选：… · N 项 ✕」，点 ✕ 回到「全部」。
- 筛选只决定显示哪些，不改变勾选；「执行」仍执行全部已勾选项。「全选」只作用于当前显示的项
  （不筛操作时与原来一样不含可选的清理 / 删除）。
- 分组卡片：点片名那一行展开 / 收起（箭头旋转，读屏有「已展开 / 已收起」），默认展开第一组；
  收起时显示「N 项 · 已选 M」等摘要；「重新匹配」是单独的按钮。展开后直接列出该组的操作并可逐项勾选。
- 操作名称统一为两字：命名 / 新建 / 移动 / 海报 / 清理 / 删除（含执行确认框的摘要）。
- 原来的分类子页（scrape/preview/ops/…）路由保留，但预览页不再链接过去。

## 行为不变
- 执行前仍有确认框；不会自动执行；测试不连接任何真实账号。

## 改动的文件
新增：
- app/src/main/java/com/tamu/pan123/data/scrape/PreviewFilter.kt（筛选逻辑，纯函数）
- app/src/main/java/com/tamu/pan123/ui/theme/AppFonts.kt
- app/src/main/res/font/mona_sans.ttf、geist_mono.ttf、noto_sans_sc_regular.otf、noto_sans_sc_bold.otf
- app/src/main/assets/licenses/ofl-mona-sans.txt、ofl-geist-mono.txt、ofl-noto-sans-sc.txt
- app/src/test/java/com/tamu/pan123/scrape/PreviewFilterTest.kt（13 个测试）
- docs/CHANGES-v0.3.5-iorange.md（本文件）

修改：
- app/build.gradle.kts（versionCode 16 / versionName 0.3.5）
- app/src/main/java/com/tamu/pan123/BuildConfigInfo.kt、App.kt
- app/src/main/java/com/tamu/pan123/ui/theme/IosTheme.kt（配色与字体令牌）
- app/src/main/java/com/tamu/pan123/ui/components/IosComponents.kt、IosAlert.kt
- app/src/main/java/com/tamu/pan123/ui/AppRoot.kt
- app/src/main/java/com/tamu/pan123/ui/screens/ScrapeScreens.kt、PreviewOpsScreen.kt、SettingsScreens.kt、
  SettingsMoreScreens.kt、AccountsScreens.kt、CloudScreen.kt、GuangyaLoginScreen.kt、LoginScreens.kt、
  P115LoginScreen.kt、TaskItemsScreen.kt、TasksScreen.kt、TmdbNetScreen.kt
- app/src/main/java/com/tamu/pan123/data/scrape/PlanSelection.kt（摘要用统一短名）
- app/src/test/java/com/tamu/pan123/ScreenshotTest.kt（预览筛选 / 展开收起 / 设置顺序测试）
- app/src/test/java/com/tamu/pan123/v03/V033Test.kt（许可检查允许 SIL OFL 1.1）

## 与设计稿的差异
- 浅色模式的文字链接用 #BB4E00 而不是 #FF8B37（可读性）。
- Noto Sans SC 子集只在 Android 10+ 作为中文回退；Android 8–9 和子集外的生僻字用系统字体。
- 分组标题行保留原来的整组勾选（三态）。
- 「删除」格子只在计划里有删除操作时出现。
- 应用没有设计稿那样的首页，「部」单位加在刮削页的「最近一次计划」里；「未匹配」统计含文件和分组，所以用「项」。
- APK 体积约 5.3 MB → 8.2 MB（内置字体）。
