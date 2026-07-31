# Telegram Android MCP 接口覆盖率与正确性审计

## 1. 文档信息

| 项目 | 值 |
|---|---|
| 审计日期 | 2026-07-30 |
| 审计对象 | Telegram Android debug MCP 原型 |
| Telegram 版本 | 12.9.0 |
| 源码基线 | `9bcf3d2769c6d3f07105a992e5d9493e33ac3348` |
| Android 包名 | `org.telegram.messenger.beta` |
| 构建变体 | `afatDebug` |
| MCP Server 版本 | `0.1.0` |
| MCP 协议声明 | `2025-03-26` |
| 运行证据目录 | `.mcp-work/telegram-mcp-20260729/` |
| 审计方式 | 源码静态审计、接口目录审计、协议核对、现有构建与运行证据复核、严格产物审计 |

本文审计的是当前工作区中的 MCP 实现，而不是 Telegram 官方客户端对 MCP 的支持情况。文中的覆盖率范围是按稳定、可组合的用户意图估算，不是用 UI 类、按钮或 RPC 数量直接相除得到的精确百分比。

## 2. 结论摘要

当前实现已经形成了一个安全意识较好、结构基本完整的 MCP 原型，但尚不能视为“基本覆盖 Telegram Android”，也不能认定 46 个接口已经能够圆满完成各自业务。

核心结论如下：

- 目录中共有 46 个 MCP 工具，工具发现和输入 Schema 基本完整。
- 项目自己的能力清单共有 56 项，其中 46 项是 MCP 工具、10 项是系统边界；这 56 项来自手工清单，不是 Telegram 全功能全集。
- 46 项 GUI 覆盖全部被生成器标记为 `partial`，没有任何一项被标记为 `full`。
- 现有验证报告中只有 2 个工具通过运行时成功路径，44 个工具因未登录而被阻塞。
- 成功的两个工具是 `telegram.system.health` 和 `telegram.account.list`；尚无成功的 Telegram 用户业务写操作。
- 按完整 APP 的稳定用户意图估算，当前可靠语义覆盖约为 15%–25%；计入只能部分完成的功能，约为 25%–35%；约 65%–75% 仍未覆盖。
- 消息写入、删除、群组/频道写入、会话终止等接口存在 P0 级正确性或可验证性问题，不应直接用于真实账号上的不可逆任务。
- 大多数未覆盖功能都可以 MCP 化。真正不能无人值守完成的是可信认证、系统授权、最终付款确认和必须由人参与的现实行为；这些应采用人机接力，而不是把整个业务域放弃。

因此，当前适合的产品定位是：

> 已构建、可发现、可继续验证和修复的 Telegram Android MCP 原型；尚未达到生产级完整性或业务闭环要求。

## 3. 审计口径

本审计严格区分以下证据层，避免把“接口存在”误认为“功能成功”：

1. **注册证据**：工具能够出现在 `tools/list` 中。
2. **契约证据**：输入 Schema、风险注解和错误结构存在且可解析。
3. **实现证据**：工具已经连接到 Java handler、controller 或 MTProto 请求。
4. **运行证据**：安装后的 APP 实际调用工具并得到预期结果。
5. **业务效果证据**：服务端和本地状态确实发生了目标变化。
6. **独立回读证据**：使用不同读取路径确认变化，而不是读取刚刚手动修改的同一缓存。
7. **GUI 等价证据**：GUI 能看到相同结果，且没有破坏 Telegram 自身状态机。
8. **持久化证据**：重启 APP 后结果仍然正确。
9. **安全与清理证据**：错误、超时、重试和清理均有可审计结果。

只有完成对应业务所需要的全部证据层，工具才能标记为 `verified` 或对应 GUI 功能的 `full`。

## 4. 现有 MCP 工具盘点

当前目录定义了 46 个工具，按域分布如下：

