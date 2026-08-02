# Telegram Android MCP Agent 接口目录

生成时间：`2026-08-01T18:00:25.869311+00:00`

共 `201` 个已安装接口。Agent 必须先解析目标、核对 schema，并在写入后回读验证。

风险：`read-only` 只读；`write` 写入；`external-or-network` 依赖外部服务；`destructive/confirmation-required` 必须由人确认。

## 账号（`account`，2 个）

多账号槽位与本人资料。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.account.get_me` | preferred | read-only | 账号：读取指定账号自己的安全资料摘要。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.account.list` | preferred | read-only | 账号：查看 Telegram 多账号槽位及安全的身份摘要。 返回结构化成功或可操作错误，不返回认证秘密。 无业务参数。 |

## bot（`bot`，6 个）

bot。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.bot.button_list` | preferred | read-only | bot：从精确服务器消息读取 inline/reply 键盘的行列、类型、文本和人机接力要求，不暴露 callback 私有字节。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id。 |
| `telegram.bot.button_press` | advanced | destructive/confirmation-required | bot：按精确消息与行列执行 callback/game/普通回复按钮或返回可信 UI 接力要求；具有本地去重。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id, row, column, idempotency_key。 属于bot 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.bot.command_list` | preferred | read-only | bot：从 Bot 完整资料读取命令、说明、简介和隐私政策链接。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：bot。 |
| `telegram.bot.inline_query` | preferred | read-only | bot：在明确会话上下文向 Inline Bot 查询结果并返回 query_id、稳定 result_id 和分页 offset。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：bot, peer。 |
| `telegram.bot.inline_send` | advanced | external-or-network | bot：把 inline_query 返回的 query_id 和 result_id 幂等发送到明确会话。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, query_id, result_id, idempotency_key。 属于bot 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.bot.start` | advanced | external-or-network | bot：在 Bot 私聊或明确群组上下文发送带 start_param 的幂等 Bot 启动请求。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：bot, idempotency_key。 属于bot 功能的底层接口，仅在语义接口不能满足时使用。 |

## business（`business`，13 个）

business。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.business.away_set` | advanced | external-or-network | business：启用或停用离开消息；启用时绑定名为 away 的快捷回复、发送日程、在线状态条件和收件人策略。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：enabled。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.bot_delete` | advanced | destructive/confirmation-required | business：断开明确 Bot 与当前账号的 Business 连接并撤销其授权。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：bot。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.bot_list` | preferred | read-only | business：从服务器列出账号连接的 Business Bot、完整权限、会话范围和连接摘要。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.business.bot_set` | advanced | external-or-network | business：连接支持 Business 的 Bot，或完整替换同一 Bot 的 14 项权限与会话范围；不同 Bot 必须先显式删除。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：bot, rights, recipients, replace。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.get` | preferred | read-only | business：从自己的完整服务器资料读取 Business 简介、位置、营业时间、欢迎消息和离开消息状态。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.business.greeting_set` | advanced | external-or-network | business：启用或停用欢迎消息；启用时绑定名为 hello 的快捷回复、无活动天数和明确收件人策略。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：enabled。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.hours_set` | advanced | external-or-network | business：设置 IANA 时区和一周分钟轴上的已排序非重叠开放区间，或清除营业时间。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, timezone_id, weekly_open, clear。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.intro_set` | advanced | external-or-network | business：设置或清除 Business 欢迎页标题和说明；贴纸保持为空以避免不透明文档引用。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, title, description, clear。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.link_create` | advanced | external-or-network | business：创建带格式化预填消息和可选标题的幂等 Business 聊天链接。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：message, idempotency_key。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.link_delete` | advanced | destructive/confirmation-required | business：按稳定 slug 永久删除一个 Business 聊天链接。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：slug。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.link_edit` | advanced | external-or-network | business：按稳定 slug 修改 Business 链接的预填消息和标题。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：slug, message。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.business.link_list` | preferred | read-only | business：从服务器列出账号的 Business 聊天链接、预填消息、标题和访问次数。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.business.location_set` | advanced | external-or-network | business：设置或清除 Business 地址，并可同时设置明确经纬度，不读取设备定位。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, address, latitude, longitude, clear。 属于business 功能的底层接口，仅在语义接口不能满足时使用。 |

## call（`call`，4 个）

