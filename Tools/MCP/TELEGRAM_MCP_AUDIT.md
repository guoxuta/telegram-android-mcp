# Telegram Android MCP 覆盖、正确性与验证审计

审计日期：2026-08-01

源码版本：Telegram Android 12.9.0

源码基线：`3af9a867f5b72eb6e7062cf83ada1cc64bdc7668` 加当前工作区 MCP 改动

调试包：`org.telegram.messenger.beta` / `afatDebug` / `x86_64`

## 1. 结论先行

当前确定性能力模型包含 **211 项**：**201 个已实现 MCP 工具**和 **10 个可信系统边界**。因此，对“当前模型”这一有限分母，接口暴露率是 **201/211 = 95.3%**，且没有 catalog-only、无 Java handler 的工具。

这个 95.3% **不是 Telegram 原 APP 覆盖率**。Telegram 没有官方原子功能清单，当前 211 项又是人工语义归并结果；源码中仍存在未进入模型的可程序化功能族。继续给出“完整 APP 已覆盖 80%/90%”一类数字会制造假精度，本报告撤销旧版的此类估算。

对原问题的准确回答是：

- 已覆盖消息、对话、群组/频道、论坛主题、Stories、联系人、Bot、Business、Quick Reply、文件、通知、隐私、资料、代理、存储、网络、Stars 只读状态等主要 Agent 工作流；完整工具表见 [Agent Catalog](../../agent/TELEGRAM_MCP_AGENT_CATALOG.md)。
- 当前模型内剩余 10 项均为明确可信边界；模型外至少还有 **12 个可 MCP 化功能族、30 个以上原子动作**，所以不能宣称“能 MCP 化的都已完成”。这个 30+ 只是下限，不是全 APP 精确缺口数。
- 当前 APK、目录、服务、安全负向路径及本地文件闭环已经通过构建与设备验证；但是 beta 调试包目前 **未登录**。当前运行证据为 18 个真实成功工具、24 个登录阻塞工具和 159 个注册/Schema 级工具。因此严格业务门禁仍为 **FAIL**，不能宣称 201 个接口均圆满完成业务。

## 2. 审计口径

本审计将以下证据严格分开：

1. `tools/list` 可发现；
2. 输入、输出、风险、确认和错误契约可解析；
3. Java handler 连接到 Telegram controller/helper/MTProto 或受控本地状态；
4. 当前安装 APK 能实际调用；
5. 写入由服务器或不同读取路径独立确认；
6. 强制停止/重启后幂等和持久化仍成立；
7. 测试对象已清理。

只有第 4～7 层与业务相关的必要证据齐全，工具才能标记为 `runtime-verified`。出现在目录中不等于业务成功。

## 3. 当前模型和已覆盖范围

### 3.1 精确清单

| 项目 | 数量 | 含义 |
|---|---:|---|
| 建模能力 | 211 | 本轮人工审计模型，不等于整个 APP 的官方分母 |
| 已实现工具 | 201 | 有确定性 catalog、闭合输入 Schema 和 Java 分派 |
| 可信系统边界 | 10 | 明确降级为人机接力 |
| 仅目录、无 handler | 0 | Java `case` 与目录名称集合完全相等 |
| 工具命名域 | 29 | 便于 Agent 检索和按域裁剪 |

201 个工具按名称域分布如下：

| 域 | 工具数 | 域 | 工具数 | 域 | 工具数 |
|---|---:|---|---:|---|---:|
| chat | 35 | message | 22 | story | 15 |
| business | 13 | file | 12 | profile | 10 |
| quick_reply | 10 | dialog | 9 | contact | 8 |
| sticker | 7 | topic | 7 | bot | 6 |
| notification | 6 | folder | 5 | call | 4 |
| proxy | 4 | settings | 4 | draft | 3 |
| gif | 3 | payments | 3 | account | 2 |
| network | 2 | privacy | 2 | qr | 2 |
| session | 2 | storage | 2 | peer/security/system | 各 1 |

### 3.2 已覆盖的主要业务