| 域 | 数量 | 主要能力 | 当前判断 |
|---|---:|---|---|
| system | 1 | 健康检查 | 已运行验证，但不属于 Telegram 用户业务 |
| account | 2 | 账号槽位、本人资料 | 账号槽位已验证；本人资料仅验证了未登录安全错误 |
| peer | 1 | peer 解析 | 已注册，未登录成功路径未验证 |
| dialog | 8 | 列表、归档、静音、置顶、清历史 | 部分覆盖；读取依赖缓存，写入和回读不够独立 |
| message | 13 | 历史、搜索、文本发送/编辑/删除/转发、反应、已读、置顶 | 当前最大功能域，但包含多项 P0 问题 |
| draft | 3 | 获取、设置、清空草稿 | 可达但存在覆盖原有富草稿状态的问题 |
| contact | 5 | 列表、搜索、拉黑列表、拉黑/取消 | 搜索契约与实现不一致，Bool 响应可能假成功 |
| chat | 8 | 创建群/频道、基础资料、成员列表、加入/退出 | 只覆盖基础动作，不覆盖管理能力；写入同步尚不可靠 |
| settings | 2 | 读取和修改少量布尔设置 | 仅 13 个本地设置，且实际是全局配置而非账号配置 |
| profile | 1 | 修改基础资料 | 未完成登录后的成功闭环验证 |
| session | 2 | 会话列表、终止会话 | 终止回读逻辑存在结构性假阳性 |

完整接口目录见 [`agent/TELEGRAM_MCP_AGENT_CATALOG.md`](../../agent/TELEGRAM_MCP_AGENT_CATALOG.md)。

## 5. 覆盖率结论

### 5.1 不能采用的“82.1%”口径

`46 / 56 = 82.1%` 只能表示当前手工能力清单中的暴露比例，不能表示 Telegram APP 覆盖率，原因包括：

- 46 个候选能力在 [`generate_capability_inventory.py`](generate_capability_inventory.py) 中由 `CAPABILITIES` 手工写死。
- 10 个系统边界同样来自手工维护的 `SYSTEM_BOUNDARIES`。
- 生成器只遍历这两份列表，不会从 Telegram GUI、controller 和 RPC 自动发现完整用户意图。
- 生成器固定把所有已暴露 GUI 能力写成 `coverage_status: partial`。
- 生成器中的源码证据经常只证明 Telegram 原 APP 存在某条业务路径，并不证明 MCP handler 实际使用了同一条路径。

典型例子：清单把文本发送的 `SendMessagesHelper` 当作稳定业务 seam，但 MCP handler 实际直接发送 `TL_messages_sendMessage`，两者并不等价。

### 5.2 分层覆盖结果

| 覆盖口径 | 结果 | 结论 |
|---|---:|---|
| 工具注册 | 46/46 | 只证明工具可发现 |
| 手工能力清单 | 46/56 | 46 项已暴露，10 项被标记为系统边界 |
| 能力状态 | 2 verified、44 implemented、10 degraded | `implemented` 不等于运行成功 |
| GUI 覆盖状态 | 46 partial、10 system-boundary | 没有 `full` |
| 工具验证报告 | 2 passed、44 blocked | 当前 beta 包未登录 |
| 运行时工具证据 | 2 success、1 safe-error、43 registration/schema-only | 没有成功写操作 |
| 全 APP 可靠语义覆盖估算 | 15%–25% | 能独立表达的基础用户意图范围 |
| 全 APP 部分可达覆盖估算 | 25%–35% | 包含缺少完整参数、回读或状态同步的能力 |
| 未覆盖估算 | 65%–75% | 仍需新增工具、补充语义或人机接力 |

严格产物审计得到 `ok: false`，原因是 44 个验证状态为 `blocked` 的工具仍被能力清单标记为 `implemented`，没有同步降级。相关原始证据：

- [`capability-inventory.json`](../../.mcp-work/telegram-mcp-20260729/capability-inventory.json)
- [`validation-report.json`](../../.mcp-work/telegram-mcp-20260729/validation-report.json)
- [`runtime-validation.json`](../../.mcp-work/telegram-mcp-20260729/runtime-validation.json)
- [`surface-scan.json`](../../.mcp-work/telegram-mcp-20260729/surface-scan.json)

### 5.3 全 APP 功能族覆盖

当前有实质性但不完整覆盖的功能族主要是：