call。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.call.hang_up` | advanced | destructive/confirmation-required | call：挂断本应用进程中的当前 Telegram 通话；不存在通话时幂等成功。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 属于call 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.call.history` | preferred | read-only | call：从 Telegram 服务器分页读取语音/视频通话记录、方向、时长和结束原因。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, missed_only, min_date, max_date, offset_id, limit。 |
| `telegram.call.mute_set` | advanced | write | call：在已有通话中设置麦克风静音状态；不申请权限、不启动采集或发起通话。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：muted。 属于call 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.call.status` | preferred | read-only | call：读取本应用进程当前 VoIP 服务状态、通话 peer、麦克风静音和视频可用性。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |

## 群组与频道（`chat`，35 个）

创建、加入、退出及资料维护。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.chat.admin_log` | preferred | read-only | 群组与频道：分页读取频道或超级群组的服务端管理员事件摘要。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.anti_spam_set` | advanced | external-or-network | 群组与频道：启用或关闭 Telegram 原生超级群组反垃圾系统。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, enabled。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.auto_delete_set` | advanced | external-or-network | 群组与频道：设置群组或频道新消息自动删除周期；0 关闭。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, seconds。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.boost_status` | preferred | read-only | 群组与频道：读取频道或超级群组的助力等级、计数、下一等级、受众和当前账号助力槽。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.create_channel` | advanced | external-or-network | 群组与频道：创建广播频道、超级群组或启用 forum 标志的论坛并设置标题和简介。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：title, kind, idempotency_key。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.create_group` | advanced | external-or-network | 群组与频道：用明确成员列表创建 Telegram 群组。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：title, members, idempotency_key。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.delete_owned` | advanced | destructive/confirmation-required | 群组与频道：仅当当前账号是 creator 时永久删除目标群组、频道、超级群组或论坛。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.get` | preferred | read-only | 群组与频道：读取群组、超级群组或频道的简介、人数、权限和置顶摘要。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.history_visible_set` | advanced | external-or-network | 群组与频道：设置新加入超级群组的成员能否看到加入前的消息历史。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, visible。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.invite_create` | advanced | external-or-network | 群组与频道：创建具有明确有效期、次数、审批和标题的邀请链接。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, idempotency_key。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.invite_list` | preferred | read-only | 群组与频道：分页读取当前管理员创建的有效或已撤销邀请链接。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.invite_revoke` | advanced | destructive/confirmation-required | 群组与频道：撤销一个明确邀请链接并验证其 revoked 状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, link。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.join_public` | advanced | destructive/confirmation-required | 群组与频道：解析公开 username 并加入对应频道/群组。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.join_request_decide` | advanced | destructive/confirmation-required | 群组与频道：批准或拒绝一个明确用户的待处理入群申请。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, user, approve。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.join_request_list` | preferred | read-only | 群组与频道：分页读取群组或频道尚待处理的加入申请。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.leave` | advanced | destructive/confirmation-required | 群组与频道：让当前账号退出指定群组或频道。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.linked_set` | advanced | destructive/confirmation-required | 群组与频道：把广播频道链接到一个超级群组，或用空 group_peer 解除链接。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, group_peer。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.member_add` | advanced | external-or-network | 群组与频道：把明确用户加入有权限管理的群组或频道。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, member。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.member_admin_set` | advanced | destructive/confirmation-required | 群组与频道：提升或撤销成员管理员，并为超级群组/频道设置细粒度权利。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, member。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.member_get` | preferred | read-only | 群组与频道：从服务器读取某个用户在群组或频道中的成员、封禁、角色和权限状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, member。 |
| `telegram.chat.member_remove` | advanced | destructive/confirmation-required | 群组与频道：从群组/频道移除用户，可选择保留封禁状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, member。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.member_restrict` | advanced | destructive/confirmation-required | 群组与频道：合并修改超级群组/频道成员的发送、媒体和管理允许项。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, member, allowed。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.members_list` | preferred | read-only | 群组与频道：分页读取群组、超级群组或频道中当前账号有权查看的成员。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.participants_hidden_set` | advanced | external-or-network | 群组与频道：启用或关闭普通成员不可见完整成员列表。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, enabled。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.permissions_get` | preferred | read-only | 群组与频道：从服务器读取群组或频道的默认成员允许项。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.permissions_set` | advanced | destructive/confirmation-required | 群组与频道：合并修改群组或频道的默认成员允许项。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, allowed。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.photo_clear` | advanced | destructive/confirmation-required | 群组与频道：清除明确群组或频道的当前头像。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.photo_upload` | advanced | external-or-network | 群组与频道：从 MCP 私有暂存区上传图片并设置为明确群组或频道的头像。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, file_ref, idempotency_key。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.reactions_get` | preferred | read-only | 群组与频道：从服务器完整资料读取群组或频道允许的表情反应、数量上限和付费反应状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.reactions_set` | advanced | external-or-network | 群组与频道：设置群组或频道允许的全部、部分或禁用反应，并可设置自定义表情、数量上限和付费反应。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, mode。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.signatures_set` | advanced | external-or-network | 群组与频道：设置广播频道消息签名及签名资料展示。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, enabled。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.slow_mode_set` | advanced | external-or-network | 群组与频道：把超级群组慢速模式设为 Telegram 支持的明确秒数。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, seconds。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.update_about` | advanced | external-or-network | 群组与频道：修改有管理权限的 chat 简介。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, about。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.update_title` | advanced | external-or-network | 群组与频道：修改有管理权限的 chat 标题。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, title。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.chat.username_set` | advanced | external-or-network | 群组与频道：设置或清除频道/超级群组公开 username。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, username。 属于创建、加入、退出及资料维护的底层接口，仅在语义接口不能满足时使用。 |

## 联系人（`contact`，8 个）

联系人检索、屏蔽与解除屏蔽。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.contact.block` | advanced | destructive/confirmation-required | 联系人：阻止指定用户或频道继续联系当前账号。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 属于联系人检索、屏蔽与解除屏蔽的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.contact.blocked_list` | preferred | read-only | 联系人：分页读取当前账号的封禁列表。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, offset, limit。 |
| `telegram.contact.delete` | advanced | destructive/confirmation-required | 联系人：从 Telegram 云端联系人中永久移除一个用户；不会删除双方聊天。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：user。 属于联系人检索、屏蔽与解除屏蔽的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.contact.get` | preferred | read-only | 联系人：从 Telegram 云端联系人全集精确读取一个用户及其联系人姓名。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：user。 |
| `telegram.contact.list` | preferred | read-only | 联系人：列出云端联系人，不读取 Android 通讯录。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, limit。 |
| `telegram.contact.search` | preferred | read-only | 联系人：按关键词搜索 Telegram 用户、群组和已有联系人。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：query。 |
| `telegram.contact.unblock` | advanced | external-or-network | 联系人：恢复指定用户或频道与当前账号的联系权限。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 属于联系人检索、屏蔽与解除屏蔽的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.contact.upsert` | advanced | external-or-network | 联系人：按用户写入完整联系人姓名；存在时修改，不存在时新增。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：user, first_name, idempotency_key。 属于联系人检索、屏蔽与解除屏蔽的底层接口，仅在语义接口不能满足时使用。 |

## 会话（`dialog`，9 个）

会话列表、归档、静音和置顶。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.dialog.archive` | standard | write | 会话：把指定对话移入归档文件夹。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.clear_history` | advanced | destructive/confirmation-required | 会话：保留会话但清空其消息历史，可选为双方清除。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 属于会话列表、归档、静音和置顶的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.dialog.get` | preferred | read-only | 会话：从服务器读取单个对话的文件夹、置顶、未读和通知状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.list` | preferred | read-only | 会话：分页列出当前账号的私聊、群组、频道与文件夹状态。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, limit, folder_id, offset_id, offset_date, offset_peer。 |
| `telegram.dialog.mute` | standard | write | 会话：按会话或话题永久静音通知。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.pin` | standard | write | 会话：在主列表或归档中置顶一个会话。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.unarchive` | standard | write | 会话：把指定对话移回主列表。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.unmute` | standard | write | 会话：恢复指定会话或话题的通知。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.unpin` | standard | write | 会话：取消指定会话的置顶状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |

## 草稿（`draft`，3 个）

设置或清除指定会话草稿。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.draft.clear` | standard | write | 草稿：清空会话或话题草稿。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.draft.get` | preferred | read-only | 草稿：读取会话或话题当前保存的文本草稿。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.draft.set` | standard | write | 草稿：为会话或话题保存文本草稿。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, text。 |