- 账号健康、本人资料、用户名、生日、Emoji Status、头像、设备会话；
- 对话列表、搜索、归档、置顶、静音、未读、草稿、历史和共享媒体；
- 文本、格式化实体、媒体、联系人卡、明确经纬度、骰子、投票、贴纸、GIF；
- 发送、读取、编辑、删除、转发、反应、置顶、已读和基础定时发送；
- 联系人、拉黑、通知、隐私、文件夹筛选规则；
- 群组/频道创建和资料、成员、管理员、权限、禁言、慢速模式、自动删除、邀请链接、加入申请、管理员日志、讨论组、Boost 状态；
- Forum Topic 创建、读取、修改、关闭/重开、置顶和删除；
- Stories 读取、发布、编辑、删除、归档、置顶、隐私、浏览者和反应；
- Bot 命令、Inline Query、按钮/Callback；
- Business 资料、时间、地点、欢迎/离开消息、Business Bot、Business Link；
- Quick Reply 快捷方式和消息的创建、编辑、排序、删除、发送；
- 自动下载、本地设置白名单、缓存、流量、代理、两步验证状态；
- Stars 余额、交易、订阅的只读状态；
- 私有文件暂存、消息附件下载、受控 Base64 读写、离线二维码编解码；
- 最大 4 GiB 的可恢复分片暂存：begin/list/status/append/commit/cancel。

## 4. 尚未覆盖但可以 MCP 化

下面是已确认的模型外缺口。它们不应被归入“不可自动化”。

### 4.1 消息与媒体

1. Voice、普通 Audio、Music 和 Round Video 的显式元数据：duration、waveform、performer、title、round 标志等；
2. 定时消息改期、立即发送、重复策略和更完整的定时媒体管理；
3. Quote 文本片段、quote offset、跨会话回复头等高级回复语义；
4. 媒体组每项 caption、顺序、spoiler、混合媒体和媒体组编辑；
5. Saved Messages 标签的增删改查与高级共享媒体筛选。

### 4.2 组织、统计和管理

1. 共享聊天列表/共享文件夹邀请的创建、加入、撤销和状态读取；
2. 频道统计、消息统计、Story 统计和图表数据；
3. 更完整的成员搜索、批处理和审核队列；
4. 频道 monetization、Boost 资格变更和结算前状态，而不仅是只读摘要；
5. 管理员争议处理和更细粒度的批量治理组合。

### 4.3 Mini App、群通话和 Secret Chat

1. Mini App/Web App 启动参数、会话准备、结果回传和状态读取；任意网页内部视觉操作仍需受控 WebView/浏览器接力；
2. 群通话/直播的创建、排期、参与者列表、角色、静音和结束控制面；真实音视频流仍是人机边界；
3. Secret Chat 创建、状态、TTL 和消息生命周期。技术上可做，但必须有更严格确认、日志脱敏和设备绑定策略。

### 4.4 本地体验与交易前置

1. 主题、壁纸、语言包、翻译、字体、夜间模式、动画和通知声音文件；
2. 礼物目录、Invoice 解析、价格/资格、订单预览、收货与结算前校验；
3. 幂等运行账本的 `list/status/resolve` 管理工具，用独立业务读回解决 `pending/unknown/corrupt`，而不是静默过期。

## 5. 应放弃无人值守 MCP 化的边界

“放弃”指放弃绕过可信 UI 的最终动作，不是放弃整个功能域。当前 10 个边界如下：

| 边界 | MCP 可准备/恢复 | 必须由人或系统完成 |
|---|---|---|
| 登录、验证码与注册 | 读取登录状态、暂停、恢复任务 | 手机号确认、OTP、2FA、Passkey、CAPTCHA |
| 语音/视频通话 | 历史和未来控制面 | 实时听说、摄像头画面、权限确认 |
| 相机、录像、录音 | 处理和发送已有文件 | 传感器采集及知情授权 |
| Android 文件/相册选择器 | 私有暂存协议 | 系统 picker 的选择和授权 |
| 设备定位/实时位置 | 发送调用方明确给出的坐标 | 定位权限、传感器和持续追踪 |
| 摄像头扫码 | 解码已提供图片 | 现场摄像头扫描和权限 |
| 生物识别/本地密码锁 | 查询状态并请求接力 | BiometricPrompt、设备密码 |
| 最终支付/Premium/Stars/礼物购买 | 报价、资格、订单摘要 | 扣款、商店购买、强认证、签名 |
| Android 分享面板 | 准备内容 | 用户选择外部目标应用 |
| Launcher 小组件 | 准备配置/预览 | 添加、摆放和视觉确认 |

统一工作流应是：

```text
prepare -> handoff_required -> user/system action -> resume -> independent_readback
```

## 6. 已封装接口的正确性审计

### 6.1 本轮已修复的问题