- 账号和基础个人资料。
- 对话、归档、草稿和对话级通知状态。
- 纯文本消息、历史和搜索。
- 联系人及拉黑状态。
- 群组/频道的创建和基础资料操作。
- 少量本地设置。
- 登录会话列表及终止入口。

基本未覆盖或只记录为系统边界的功能族包括：

- 媒体和文件消息。
- 论坛主题。
- 完整群组/频道管理。
- Stories。
- 通话、群通话和直播。
- Bot、inline bot、Mini App 和 Business。
- 贴纸、GIF、自定义 emoji 和 emoji status。
- 自定义文件夹和共享聊天列表。
- 完整通知、隐私、外观、存储、流量和代理设置。
- Premium、Stars、礼物、boost 和支付。
- Secret Chat 及 Android 系统集成任务。

## 6. 未覆盖但适合 MCP 化的能力

以下能力拥有稳定的领域对象、controller 或 MTProto 请求，原则上适合设计为语义化 MCP 工具。

### 6.1 消息、媒体和文件

- 上传和下载文件，返回稳定 file reference、进度和校验信息。
- 发送照片、视频、相册、文档、音乐、语音和视频消息。
- 发送贴纸、GIF、联系人卡、位置、投票和骰子。
- caption、entities、Markdown/格式化、spoiler、富回复和引用。
- 修改媒体或 caption。
- 下载消息附件、列出共享媒体和按媒体类型搜索。
- 定时消息的读取、编辑、删除、重复策略和独立回读。

文件入口应采用受限暂存区、明确 MIME/大小限制和路径 allowlist，不能暴露任意 Android 文件系统访问。

### 6.2 论坛、群组和频道管理

- 主题列表、创建、编辑、关闭、重开、删除和置顶。
- 添加/移除成员、提升/撤销管理员、设置权限和封禁。
- 默认成员权限、慢速模式、自动删除、反应策略和签名。
- 邀请链接、加入申请、审批、用户名、公开/私有状态和头像。
- 关联讨论组、管理员日志、统计、boost 状态和成员搜索。

### 6.3 对话组织、联系人和搜索

- 自定义文件夹、过滤规则、排序和共享聊天列表。
- 主动同步并分页读取完整对话列表。
- 全局用户、群组和频道搜索。
- 联系人添加、编辑、删除、导入、同步和邀请。
- Saved Messages 标签、共享内容分类和高级消息过滤。

### 6.4 资料、通知、隐私和本地设置

- 头像/视频头像、用户名、生日、emoji status 和完整资料字段。
- 每会话通知例外、声音、预览和自定义通知策略。
- 在线状态、手机号、头像、转发、通话和拉群隐私规则。
- 自动删除、活跃会话安全状态、2FA 状态查询和恢复准备。
- 主题、壁纸、语言、翻译、字体、夜间模式和动画设置。
- 自动下载、缓存清理、存储统计、流量使用和代理配置。

### 6.5 Stories、Bot、Business 和内容生态

- Story 列表、查看、发布、编辑、删除、归档、隐私、浏览者和反应。
- Bot 命令、inline query、消息按钮和 callback。
- Business 简介、营业时间、位置、欢迎/离开消息、chatbot 和业务链接。
- Quick Reply 的创建、编辑、发送和管理。
- 贴纸包、GIF 收藏、自定义 emoji 和 emoji status 管理。

Mini App 页面本身通常需要浏览器或可信 WebView 接力，但其可稳定表达的启动、状态和结果部分可以封装。

### 6.6 通话、Secret Chat 和金融状态

- 通话记录、预约、发起请求、接听/挂断、静音和状态查询。
- 群通话、直播和参与者控制中的非媒体流部分。
- Secret Chat 的创建、TTL、状态查询和消息操作；应按高风险、显式确认和严格日志脱敏处理。
- Premium、Stars、礼物、invoice 和 boost 的余额、报价、资格和状态查询。
- 支付前订单准备、风险提示和最终确认所需信息。

## 7. 需要人机接力的功能

当前清单把 10 项整体标记为 `non-automatable`，这个结论过于宽泛。应将业务中可 MCP 化的部分和必须由用户完成的可信步骤拆开。