## file（`file`，12 个）

file。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.file.delete` | advanced | destructive/confirmation-required | file：删除 APP 私有 MCP 暂存文件及其元数据。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：file_ref。 属于file 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.file.download_message` | standard | write | file：下载指定消息的文档、音视频或照片并复制到受限私有暂存区。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id。 |
| `telegram.file.get` | preferred | read-only | file：按稳定 file_ref 读取私有暂存文件的完整性元数据。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：file_ref。 |
| `telegram.file.list` | preferred | read-only | file：列出 APP 私有、大小受限的 MCP 文件暂存区，不暴露任意 Android 路径。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：limit。 |
| `telegram.file.put_base64` | standard | write | file：把明确提供的 Base64 内容原子写入 APP 私有暂存区。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：name, mime_type, base64。 |
| `telegram.file.read_base64` | preferred | read-only | file：按最多 1 MiB 的窗口读取私有暂存文件，不暴露文件系统路径。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：file_ref。 |
| `telegram.file.upload_append` | standard | write | file：按精确 offset 追加不超过 512 KiB 的已验证分块。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：upload_ref, offset, base64, chunk_sha256。 |
| `telegram.file.upload_begin` | standard | write | file：创建或恢复 APP 私有分块上传会话。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：name, mime_type, total_size, sha256。 |
| `telegram.file.upload_cancel` | advanced | destructive/confirmation-required | file：删除未提交 part 与会话，保留已提交 file_ref。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：upload_ref。 属于file 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.file.upload_commit` | standard | write | file：验证完整大小和 SHA-256 后原子生成稳定 file_ref。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：upload_ref。 |
| `telegram.file.upload_list` | preferred | read-only | file：列出活动与终态上传会话，便于恢复遗失的 upload_ref。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：limit, offset, state。 |
| `telegram.file.upload_status` | preferred | read-only | file：读取上传进度和最终文件引用。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：upload_ref。 |

## folder（`folder`，5 个）

folder。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.folder.delete` | advanced | destructive/confirmation-required | folder：永久删除一个自定义文件夹；不会删除其中的聊天。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：folder_id。 属于folder 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.folder.get` | preferred | read-only | folder：按稳定 folder_id 精确读取一个服务器聊天文件夹。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：folder_id。 |
| `telegram.folder.list` | preferred | read-only | folder：从服务器读取默认及自定义聊天文件夹、完整筛选条件和排序。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.folder.reorder` | advanced | external-or-network | folder：用包含全部自定义文件夹且无重复的 ID 列表原子更新排序。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：folder_ids。 属于folder 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.folder.upsert` | advanced | external-or-network | folder：创建自定义文件夹，或在 replace=true 时原子替换指定文件夹的完整筛选规则。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：title, idempotency_key。 属于folder 功能的底层接口，仅在语义接口不能满足时使用。 |

## gif（`gif`，3 个）

gif。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.gif.saved_list` | preferred | read-only | gif：从服务器读取账号的已保存 GIF 文档。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, offset, limit。 |
| `telegram.gif.saved_set` | standard | write | gif：从精确服务器消息取得 GIF 文档，设置其云端保存状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：source_peer, source_message_id, saved。 |
| `telegram.gif.send_saved` | advanced | external-or-network | gif：按已保存列表中的 document_id 通过 Telegram 发送助手发送 GIF。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, document_id, idempotency_key。 属于gif 功能的底层接口，仅在语义接口不能满足时使用。 |

