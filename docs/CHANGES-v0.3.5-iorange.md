# PanScraper v0.3.5 改动说明（相对 v0.3.3 的累计改动）

v0.3.4 没有公开发布，所以本文件把 v0.3.4 的安全 / 稳定性修复和 v0.3.5 的界面改版、审查修复合在一起，
都是相对上一个公开版本 v0.3.3 的变化。

## 升级说明
- v0.3.3 公开发布的 APK（GitHub Release v0.3.3 的 PanScraper-v0.3.3.apk，SHA-256 3e1e646a…9877afd）
  已经是用正式密钥签名的，证书 SHA-256 为 `ecde1865a0735e50c782f8cd32c9dce200640e167fa53e6de82de83098778c6c`，
  与 v0.3.5 相同，**可以直接覆盖安装，不用卸载，数据保留**。（已在构建机上用 apksigner 核对 v0.3.3 发布包与 v0.3.5 包的证书。）
- 例外：如果装的是更早的测试包（v0.1 / v0.2 系列，或没有从 GitHub Release 下载的 v0.3.3 测试构建，它们用的是 Android 调试证书），
  系统会提示签名不一致，需要先卸载再安装（本地数据会被清除）。
- 已开启「信任自签名证书」的 WebDAV 账号，升级后需要点一次「测试连接」确认证书指纹。

## 一、界面改版（方案 I-orange）
- 配色：按钮、开关、勾选框、选中的底栏图标统一用应用图标的橙 #FF8B37（浅色 / 深色相同）；开关圆钮和勾是 #0F0F0F；
  浅色模式按钮没有深色描边或阴影。浅色模式的文字链接用 #BB4E00（橙色文字在白底上读不清）。
- 勾选框改为 22dp 圆角方块，勾更大、更粗；部分选中显示横线。
- 大按钮改为 56dp 高的胶囊；卡片圆角 24dp + 发丝描边；分段控件改为胶囊形。
- 底栏：选中图标橙色 + 背后胶囊，文字用正文色加粗；平板侧栏选中为橙底黑字。
- 设置页：「账号与网络」（资源中心 + TMDB 网络）放在「刮削 · 命名」上面。
- 刮削页「最近一次计划」的数字带单位：分组「N 部」，操作项 / 未匹配「N 项」。
- 内置字体：Mona Sans（可变字体）、Geist Mono（数字）、Noto Sans SC 子集（常用 4364 字，Android 10+ 作为中文回退），
  均为 SIL OFL 1.1，许可全文在 assets/licenses/，并列入「开源许可」页。APK 约 5.3 MB → 8.2 MB。

## 二、预刮削预览
- 上排状态筛选：全部 / 已匹配 / 未匹配 / 跳过（总有一项选中，带数量）。
- 下排操作格子：命名 / 新建 / 移动 / 海报 / 清理（计划里有删除时再加「删除」），显示「已选/总数」；
  点一下筛选，再点一次取消；与上排一起作用；选未匹配 / 跳过时下排变灰、不可点。
- 有筛选时显示「正在筛选：… · N 项 ✕」，点 ✕ 回到「全部」。
- 筛选只决定显示哪些，不改变勾选；「执行」仍执行全部已勾选项（执行前仍有确认框）。「全选」只作用于当前显示的项。
- 分组卡片：点片名那一行展开 / 收起（箭头旋转），默认展开第一组；收起时显示「N 项 · 已选 M」；「重新匹配」是单独的按钮。
- 操作名称统一为两字：命名 / 新建 / 移动 / 海报 / 清理 / 删除。

## 三、安全与稳定性修复（v0.3.4 审查，均相对 v0.3.3）
- H3 自动更新：只从本仓库的 GitHub Release 下载；下载后核对文件大小，发布说明或 .sha256 文件里有 SHA-256 时必须一致；
  安装前核对安装包的包名和签名证书与当前 App 完全一致，任何一项不对都删掉文件并拒绝安装。