- UI/controller/helper 调用切回正确线程；避免在同一 queue 阻塞等待导致自锁；
- 文本、媒体和结构化消息复用 APP 发送状态机，并按稳定消息 ID 独立回读；
- Telegram RPC 使用 `FailOnServerErrors | DoNotWaitFloodWait`，不把 RPC 错误、空响应或 `Bool=false` 当成功；
- 21 个受解析时易漂移的写入口先用原始规范参数做持久化幂等重放，再解析 peer/file/button/topic；
- 整数参数使用精确 `BigDecimal` 转换，拒绝小数和 32/64 位溢出；
- 写入返回 operation、acknowledged、committed 和 readback 建议；高风险工具要求 `_confirm: true`；
- Forum 普通消息、General Topic、指定 Topic 和 Topic Draft 分开路由；
- Proxy upsert/delete 与 active 配置使用同一 SharedPreferences 提交，并在失败时恢复内存状态；
- Session terminate 用同步持久化回放标记，并按正确 account 回读；
- 文件 metadata 持久化失败时只回滚本次新建文件，不删除既有合法目标；
- 分片上传支持精确 offset、chunk SHA、fsync、相同块重放、冲突拒绝、完整 SHA、原子 commit 和 terminal tombstone；
- `upload_begin` 不再自动复活 cancelled 会话；只有 `reopen_cancelled=true` 才重开；
- `upload_list` 增加 offset、状态过滤、总数、next offset 和时间字段；
- complete/cancelled/final-present 分离，purge 未完成不再返回虚假成功；
- 已存在最终文件时仍持久化 complete tombstone，使返回的 upload_ref 可 status/commit 重放；
- `storage.cache_clear(mcp_staging)` 同时清除暂存文件索引和上传会话，并与全部暂存消费者共用服务锁，消除并发幽灵会话；
- 输出契约改为互斥的 `ok=true/data` 与 `ok=false/error`，服务端对自身输出做运行时校验；
- 8 个核心消息/文件工具具有字段级 typed output，其他工具至少具有强制判别 envelope；
- 服务只绑定 loopback，使用 256-bit bearer token、常量时间比较、来源限制、请求体上限和 `no-store`；
- evidence 报告脱敏账号/peer/message/operation 等真实标识，同时保留命名 SHA-256 摘要。
- 修复 debug `ANRDetector` 在慢冷启动时自动 HPROF 造成的停顿自放大：保留完整线程栈和手动/OOM 堆转储，仅取消 watchdog 自动堆转储，并在主线程首次响应后才武装、每次连续卡顿只报告一次。

### 6.2 当前设备证据

| 层级 | 当前结果 | 证明范围 |
|---|---:|---|
| 生成器 | 211 能力 / 201 工具 / 10 边界 | 当前模型无 catalog-only 工具 |
| Java 分派 | 201/201 | catalog 名称与 Java `case` 集合一致 |
| 输出 Schema | 9 种 | 1 个判别 envelope + 8 个核心 typed data 合约 |
| Python 回归 | 25/25 | 目录、Schema、网关、安全、并发锁、脱敏和未登录本地闭环编排 |
| Gradle 构建 | exit 0 | `afatDebug/x86_64` 编译打包成功，34m26s |
| APK | 82,015,038 bytes | SHA-256 `e952472c783bdd3082b58eb89908e7ae65238cdfd2be1ddfd4a6b7506aa00f3a` |
| APK 签名 | v1/v2 verified | 调试包完整性通过 apksigner |
| APK 内 catalog | 201 工具 | SHA-256 `77a1103678b56dea6ce05760a3a35992aff51181a3f2e34275c5039ca33a2f15`，与工作树一致 |
| WSL ADB 安装 | Success | 设备 base.apk 与主机 APK hash 一致 |
| 必填空参负向 | 163/163 | 全部 fail-closed |
| 破坏性缺确认 | 33/33 | 全部 fail-closed |
| 当前真实成功 | 18 | 系统/本地读取，以及 file/QR/1.1 MiB 分片上传闭环 |
| 登录阻塞 | 24 | beta 包 4 个账号槽均未激活 |
| 仅注册/Schema | 159 | 不能当作业务成功 |
| 验收检查 | 13 passed / 1 blocked-login / 1 login-gate failed | 本地写入及显式最终清理通过；服务端业务因未登录未执行 |
| 清理 | passed | 独立读回 staged files=0、upload sessions=0 |
| 稳定性 | passed（当前范围） | 新 AVD 连续重启：6.5s 恢复，0 system/internal ANR、0 crash、0 HPROF |
| 严格门禁 | **FAIL** | 尚无登录写入、重启持久化和全工具业务证据 |

### 6.3 仍存在的接口限制