## 消息（`message`，22 个）

历史、搜索、发送、编辑、转发、删除和已读状态。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.message.delete` | advanced | destructive/confirmation-required | 消息：删除指定消息，可选仅自己或所有人。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_ids。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.edit_caption` | advanced | external-or-network | 消息：编辑有权修改的照片、视频、音频或文件 caption，支持 APP composer Markdown 实体。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id, caption。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.edit_text` | advanced | external-or-network | 消息：编辑当前账号有权修改的文本消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id, text。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.forward` | advanced | external-or-network | 消息：把现有消息转发到另一个 peer。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：from_peer, to_peer, message_ids, idempotency_key。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.get` | preferred | read-only | 消息：读取指定 peer 中一组明确消息 ID 的最新服务器对象。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_ids。 |
| `telegram.message.history` | preferred | read-only | 消息：按 peer 和偏移量分页读取可显示的消息历史。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.message.mark_read` | standard | write | 消息：把会话或话题读进度推进到指定消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, max_message_id。 |
| `telegram.message.mark_unread` | standard | write | 消息：把指定会话标记为未读提醒。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.message.media_search` | preferred | read-only | 消息：在精确会话内按照片、视频、文件、音乐、语音、GIF、链接等类型分页搜索。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.message.pin` | advanced | external-or-network | 消息：在有权限的会话中置顶一条消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.poll_close` | advanced | destructive/confirmation-required | 消息：对自己有权编辑的投票或测验执行不可逆提前关闭。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.poll_vote` | advanced | external-or-network | 消息：对精确服务器投票按零基答案索引提交选项；空列表用于撤回允许修改的投票。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id, answer_indices。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.reaction_set` | advanced | external-or-network | 消息：为一条消息设置单个标准 emoji 反应；空字符串用于移除自己的反应。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id, reaction。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.scheduled_list` | preferred | read-only | 消息：列出指定会话尚未发送的定时消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.message.search` | preferred | read-only | 消息：在指定会话或全局按文本搜索消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：query。 |
| `telegram.message.send_contact` | advanced | external-or-network | 消息：发送包含手机号和姓名的 Telegram 联系人卡片，可回复、静默或定时。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, phone_number, first_name, idempotency_key。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.send_dice` | advanced | external-or-network | 消息：发送账号服务器当前支持的骰子/飞镖/球类/老虎机 emoji 并返回服务器随机结果。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, idempotency_key。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.send_location` | advanced | external-or-network | 消息：发送显式经纬度；提供 title 时发送地点卡片，不访问设备定位权限。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, latitude, longitude, idempotency_key。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.send_media` | advanced | external-or-network | 消息：从私有 MCP 暂存区发送单个或最多十个照片、视频或文档。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, file_refs, idempotency_key。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.send_poll` | advanced | external-or-network | 消息：发送匿名或实名、单选或多选投票，也可发送带正确答案和解析的测验。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, question, answers, idempotency_key。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.send_text` | advanced | external-or-network | 消息：向一个 peer 发送纯文本或 Telegram composer Markdown，可选回复、链接预览、静默和定时发送。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, text, idempotency_key。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.message.unpin` | advanced | external-or-network | 消息：取消指定消息的置顶状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id。 属于历史、搜索、发送、编辑、转发、删除和已读状态的底层接口，仅在语义接口不能满足时使用。 |

## network（`network`，2 个）

network。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.network.usage` | preferred | read-only | network：按移动网络、Wi-Fi 和漫游读取当前账号在本机的消息、媒体、文件和通话流量计数。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.network.usage_reset` | advanced | destructive/confirmation-required | network：显式确认后仅重置指定账号和网络类型的本机流量计数，不影响 Telegram 云端内容。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：network。 属于network 功能的底层接口，仅在语义接口不能满足时使用。 |

## notification（`notification`，6 个）

notification。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.notification.global_get` | preferred | read-only | notification：从服务器读取私聊、群组、频道或 Stories 的全局静音、预览、声音和发送者显示策略。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：domain。 |
| `telegram.notification.global_set` | advanced | external-or-network | notification：按字段修改私聊、群组、频道或 Stories 全局通知，服务器独立回读后同步本机 GUI 偏好。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：domain。 属于notification 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.notification.peer_get` | preferred | read-only | notification：从服务器读取会话或论坛话题的通知例外和静音状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.notification.peer_set` | advanced | external-or-network | notification：按字段更新会话或论坛话题的静音、预览、声音和 Story 通知例外；未提供字段保持服务器现值。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 属于notification 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.notification.reactions_get` | preferred | read-only | notification：读取消息反应、Story 反应和投票通知来源、预览与声音。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.notification.reactions_set` | advanced | external-or-network | notification：在服务器当前完整对象上按字段修改消息反应、Story 反应、投票通知、预览或声音，避免覆盖未提供字段。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, messages, stories, poll_votes, show_previews, sound。 属于notification 功能的底层接口，仅在语义接口不能满足时使用。 |

