# Telegram Android MCP Agent 接口目录

生成时间：`2026-07-30T07:45:20.929051+00:00`

共 `46` 个已安装接口。Agent 必须先解析目标、核对 schema，并在写入后回读验证。

风险：`read-only` 只读；`write` 写入；`external-or-network` 依赖外部服务；`destructive/confirmation-required` 必须由人确认。

## 账号（`account`，2 个）

多账号槽位与本人资料。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.account.get_me` | preferred | read-only | 账号：读取指定账号自己的安全资料摘要。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.account.list` | preferred | read-only | 账号：查看 Telegram 多账号槽位及安全的身份摘要。 返回结构化成功或可操作错误，不返回认证秘密。 无业务参数。 |

## 群组与频道（`chat`，8 个）

创建、加入、退出及资料维护。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.chat.create_channel` | preferred | external-or-network | 群组与频道：创建广播频道或超级群组并设置标题和简介。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：title, kind。 |
| `telegram.chat.create_group` | preferred | external-or-network | 群组与频道：用明确成员列表创建 Telegram 群组。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：title, members。 |
| `telegram.chat.get` | preferred | read-only | 群组与频道：读取群组、超级群组或频道的简介、人数、权限和置顶摘要。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.join_public` | preferred | destructive/confirmation-required | 群组与频道：解析公开 username 并加入对应频道/群组。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.leave` | preferred | destructive/confirmation-required | 群组与频道：让当前账号退出指定群组或频道。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.members_list` | preferred | read-only | 群组与频道：分页读取群组、超级群组或频道中当前账号有权查看的成员。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.chat.update_about` | preferred | external-or-network | 群组与频道：修改有管理权限的 chat 简介。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, about。 |
| `telegram.chat.update_title` | preferred | external-or-network | 群组与频道：修改有管理权限的 chat 标题。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, title。 |

## 联系人（`contact`，5 个）

联系人检索、屏蔽与解除屏蔽。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.contact.block` | preferred | destructive/confirmation-required | 联系人：阻止指定用户或频道继续联系当前账号。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.contact.blocked_list` | preferred | read-only | 联系人：分页读取当前账号的封禁列表。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, offset, limit。 |
| `telegram.contact.list` | preferred | read-only | 联系人：列出云端联系人，不读取 Android 通讯录。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, limit。 |
| `telegram.contact.search` | preferred | read-only | 联系人：按关键词搜索 Telegram 用户、群组和已有联系人。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：query。 |
| `telegram.contact.unblock` | preferred | external-or-network | 联系人：恢复指定用户或频道与当前账号的联系权限。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |

## 会话（`dialog`，8 个）

会话列表、归档、静音和置顶。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.dialog.archive` | preferred | write | 会话：把指定对话移入归档文件夹。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.clear_history` | preferred | destructive/confirmation-required | 会话：保留会话但清空其消息历史，可选为双方清除。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.list` | preferred | read-only | 会话：分页列出当前账号的私聊、群组、频道与文件夹状态。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, limit, folder_id。 |
| `telegram.dialog.mute` | preferred | write | 会话：按会话或话题永久静音通知。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.pin` | preferred | write | 会话：在主列表或归档中置顶一个会话。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.unarchive` | preferred | write | 会话：把指定对话移回主列表。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.unmute` | preferred | write | 会话：恢复指定会话或话题的通知。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.dialog.unpin` | preferred | write | 会话：取消指定会话的置顶状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |

## 草稿（`draft`，3 个）

设置或清除指定会话草稿。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.draft.clear` | preferred | write | 草稿：清空会话或话题草稿。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.draft.get` | preferred | read-only | 草稿：读取会话或话题当前保存的文本草稿。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.draft.set` | preferred | write | 草稿：为会话或话题保存文本草稿。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, text。 |

## 消息（`message`，13 个）

历史、搜索、发送、编辑、转发、删除和已读状态。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.message.delete` | preferred | destructive/confirmation-required | 消息：删除指定消息，可选仅自己或所有人。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_ids。 |
| `telegram.message.edit_text` | preferred | external-or-network | 消息：编辑当前账号有权修改的文本消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id, text。 |
| `telegram.message.forward` | preferred | external-or-network | 消息：把现有消息转发到另一个 peer。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：from_peer, to_peer, message_ids, idempotency_key。 |
| `telegram.message.get` | preferred | read-only | 消息：读取指定 peer 中一组明确消息 ID 的最新服务器对象。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_ids。 |
| `telegram.message.history` | preferred | read-only | 消息：按 peer 和偏移量分页读取可显示的消息历史。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.message.mark_read` | preferred | write | 消息：把会话或话题读进度推进到指定消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, max_message_id。 |
| `telegram.message.mark_unread` | preferred | write | 消息：把指定会话标记为未读提醒。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.message.pin` | preferred | external-or-network | 消息：在有权限的会话中置顶一条消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id。 |
| `telegram.message.reaction_set` | preferred | external-or-network | 消息：为一条消息设置单个标准 emoji 反应；空字符串用于移除自己的反应。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id, reaction。 |
| `telegram.message.scheduled_list` | preferred | read-only | 消息：列出指定会话尚未发送的定时消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |
| `telegram.message.search` | preferred | read-only | 消息：在指定会话或全局按文本搜索消息。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：query。 |
| `telegram.message.send_text` | preferred | external-or-network | 消息：向一个 peer 发送纯文本，可选回复、静默和定时发送。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, text, idempotency_key。 |
| `telegram.message.unpin` | preferred | external-or-network | 消息：取消指定消息的置顶状态。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer, message_id。 |

## 目标解析（`peer`，1 个）

将公开用户名或稳定内部引用解析为可操作目标。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.peer.resolve` | preferred | read-only | 目标解析：把 username 或稳定引用解析为 Agent 可复用的 peer 引用。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：peer。 |

## 个人资料（`profile`，1 个）

姓名、简介等本人资料维护。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.profile.update` | preferred | external-or-network | 个人资料：修改当前账号的 first_name、last_name 或 about。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, first_name, last_name, about。 |

## 登录会话（`session`，2 个）

列出与终止其他已授权设备会话。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.session.list` | preferred | read-only | 登录会话：列出当前账号的设备登录会话，不返回授权哈希或秘密。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account。 |
| `telegram.session.terminate` | preferred | destructive/confirmation-required | 登录会话：终止指定的非当前设备登录会话。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：session_id。 |

## 设置（`settings`，2 个）

Agent 允许范围内的本地设置。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.settings.get` | preferred | read-only | 设置：读取白名单内的显示、播放、流媒体与列表设置。 返回结构化成功或可操作错误，不返回认证秘密。 可选参数：account, keys。 |
| `telegram.settings.set` | preferred | write | 设置：幂等修改白名单内的本地设置，不允许任意 SharedPreferences 键。 返回结构化成功或可操作错误，不返回认证秘密。 必填参数：values。 |

## 运行状态（`system`，1 个）

MCP、账号槽位和网络状态。

| MCP 接口 | 层级 | 风险 | 功能与调用提示 |
| --- | --- | --- | --- |
| `telegram.system.health` | preferred | read-only | 运行状态：确认应用进程、MCP 版本、登录槽位和网络状态。 返回结构化成功或可操作错误，不返回认证秘密。 无业务参数。 |