1. **输出仅部分强类型**：201 个工具都校验判别 envelope，但只有 8 个核心工具声明 data 的必需字段；其余 193 个仍需逐工具建模输出字段。
2. **4 GiB 是暂存上限，不是同步发送保证**：分片上传可暂存到 4 GiB，但媒体业务等待仍约 120 秒，host HTTP 等待约 310 秒。慢网/大文件发送只能标记 Partial，长期应改为异步 `operation_ref + status/cancel`。
3. **不确定幂等记录需要人工对账**：`pending/unknown/corrupt` 为避免重复副作用不会自动删除；极端积累到容量上限会阻塞新幂等写。应新增安全的 list/status/resolve，而不是按时间粗暴清空。
4. **单体 service 过大**：后续应按 message/chat/story/business/settings/file 拆分 domain adapter。
5. **服务器与资格条件**：Premium、Business、频道权限、FloodWait、第二账号、活跃通话等条件失败必须保持 `runtime-blocked-*`，不能包装成实现成功。

## 7. 为什么现在不能回答“所有接口均圆满完成”

当前 beta 调试包停在 `Start Messaging`，MCP health 显示账号槽 0～3 全部 `activated=false`。登录前扩展验收结果：

- 18 个 `runtime-verified`；
- 24 个 `runtime-blocked-not-logged-in`；
- 159 个 `registered-schema-verified`；
- file、QR 和 1.1 MiB 非对齐分片上传闭环通过；唯一 `failed` 检查是验收器明确拒绝继续执行需要登录的 Saved Messages 流程；
- cleanup 为 `passed`，独立读回暂存文件与上传会话均为 0；
- strict gate 为 `failed`。

这证明实现可加载、协议安全和错误路径稳定，但不证明 Telegram 服务端业务生效。必须在 **同一个 `org.telegram.messenger.beta` 包**完成 GUI 登录后，重新运行写验收和强制重启测试。

即使登录后，以下工具仍需要专门 fixture，不能用唯一当前会话或真实他人账号冒险验证：第二设备会话终止、成员封禁/提权、加入申请审批、Premium-only Business 设置、活跃通话控制、最终支付。

## 8. 最终完成门禁

对外宣称完成前必须同时满足：

1. 当前 APK/hash/签名/设备 base.apk 一致；
2. `tools/list`、APK 内 catalog、生成器和 Java 分派均为 201；
3. beta 包已登录，扩展验收 `failed=0`；
4. 所有成功写入均由不同读取路径或服务器结果精确确认；
5. 同一幂等键在 APP 强制停止/重启后返回相同业务 ID，且没有重复副作用；
6. 可逆设置在重启后保持，并能恢复原值；
7. 临时消息、文件、上传会话、文件夹、群组、Topic 和代理 fixture 全部清理；
8. 无 MCP 引起的 crash/ANR，设备日志无遗留 HPROF；
9. 能力清单以最终 runtime report 重新生成，`--check` 无 drift；
10. 独立审查者复核目录、handler、运行证据和业务结果没有混淆。

## 9. 对原始两个问题的直接回答

### 9.1 覆盖多少，缺多少，哪些该做，哪些应放弃？

- **可精确回答的只有当前模型**：201/211 = 95.3%，另外 10 项是可信边界。
- **不能精确回答整个 APP 百分比**：没有可信总分母，当前模型还漏掉至少 12 个可 MCP 化功能族和 30+ 个原子动作。
- 下一阶段应优先补：显式 Voice/Audio/Round Video、完整定时消息、Quote/跨会话回复、Saved 标签、共享聊天列表、统计、Mini App 生命周期、群通话控制面、Secret Chat、本地语言/翻译和交易前置。
- 应放弃无人值守绕过：OTP/2FA/Passkey/CAPTCHA、生物识别、Android 权限/Picker、现场采集、实时音视频参与、最终付款、系统分享选择和 Launcher 放置；保留 prepare/handoff/resume。

### 9.2 现有接口有无问题，能否完成对应业务？

- 原实现存在多项可导致死锁、假成功、错误幂等、越界整数、持久化不一致、上传终态矛盾和缓存幽灵会话的问题；本轮已逐项修复并通过静态、单元、构建、安装和非写运行验证。
- 当前 201 个接口都有 handler，核心协议和安全失败路径可靠；但只有 18 个获得当前 APK 的真实成功证据，24 个被 beta 未登录阻塞，159 个只有注册/Schema 证据。
- 因此当前结论是：**实现层显著改进，已验证部分可用；“所有可 MCP 化功能均已完成、所有接口均业务通过”这两个目标尚未达到。**