| 领域 | 可由 MCP 完成 | 必须由用户或系统完成 |
|---|---|---|
| 登录 | 准备登录、查询状态、等待并恢复流程 | 手机号确认、验证码、2FA、Passkey、CAPTCHA |
| 文件/相册 | 发送受控暂存区中的已有文件 | 系统 picker 中的最终选择和授权 |
| 相机/麦克风 | 上传、处理和发送已经生成的媒体 | 权限授予、现场拍摄和录音 |
| 定位 | 发送明确经纬度、管理已有实时位置 | 读取设备传感器和实时位置授权 |
| 二维码 | 解码用户提供的图片、处理解析结果 | 通过摄像头现场扫描和授权 |
| 通话 | 查询、预约、发起、接听/挂断和状态控制 | 真实语音/视频交流及权限确认 |
| 支付 | 查询余额/报价、准备订单、返回确认摘要 | 最终付款、系统购买、强认证和签名 |
| 外部分享 | 准备内容和显式目标参数 | Android chooser 中的选择 |
| 桌面组件 | 准备配置数据和预览 | Launcher 中的添加、摆放和视觉确认 |

推荐统一采用以下状态机：

```text
prepare -> handoff_required -> user/system action -> status/resume -> verified
```

## 8. 应放弃无人值守自动化的部分

以下行为不应通过 MCP 绕过，也不应伪装成成功：

- 绕过生物识别、本地密码、验证码、2FA、Passkey 或 CAPTCHA。
- 绕过 Android 权限弹窗、可信系统确认界面或安全警告。
- 未经用户最终确认执行付款、购买、签名或不可逆授权。
- 替用户完成真实语音/视频交流。
- 未经知情同意进行现场拍摄、录音或持续定位。
- 依赖主观视觉判断、手势反馈且没有稳定领域语义的自由裁剪、绘画和布局操作。

放弃的是“无人值守绕过可信边界”，不是放弃整个功能域。对应 MCP 应提供准备、状态、接力说明和恢复接口。

## 9. 已有实现的优点

当前实现中值得保留的设计包括：

- `ApplicationLoader` 和 MCP Server 内部均检查 debug 状态，release 不启动服务。
- 服务只绑定 `127.0.0.1`。
- 使用随机 256-bit bearer token，并使用常量时间比较。
- 请求体限制为 1 MiB。
- HTTP 响应包含 `Cache-Control: no-store` 和 `X-Content-Type-Options: nosniff`。
- 输入对象 Schema 默认关闭额外字段，减少模型误传和参数注入。
- 高影响操作要求 `_confirm: true`，宿主网关还提供本地确认。
- 宿主侧实现了敏感字段脱敏和 token 隔离。
- 文本发送和转发已经引入幂等键和回放缓存的基本设计。
- 工具目录由生成器确定性生成，并存在 drift check。
- APK 构建和安装证据完整，APK 内目录与当前 catalog 一致。
- 工具错误使用结构化 envelope，并区分 `retryable`。

这些基础可以继续使用，不需要推倒重写。

## 10. 关键缺陷

### AUD-P0-01：消息写操作绕过 Telegram 自身发送状态机

**证据**

- GUI 在 [`ChatActivityEnterView.java`](../../TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java) 中构造 `SendMessagesHelper.SendMessageParams`。
- `SendMessagesHelper` 负责本地 pending message、实体、回复、草稿、重试、付费消息、定时重复、存储和 UI 一致性。
- MCP 在 [`TelegramMcpService.java`](../../TMessagesProj/src/main/java/org/telegram/messenger/mcp/TelegramMcpService.java) 中直接构造并发送 `TL_messages_sendMessage`。

**影响**

服务器可能已经接受消息，但本地数据库、发送状态、草稿和 GUI 没有以 Telegram 预期方式更新。相同问题也影响直接发送 edit、forward、reaction 和部分 chat 写请求。

**整改**

- 优先调用 GUI 使用的稳定 domain helper，而不是直接复制 MTProto 请求。
- 如果必须直调 MTProto，应完整复刻 helper 的本地消息、随机 ID、存储、差分、错误和重试状态机，并以专项测试证明等价。

### AUD-P0-02：`processUpdates` 线程约束被违反

**证据**

