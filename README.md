# Handora 1

[English](README.en.md) | 简体中文

这是一个面向安卓系统、用于微信消息接管的侧载原型，目前测试过vivo Android 13/One Plus Android 15都可正常运行。应用读取屏幕内容，
调用LLM回复，并使用自定义输入法写入和发送。设备操作同时支持现有
Shizuku 传输和第一阶段 Route C 无障碍传输。要用请下载最新发布版本，老旧版本会有一些bug

> 这是公开的源代码仓库，不是腾讯或微信官方产品。APK 不纳入源码仓库，
> 仅可能通过 GitHub Releases 提供测试构建。使用者只能操作自己有权使用的
> 账号和设备；必须遵守微信条款、当地法律和无障碍服务相关规则。不得用于
> 骚扰、垃圾消息、冒充或未经同意的自动化。

## 使用方法

- 完成初次运行前配置后打开微信，点进需要代理聊天的联系人聊天界面，回到 App
  开启代理后切回聊天界面即可。建议开启悬浮球功能，方便控制暂停或停止。
  

## 下载 APK

- [下载 1.0.129 Route C 测试版](https://github.com/mslaxne/handora-android-public/releases/tag/v1.0.129-test.1)，我没有其他手机了，希望能得到各位的测试反馈
- 该构建使用 Android 开发/调试证书签名
- 问题反馈请提供设备型号、Android 版本、微信版本、App 版本和复现步骤；
  不要上传 API Key、聊天内容或其他敏感数据。

## 功能

- 设置界面可自定义人格并上传聊天样例，LLM会根据你指定的人格聊天
- 可以编辑保存多个不同人格，并分别指定对不同的联系人用不同的人格
- 支持人格感知的 LLM 回复生成；身份、角色和提示注入边界只通过系统提示约束，
  不再用关键词或固定文案替换模型正文。
- 使用 MediaProjection 截屏，默认延迟 5 秒。
- 通过无障碍手势点击对应消息栏与发送区域。
- 自定义 InputMethodService 写入测试文本 `这是测试`。
- 独立 `routeB` 构建通过 Shizuku UserService 完成截图和点击。
- Route C 通过本地 AccessibilityService 完成截图、手势点击、前台包名识别和
  全局操作。
- 持久保存传输路线选择；Route C 是默认运行路径。
- Route C 使用常驻 BotIme 写入，不通过程序自动切换输入法。Route C 是无界面
  输入法，不显示输入视图，也不请求软键盘 inset，因此写入时微信仍保持正常输入栏
  位置。
- BotIme 在 `commitText` 和清理文本前重新检查当前编辑器包名；如果焦点离开
  `com.tencent.mm`，会在修改文本前拒绝操作。
- 通过 `OcrEngine` 接口支持本地 RapidOCR Android 识别。
- 低内存读屏使用按行缓存的 `BitmapPixelSource`，不再创建整屏
  `IntArray` 副本。每帧仍执行 OCR，因为较小的新消息气泡可能低于整屏场景差异
  阈值。
- 短消息在持续可见时去重；追踪器观察到气泡消失并重新出现后再次启用检测。
  长文本模糊匹配和单快照出现次数规则保持不变。
- Route C 发送截图从 1.5 秒间隔开始；如果 Android 报告限流，自动退避到
  3.5 秒。轮询仍保持原有的 3 秒节奏。
- 截图/OCR 轮询运行在独立 worker；模型生成和投递运行在串行 action worker。
  设备截图与发送共享锁，避免发送时键盘移动污染 OCR。
- 支持前台轮询、基线抑制和 3 秒连发收集。
- Agent 启动或恢复后会评估当前可见聊天屏幕。最后一条是对方消息时，将持久
  会话历史与整屏可见消息合并、去重后交给模型；最后一条是自己或无法判断来源
  时不主动回复。
- 同一首屏会生成确定性 ID。未发送、待发送或部分发送的回复会复用，不会重复
  调用模型；发送结果未知或已失败的任务需要人工检查。
- 模型错误会持久化重试，最多 5 次，使用 5/10/20/40 秒退避，并在 Home 显示
  队列数量。
- 自动发送具有硬限制：规范化联系人白名单、每日发送气泡上限、基于实际 token
  用量的每日 API 费用上限，以及可配置的免打扰时段。
- 支持回复拆分、自定义输入法写入和 Shizuku 发送点击确认。
- 测量得到的快速发送点只适用于精确匹配的屏幕、密度和微信版本配置。未知配置
  会从当前截图检测绿色发送按钮中心，而不是点击固定坐标。BotIme 不提供手动
  测试写入按钮。
- 共享 `DeviceProfile` 提供归一化输入锚点、消息保护边界、快速 OCR 区域、
  输入栏范围和绿色按钮搜索半径。已验证的 vivo 布局保留原始测量像素；未知屏幕
  使用有界比例。
- 设置中的“设备适配”支持本地截图校准。应用会验证输入栏和绿色发送按钮是否
  可见，保存包含屏幕、密度、方向、字体缩放和微信版本的配置，并在解析后删除
  截图。
- 启动检查区分硬需求和可靠性提示。通知、传输、输入法或 API key 缺失时，会
  阻止对应能力；电池优化白名单或未适配布局只显示警告。
- 设置中的“模型配置”支持 DeepSeek、免费/自定义 OpenAI 兼容服务和关闭生成。
  免费服务使用用户自己的 provider key，应用中不打包共享 API key。
- 如果 BotIme 已启用但不是默认输入法，Route C 会打开系统输入法选择器；只有
  输入法被禁用时才打开输入法管理页面。
- 主动发送期间不再重新检查聊天标题像素，因为微信会把标题替换为
  `对方正在输入中`。前台包名和 BotIme 编辑器包名检查仍保留。自动发送期间，
  用户不得切换聊天。
- 支持显式 Agent 会话、按聊天隔离的记忆、关闭时事件摘要，以及可配置的
  1/3/7/31 天或永久保留。
- 切换联系人或停止 Agent 时，立即分离当前会话。单后台记忆 worker 会总结
  正在关闭和待处理会话，支持持久恢复，并在界面显示队列进度。
- 每个联系人都有可读的摘要归档，包含本地开始/结束时间、持续时间，以及按天数
  或条数独立配置的保留策略。
- 记忆保留策略保护未解决事件，以及日期为今天或未来的事件，即使其最后提及时间
  早于清理截止点。
- 支持可拖动的悬浮气泡，分别提供开始/暂停、停止和收起操作。
- Home 左上角提供 `退出` 操作：停止轮询、移除气泡、取消通知，尝试有界关闭
  记忆，然后在记忆总结失败或超时时仍完成退出。
- Home、Memory 和 Settings 使用轻量原生布局，诊断功能移至高级区域。详见
  `UI_LIGHT.md`。

## 代码边界

- `AgentStartPolicy` 负责可测试的启动决策和设置跳转目标。
- `AgentControl` 是 Home、悬浮气泡和诊断页面共用的唯一启动/暂停/恢复/停止
  入口。
- `HomePageView` 负责 Home 渲染；`MainActivity` 保留导航、权限和生命周期。
- `MemoryPageView`、`SettingsPageView` 和 `DiagnosticsPageView` 分别负责页面
  渲染、刷新和页面内操作。
- `BotLoopService` 负责前台服务生命周期和总体编排。
  `PollingCoordinator`、`ReplyGenerationCoordinator`、`MemoryCoordinator` 和
  `SendQueueCoordinator` 分别负责各自的循环职责。
- `ReplyRetryStore` 将失败生成任务持久化到 `files/reply-retries/`，重试仍
  限制在原聊天范围内。
- `AppShutdownCoordinator` 负责独立的本地优先退出路径，不经过常规停止并总结
  页面，也不会无限等待记忆总结。
- `ImeWritePolicy` 是 `commitText` 和 `deleteSurroundingText` 前最后执行的
  包名保护。
- `MainUiComponents` 包含共享原生 UI 组件。
- `ProbeStatus` 是独立于 Activity 的应用级诊断状态。

## 构建

使用 Android Studio 打开此目录，或在将 `local.properties` 指向包含 platform 35
和 build-tools 35 的 Android SDK 后，通过 Gradle 构建。

完整执行 Route C 构建、测试、Lint、安装和状态恢复门禁：

```powershell
.\deploy.ps1 -Flavor routeC
```

```powershell
gradlew.bat :app:assembleRouteCRelease
```

APK 输出位置：

```text
app/build/outputs/apk/routeC/release/app-routeC-release.apk
```

运行纯逻辑回归和 Lint：

```powershell
gradlew.bat :app:testRouteCDebugUnitTest :app:lintRouteCDebug
```

默认 `routeC` APK 不包含 Shizuku provider、AIDL 和 API 依赖。构建回退版本：

```powershell
.\deploy.ps1 -Flavor routeB
```

```text
app/build/outputs/apk/routeB/release/app-routeB-release.apk
```

## 设备配置

1. 安装并打开 APK。
2. 允许通知权限。
3. 点击截图按钮并批准全屏捕获。
4. 在 Android 无障碍设置中启用应用服务。
5. 启用 `Handora 1 输入法`，并将 BotIme 设为系统默认键盘。
6. 保持微信位于前台，以执行延迟截图和点击测试。
7. 将应用加入系统电池优化白名单；vivo 设备如有 iManager，还需允许自启动和
   后台活动。
8. 在目标 vivo 构建上保持应用无障碍服务开启，作为后台保活。Route C 使用它
   完成截图和手势。

## Route B 回退构建

安装并启动 Shizuku 13.6.0 或更高版本，然后使用应用中的
`连接 / 授权 Shizuku` 操作。特权 UserService 以 shell UID 运行，只实现
`screencap` 和 `input tap`。

Shizuku 控件只会编译进 `routeB` flavor。默认 `routeC` flavor 不包含 Shizuku
provider、AIDL 或 API 依赖。

RapidOCR AAR 位于 `app/libs/`；第三方说明见 `THIRD_PARTY_NOTICES.md`。

## Route C 配置

1. 启用应用 AccessibilityService。
2. 启用 BotIme 并将其设为系统默认键盘。
3. 停止 Agent，在设置中启用“路线 C 无障碍传输”。
4. 确认前台聊天是微信，然后运行现有 dry-run。

Route C 不会选择或恢复输入法。如果 BotIme 不是当前默认输入法，Route C 的启动
和测试入口会提前拒绝；输入连接不是微信时，发送器也会拒绝写入。详见
`ROUTE_C.md`。

截图保存在应用私有目录 `files/captures`。

通过“模型配置”设置密钥。密钥使用 Android Keystore 加密，绝不会
从本仓库或 `.env` 文件读取。
