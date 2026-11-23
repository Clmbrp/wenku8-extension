# Wenku8 数据访问审计

调查日期：2026-11-22（Asia/Shanghai）。Codex 本次调查没有登录、提交搜索、抓取正文或调用私人 API；另收录用户之后提供的已登录 Chrome 截图及 DOM 证据。

**证据标记**：**[实测]** 本次由 Codex 观察；**[浏览器证据]** 用户报告的 Fedora Chrome 人工实测或提供的截图、DOM；本次新增的 Network 和字符集结果均来自已登录 Chrome，不是 Codex 或 Mihon 的独立实测；**[源码]** 参考项目实现或项目作者的陈述；**[不确定]** 缺少当前环境或授权证据。源码 URL 固定到调查时的提交；第三方声称的真站验证仍按 [源码] 处理。

补充证据：**[样本]** 表示用户从浏览器保存的 HTML 文件中观察到的结构，或用户明确报告的操作结果；不等于本次网络请求实测。原始样本保留在本地 `assets/` 并被 Git 忽略；[脱敏结构样本](fixtures/wenku8/README.md) 是独立构造的虚构示例。

## 本次环境与访问结果

- **[阶段 2 真机通过，用户提供]** Wenku8 1.6.4 从来源 WebView 的 HTTPS 入口可完成账号登录。仅完成登录时热门榜仍可能提示安全验证。访问受保护页面并手动完成站点验证后，用户重新打开 HTTPS 入口登录，关闭 WebView 后刷新热门榜，Mihon 显示真实书目。这验证了该设备、会话和网络中的普通客户端访问。会话变化或网络变化后仍可能出现拦截。
- **[阶段 3]** 已接入热门封面、详情和目录，封面链路及真机验收状态见下节。

