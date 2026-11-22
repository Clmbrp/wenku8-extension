# Wenku8 Mihon 插件开发计划

## 阶段 0：工程初始化与首次编译

- 仓库分支：`src`；保留 BiliNovel 和其他参考模块。
- 模块：`src/zh/wenku8`；插件及来源名称为“轻小说文库”，语言为 `zh`。
- 来源 ID：`845240625336071213`，为本插件固定的独立 ID；发布后应保持稳定，避免丢失来源关联。它按工程的名称/语言/版本 ID 默认算法生成，并已显式固定。
- 版本：`versionCode = 1`，扩展 API 为 `1.6`，生成版本名 `1.6.1`。
- `https://wenku8.invalid` 是故意不可用的占位域名，并非已验证站点。内容分级暂定 `MIXED`，实际分级待核实。图标使用 `core` 的默认资源。
- 所有业务方法均为明确抛出异常的占位实现；`supportsLatest` 暂为 `false`。此 APK 仅验证打包，不提供阅读功能。

## 环境与约定

- 使用项目 `./gradlew`，Gradle Wrapper 9.7.1、AGP 9.4.1、Kotlin 2.4.10；以项目文件为准，不为绕过错误随意升级。
- 使用默认 `~/.gradle`、`~/Android/Sdk`；本次 JDK 为 OpenJDK 25.0.5，编译目标由工程配置为 Java 11。SDK 配置为最低 26、编译及目标 37。
- 复用现有缓存和代理，不创建额外的 Gradle 用户目录。依次构建，必要时先排查 VS Code Gradle 同步和锁持有者。工程的 `preBuild` 会执行 Spotless 自动格式化，构建后须检查工作区。
- 使用 `keiyoushi.annotation.Source` 标记继承 `eu.kanade.tachiyomi.source.online.HttpSource` 的抽象类，使用 Mihon `tachiyomix:1.6.0` 的协程式接口。Gradle 的 `source` 配置由 KSP 生成入口及来源属性；不要套用旧版 `KeiSource` 的 request/parse 模板。
- 扩展资源和 manifest 由工程约定及 `core` 默认资源合并提供；后续增加专属资源时遵循现有模块目录布局。

## 验证记录

- `./gradlew :src:zh:bilinovel:assembleDebug --console=plain`：成功；现有 `BiliNovelWeb.kt` 有日期解析 API 弃用警告。
- `./gradlew :src:zh:wenku8:assembleDebug --console=plain`：成功。初次尝试因不存在的 `ContentWarning.UNSPECIFIED` 配置失败，修正为工程支持的 `MIXED` 后通过。
- APK：`src/zh/wenku8/build/outputs/apk/debug/tachiyomi-zh.wenku8-v1.6.1.apk`。已检查生成的来源入口、ID 和 APK 元数据；尚未在设备上安装或验证运行。

## 后续阶段

1. **站点与技术调研**：核实 Wenku8 官方基础域名、可访问性、内容分级、页面编码和页面结构，明确功能边界与测试样本；替换占位域名。记录验证日期和证据。
2. **发现与书籍信息**：实现列表、搜索、详情、章节列表及必要的数据解析，覆盖分页和异常情形。
3. **阅读体验**：确定 Mihon 中正文与插图的呈现方案，实现章节内容及页面处理，验证编码与章节顺序。
4. **账户及可选功能**：按实际需求评估并实现登录和同步，处理会话和失败情形。
5. **测试与交付**：补充有代表性的解析与回归测试、设备验证、图标及发布前检查。

进入阶段 1 的条件：阶段 0 的基线和新模块构建通过，来源 ID 已固定；开始调研时须先核实真实域名和内容分级，不能把占位配置当成站点事实。各后续阶段需单独执行，本次不自动继续。