## payments（`payments`，3 个）

payments。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.payments.stars_status` | preferred | read-only | payments：从服务器读取当前账号的 Stars 或 TON 余额、首屏流水和订阅摘要；不创建订单或执行购买。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, ton。 |
| `telegram.payments.stars_subscriptions` | preferred | read-only | payments：从服务器分页读取当前账号的 Stars 订阅、续费价格、到期时间和余额不足状态。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, offset, missing_balance_only。 |
| `telegram.payments.stars_transactions` | preferred | read-only | payments：按方向、顺序和订阅筛选从服务器分页读取 Stars 或 TON 交易；收据 URL 和 bot payload 保持脱敏。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, direction, ascending, ton, subscription_id, offset, limit。 |

## 目标解析（`peer`，1 个）

将公开用户名或稳定内部引用解析为可操作目标。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.peer.resolve` | preferred | read-only | 目标解析：把 username 或稳定引用解析为 Agent 可复用的 peer 引用。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |

## privacy（`privacy`，2 个）

privacy。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.privacy.get` | preferred | read-only | privacy：按隐私域从服务器读取基础策略、用户/群组例外及 Premium、联系人、Bot 规则。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：key。 |
| `telegram.privacy.set` | advanced | external-or-network | privacy：用 Agent 友好的基础策略和显式例外完整替换一个隐私域；必须传 replace=true。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：key, base, replace。 属于privacy 功能的底层接口，仅在语义接口不能满足时使用。 |

## 个人资料（`profile`，10 个）

姓名、简介等本人资料维护。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.profile.birthday_set` | advanced | external-or-network | 个人资料：设置或清除当前账号生日；清除时省略年月日并传 clear=true。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, year, month, day, clear。 属于姓名、简介等本人资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.profile.emoji_status_set` | advanced | external-or-network | 个人资料：设置带可选到期时间的自定义 emoji status，或显式清除；64 位 document ID 使用字符串避免精度丢失。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, document_id, until, clear。 属于姓名、简介等本人资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.profile.get` | preferred | read-only | 个人资料：从服务器读取当前账号的姓名、简介、用户名、生日和公开资料状态。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.profile.photo_clear` | advanced | destructive/confirmation-required | 个人资料：清除当前账号头像但不删除头像历史。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 属于姓名、简介等本人资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.profile.photo_delete` | advanced | destructive/confirmation-required | 个人资料：永久删除一张明确 photo ID 的账号历史头像。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：photo_id。 属于姓名、简介等本人资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.profile.photo_list` | preferred | read-only | 个人资料：从服务器分页列出当前账号的头像历史，并以字符串返回 64 位 photo ID。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, offset, max_id, limit。 |
| `telegram.profile.photo_set` | advanced | external-or-network | 个人资料：把 photo_list 返回的一张历史头像设置为当前账号头像。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：photo_id。 属于姓名、简介等本人资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.profile.photo_upload` | advanced | external-or-network | 个人资料：从 MCP 私有暂存区上传图片并将其设置为当前账号头像。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：file_ref, idempotency_key。 属于姓名、简介等本人资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.profile.update` | advanced | external-or-network | 个人资料：修改当前账号的 first_name、last_name 或 about。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, first_name, last_name, about。 属于姓名、简介等本人资料维护的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.profile.username_set` | advanced | external-or-network | 个人资料：设置或清除当前账号的公开 username，并执行服务器读回。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：username。 属于姓名、简介等本人资料维护的底层接口，仅在语义接口不能满足时使用。 |

