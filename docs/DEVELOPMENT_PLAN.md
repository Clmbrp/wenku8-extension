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

## 阶段 0 验证记录

- `./gradlew :src:zh:bilinovel:assembleDebug --console=plain`：成功；现有 `BiliNovelWeb.kt` 有日期解析 API 弃用警告。
- `./gradlew :src:zh:wenku8:assembleDebug --console=plain`：成功。初次尝试因不存在的 `ContentWarning.UNSPECIFIED` 配置失败，修正为工程支持的 `MIXED` 后通过。
- APK：`src/zh/wenku8/build/outputs/apk/debug/tachiyomi-zh.wenku8-v1.6.1.apk`。已检查生成的来源入口、ID 和 APK 元数据；尚未在设备上安装或验证运行。

## 阶段 1：站点与技术调研

[访问审计](ACCESS_AUDIT.md) 记录了网站地址、编码和页面结构。HTML 解析以脱敏样本和已验证的页面为依据。

## 阶段 2：会话与真机联网验收

关键联网验收已通过。用户在同一个 Mihon 实例中手动完成站点安全验证，随后重新打开 HTTPS 登录入口并登录。返回刷新后，插件在该设备、会话与网络下成功获取并显示真实热门书目。单独完成账号登录仍可能遇到安全验证。

版本 `1.6.5` 在来源设置提供「账号登录（HTTPS）」和「站点安全验证（HTTPS 榜单）」两个入口。Mihon 打开来源 WebView 时读取当前选项，插件沿用宿主 Cookie。收到登录跳转、安全验证、直接拒绝或可解析内容时分别处理。

## 阶段 3：发现与书籍信息

- 第一批（1.6.6）已接入热门封面、书籍详情和章节目录。封面使用已核实的 CDN 地址，详情按需请求，目录保留卷名及插图章节，用同书路径标识每章。20 项单元测试全部通过，解析覆盖多卷、单卷、无卷名、缺字段及异常 HTML。Debug 构建成功，产物位于 `src/zh/wenku8/build/outputs/apk/debug/tachiyomi-zh.wenku8-v1.6.6.apk`。
- 第一批真机验收待完成。更新 APK 后刷新热门榜，打开一本多卷书，核对热门与详情封面、完整书名、作者、简介，以及卷章顺序。封面异常时可查看 Debug 版的 `Wenku8Access` 封面日志。已获得的电脑端 JPEG 和浏览器样本证据见 [访问审计](ACCESS_AUDIT.md#阶段-3-第一批封面详情和目录)。
- 下一批实现书名搜索及结果列表、直达详情和异常响应的处理。

## 后续阶段

4. **阅读体验**：实现正文和插图呈现，验证字符编码、图文顺序及 Mihon 阅读器行为。
5. **测试与交付**：完成代表性样本、设备回归、图标及发布前检查。账户同步按实际需求单独评估。