- [`MessagesController.processUpdates`](../../TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java) 明确注明必须运行在 `Utilities.stageQueue`。
- MCP 的 `processUpdates` wrapper 通过 `uiCall` 在主线程调用该方法。

**影响**

可能产生竞态、数据库/缓存顺序错误、偶发崩溃或 GUI 状态不同步。所有调用该 wrapper 的网络写操作均受影响。

**整改**

- 将 update 处理投递到 `Utilities.stageQueue`。
- 等待正确的完成信号，而不是只等待 UI 线程任务返回。
- 增加线程断言、并发回归和 crash-log 检查。

### AUD-P0-03：message ID 与 peer 缺少归属校验

**证据**

- 非频道 `message.get` 使用不携带 peer 的 `TL_messages_getMessages`。
- 返回消息被包装成调用方传入的 peer，但没有验证每条消息的真实 dialog。
- 非频道删除请求最终只发送 message ID；MCP 在执行前没有确认 ID 属于目标 peer/topic。

**影响**

错误的 peer 和 message ID 组合可能读取、编辑、置顶或删除另一个对话中的消息；确认界面展示的目标也可能与实际副作用不一致。

**整改**

- 所有消息写操作先执行强制 preflight。
- 验证每个 ID 的真实 dialog、topic、作者、可编辑/可删除权限和消息类型。
- 批量操作必须全量验证后再提交，默认不允许部分成功。

### AUD-P0-04：非空响应被当作成功，可能产生假阳性

**证据**

- 通用 `request` helper 只检查 error 和 null response。
- 拉黑、取消拉黑、修改群简介和终止会话等请求可能返回 Telegram `BoolFalse`。
- handler 没有检查 Bool 值，却返回 `ok: true` 或 `terminated: true`。

**影响**

智能体会在业务实际失败时继续执行后续步骤，造成错误决策或错误安全结论。

**整改**

- 每个工具声明允许的成功响应类型和业务谓词。
- `BoolFalse`、空 ID、空 chat 或缺失预期 update 必须视为失败或未知结果。
- 将响应形状检查纳入工具级单元测试。

### AUD-P0-05：定时消息返回值和回读路径不完整

**证据**

- `extractMessageIds` 支持普通新消息和 `TL_updateShortSentMessage`，但遗漏 `TL_updateNewScheduledMessage`。
- 定时发送仍可能返回空 `message_ids`。
- 普通历史或普通 `message.get` 不能作为定时消息的可靠回读。

**影响**

工具可能报告发送成功，却不给调用方可继续操作的 scheduled message ID，也无法验证或清理。

**整改**

- 提取 scheduled update 中的稳定 ID。
- 定时消息使用独立的 list/get/edit/delete/readback 契约。
- 没有得到预期 scheduled object 时不得返回完整成功。

### AUD-P0-06：`session.terminate` 的引用和回读结构性失真

**证据**

- `session.list` 每次为所有 authorization 生成新的随机 `session_id`。
- `session.terminate` 在请求发送前从内存中删除引用。
- 清单建议通过“新列表中不再出现旧 session_id”验证，但列表每次都会更换全部 ID。
- 工具声明为幂等，第二次调用同一 ID 却会返回 `STALE_REFERENCE`。

**影响**

终止失败也可能通过回读；超时或错误后无法安全重试；幂等声明与实际行为不一致。

**整改**

- 使用基于 authorization hash 的稳定、不可逆 HMAC 引用，或在受控存储中保持稳定映射。
- 请求确认成功后再失效引用。
- 回读真实 authorization 集合或稳定设备指纹，而不是随机引用是否消失。
- 修正幂等语义和失败恢复流程。

### AUD-P0-07：超时后副作用未知，却允许直接重试

**证据**

- 网络超时后本地取消请求并返回 `retryable: true`，但服务器可能已经提交。
- `uiCall` 超时后无法撤销已经投递到主线程的 Runnable。
- 除发送和转发外，大部分写操作没有幂等键。

**影响**

创建群组/频道、修改资料或其他写操作可能在调用方收到失败后发生；直接重试可能生成重复对象或重复副作用。

**整改**