## proxy（`proxy`，4 个）

proxy。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.proxy.delete` | advanced | destructive/confirmation-required | proxy：从本机列表删除指定代理；删除当前代理会同时安全禁用代理连接。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：proxy_id。 属于proxy 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.proxy.list` | preferred | read-only | proxy：列出 Telegram 本机代理、选中/启用状态和连通性摘要；密码与 MTProto secret 永不回传。 返回结构化成功或可操作错误，不返回认证秘密。 无业务参数。 |
| `telegram.proxy.select` | advanced | write | proxy：选择已有代理并精确设置消息连接与通话代理开关；MTProto 代理不允许用于通话。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：proxy_id, enabled。 属于proxy 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.proxy.upsert` | standard | write | proxy：添加 SOCKS5/MTProto 代理，或按稳定 proxy_id 修改现有代理；不会自动启用新代理且不回传凭据。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：type, address, port。 |

## qr（`qr`，2 个）

qr。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.qr.decode_file` | preferred | read-only | qr：使用 APP 内置 ZXing 离线解码私有 MCP 暂存区中的图片，不请求摄像头权限。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：file_ref。 |
| `telegram.qr.encode` | standard | write | qr：使用 APP 内置 ZXing 将文本离线编码为 PNG，并写入受限私有 MCP 暂存区。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：text。 |

## quick_reply（`quick_reply`，10 个）