- **[2026-11-23 用户 Chrome 实测]** 在同一 Android 手机的 Chrome，`https://www.wenku8.net/login.php` 登录页、`POST https://www.wenku8.net/login.php?do=submit` 表单和 `https://www.wenku8.net/checkcode.php` 验证码图片均使用 HTTPS，用户报告该浏览器可登录。同一手机 Mihon WebView 显示 HTTP 登录地址并报告校验码错误；这不证明两者使用相同会话或请求链。
- **[2026-11-23 设备及源码]** 已连接设备的包管理器报告插件版本 `1.6.3`、Mihon `0.21.3`。对照 [Mihon v0.21.3 来源页面](https://github.com/mihonapp/mihon/blob/feadad5e1e2bf638be6e2ca317d11657b09d4596/app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt)、[WebView 导航](https://github.com/mihonapp/mihon/blob/feadad5e1e2bf638be6e2ca317d11657b09d4596/app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt)与 [CookieJar](https://github.com/mihonapp/mihon/blob/feadad5e1e2bf638be6e2ca317d11657b09d4596/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/AndroidCookieJar.kt)：来源浏览页初始地址取 `getHomeUrl()`；宿主后续导航允许同主机 HTTP；默认 CookieJar 使用 Android `CookieManager`。Wenku8 1.6.3 代码提供 HTTPS 登录初始地址，插件的 OkHttp 跳转检查不控制宿主 WebView 后续导航，也不能保证表单或验证码请求共用有效会话。设备上未发现可用 WebView 远程调试通道，未获取本次导航链或请求 Cookie。
- **[源码对比]** Wenku8 没有覆盖 `headersBuilder()`，使用宿主默认 User-Agent；BiliNovel 显式添加了自己网站使用的 User-Agent、Referer 和语言等请求头。两站行为不同，不能由 BiliNovel 可登录推断 Wenku8 必须采用这些值；不能据此断定 UA 或 Cookie 为降级根因。站点重定向、页面导航、宿主导航处理及设备会话均为待证实可能性。
- **[结论与安全边界]** Chrome HTTPS 流程已证实，但无法仅凭现有证据定位 Mihon WebView 的 HTTP 来源或验证码失败原因；不更改凭据、伪造 Cookie、禁用 TLS 或更换 UA 猜测修复。若 WebView 后续导航或表单最终使用 HTTP，扩展仅覆盖初始入口，无法保证安全提交；停止输入/提交凭据，使用已验证的 Chrome HTTPS 页面，或等待宿主/站点支持可验证的 HTTPS 流程。Chrome 登录不保证其 Cookie 可用于 Mihon。

- **[2026-11-23 用户设备报告]** Android Chrome 的 HTTPS 登录可用；Mihon WebView 最终出现 `http://www.wenku8.net/login.php`（含跳转参数），验证码图片可见但提交提示校验码错误；热门页另报“页面跳转到未验证的站点”。当前没有该设备的完整导航状态/重定向链、登录表单 action 和验证码请求的会话证据，不能认定 HTTP 是验证码失败的唯一原因，也不能确认热门页实际跳转目标。
- **[宿主源码与插件检查]** Mihon 所检查的源码中，来源浏览页通过 `getHomeUrl()` 设置 WebView 初始地址；之前插件继承的默认值是 HTTPS `baseUrl`。所见 HTTP 地址发生在该入口之后，但无法仅凭最终地址判断它来自 HTTP Location、页面链接、脚本还是其他导航。新版本覆盖 `getHomeUrl()` 为 `https://www.wenku8.net/login.php`，避免必须从首页进入登录；书籍详情等其他 WebView 入口可能使用各自页面 URL。插件不能凭此控制宿主后续导航、站点表单 action、验证码图片请求和 POST。
- **[2026-11-23 本地测试]** 模拟站点返回同主机、默认端口的 HTTP `/login.php` 跳转时，旧代码将其报告为未验证站点；新代码不请求该 HTTP URL，而按登录 Cookie 的有无提示登录或可能过期。外域、其他 HTTP 路径与非标准端口仍拒绝。此测试说明一种可以复现的异常路径，并未证明用户设备的重定向目标就是该路径。
- **[待设备验证]** 核实 HTTPS 登录页最终是否仍留在 HTTPS、表单 POST 目标及验证码图片是否使用相同会话；不得记录密码、验证码、Cookie 或 Token。如页面或提交目标降为 HTTP，应停止提交，不能保证扩展覆盖 WebView 的后续导航。站点的 HTTP/HTTPS Cookie 属性、验证码更新时序或 WebView 差异也可能影响结果，需要安全的脱敏证据和真机复测，不能声称已修复登录。

- **[阶段 2 实测]** 一次通过既有代理对 `GET https://www.wenku8.net/top.php` 的请求，代理 CONNECT 200、TLS 验证结果 0，最终 HTTP 403、无重定向；沙箱内请求因禁止套接字失败。未尝试绕过或反复请求。此结果不是插件真机请求，不能证明插件能获取榜单。
- **[阶段 2 设备]** `adb devices -l` 在获准的宿主环境下未列出设备，因此插件安装、WebView 登录、Cookie 共享和 OkHttp 访问均未完成真机验证。阶段 1 用户报告的已登录 Chrome 页面与 Network 结果仍是浏览器辅助证据，并非本次 APK 验证。
- **[宿主源码]** 在 Mihon 主仓库提交 `f8e30a86086020aaee1e89a9d015373b1a4ff026` 中，[NetworkHelper](https://github.com/mihonapp/mihon/blob/f8e30a86086020aaee1e89a9d015373b1a4ff026/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt) 使用 [AndroidCookieJar](https://github.com/mihonapp/mihon/blob/f8e30a86086020aaee1e89a9d015373b1a4ff026/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/AndroidCookieJar.kt)，后者连接 Android `CookieManager`；宿主另有 [WebView 界面](https://github.com/mihonapp/mihon/blob/f8e30a86086020aaee1e89a9d015373b1a4ff026/app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewScreen.kt)。此源码说明该版本的设计，不能代替目标设备和其他 Mihon 分支上的验证。宿主默认客户端包含可能自动启动后台 WebView 的 Cloudflare 拦截器，插件仅移除这个已知类；其他宿主版本是否存在不同拦截器仍需验证。

- **[实测]** 沙箱内 DNS 查询和 curl 无法创建网络套接字（`Operation not permitted`），此结果只说明沙箱限制。获准使用现有网络后，本机解析器对 `www.wenku8.net` 返回 Cloudflare CNAME 和 A 记录；`www.wenku8.com`、`www.wenku8.cn` 也各有 A 记录。DNS 会变化，本机 DNS 查询并不证明代理实际使用相同解析结果。
- **[实测]** 使用现有代理访问 `GET https://www.wenku8.net/`，代理 CONNECT 返回 200，TLS 校验结果为 0，最终 HTTP/2 403、无重定向，响应 `text/html; charset=UTF-8`，页面为 Cloudflare 的 `Attention Required!` / `Sorry, you have been blocked`。这是一张拦截页，并非网站内容或登录页；原因和触发条件未确定。它不是 429，也不能证明目标正文编码为 UTF-8。
- **[实测]** 同代理访问 `/robots.txt` 得到 403，未能读取其内容；因此无法从该文件判断抓取规则或授权。外部网页获取工具访问候选首页也得到 403，但不等同于用户 Chrome 或 Android WebView 的行为。
- **[实测]** `https://www.wenku8.com/` 经一次重定向到 `https://www.wenku8.net/`，最终 403；`https://www.wenku8.cn/` 代理 CONNECT 返回 200 后 TLS 握手异常 EOF，未获得 HTTP 状态。不能仅据此认定 `.cn` 停用、属于本站或其证书无效。历史域名的所有权和稳定性均未验证，不自动切换。
- **[浏览器证据]** 用户报告已在 Fedora Chrome 登录并访问 `https://www.wenku8.net/top.php`，截图显示实际排行页面，提供了「总热度榜」的 DOM。此观察与 Codex 代理下首页被拦截并不矛盾；不能证明 Mihon 的 OkHttp 或 WebView 请求成功，也不能确定 HTTP 状态码、响应编码或登录前行为。
- **[不确定]** Codex 未做登录态测试；缺少 Chrome 与 Android WebView/OkHttp 对照、429、超时及目录重定向证据。由于首页已被拦截，Codex 没有继续逐个请求业务页面或反复重试。用户之后报告的搜索重定向实测见下文。
- **[不确定]** 未取得可核验的网站现行服务条款、robots 规则或公开 API 授权；开源项目可访问或公开代码本身不构成本站许可。后续需核实站点明确允许的访问方式、频率与内容使用范围；对 403、429、验证、登录提示停止自动请求，由用户处理或联系站点。

## 逐功能地址与数据契约

除特别标记的用户浏览器证据及上文实测外，以下 URL 均为源码候选，**未由 Codex 实测**。相对路径以 `https://www.wenku8.net` 为候选基址；不因此修改当前插件的 `.invalid` 占位配置。源码字段/选择器不能视为当前页面事实。

| 功能 | 方法和候选地址 | 返回、字段与认证证据 | 状态及问题 |
| --- | --- | --- | --- |
| 首页 | [源码] GET `/` 或 `/index.php` | [源码] HTML；LightNovelReader 使用最近更新中的书名、作者、详情和最新章节链接；LiteTale 首页按 Content-Type/页面声明处理编码。 | [实测] `/` 返回上文 403 UTF-8 拦截页；正常 HTML 及登录差异 [不确定]。 |
| 热度排名汇总页 | [浏览器证据] `/top.php`；请求方法 [不确定] | [浏览器证据] 多分类 HTML 页面；「总热度榜」为 `ul.ultop > li > a[href]`，完整标题见 `tiptitle`，详情路径见 `href`。 | [浏览器证据] 用户已登录 Chrome 页面可见；HTTP 状态、charset、未登录行为和 Mihon 普通请求 [不确定]。详见下文。 |
| 排行 | [源码] GET `/modules/article/toplist.php?sort={sort}&page={page}`；LiteTale 加 `charset=gbk` | [源码] HTML；`table.grid`、`a[href*=/book/]`，作者/状态/更新等文本；sort 如 `allvisit`、`monthvisit`、`lastupdate`。LightNovelReader 代码要求登录。 | [源码] 未实测；登录前是否重定向、字段、分页和响应编码 [不确定]。 |
| 搜索 | [浏览器证据] `POST /so.php` → 302 → `GET /modules/article/search.php?...`；该例再次 302 到详情。其他候选参数见源码。 | [样本] HTML 多结果列表；[浏览器证据] 搜索「化」返回列表。 | [浏览器证据] 已登录 Chrome 验证单次跳转链；唯一匹配规则、登录条件及频率限制 [不确定]。 |
| 书籍详情 | [浏览器证据] GET `/book/6.htm`；[源码] 另有 `/modules/article/articleinfo.php?id={aid}&charset=gbk` | [样本] HTML `#content` 包含书籍字段和目录链接；[浏览器证据] `/book/6.htm` 返回 200，HTML 声明 GBK。两条地址不能假定等价。 | [不确定] 其他书籍、登录前行为与 Mihon 访问。 |
| 目录 | [源码] GET `/novel/{cat}/{aid}/index.htm`；LiteTale 另用 `/modules/article/reader.php?aid={aid}&charset=gbk` | [源码] HTML；`table.css`、卷 `td.vcss`、章节 `td.ccss a`；LiteTale 的 reader 解析查询参数 `aid`/`cid`。优先保留页面提供的链接，不假定两种形态相同。 | [源码] 未实测；排序、卷结构、权限和编码 [不确定]。 |
| 章节正文 | [源码] GET `/novel/{cat}/{aid}/{cid}.htm` | [源码] HTML `#content`、文本和 `<br>`，排除导航，保留 `<img>` 及顺序；两个项目均有 GBK 解码依据。 | [源码] 未实测；内容授权、编码、分页、真实文本/图片顺序及登录差异 [不确定]。 |
| 封面/插图 | [样本] HTML 中的 `img src`；[浏览器证据] HTTPS GET `pic.777743.xyz/0/6/13917/31759.jpg` | [浏览器证据] 禁用缓存后返回 200 `image/jpeg`。 | [不确定] 其他图片域名、所有权、Referer/Cookie 要求和 Mihon 可用性；不假设所有小说共用域名。 |
| 登录/会话 | [源码] 网页 GET `/login.php`，表单 POST `/login.php?do=submit&jumpurl=...`；LiteTale POST `/login.php` | [源码] 表单字段包括 `username`、`password`、`usecookie`、`action`，LiteTale 还使用 `checkcode`；涉及 `jieqiUserInfo`、`PHPSESSID`。两项目形式不同，不能断言现行表单。 | [不确定] 登录方式、验证码及 WebView 与 Mihon 客户端 Cookie 同步行为；不自动识别或绕过验证、不记录凭据。 |
| 书架 | [源码] GET `/modules/article/bookcase.php` | [源码] HTML；项目区分书籍 `aid` 和书架项 ID。 | [不确定] 权限、读写方式和当前可用性；本阶段不请求或修改书架。 |
| 网站打包文本 | [源码] LightNovelReader 记载下载页 `/modules/article/packshow.php?id={aid}&type=txtfull` 和另一主机的文本下载。 | [源码] 项目作者描述文本编码选项与登录差异；这是下载通道，不是已证实的正式 API。 | [不确定] 授权、可用性及使用范围；不请求、不作为备用方案。 |

## 用户 Chrome 的热度排名 DOM

- **[浏览器证据]** 用户提供的 `/top.php` 截图显示「总热度榜」「电击热度」「富士见热度」「角川热度」「月度热榜」「MF.J热度」「Fami通热度」「GA文库热度」等多个榜块。截图仅覆盖可见区域，不代表已列出全部分类，也不证明其他榜块的 DOM 和 `ul.ultop` 完全相同。
- **[浏览器证据]** Elements 面板中，总热度榜可见的层级为 `div.blocktop` 内的 `div.blockcaption`（标题）与 `div.blockcontent > ul.ultop`；所给片段包含 15 个 `li`，每项的直接子元素是带 `href` 的 `a`。可在已确定的该榜容器内使用 `ul.ultop > li > a[href]` 选择条目；若遍历页面，应先按各个榜块及标题分组，核实其他榜块结构后再分别提取，避免混合分类。各榜是否共用 `div.blocktop` 和 `ul.ultop` 仍待逐块核实。
- **[浏览器证据]** 片段中链接文本有截断，而 `tiptitle` 包含更完整的标题，可能带括号内别名。例如 `/book/1787.htm` 的展示文本截断，`tiptitle` 保留较完整名称。**[不确定]** 对片段之外的所有条目，`tiptitle` 是否总存在、是否总为规范书名以及括号是否应拆成别名尚未验证；拟优先读取非空 `tiptitle`，缺失时回退非空链接文本，保留原文，不推断别名。
- **[浏览器证据]** `href` 示例为 `/book/1973.htm` 等根相对路径。**[不确定]** 未来实现应以实际页面 URL 作为基址解析为绝对 URL，并限制在经确认的可信站点域名内；例如该页面的 `/book/1973.htm` 对应 `https://www.wenku8.net/book/1973.htm`。书籍 ID 可从已验证的 `/book/{数字}.htm` 路径提取；其他路径形态尚未验证。`target="_blank"` 不属于书籍 ID。
- **[不确定]** 截图没有 Network 响应头，无法确定请求方法、状态、字符编码、重定向或 Cookie 需求；画面已有登录状态，也无法据此断言未登录是否可访问。`/top.php` 是汇总页，不能把它的 `ul.ultop` 结构直接应用于源码中另一个 `/modules/article/toplist.php` 列表页。

## 书籍、搜索和章节样本补充

- **[样本] 编码：** 五个本地保存文件的实际字节均为 UTF-8，但 HTML 中仍声明 `charset=gbk`，保存文件不能当作原始响应字节。**[浏览器证据]** 已登录 Chrome 的 `/book/6.htm` 响应头为 `Content-Type: text/html`，未显式指定 charset；HTML 声明 `charset=gbk`，`document.characterSet` 为 `GBK`。这验证该次详情页由 Chrome 按 GBK 解码，不能推及所有页面或证明保存文件仍是原始字节。网络层不得一律按 UTF-8 解码，应结合响应头、HTML 声明和原始字节处理 GBK；脱敏样本统一是 UTF-8，不证明站点编码。
- **[样本] 搜索：** `search.html` 的表单为 `POST /so.php`，字段名包括 `searchkey` 和隐藏的 `charset=gbk`；这说明 HTML 声明的表单提交方式，不证明现网提交结果。多结果位于 `#content table.grid`，条目含标题链接、封面、作者等；同一本书的封面、标题和「我要阅读」可重复指向相同 `/book/{id}.htm`，按书籍条目或规范详情 URL 去重，不能把所有链接当作不同结果。标题链接的 `title` 在样本中可提供比截断文本更完整的名称。分页链接指向 `/modules/article/search.php` 并含搜索参数和 `page`，最终分页行为待验证。
- **[浏览器证据] 搜索重定向：** 已登录 Fedora Chrome 中，首页搜索表单为 `method="post" action="/so.php" target="_blank"`。搜索「化物语」的 Network 链为：`POST /so.php` → HTTP 302，`Location: /modules/article/search.php...`；`GET /modules/article/search.php?...` → HTTP 302，`Location: /book/6.htm`；`GET /book/6.htm` → HTTP 200。搜索「化」得到多结果列表。**[不确定]** 是否仅在唯一匹配时跳转、其他关键词的跳转机制以及未登录/Mihon 行为均未验证；不能把此例推广为所有搜索均遵循相同规则。设计应识别可信最终 URL 与页面结构：详情映射单条结果，列表映射多条；无法识别时返回明确错误，不误判为空列表。
- **[样本] 详情与目录：** `detail.html` 的 `#content` 含标题、作者、状态等信息，目录链接为 `/novel/0/6/index.htm`；`catalog.html` 的 `table.css` 用 `td.vcss` 标示卷、`td.ccss > a[href]` 标示章节，存在没有链接的空 `td.ccss`。目录链接如 `13917.htm` 是相对于目录页面 URL 的相对路径，应按 URL 标准解析成 `/novel/0/6/13917.htm`；保留卷章文档顺序、跳过无链接的空单元格。不能以文件所在的本地 `assets/` 目录作为网页基址。
- **[样本] 正文结构：** `chapter.html` 的 `#content` 包含文本节点和 `<br>`，无内容图片；`chapter_image.html` 的 `#content` 包含 6 个 `div.divimage`，各有包在链接中的 `img.imagecontent`，无章节正文文本；两者首尾均有 `ul#contentdp` 导航，不应作为正文。正文处理应按 DOM 顺序遍历并形成有序文本块与图片块，不得因为没有文字而舍弃纯插图章节，也不得将图片统一移到末尾。**[不确定]** 此次没有实际混排样本；第三种混排用虚构样本覆盖设计，现网结构仍需验证。
- **[样本] 图片地址：** 插图章节 `/novel/0/6/13917.htm` 的 6 个 `img[src]` 指向 `https://pic.777743.xyz/0/6/13917/31756.jpg` 至 `31761.jpg`，图片所在 `<a href>` 也指向图片；详情和搜索封面样本使用 `http://img.wenku8.com/...`。**[浏览器证据]** 已登录 Chrome 在禁用缓存后，以 HTTPS GET 获取其中 `31759.jpg`，返回 HTTP 200、`Content-Type: image/jpeg`、`Content-Length: 63612`、`Cf-Cache-Status: HIT`。这证明该次浏览器获取成功，`HIT` 是服务器侧缓存状态，不能据此推断其余图片、其他小说或 Mihon 均可访问。**[不确定]** 真实相对图片路径、域名归属、重定向、Referer/Cookie 要求和 Android 明文流量策略仍待验证；相对 `src` 按响应最终页面 URL（若有 HTML `base` 则遵循其语义并校验目标）解析，虚构混排样本仅测试通用规则。不能将第三方图片域视作已授权，也不应推导替换域名。
- **[不确定] 访问约束：** 保留图片原始 URL 并在后续设备上确认宿主网络安全策略、响应状态、重定向、Referer、Cookie 作用域和网站使用规则；不向跨域图片主机主动附加站点 Cookie 或敏感请求头，不为绕过限制而降级 HTTPS 或更换域名。

## 阶段 3 第一批：封面、详情和目录

- **[样本] 原始封面地址。** `detail.html` 的主封面为 `http://img.wenku8.com/image/0/6/6s.jpg`，`search.html` 的 20 个封面符合 `/image/{ID 除以 1000 取整}/{ID}/{ID}s.jpg`。总热度榜的书籍链接提供 ID，没有封面字段。章节插图样本使用 `pic.777743.xyz`，与这些封面属于不同站点。
- **[电脑实测] 封面 HTTPS。** 对 `img.wenku8.com` 的两个样本进行过匿名 GET，`/image/0/6/6s.jpg` 和 `/image/1/1973/1973s.jpg` 的 HTTPS 响应均为 HTTP 200、`image/jpeg`，分别为 17,745 和 17,983 字节。请求未附加 Referer，说明这些电脑端样本可直接获取。LiteTale 的图片下载实现附加 Referer，属于该项目的请求策略，Android 所用网络是否另有要求仍待验证。 [LiteTale 图片请求源码](https://github.com/metaMMY07/LiteTale/blob/ab4129b56e8825d375979d8f8dff77dfd44a1266/rust/src/wenku8/client.rs)
- **[源码] 区分网页与 Mihon 的失败。** HTTPS 网页引用 HTTP 封面会触发 WebView 的混合内容策略。Mihon v0.21.3 的 WebView 未显式放开该模式。用户报告过网页破图，缺少对应的请求结果，具体原因仍待确认。原生列表和详情封面由 Mihon 的 `MangaCoverFetcher` 读取 `thumbnail_url`，使用来源的 OkHttp 客户端和 `headers` 请求，不经过 WebView。1.6.4 的热门列表尚未填充 `thumbnail_url`，截图中的占位图无法说明 CDN 请求结果。 [Android WebView 文档](https://developer.android.com/reference/android/webkit/WebSettings#setMixedContentMode(int)) · [Mihon WebView](https://github.com/mihonapp/mihon/blob/feadad5e1e2bf638be6e2ca317d11657b09d4596/app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt) · [Mihon 封面加载器](https://github.com/mihonapp/mihon/blob/feadad5e1e2bf638be6e2ca317d11657b09d4596/app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt)
- **[已实现／单元测试] 封面解析。** 详情页按响应 URL 和 HTML `base` 语义解析 `img[src]`。仅采纳默认端口、无用户信息、且图片路径与当前书籍 ID 一致的 `img.wenku8.com` 封面，使用已验证的 HTTPS 地址。榜单及缺图的详情以该 CDN 的已知规则填充 URL。维持宿主默认请求头与 TLS 校验。Debug 版按已知封面路径记录响应状态、媒体类型是否为图片和最终目标是否仍为已知 CDN 路径；连接失败记录 `NETWORK`，日志不包含 URL 查询参数、Cookie、认证头或响应体。
- **[已实现／单元测试] 详情和目录。** 详情从 `#content` 提取标题、作者、简介、分类、状态及封面；缺少可选字段时可用同书目录或封面识别详情结构。按详情页给出的同书目录链接请求 `table.css`，从 `td.vcss` 获取卷名，从 `td.ccss > a[href]` 获取章名与链接。目录保留站点卷章顺序和插图条目，按同书规范路径去重，传给 Mihon 时转为新章在前的来源顺序。LightNovelReader 的选择器支持这一结构，LiteTale 的目录解析使用相同的卷章单元格，但取带 `aid`/`cid` 的另一种链接。 [LightNovelReader 选择器](https://github.com/Xempastissimo/LightNovelReader/blob/16ebd0dd2ee82c7cc159595f9e26e8cab1dfda50/app/src/main/java/com/xempastissimo/lightnovelreader/data/source/wenku8/Wenku8Selectors.kt) · [LiteTale 目录解析](https://github.com/metaMMY07/LiteTale/blob/ab4129b56e8825d375979d8f8dff77dfd44a1266/rust/src/wenku8/client.rs)
- **[本地样本回放]** 1.6.6 从已保存的书籍详情和目录提取出标题、作者、简介、封面和分属三个卷组的 21 个目录条目，包含「上卷 插图」`/novel/0/6/13917.htm`。回放使用原始样本的本地保存内容，没有向站点发起请求。
- **[Android 真机，用户报告]** 1.6.6 已显示真实热门书目、封面、书籍详情和带卷名的章节目录，多卷排序初步正确。正文阅读和章节插图渲染留在后续阶段。

## 阶段 3 第二批：热门、最新和搜索

- **[样本与用户报告]** 已保存的 `assets/search.html` 导航把「热门轻小说」指向 `/modules/article/toplist.php?sort=allvisit`，把「今日更新」和「最近更新」指向 `sort=lastupdate`。用户确认网页排行榜有分页。LightNovelReader 和 LiteTale 的榜单请求均使用该路径及 `page` 参数，按 `table.grid` 解析书籍卡片。单独的热度汇总页 `/top.php` 继续作为可选的 WebView 人工验证入口。匿名检查未取得榜单内容，登录后热门与最新的页面结构和翻页仍待 Android 实测。 [LightNovelReader 地址定义](https://github.com/Xempastissimo/LightNovelReader/blob/16ebd0dd2ee82c7cc159595f9e26e8cab1dfda50/app/src/main/java/com/xempastissimo/lightnovelreader/data/source/wenku8/Wenku8Urls.kt) · [LiteTale 客户端](https://github.com/metaMMY07/LiteTale/blob/ab4129b56e8825d375979d8f8dff77dfd44a1266/rust/src/wenku8/client.rs)
- **[浏览器实测与样本]** 中文完整书名的已确认请求链为 `POST /so.php` → 302 → `GET /modules/article/search.php?…` → 302 → `GET /book/6.htm`。搜「化」的保存样本中，`#content table.grid` 包含多个结果，`#pagelink` 内 `#pagestats` 为 `1/2`，`a.next` 指向 `/modules/article/search.php?searchtype=articlename&searchkey=%BB%AF&page=2`。插件首屏提交 GBK 网页表单，后续用相同词的 GBK URL 直接请求对应页码。无结果页候选只在有明确提示或空的搜索结果表时返回空，具体站点空结果文案仍待实测。
- **[已实现]** 两类榜单及搜索结果保留页面顺序，优先使用完整提示标题，合并同一书籍的多个链接并继续使用经确认的 HTTPS 封面。分页比对当前页标记和指向同一列表的下一页链接，拒绝错页或无法识别的页面。Mihon v0.21.3 在分页会话内按书籍 URL 过滤跨页重复，插件无需在多个搜索或标签之间共享翻页状态。 [Mihon 分页源码](https://github.com/mihonapp/mihon/blob/feadad5e1e2bf638be6e2ca317d11657b09d4596/data/src/main/java/tachiyomi/data/source/SourcePagingSource.kt)
- **[本地样本回放]** 将保存的「化」搜索结果作为模拟 GBK 响应回放，成功提取 20 本不同书籍和 HTTPS 封面，并识别第 2 页链接。未向站点发送搜索请求。

## 阶段 3 稳定性：搜索翻页与请求节流

- **[Android 真机，用户报告]** 1.6.7 的热门可翻页，最近更新、搜索、封面、详情和目录可用。「欢迎」「化」有结果渲染后仍出现「未识别到搜索结果」，「化物语」单本结果没有该提示。快速下滑或打开书籍会遇到 1.6.7 的 HTTP 429 提示。后续未能从手机日志读取当时的搜索响应，因此尚无法确认该次搜索响应的具体形态。
- **[源码与回归复现]** Mihon 依据 `hasNextPage` 预取后续结果，并针对完全空的追加页抛出无结果错误。1.6.7 首屏解析了下一页链接，但后续丢弃完整地址并重新拼接 GET，仅首屏认可明确空结果。用包含额外分页参数的下一页链接、以及已公告但后来明确为空的下一页，分别在旧代码上复现两次同文案异常。1.6.8 保存并验证同源下一页地址，保留原始编码和额外参数，已确认的末页优先于残留导航。如果后一页被站点明确标为空且前一页已发布，向 Mihon 返回一条已显示的书目以结束追加，宿主按 URL 过滤，不产生新卡片。首次搜索无结果仍返回空列表，未知 HTML 仍报解析错误。[Mihon 分页源码](https://github.com/mihonapp/mihon/blob/feadad5e1e2bf638be6e2ca317d11657b09d4596/data/src/main/java/tachiyomi/data/source/SourcePagingSource.kt)
- **[已实现与模拟验证]** 主站热门、最近更新、搜索、详情、目录及重定向跳数经过同一个协程队列，默认相邻请求起发间隔至少 2 秒。相同 URL 的 GET、相同词的未完成搜索提交按请求分别合并。没有订阅者时停止等待或取消 HTTP 请求。明确 429 或站内频繁访问提示触发全站冷却，优先遵守 `Retry-After` 秒数或 HTTP 日期，缺失或无效时为 30 秒。冷却期间快速失败并提示剩余时间，无自动重试。封面 CDN 不进入这条队列。2 秒为可调的本地默认值，网站实际阈值仍未确认。[LightNovelReader RateLimiter](https://github.com/Xempastissimo/LightNovelReader/blob/master/app/src/main/java/com/xempastissimo/lightnovelreader/data/network/RateLimiter.kt) · [LightNovelReader RefreshThrottle](https://github.com/Xempastissimo/LightNovelReader/blob/master/app/src/main/java/com/xempastissimo/lightnovelreader/data/network/RefreshThrottle.kt)
- **[实现选择]** 仓库 `core` 的 `rateLimit()` 在 OkHttp 网络拦截器内等待，取消后仍可能占用网络工作线程直到间隔到期。Wenku8 在提交 OkHttp 请求前挂起协程，并持续复用宿主 `await()`、Cookie 和 HTTPS 访问策略。Debug 只记录搜索页码、是否使用站点分页链接和结果分类，不输出关键词、链接查询参数或响应体。
- **[1.6.8 真机日志]** 搜索页 1 在 20:55:34 返回 HTTP 200 并显示结果。此后从站点分页链接请求页 2，间隔约 2 秒、4 秒和 6 秒均返回 HTTP 200。前两次未识别到书目，第三次正常解析。日志未包含响应正文，因此无法确认前两次 HTML 的具体内容或站点阈值。普通书目页和列表的倒计时提示来自 1.6.8 在已知冷却期间直接抛出的异常。
- **[1.6.9 策略]** 普通 HTML 请求仍按 2 秒间隔起发，搜索结果端点单独间隔至少 6.5 秒。6.5 秒是根据上述观测选择的客户端初始值。对站点已公告且通过 URL 验证的后续页，收到成功响应但缺少可识别书目时最多延后重取一次。明确空结果直接结束，登录、验证、外域和真实解析错误仍按各自分支处理。
- **[1.6.9 交互]** 请求在服务器冷却期内挂起，Mihon 继续显示加载状态。明确限流时按 `Retry-After` 等待，无有效响应头时等待 30 秒，之后同一请求最多自动恢复一次。持续限流才报告本次加载失败。等待中的搜索页会让出请求队列，普通请求可使用更早的可用时隙，离开页面会取消无人等待的任务。LightNovelReader 将正常间隔和全局冷却分开；LiteTale 记录主机冷却截止时间，Rust GET 网络错误重试另有次数和时间预算。 [LightNovelReader RateLimiter](https://github.com/Xempastissimo/LightNovelReader/blob/master/app/src/main/java/com/xempastissimo/lightnovelreader/data/network/RateLimiter.kt) · [LiteTale 冷却](https://github.com/metaMMY07/LiteTale/blob/ab4129b56e8825d375979d8f8dff77dfd44a1266/lib/services/wenku8_rate_limit.dart) · [LiteTale 请求策略](https://github.com/metaMMY07/LiteTale/blob/ab4129b56e8825d375979d8f8dff77dfd44a1266/rust/src/request_policy.rs)

## 阶段 4.1 章节正文

- **[样本]** 文字和纯插图章节保存样本均以 `#contentmain` 包裹 `#title`、广告及 `#content`。正文区域的头尾各有 `ul#contentdp`。正文文本通过 HTML 实体、缩进和 `br` 分隔。插图样本中 `div.divimage img.imagecontent` 直接给出 6 张图片的地址。
- **[已实现／模拟测试]** 读取章节时使用阶段 3 的 HTTPS、会话、编码与冷却策略。只有结构匹配的章节正文进入有序内容模型。登录、Cloudflare、429、连接失败或缺少内容分别报错。固定样式只渲染文字，解析出的插图链接不发起请求。
- **[源码与许可]** BiliNovel App 版先把章节数据转换为内联文本，再通过 `TextInterceptor.prepare()` 分页；拦截器从本地页面 URL 还原排版并绘制 PNG。公共 `lib/textinterceptor` 同样使用 Android 排版，但直接绘制整段单图。Wenku8 在仓库 Apache-2.0 许可下改编了 BiliNovel 的 `StaticLayout`／`Canvas` 绘制方式，按实际行边界切页并使用不含正文的本地 URL。两份现有实现及其网络接口保持独立。 [仓库许可](../LICENSE) · [BiliNovel 渲染器](../src/zh/bilinovel/src/eu/kanade/tachiyomi/extension/zh/bilinovel/TextInterceptor.kt) · [公共拦截器](../lib/textinterceptor/src/keiyoushi/lib/textinterceptor/TextInterceptor.kt)
- **[真机]** 1.6.10 用户已核对正文、长章翻页与重新打开，文字与网站一致。条漫截图中的章节过渡留到 4.2 核对。

## 阶段 4.2 插图与图文混排

- **[真实样本与有限联网]** 已保存的《化物语》卷首插图章给出 6 个 HTTPS JPEG 地址，主机为 `pic.777743.xyz`。仅抽查其中一张，电脑端收到 HTTP 200、JPEG、812 × 600 像素。此结果只覆盖该地址及当前出口。真实混排 HTML 与 Android 图片加载效果待真机验收。
- **[源码与模拟验证]** 插图使用正文节点提供的地址，支持相对、协议相对和转义路径。生产请求验证每次 HTTPS 跳转。主站地址共享阶段 3 的节流与冷却。外部图床独立访问，省略主站请求头及 Cookie。插图 429 或非图片响应只使对应阅读页失败。图片不会随章节 HTML 一起下载。
- **[源码与许可]** 参考仓库 Apache-2.0 下的 BiliNovel 当前 App 模式。App 分页先提交文字，再为插图建立独立图片页。Wenku8 在已验证的文字渲染器上加独立图片页，复用自己的文件与页面缓存。 [BiliNovel 章节结构](../src/zh/bilinovel/src/eu/kanade/tachiyomi/extension/zh/bilinovel/ChapterContent.kt) · [BiliNovel App 分页](../src/zh/bilinovel/src/eu/kanade/tachiyomi/extension/zh/bilinovel/TextInterceptor.kt) · [仓库许可](../LICENSE)

## 开源项目证据与界限

1. **LightNovelReader**：**[源码]** [地址及编码](https://github.com/Xempastissimo/LightNovelReader/blob/16ebd0dd2ee82c7cc159595f9e26e8cab1dfda50/app/src/main/java/com/xempastissimo/lightnovelreader/data/source/wenku8/Wenku8Urls.kt)、[选择器](https://github.com/Xempastissimo/LightNovelReader/blob/16ebd0dd2ee82c7cc159595f9e26e8cab1dfda50/app/src/main/java/com/xempastissimo/lightnovelreader/data/source/wenku8/Wenku8Selectors.kt)、[来源流程](https://github.com/Xempastissimo/LightNovelReader/blob/16ebd0dd2ee82c7cc159595f9e26e8cab1dfda50/app/src/main/java/com/xempastissimo/lightnovelreader/data/source/wenku8/Wenku8Source.kt)。榜单、搜索、详情、目录及正文均为网页 HTML；登录有表单和浏览器路径、保存站点 Cookie，正文含图片。其 [网络说明](https://github.com/Xempastissimo/LightNovelReader/blob/16ebd0dd2ee82c7cc159595f9e26e8cab1dfda50/README.md) 记载 HTML 使用 WebView、普通客户端可能被拦截，Cookie 未必充分；其浏览器抓取能力是该独立 App 的实现，不能假设 Mihon 扩展有同等能力。
2. **LiteTale**：**[源码]** [Rust 客户端](https://github.com/metaMMY07/LiteTale/blob/ab4129b56e8825d375979d8f8dff77dfd44a1266/rust/src/wenku8/client.rs) 以 `reqwest` 请求网站 HTML，处理 GBK、榜单、搜索、详情、目录、正文与插图；登录提交包含人工输入的 `checkcode`，并使用 Cookie 存储。它也有独立的本地索引搜索。调用函数名中的 `api` 不等于站点提供正式公开 API。[不确定] 这些 HTTP 请求在当前出口是否可用。
3. **MewX Android**：**[源码]** [项目说明](https://github.com/MewX/light-novel-library_Wenku8_Android/tree/aa52ae092af287dbba297807c1b8a5e556935914) 称使用作者自己的 Cloudflare Worker API 中继，且提到站长对 API 公开的限制；是项目作者的描述，不是官方授权文件。[调用方](https://github.com/MewX/light-novel-library_Wenku8_Android/blob/aa52ae092af287dbba297807c1b8a5e556935914/studio-android/LightNovelLibrary/app/src/main/java/org/mewx/wenku8/activity/NovelInfoActivity.java) 对详情、目录和正文调用 POST；[公开 API 桩](https://github.com/MewX/light-novel-library_Wenku8_Android/blob/aa52ae092af287dbba297807c1b8a5e556935914/studio-android/LightNovelLibrary/api-stub/src/main/java/org/mewx/wenku8/api/Wenku8API.java) 显示榜单、搜索、登录等方法名，却不提供有效地址和参数；[解析器](https://github.com/MewX/light-novel-library_Wenku8_Android/blob/aa52ae092af287dbba297807c1b8a5e556935914/studio-android/LightNovelLibrary/app/src/main/java/org/mewx/wenku8/global/api/Wenku8Parser.java) 解析 XML 形式的元信息与目录，项目也处理正文/插图；调用方使用 `LightUserSession` 登录和恢复状态。真实协议和授权 [不确定]，不调用该中继或推断它是公开接口。
4. **本仓库 BiliNovel**：**[源码]** [网页实现](../src/zh/bilinovel/src/eu/kanade/tachiyomi/extension/zh/bilinovel/BiliNovelWeb.kt) 为榜单、搜索、详情、目录及正文做 HTML 解析，并在搜索验证时提示用户手动在 WebView 操作；[来源类](../src/zh/bilinovel/src/eu/kanade/tachiyomi/extension/zh/bilinovel/BiliNovel.kt) 有独立 APP API 分支和 Cookie 状态检查，[API 会话](../src/zh/bilinovel/src/eu/kanade/tachiyomi/extension/zh/bilinovel/Api.kt) 有该站专属登录/令牌；正文经其文本拦截器转成阅读页。两站的地址、验证、登录及渲染细节不能直接等同。

## 决策与浏览器验证需求

- **[源码 + 不确定] 主方向 A：** 若站点规则允许、且当前环境与实际 Mihon 设备均验证普通 HTTP 可读，使用用户主动请求驱动的 HTML 获取与解析。当前环境首页被拦截，A 的线上可行性尚未成立。
- **[源码 + 不确定] 有限备用 B：** 仅在 Mihon 确有可用入口、站点允许、用户自行完成登录/验证且设备验证会话可用时，用宿主 WebView 处理交互，再测试普通请求是否能使用会话；不得假设 Cookie 可解决 403。若仍被拦截即停止并提示，不能通过隐藏 WebView 批量取页或自动解验证。是否能从扩展安全调用宿主入口与共享会话尚待验证。
- **[不确定] C：** 仅在找到站点正式公开或明确授权的 API/数据通道并确认使用条款后重新评估；当前没有足够证据，第三方中继不纳入方案。

用户已提供总热度榜 DOM、详情/目录/搜索/两种章节的本地样本及上述浏览器 Network 信息。仍需核实网站使用规则、真实图文混排、其他页面的编码和登录前后差异，以及 Mihon 设备上的 WebView 登录、Cookie 共享和普通 HTTP/图片请求。其他榜块结构也有待确认。无需密码、Cookie、Token、完整请求头、HAR 或浏览器配置；勿包含账号信息或整章正文。浏览器能访问仅证明该浏览器会话，不证明 Mihon/OkHttp 可用。

**[不确定] 阶段 1 验收：** 访问方向、结构样本及已登录 Chrome 的关键证据已记录，具备启动阶段 2 离线编码与真机验证规划的条件；真实联网功能和发布仍等待站点规则与 Mihon 设备验证。阶段 2 须验证 WebView 登录、Cookie 共享及普通 HTTP/图片访问；真实正文分页、图文顺序与显示质量属于后续阅读渲染阶段。真实混排和站点使用规则仍待确认。本次不实现阶段 2。