- M1 WebDAV 证书：去掉「信任所有证书」。先按系统规则校验；自签名证书只接受用户在「测试连接」时确认过的那一张（按指纹固定）。
- M2 安全存储：加密存储不可用时不再明文落盘，登录信息只留在内存并在设置页提示；旧版留下的明文文件启动时迁移后删除。
- M3 更新源：下载地址只认 GitHub 官方主机和本仓库路径；重定向每一跳都重新检查。
- M4 123 网页登录：去掉 JavaScript 桥；只允许在 123 官方页面里登录和读取登录信息；读到的登录信息先验证通过才保存。
- M5 删除多余文件夹：删除前完整递归检查里面有没有视频（有上限，查不完就不删）。
- M6 任务状态：多处同时更新任务状态时加锁，不再互相覆盖。
- M7 计划 / 任务 / 历史文件：改为原子写入（先写临时文件再替换），写失败会报错而不是留下半个文件。
- M8 海报缓存：只接受真的图片（检查文件头），限制 15 MB，扩展名白名单，原子写入。
- M9 NFO 文件：统一转义，片名 / 简介里有特殊字符也能生成合法的 XML。
- L1 域名判断：改为按「点」边界匹配，避免 evil-themoviedb.org 这类域名混过去。
- L3 日志轮转：检查改名结果，失败时清空，日志不会无限增长。
- L4 前台服务重启：不再整份重跑计划（见 N4）；保活仍只靠前台服务（部分修复）。
- L5 删除了不再使用的暂停锁。
- L6 扫描与重新匹配串行执行，缓存改为线程安全。
- L7 目录太大被截断时明确提示「只扫描了前 80 项」。
- L8 删除了不再使用的旧命名函数。
- L9 中文数字解析遇到异常输入不再闪退；片名年份取最后一个年份的规则加了注释说明取舍。
- L10 执行时再次检查文件名（拒绝空名、.、..、带斜杠、超过 255 字节）。
- L11 崩溃日志文件名精确到毫秒、原子写入，并且最先安装。
- L12 依赖关系：明确「新建文件夹」不再依赖别的操作（只取一层依赖），并在调试构建里加了断言。
- L13 日志 / 崩溃报告里不会打印 token、Cookie 等登录信息。
- N1 正式版用专用密钥签名（不是调试证书）；H1 正式版不可调试。
- N2 网络安全配置：局域网 WebDAV 可以用 http，网盘 / TMDB / GitHub 等固定域名强制 https。
- N3 光鸭网页登录：先验证再保存，只在官方页面读取，限制跳转域名。
- N4 后台服务被系统重启时，不会悄悄重跑整个计划；只恢复用户没结束的任务，暂停的保持暂停。
- N5 成功 / 失败 / 跳过数统一从任务项里数，不再出现对不上的情况。
- N6 取消操作不再被当成失败。
- N7 不再二次反转义 TMDB 文本，季名 / 简介不会被改坏。
- N8 123 上传和 115 接口返回空数据时给出明确错误而不是闪退；115 解压不再限制 8 KB。
- N9 正式版开启代码压缩与混淆（R8）。

## 四、v0.3.5 审查修复
- V35-1 更新安装前的校验重复调用了两次，删掉一次。
- V35-2 网络安全配置里写明规则：新增固定域名必须加进禁明文名单；补上了漏掉的 TMDB 图片镜像（img.amodb.dev、wsrv.nl）
  和 DoH（dns.alidns.com、doh.pub）；新增单元测试扫描源码里的固定地址，漏加会测试失败。
- V35-3 预览页的计数改为一次遍历算完，并按「计划 + 筛选」缓存，计划很大时也不会每次重绘都重新扫描。
- V35-4 测试钩子（SecureStore 测试用明文存储 / 模拟密钥失败、更新检查的测试地址）只在调试构建里生效；
  正式版里 R8 已把这些字段整体删除（mapping 中不存在）。
- M10 扫描目录树向上找父目录时不会越过根目录闪退。

## 五、测试
- testDebugUnitTest：350 个，0 失败，4 个跳过（跳过的是需要真实账号 / 网络的用例，测试时未设置相关环境变量）。

## 六、发布规则（release rules）
1. 每次发布必须在 Release 说明里写 APK 的 SHA-256，或附带同名 `.sha256` 文件（否则自动更新的哈希校验不生效）。
2. 代码里新增任何固定域名，必须同时加进 `app/src/main/res/xml/network_security_config.xml` 的禁明文 domain-config
   （NetworkSecurityConfigTest 会检查）。
3. 发布签名密钥是自动更新信任链的根，必须离线备份；丢失后无法再发布可覆盖升级的版本。

## 改动的文件（相对 v0.3.3，主要）
- 界面：ui/theme/IosTheme.kt、AppFonts.kt（新）；ui/components/IosComponents.kt、IosAlert.kt；ui/AppRoot.kt；
  ui/screens/ScrapeScreens.kt、PreviewOpsScreen.kt、SettingsScreens.kt、SettingsMoreScreens.kt、AccountsScreens.kt、CloudScreen.kt、
  GuangyaLoginScreen.kt、LoginScreens.kt、P115LoginScreen.kt、TaskItemsScreen.kt、TasksScreen.kt、TmdbNetScreen.kt
- 预览筛选：data/scrape/PreviewFilter.kt（新）、PlanSelection.kt
- 安全 / 稳定性：data/update/UpdateChecker.kt、data/SecureStore.kt、data/MemoryPrefs.kt（新）、data/webdav/CertPinning.kt（新）、
  WebDavDrive.kt、data/net/HostMatch.kt（新）、NetLog.kt、data/io/AtomicFiles.kt（新）、data/exec/ExecutionEngine.kt、
  ArtworkFetcher.kt、NfoXml.kt（新）、TaskStore.kt、TaskModels.kt、data/scrape/PlanStore.kt、Scanner.kt、data/naming/Naming.kt、
  data/pan/Pan123Client.kt、Models.kt、data/p115/Drive115.kt、Ec115Cipher.kt、data/guangya/GuangyaDrive.kt、
  data/history/HistoryStore.kt、data/tmdb/TmdbNet.kt、TmdbClient.kt、data/webdav/WebDavAddress.kt、data/Settings.kt、BuildConfigInfo.kt、service/ExecForegroundService.kt、CrashReporter.kt、App.kt
- 构建 / 资源：app/build.gradle.kts（版本 0.3.5 / 16、正式签名、R8、buildConfig）、proguard-rules.pro（新）、
  res/xml/network_security_config.xml（新）、AndroidManifest.xml、res/font/（新）、assets/licenses/ofl-*.txt（新）
- 测试：app/src/test/.../v034/（新）、v035/（新：NetworkSecurityConfigTest、HolderOfTest、DependencyLeafTest）、
  scrape/PreviewFilterTest.kt（新，14 个）、ScreenshotTest.kt、v03/V033Test.kt