quick_reply。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.quick_reply.create_text` | advanced | external-or-network | quick_reply：通过 Telegram 自身发送状态机创建快捷回复及其首条格式化文本消息；hello/away 可供 Business 自动消息使用。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：shortcut, text, idempotency_key。 属于quick_reply 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.quick_reply.delete` | advanced | destructive/confirmation-required | quick_reply：永久删除一个普通快捷回复及其全部模板消息；Business hello/away 必须由对应设置管理。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：shortcut_id。 属于quick_reply 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.quick_reply.get` | preferred | read-only | quick_reply：按稳定 shortcut ID 或精确名称读取快捷回复及其独立消息空间中的全部消息；省略选择器时返回紧凑快捷回复列表以便发现。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, shortcut_id, shortcut。 |
| `telegram.quick_reply.list` | preferred | read-only | quick_reply：从服务器列出快捷回复的稳定 ID、名称、顺序、首条消息和消息数量。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.quick_reply.message_add_text` | advanced | external-or-network | quick_reply：通过 Telegram 自身发送状态机向既有快捷回复追加一条格式化文本消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：shortcut_id, text, idempotency_key。 属于quick_reply 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.quick_reply.message_delete` | advanced | destructive/confirmation-required | quick_reply：从指定快捷回复中永久删除一至多条明确 ID 的模板消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：shortcut_id, message_ids。 属于quick_reply 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.quick_reply.message_edit_text` | advanced | external-or-network | quick_reply：在独立快捷回复消息空间内编辑一条明确 ID 的格式化文本消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：shortcut_id, message_id, text。 属于quick_reply 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.quick_reply.rename` | advanced | external-or-network | quick_reply：按稳定 shortcut ID 修改普通快捷回复名称；保留 hello/away Business 名称。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：shortcut_id, shortcut。 属于quick_reply 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.quick_reply.reorder` | advanced | external-or-network | quick_reply：以完整、无重复的 shortcut ID 列表替换服务器快捷回复顺序。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：shortcut_ids, replace。 属于quick_reply 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.quick_reply.send` | advanced | external-or-network | quick_reply：把快捷回复的全部或指定模板消息发送到明确 peer，并返回每条新消息的稳定 ID。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, shortcut_id, idempotency_key。 属于quick_reply 功能的底层接口，仅在语义接口不能满足时使用。 |

## security（`security`，1 个）

security。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.security.two_step_status` | preferred | read-only | security：从服务器读取两步验证、恢复邮箱、安全值和待重置状态；SRP 参数、提示和邮箱掩码保持脱敏。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |

## 登录会话（`session`，2 个）

列出与终止其他已授权设备会话。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.session.list` | preferred | read-only | 登录会话：列出当前账号的设备登录会话，不返回授权哈希或秘密。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.session.terminate` | advanced | destructive/confirmation-required | 登录会话：终止指定的非当前设备登录会话。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：session_id。 属于列出与终止其他已授权设备会话的底层接口，仅在语义接口不能满足时使用。 |

## 设置（`settings`，4 个）

Agent 允许范围内的本地设置。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.settings.auto_download_get` | preferred | read-only | 设置：读取移动网络、Wi-Fi 和漫游的当前预设、媒体上限、聊天类型掩码及预加载行为。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.settings.auto_download_set` | advanced | external-or-network | 设置：将单个网络精确设为关闭、低、中或高预设，持久化本机选择并沿用 Telegram GUI 的云端预设同步路径。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：network, preset。 属于Agent 允许范围内的本地设置的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.settings.get` | preferred | read-only | 设置：读取白名单内的显示、播放、流媒体与列表设置。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：keys。 |
| `telegram.settings.set` | standard | write | 设置：幂等修改白名单内的本地设置，不允许任意 SharedPreferences 键。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：values。 |

## sticker（`sticker`，7 个）

sticker。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.sticker.favorite_list` | preferred | read-only | sticker：从服务器读取账号的收藏贴纸文档，64 位 ID 使用字符串。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, offset, limit。 |
| `telegram.sticker.favorite_set` | standard | write | sticker：从精确服务器消息取得贴纸文档，设置其收藏状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：source_peer, source_message_id, saved。 |
| `telegram.sticker.pack_search` | preferred | read-only | sticker：按关键词搜索服务器贴纸包或自定义 emoji 包，返回稳定 short_name、安装和归档状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：query。 |
| `telegram.sticker.pack_set` | advanced | destructive/confirmation-required | sticker：按公开 short_name 安装或卸载贴纸/emoji 包；卸载要求显式确认并以服务器 getStickerSet 回读。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：short_name, installed。 属于sticker 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.sticker.search` | preferred | read-only | sticker：从服务器按 emoji 搜索贴纸并返回仅在当前 APP/MCP 生命周期有效的签名 document_ref。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：emoji。 |
| `telegram.sticker.send` | advanced | external-or-network | sticker：使用 sticker.search 返回的短期签名引用经 SendMessagesHelper 发送贴纸，支持回复、静默和定时。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, document_ref, idempotency_key。 属于sticker 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.sticker.send_saved` | advanced | external-or-network | sticker：按收藏列表中的 document_id 通过 Telegram 发送助手发送贴纸。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, document_id, idempotency_key。 属于sticker 功能的底层接口，仅在语义接口不能满足时使用。 |

