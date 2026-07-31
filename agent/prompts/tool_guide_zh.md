# Telegram Android MCP 工具说明

## 六个 Agent 网关

- `telegram_capabilities`：查看功能域、数量与风险分布。
- `telegram_search_tools`：用中英文关键词检索真实接口。
- `telegram_tool_schema`：读取精确输入 schema、风险和必填参数。
- `telegram_call`：执行一个真实 MCP；高影响操作由本地终端确认。
- `telegram_batch`：顺序执行多个已核对 schema 的调用。
- `telegram_get_context`：读取健康状态、账号、本人资料与最近会话。

## 真实 MCP 功能域

- `system`：健康检查。
- `account`、`profile`：账号摘要和本人资料。
- `peer`：`saved`、公开用户名或已缓存稳定 ID 的目标解析；不接受手机号。
- `dialog`：会话列表、归档、静音、置顶与经确认的历史清理。
- `message`：历史、按 ID 读取、定时消息、搜索、发送、编辑、删除、转发、表情反应、已读与消息置顶。
- `draft`：草稿读取、设置与清除，支持话题 ID。
- `contact`：联系人列表、搜索、封禁列表、屏蔽与解除屏蔽。
- `chat`：群组/频道创建、完整资料和成员读取、资料修改、加入与退出。
- `settings`：受支持本地设置的读取和修改。
- `session`：其他登录设备的列出和终止。

调用原则：先找工具，再看 schema；先解析目标，再执行写入；最后回读验证。发送和转发重试必须复用同一个 `idempotency_key`。

## 写后验证映射

- `message.send_text` / `message.edit_text` / `message.reaction_set` / `message.pin` / `message.unpin` -> `message.get`。
- `message.delete` -> `message.get` 应返回不存在；不要把提交删除请求直接当成删除成功。
- `draft.set` / `draft.clear` -> `draft.get`，并核对相同 `peer` 与 `topic_id`。
- `dialog.archive` / `unarchive` / `mute` / `unmute` / `pin` / `unpin` -> `dialog.list`。
- `chat.create_group` / `create_channel` / `update_title` / `update_about` -> `chat.get`；成员读取用 `chat.members_list`。
- `contact.block` / `unblock` -> `peer.resolve` 或 `contact.blocked_list`。
- `settings.set` -> `settings.get`；`profile.update` -> `account.get_me`；`session.terminate` -> `session.list`。

自动闭环只在 Saved Messages 使用临时 `MCP-E2E-` 数据。群组、联系人、频道、公开加入目标和设备会话必须使用用户明确提供的可丢弃 fixture；缺失时返回可操作的阻塞说明。