- 超时返回 `outcome: unknown`，携带 operation ID 和明确 read-before-retry 指令。
- 为可重复写入增加幂等键或可检索的 client operation ID。
- 先独立读取当前状态，再决定重试、补偿或报告人工处理。

### AUD-P0-08：MCP 生命周期和 Streamable HTTP 实现不完整

服务声明协议版本 `2025-03-26`，但当前实现存在：

- 未验证 `Origin`。
- 未强制首先执行 `initialize`，也未维护初始化状态。
- 未校验 `jsonrpc: "2.0"`。
- `notifications/initialized` 返回 204，而该版本的 HTTP 通知约定为 202。
- 不提供 SSE 时，`GET /mcp` 返回 404，而规范要求 405。
- 未知 resource 异常可能落入通用 HTTP 500，而不是 JSON-RPC 错误。
- 声明旧协议版本的同时返回后续版本引入的 `structuredContent`，存在版本混用。

对照规范：

- [MCP 2025-03-26 生命周期](https://modelcontextprotocol.io/specification/2025-03-26/basic/lifecycle)
- [MCP 2025-03-26 传输](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [MCP 2025-03-26 工具](https://modelcontextprotocol.io/specification/2025-03-26/server/tools)
- [MCP 2025-06 变更记录](https://modelcontextprotocol.io/specification/2025-06-18/changelog)

**整改**

- 选择一个明确协议版本并完整实现其生命周期和传输要求。
- 增加 Origin allowlist、initialize 状态、通知语义和 JSON-RPC 错误测试。
- 如果继续使用 `structuredContent`，应协商支持对应版本并为工具增加输出 Schema。

### AUD-P1-01：对话和联系人读取把缓存当成完整数据

**证据**

- `dialog.list` 只读取 `MessagesController.getDialogs(folderId)` 当前缓存。
- 没有主动加载、同步状态、offset/cursor 或完整性标志。
- `contact.list/search` 只遍历 `ContactsController.contacts`。
- `contact.search` 不搜索群组或 Telegram 全局用户，却可能被目录描述成更广泛搜索。

**影响**

空结果无法区分“没有对象”和“缓存尚未加载”；分页任务可能漏掉目标；智能体可能错误地创建重复联系人或判断会话不存在。

**整改**

- 返回 `source`、`complete`、`sync_state`、`next_cursor`、`total_count` 和 `stale_after`。
- 提供主动同步和真正的服务端分页接口。
- 将本地联系人搜索与全局 peer 搜索拆成不同工具。

### AUD-P1-02：多个回读策略不是独立证明

存在以下弱回读或错误回读：

- topic 静音通过 dialog 级 `isDialogMuted(dialog.id, 0)` 验证。
- topic 已读通过 dialog 级 unread count 验证。
- 删除通过首屏历史或搜索中未出现验证。
- 旧消息置顶通过首屏历史验证，目标可能根本不在首屏。
- 联系人拉黑通过刚刚被 handler 手动修改的同一内存缓存验证。
- settings 测试把当前值再次写为当前值，不能证明写入。
- draft 回读只证明当前进程内缓存，不证明服务器同步和重启持久化。

**整改**

- 为每个写工具定义独立服务器读取或存储读取路径。
- 测试使用与原值不同的唯一值。
- 增加 APP 重启后的二次回读。
- 删除验证必须按精确 ID 和目标 peer 查询，不能使用搜索缺失替代。

### AUD-P1-03：草稿和设置契约丢失业务语义

- `draft.set` 只接收文本，会覆盖或清除原有 entities、reply、quote、media、effect 等富草稿状态。
- 空文本在实际效果上可能等同清空，但返回语义未明确。
- settings 工具接收 account 参数，实际写入的是 `SharedConfig` 全局配置。
- 只暴露少量布尔设置，没有说明适用作用域、持久化位置和是否需要重启。

应将覆盖/合并策略、作用域、持久化和丢失字段写入 Schema 与结果；无法保真的写入应拒绝或要求显式 `replace: true`。

### AUD-P1-04：批处理异常会丢失部分执行结果

宿主 `telegram_batch` 顺序执行工具。如果中途出现传输或 Schema 异常，外层 dispatcher 可能只返回一个 gateway error，前面已经成功执行的结果不会交还调用方。

调用方无法判断哪些写操作已经发生，重放整个 batch 会产生重复副作用。批处理应返回逐项状态、`completed_count`、失败索引、每项 operation ID，并禁止在不具备幂等性时自动重放。

### AUD-P1-05：Agent 契约仍缺少输出和提交语义

- 46 个工具全部被标记为 `preferred`，没有区分基础、标准和高风险高级工具。
- 缺少机器可读的 output Schema。
- 多数写接口没有明确 `acknowledged`、`committed`、`locally_applied`、`persisted` 和 `readback_verified` 的差别。
- 错误结果缺少 `outcome_unknown`、`readback_tool` 和安全重试指令。

建议统一写操作结果：

```json
{
  "operation_id": "...",
  "acknowledged": true,
  "committed": true,
  "locally_applied": true,
  "readback_verified": true,
  "persistence_verified": false,
  "outcome": "confirmed",
  "readback": {
    "tool": "telegram.message.get",
    "arguments": {}
  }
}
```

## 11. 现有接口能否圆满完成对应业务

| 工具域 | 当前能否圆满完成 | 判断依据 |
|---|---|---|
| system | health 可以完成自身诊断 | 已运行验证，但不是 Telegram 业务 |
| account | 否 | 只有账号槽位成功；登录后资料和账号切换闭环未验证 |
| peer | 否 | 成功路径未运行验证，access-hash/cache 依赖较强 |
| dialog | 否 | 缓存不完整，controller 写操作多为异步，回读不足 |
| message | 否 | 业务 seam、线程、peer 归属、定时 ID 和超时均有 P0 问题 |
| draft | 否 | 富草稿语义丢失，缺少服务器/重启持久化证明 |
| contact | 否 | 搜索范围与描述不一致，BoolFalse 可能假成功 |
| chat | 否 | 只覆盖基础子集，写操作同步和返回形状未验证 |
| settings | 否 | 范围很窄，账号作用域与全局配置语义不一致 |
| profile | 否 | 只有基础字段，成功写入和持久化未验证 |
| session | 否 | 终止引用和回读存在结构性缺陷 |

截至本审计，没有一个 Telegram 用户业务域完成了“写入成功、独立回读、GUI 等价、重启持久化和清理”全链路证明。

## 12. 验证现状

### 12.1 已完成

- 工具目录生成和 drift check。
- 9 个 Python 单元测试，主要覆盖 catalog、网关、Schema 和脱敏。
- `afatDebug` APK 构建。
- APK 安装、启动和私有 MCP token 生成。
- MCP 健康检查和账号槽位读取。
- 未登录状态下 `account.get_me` 的安全错误。
- 工具注册、输入 Schema 和基本协议/安全检查。

### 12.2 尚未完成

- Java MCP 单元测试。
- Android instrumentation 测试。
- 登录后的 43 个业务工具成功路径。
- 成功的 Telegram 写操作。
- Saved Messages 的完整 create/read/update/read/delete/read。
- GUI 与 MCP 双向等价验证。
- APP 重启后的持久化验证。
- wrong-peer、BoolFalse、timeout-after-commit、重复重试和线程竞态测试。
- 群组、频道、联系人、会话等专用可丢弃 fixture 验证。
- 严格审计通过。
- 自动清理或明确恢复计划。

## 13. 整改优先级

### P0：允许真实业务写入前必须完成

1. 将消息和其他高风险写操作迁移到 Telegram GUI 使用的稳定 domain helper。
2. 修复 `processUpdates` 的 `stageQueue` 线程约束。
3. 为 message ID 增加 peer/topic 归属和权限 preflight。
4. 校验 Bool、响应类型、返回 ID 和预期 update，禁止非空即成功。
5. 修复定时消息 ID 与独立回读。
6. 重构 session 稳定引用、成功判断和回读。
7. 为超时引入 `outcome: unknown`、read-before-retry 和幂等策略。
8. 补齐 MCP Origin、initialize、通知、GET/405 和 JSON-RPC 错误语义。
9. 在上述问题修复前，默认禁止真实账号上的 destructive 工具。

### P1：达到可靠 Agent 操作前必须完成

1. 为 dialog/contact 等缓存读取增加主动同步、分页和完整性状态。
2. 为每个写工具建立真正独立的 readback。
3. 明确 draft replace/merge 和 settings global/account scope。
4. 修复 batch 部分执行结果和重放安全。
5. 增加 output Schema、operation ID、提交阶段和持久化字段。
6. 增加 Java、instrumentation、负向、超时和并发测试。
7. 使用一次性已登录账号完成业务闭环、GUI 等价和重启验证。

### P2：扩大 Telegram 功能覆盖

推荐顺序：

1. 受控暂存文件、上传、下载和媒体发送。
2. 自定义文件夹、论坛主题和高级消息语义。
3. 群组/频道管理、邀请链接和权限。
4. 通知、隐私、联系人和完整资料。
5. Stories、Bot、Business、Quick Reply 和贴纸生态。
6. 通话控制、Secret Chat 和金融状态。
7. 采用 `prepare/handoff/resume` 衔接登录、系统 picker、权限和付款确认。

## 14. 建议的验收矩阵

| 层级 | 验收内容 | 当前状态 | 完成标准 |
|---|---|---|---|
| L0 | 生成物 drift、能力清单、严格审计 | 未通过 | `--strict` 无错误 |
| L1 | 编译、静态检查、Java/Python 单测 | 部分通过 | 所有 adapter、转换和响应类型有测试 |
| L2 | MCP 生命周期、HTTP、鉴权、Origin、Schema | 部分通过 | 目标协议版本一致且负向测试通过 |
| L3 | 登录后只读工具 | 未完成 | 每个工具至少一个真实成功和边界场景 |
| L4 | 写入闭环 | 未完成 | write → independent read → cleanup |
| L5 | 超时、重试、错误、确认、脱敏 | 未完成 | 无假成功、无未知重复副作用 |
| L6 | GUI 等价、重启持久化、crash log | 未完成 | GUI 可见、重启一致、无新增崩溃 |
| L7 | destructive fixture 和清理 | 未完成 | 使用可丢弃对象并全部清理或给出恢复计划 |

每个写工具至少应保留以下证据：

- 精确请求参数的脱敏摘要。
- 服务端响应类型和关键 ID。
- 本地状态变更证据。
- 独立回读请求和结果。
- GUI 或数据库等价证据。
- 重启后的二次回读。
- 清理结果。
- 超时或失败时的最终状态判定。

## 15. 完成定义

只有同时满足以下条件，才能宣称 MCP 已圆满覆盖某一 Telegram 用户意图：

- 工具契约能完整表达该意图，不需要模型猜测内部 ID 或隐藏参数。
- 实现调用正确的 Telegram domain seam 并遵守线程、存储和 update 约束。
- 成功响应证明业务已经提交，而不是只证明请求已发送。
- 写操作能够通过独立读取确认。
- GUI 显示与 MCP 返回一致。
- APP 重启后状态仍然正确。
- 超时和重试不会产生无法识别的重复副作用。
- 高影响操作默认失败关闭并要求明确确认。
- 测试使用可丢弃 fixture，清理成功或存在明确恢复方案。
- 对暂时不能完成的能力，清单如实标记 `partial`、`degraded` 或 `system-boundary`。

在这些条件满足前，应分别报告 `registered`、`implemented`、`runtime-verified`、`GUI-verified` 和 `degraded`，不得压缩成单一“覆盖率”。

## 16. 最终判断

1. 当前 46 个工具不是 Telegram APP 的 82% 覆盖，而是约 15%–25% 的可靠语义覆盖、25%–35% 的部分可达覆盖。
2. 约 65%–75% 的 Agent 有价值功能尚未覆盖，其中绝大多数可以通过语义化 MCP 工具实现。
3. 当前没有成功验证的 Telegram 用户业务写操作，也没有任何业务域完成完整的运行、回读、GUI 和持久化闭环。
4. 现有安全、目录、Schema、确认和宿主桥接基础值得保留。
5. 在修复 P0 问题并通过登录后的完整闭环验证前，不应让智能体在真实对象上执行删除、终止会话、退出群组或其他不可逆任务。