## storage（`storage`，2 个）

storage。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.storage.cache_clear` | advanced | destructive/confirmation-required | storage：停止当前下载后清理明确选择的缓存分类；mcp_staging 会同时清除文件目录与分片上传会话，保留草稿目录，刷新 FileLoader/媒体索引，并返回前后独立扫描。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：categories。 属于storage 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.storage.stats` | preferred | read-only | storage：按照片、视频、文档、音乐、语音、Stories、贴纸、临时文件、日志和 MCP 暂存区扫描本机缓存大小；不暴露文件路径。 返回结构化成功或可操作错误，不返回认证秘密。 无业务参数。 |

## story（`story`，15 个）

story。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.story.archive_list` | preferred | read-only | story：分页列出自己或可管理 peer 的归档 Story。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.story.can_send` | preferred | read-only | story：从服务器查询当前账号能否为自己、Bot 或所管理频道继续发布 Story。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.story.delete` | advanced | destructive/confirmation-required | story：删除自己或有删除权限的精确 Story，并以服务器缺席回读验证。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, story_id。 属于story 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.story.edit` | advanced | external-or-network | story：替换自己或可管理 Story 的媒体、caption 或可见性，并按精确 story_id 回读。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, story_id。 属于story 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.story.get` | preferred | read-only | story：从指定 peer 精确读取一条 Story 的 caption、媒体、可见性和计数。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, story_id。 |
| `telegram.story.hide_peer` | standard | write | story：把指定 peer 的 Story 移入隐藏列表，不屏蔽 peer。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.story.list` | preferred | read-only | story：按 peer 从服务器读取活跃、置顶或归档 Story，返回稳定 story_id 与分页状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.story.mark_read` | standard | write | story：把指定 peer 的 Story 已读进度推进到精确 story_id。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, story_id。 |
| `telegram.story.pin` | advanced | external-or-network | story：把自己或可管理的 Story 放到资料页置顶列表。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, story_id。 属于story 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.story.pinned_list` | preferred | read-only | story：分页列出 peer 资料页上的置顶 Story 及置顶顺序。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.story.publish` | advanced | external-or-network | story：从 MCP 私有暂存区发布图片或视频 Story，支持 caption 格式、可见性、时长、置顶和禁止转发。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, file_ref, idempotency_key。 属于story 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.story.reaction_set` | advanced | external-or-network | story：对精确 Story 设置普通 emoji、自定义 emoji 反应，或显式清除。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, story_id。 属于story 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.story.unhide_peer` | standard | write | story：把指定 peer 的 Story 恢复到主列表。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.story.unpin` | advanced | external-or-network | story：把自己或可管理的置顶 Story 移入归档。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, story_id。 属于story 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.story.views_list` | preferred | read-only | story：对自己或有管理权的 Story 分页读取浏览者、反应、转发和公开转贴。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, story_id。 |

## 运行状态（`system`，1 个）

MCP、账号槽位和网络状态。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.system.health` | preferred | read-only | 运行状态：确认应用进程、MCP 版本、登录槽位和网络状态。 返回结构化成功或可操作错误，不返回认证秘密。 无业务参数。 |

## topic（`topic`，7 个）

topic。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.topic.create` | advanced | external-or-network | topic：在有权限的论坛超级群组中创建主题。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, title, idempotency_key。 属于topic 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.topic.delete` | advanced | destructive/confirmation-required | topic：删除主题及其消息历史；General 主题不可删除。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, topic_id。 属于topic 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.topic.get` | preferred | read-only | topic：按 peer 与 topic_id 从服务器读取精确主题状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, topic_id。 |
| `telegram.topic.list` | preferred | read-only | topic：分页列出论坛超级群组的主题、未读和置顶状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.topic.pin` | advanced | external-or-network | topic：在论坛主题列表中置顶指定主题。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, topic_id。 属于topic 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.topic.unpin` | advanced | external-or-network | topic：取消指定论坛主题的置顶状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, topic_id。 属于topic 功能的底层接口，仅在语义接口不能满足时使用。 |
| `telegram.topic.update` | advanced | external-or-network | topic：原子修改主题标题、图标、关闭或隐藏状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, topic_id。 属于topic 功能的底层接口，仅在语义接口不能满足时使用。 |
