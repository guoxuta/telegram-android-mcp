#!/usr/bin/env python3
"""Generate the source-backed Telegram Android MCP capability ledger.

This is deliberately semantic: one entry represents a user intent, not a UI
button or a raw MTProto constructor.  Source anchors are resolved against the
current checkout so upstream drift fails generation instead of leaving stale
line numbers in the inventory.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


RUN_ID = "telegram-mcp-20260729"
IMPLEMENTATION = "TMessagesProj/src/main/java/org/telegram/messenger/mcp/TelegramMcpService.java"
SERVER = "TMessagesProj/src/main/java/org/telegram/messenger/mcp/TelegramMcpServer.java"


def schema_property(kind: str, description: str, **kwargs: Any) -> dict[str, Any]:
    value: dict[str, Any] = {"type": kind, "description": description}
    value.update(kwargs)
    return value


ACCOUNT = schema_property("integer", "账号槽位，0 到 3；省略时使用 Telegram 当前选中账号。", minimum=0, maximum=3)
PEER = schema_property(
    "string",
    "稳定目标引用：saved、@username、user:<id>、chat:<id>、channel:<id> 或 dialog:<signed-id>。",
    minLength=1,
)
MESSAGE_ID = schema_property("integer", "目标会话中的 Telegram 消息 ID。", minimum=1)
LIMIT = schema_property("integer", "返回数量上限。", minimum=1, maximum=100, default=50)
CONFIRM = schema_property("boolean", "确认执行高影响操作；必须显式为 true。", const=True)
SETTING_DESCRIPTIONS = {
    "autoplay_video": "自动播放视频。",
    "autoplay_gifs": "自动播放 GIF。",
    "stream_media": "允许流式播放媒体。",
    "stream_all_video": "允许流式播放所有视频。",
    "stream_mkv": "允许流式播放 MKV。",
    "save_stream_media": "保存流式媒体缓存。",
    "direct_share": "启用 Android 直接分享目标。",
    "inapp_camera": "启用应用内相机。",
    "raise_to_speak": "启用抬起说话。",
    "raise_to_listen": "启用抬起收听。",
    "sort_contacts_by_name": "按姓名排序联系人。",
    "sort_files_by_name": "按名称排序文件。",
    "three_line_layout": "会话列表使用三行布局。",
}
SETTING_KEYS = list(SETTING_DESCRIPTIONS)


@dataclass(frozen=True)
class Anchor:
    path: str
    pattern: str
    layer: str
    proves: str


@dataclass
class Capability:
    id: str
    title: str
    domain: str
    intent: str
    kind: str
    risk: str
    ui: Anchor
    callable: Anchor
    properties: dict[str, Any] = field(default_factory=dict)
    required: list[str] = field(default_factory=list)
    read_only: bool = True
    destructive: bool = False
    idempotent: bool = True
    open_world: bool = False
    confirmation: str | None = None
    readback: str | None = None
    atomicity: str = "semantic-atomic"
    side_effects: list[str] = field(default_factory=list)
    preconditions: list[str] = field(default_factory=lambda: ["目标账号已登录"])
    outputs: list[str] = field(default_factory=lambda: ["结构化结果与稳定对象引用"])
    tier: str = "preferred"

    @property
    def tool_name(self) -> str:
        return f"telegram.{self.id}"


def anchor(path: str, pattern: str, layer: str, proves: str) -> Anchor:
    return Anchor(path, pattern, layer, proves)


def props(**values: dict[str, Any]) -> dict[str, Any]:
    result = {"account": copy.deepcopy(ACCOUNT)}
    result.update(values)
    return result


UI = "TMessagesProj/src/main/java/org/telegram/ui/"
MSG = "TMessagesProj/src/main/java/org/telegram/messenger/"


CAPABILITIES: list[Capability] = [
    Capability(
        "system.health", "读取 MCP 与应用健康状态", "system", "确认应用进程、MCP 版本、登录槽位和网络状态。",
        "read", "read",
        anchor(MSG + "ApplicationLoader.java", r"public static void postInitApplication\(\)", "state", "应用服务初始化入口。"),
        anchor(MSG + "ApplicationLoader.java", r"MessagesController\.getInstance\(a\)", "domain", "账号控制器在应用初始化阶段可用。"),
        properties={}, required=[], preconditions=[], outputs=["服务版本", "账号激活状态", "网络状态"],
    ),
    Capability(
        "account.list", "列出本机账号槽位", "account", "查看 Telegram 多账号槽位及安全的身份摘要。",
        "read", "read",
        anchor(UI + "LaunchActivity.java", r"switchToAccount\(", "ui", "GUI 支持账号切换。"),
        anchor(MSG + "UserConfig.java", r"public static int getActivatedAccountsCount\(\)", "domain", "账号激活状态具有稳定读取接口。"),
        properties={}, required=[], preconditions=[], outputs=["账号槽位", "是否已登录", "用户 ID 与显示名"],
    ),
    Capability(
        "account.get_me", "读取当前账号资料", "account", "读取指定账号自己的安全资料摘要。",
        "read", "read",
        anchor(UI + "SettingsActivity.java", r"getCurrentUser\(\)", "ui", "设置页读取当前用户资料。"),
        anchor(MSG + "UserConfig.java", r"public TLRPC\.User getCurrentUser\(\)", "domain", "当前用户对象具有稳定读取接口。"),
        properties=props(), required=[], outputs=["用户 ID", "姓名", "username", "会员和机器人标志"],
    ),
    Capability(
        "peer.resolve", "解析用户或会话引用", "peers", "把 username 或稳定引用解析为 Agent 可复用的 peer 引用。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"TL_contacts_resolveUsername", "ui", "聊天 GUI 会解析 username 后打开目标。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_contacts_resolveUsername", "backend", "MTProto 提供 username 解析请求。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], outputs=["规范 peer 引用", "类型", "ID", "显示摘要"],
    ),
    Capability(
        "dialog.list", "列出对话", "dialogs", "分页列出当前账号的私聊、群组、频道与文件夹状态。",
        "read", "read",
        anchor(UI + "DialogsActivity.java", r"class DialogsActivity", "ui", "GUI 的会话列表入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getDialogs", "backend", "MTProto 提供对话分页读取。"),
        properties=props(limit=copy.deepcopy(LIMIT), folder_id=schema_property("integer", "文件夹 ID；0 为主列表，1 为归档。", minimum=0)),
        required=[], outputs=["对话 peer", "标题", "未读数", "置顶/归档/静音状态", "末条消息摘要"],
    ),
    Capability(
        "dialog.archive", "归档对话", "dialogs", "把指定对话移入归档文件夹。",
        "write", "write",
        anchor(UI + "DialogsActivity.java", r"addDialogToFolder\(dialog\.id", "ui", "GUI 归档动作调用文件夹移动。"),
        anchor(MSG + "MessagesController.java", r"public int addDialogToFolder\(long dialogId", "domain", "控制器保留归档所需本地与远端一致性。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], read_only=False,
        side_effects=["对话移入归档并同步服务器"], readback="telegram.dialog.list(folder_id=1) 中出现目标 peer",
    ),
    Capability(
        "dialog.unarchive", "取消归档对话", "dialogs", "把指定对话移回主列表。",
        "write", "write",
        anchor(UI + "DialogsActivity.java", r"addDialogToFolder\(copy, canUnarchiveCount", "ui", "GUI 提供取消归档动作。"),
        anchor(MSG + "MessagesController.java", r"public int addDialogToFolder\(long dialogId", "domain", "同一控制器原子地移动回主文件夹。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], read_only=False,
        side_effects=["对话移回主列表并同步服务器"], readback="telegram.dialog.list(folder_id=0) 中出现目标 peer",
    ),
    Capability(
        "dialog.mute", "静音对话", "notifications", "按会话或话题永久静音通知。",
        "write", "write",
        anchor(UI + "DialogsActivity.java", r"SETTING_MUTE_FOREVER", "ui", "GUI 会话菜单支持永久静音。"),
        anchor(MSG + "NotificationsController.java", r"public void muteDialog\(long dialog_id", "domain", "通知控制器统一更新设置与服务器。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "可选话题 ID；0 表示整个会话。", minimum=0)), required=["peer"], read_only=False,
        side_effects=["改变 Telegram 通知设置"], readback="telegram.dialog.list 返回 muted=true",
    ),
    Capability(
        "dialog.unmute", "取消静音对话", "notifications", "恢复指定会话或话题的通知。",
        "write", "write",
        anchor(UI + "DialogsActivity.java", r"SETTING_MUTE_UNMUTE", "ui", "GUI 会话菜单支持取消静音。"),
        anchor(MSG + "NotificationsController.java", r"public void muteDialog\(long dialog_id", "domain", "通知控制器支持幂等取消静音。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "可选话题 ID；0 表示整个会话。", minimum=0)), required=["peer"], read_only=False,
        side_effects=["改变 Telegram 通知设置"], readback="telegram.dialog.list 返回 muted=false",
    ),
    Capability(
        "dialog.pin", "置顶对话", "dialogs", "在主列表或归档中置顶一个会话。",
        "write", "write",
        anchor(UI + "DialogsActivity.java", r"pinDialog\(selectedDialog, true", "ui", "GUI 支持置顶会话。"),
        anchor(MSG + "MessagesController.java", r"public boolean pinDialog\(long dialogId", "domain", "控制器同步置顶顺序。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], read_only=False,
        side_effects=["改变对话置顶状态与顺序"], readback="telegram.dialog.list 返回 pinned=true",
    ),
    Capability(
        "dialog.unpin", "取消置顶对话", "dialogs", "取消指定会话的置顶状态。",
        "write", "write",
        anchor(UI + "DialogsActivity.java", r"pinDialog\(selectedDialog, false", "ui", "GUI 支持取消置顶。"),
        anchor(MSG + "MessagesController.java", r"public boolean pinDialog\(long dialogId", "domain", "控制器同步取消置顶。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], read_only=False,
        side_effects=["改变对话置顶状态与顺序"], readback="telegram.dialog.list 返回 pinned=false",
    ),
    Capability(
        "dialog.clear_history", "清空对话历史", "dialogs", "保留会话但清空其消息历史，可选为双方清除。",
        "write", "destructive",
        anchor(UI + "DialogsActivity.java", r"deleteDialog\(selectedDialog, 1, revoke\)", "ui", "GUI 清空历史动作调用控制器 onlyHistory=1。"),
        anchor(MSG + "MessagesController.java", r"public void deleteDialog\(final long did, int onlyHistory, boolean revoke\)", "domain", "控制器维护本地存储与远端删除历史的一致性。"),
        properties=props(peer=copy.deepcopy(PEER), for_everyone=schema_property("boolean", "有权限时是否同时为对方清除。", default=False), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "_confirm"], read_only=False, destructive=True, idempotent=True, open_world=True, confirmation="_confirm",
        side_effects=["永久清空对话消息历史"], readback="telegram.message.history 返回空列表",
    ),
    Capability(
        "message.history", "读取消息历史", "messages", "按 peer 和偏移量分页读取可显示的消息历史。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"TL_messages_getHistory", "ui", "聊天 GUI 通过历史请求加载消息。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getHistory", "backend", "MTProto 提供消息历史读取。"),
        properties=props(peer=copy.deepcopy(PEER), limit=copy.deepcopy(LIMIT), offset_id=schema_property("integer", "从该消息 ID 之前读取；0 表示最新。", minimum=0)),
        required=["peer"], outputs=["规范化消息列表", "发送者", "时间", "媒体类型", "分页游标"],
    ),
    Capability(
        "message.get", "按 ID 读取消息", "messages", "读取指定 peer 中一组明确消息 ID 的最新服务器对象。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"startLoadFromMessageId", "ui", "GUI 支持围绕指定消息 ID 定位与加载。"),
        anchor("TMessagesProj/src/main/java/org/telegram/messenger/FileRefController.java", r"TL_channels_getMessages req", "domain", "客户端按会话类型选择 channels.getMessages 或 messages.getMessages。"),
        properties=props(peer=copy.deepcopy(PEER), message_ids=schema_property("array", "消息 ID。", minItems=1, maxItems=100, uniqueItems=True, items={"type": "integer", "minimum": 1})),
        required=["peer", "message_ids"], outputs=["规范化消息对象", "回复关系", "反应摘要"],
    ),
    Capability(
        "message.scheduled_list", "读取定时消息", "messages", "列出指定会话尚未发送的定时消息。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"MODE_SCHEDULED", "ui", "聊天 GUI 有独立定时消息模式。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getScheduledHistory", "backend", "MTProto 提供定时历史读取。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], outputs=["定时消息列表与计划时间"],
    ),
    Capability(
        "message.search", "搜索消息", "messages", "在指定会话或全局按文本搜索消息。",
        "read", "read",
        anchor(UI + "FilteredSearchView.java", r"TL_messages_search", "ui", "GUI 搜索使用消息搜索请求。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_search extends", "backend", "MTProto 提供会话内消息搜索。"),
        properties=props(query=schema_property("string", "搜索文本。", minLength=1), peer=copy.deepcopy(PEER), limit=copy.deepcopy(LIMIT)),
        required=["query"], outputs=["匹配消息与 peer", "分页元数据"],
    ),
    Capability(
        "message.send_text", "发送文本消息", "messages", "向一个 peer 发送纯文本，可选回复、静默和定时发送。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"SendMessageParams params = SendMessagesHelper\.SendMessageParams\.of\(message", "ui", "GUI 发送框构造语义发送参数。"),
        anchor(MSG + "SendMessagesHelper.java", r"public void sendMessage\(SendMessageParams", "domain", "发送助手维护本地队列、重试和更新一致性。"),
        properties=props(
            peer=copy.deepcopy(PEER), text=schema_property("string", "UTF-8 文本；不得为空。", minLength=1, maxLength=4096),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1),
            silent=schema_property("boolean", "是否静默发送。", default=False),
            schedule_at=schema_property("string", "可选 ISO-8601 定时时间。", format="date-time"),
            idempotency_key=schema_property("string", "重试去重键；同一发送意图必须复用。", minLength=8, maxLength=128),
        ), required=["peer", "text", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向 Telegram 外部收件人发送消息"], readback="telegram.message.history 中按返回 message_id 读回精确文本",
    ),
    Capability(
        "message.edit_text", "编辑文本消息", "messages", "编辑当前账号有权修改的文本消息。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"editMessage\(editingMessageObject", "ui", "GUI 编辑框调用发送助手编辑消息。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_editMessage", "backend", "MTProto 提供消息编辑并返回 Updates。"),
        properties=props(peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID), text=schema_property("string", "新文本。", minLength=1, maxLength=4096)),
        required=["peer", "message_id", "text"], read_only=False, open_world=True,
        side_effects=["修改已发送消息，收件人可见"], readback="telegram.message.history 读回 message_id 的新文本",
    ),
    Capability(
        "message.delete", "删除消息", "messages", "删除指定消息，可选仅自己或所有人。",
        "write", "destructive",
        anchor(UI + "Components/DeleteMessagesBottomSheet.java", r"performDelete\(\)", "ui", "GUI 通过确认面板执行删除。"),
        anchor(MSG + "MessagesController.java", r"public void deleteMessages\(ArrayList<Integer> messages", "domain", "控制器处理频道/普通会话删除与本地存储一致性。"),
        properties=props(peer=copy.deepcopy(PEER), message_ids=schema_property("array", "待删除消息 ID。", minItems=1, maxItems=100, items={"type": "integer", "minimum": 1}), for_everyone=schema_property("boolean", "有权限时是否为所有人删除。", default=False), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "message_ids", "_confirm"], read_only=False, destructive=True, idempotent=True, open_world=True, confirmation="_confirm",
        side_effects=["永久删除消息；可能影响所有参与者"], readback="telegram.message.history 不再返回目标 message_id",
    ),
    Capability(
        "message.forward", "转发消息", "messages", "把现有消息转发到另一个 peer。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"forwardMessages", "ui", "聊天 GUI 支持批量转发。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_forwardMessages", "backend", "MTProto 提供带 random_id 的幂等转发。"),
        properties=props(from_peer=copy.deepcopy(PEER), to_peer=copy.deepcopy(PEER), message_ids=schema_property("array", "源消息 ID。", minItems=1, maxItems=100, items={"type": "integer", "minimum": 1}), silent=schema_property("boolean", "是否静默转发。", default=False), idempotency_key=schema_property("string", "转发批次去重键。", minLength=8, maxLength=128)),
        required=["from_peer", "to_peer", "message_ids", "idempotency_key"], read_only=False, open_world=True,
        side_effects=["向目标会话发送转发内容"], readback="telegram.message.history 在目标会话读回返回的消息 ID",
    ),
    Capability(
        "message.reaction_set", "设置消息表情反应", "messages", "为一条消息设置单个标准 emoji 反应；空字符串用于移除自己的反应。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"sendReaction\(primaryMessage", "ui", "聊天 GUI 通过发送助手设置或移除反应。"),
        anchor(MSG + "SendMessagesHelper.java", r"TL_messages_sendReaction req", "domain", "发送助手构造 reaction 并处理 Updates。"),
        properties=props(peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID), reaction=schema_property("string", "标准 emoji；空字符串移除反应。", maxLength=32), add_to_recent=schema_property("boolean", "是否加入最近反应。", default=True)),
        required=["peer", "message_id", "reaction"], read_only=False, idempotent=True, open_world=True,
        side_effects=["更改对外可见的消息反应"], readback="telegram.message.get 返回 chosen=true 的反应，或不再返回该反应",
    ),
    Capability(
        "message.mark_read", "标记会话已读", "messages", "把会话或话题读进度推进到指定消息。",
        "write", "write",
        anchor(UI + "DialogsActivity.java", r"markDialogAsRead\(did", "ui", "GUI 会话动作可标记已读。"),
        anchor(MSG + "MessagesController.java", r"public void markDialogAsRead\(long dialogId", "domain", "控制器同时更新本地未读状态与服务器。"),
        properties=props(peer=copy.deepcopy(PEER), max_message_id=copy.deepcopy(MESSAGE_ID), topic_id=schema_property("integer", "可选话题 ID。", minimum=0)),
        required=["peer", "max_message_id"], read_only=False,
        side_effects=["更新已读回执和未读计数"], readback="telegram.dialog.list 返回更新后的 unread_count",
    ),
    Capability(
        "message.mark_unread", "标记会话未读", "messages", "把指定会话标记为未读提醒。",
        "write", "write",
        anchor(UI + "DialogsActivity.java", r"markDialogAsUnread\(did", "ui", "GUI 会话动作可标记未读。"),
        anchor(MSG + "MessagesController.java", r"public void markDialogAsUnread\(long dialogId", "domain", "控制器同步未读标记。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], read_only=False,
        side_effects=["更新未读标志"], readback="telegram.dialog.list 返回 unread_mark=true",
    ),
    Capability(
        "message.pin", "置顶消息", "messages", "在有权限的会话中置顶一条消息。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"pinMessage\(", "ui", "聊天 GUI 提供置顶消息动作。"),
        anchor(MSG + "MessagesController.java", r"public void pinMessage\(", "domain", "控制器处理通知与单边/双边置顶语义。"),
        properties=props(peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID), notify=schema_property("boolean", "是否通知成员。", default=False)),
        required=["peer", "message_id"], read_only=False, open_world=True,
        side_effects=["改变会话置顶消息，可能通知成员"], readback="telegram.message.history 返回 pinned=true",
    ),
    Capability(
        "message.unpin", "取消置顶消息", "messages", "取消指定消息的置顶状态。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"pinMessage\(", "ui", "同一 GUI 动作支持取消置顶。"),
        anchor(MSG + "MessagesController.java", r"public void pinMessage\(", "domain", "控制器的 unpin 参数保持一致性。"),
        properties=props(peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID)), required=["peer", "message_id"], read_only=False, open_world=True,
        side_effects=["改变会话置顶消息"], readback="telegram.message.history 返回 pinned=false",
    ),
    Capability(
        "draft.get", "读取草稿", "drafts", "读取会话或话题当前保存的文本草稿。",
        "read", "read",
        anchor(UI + "Cells/DialogCell.java", r"getDraft\(currentDialogId", "ui", "GUI 会话列表读取草稿摘要。"),
        anchor(MSG + "MediaDataController.java", r"public TLRPC\.DraftMessage getDraft\(", "domain", "媒体数据控制器提供按会话和话题读取草稿的稳定接口。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "可选话题 ID。", minimum=0)),
        required=["peer"], outputs=["草稿是否存在", "文本", "日期", "回复目标", "媒体标志"],
    ),
    Capability(
        "draft.set", "保存草稿", "drafts", "为会话或话题保存文本草稿。",
        "write", "write",
        anchor(UI + "ChatActivity.java", r"getMediaDataController\(\)\.saveDraft", "ui", "GUI 离开编辑器时保存草稿。"),
        anchor(MSG + "MediaDataController.java", r"public void saveDraft\(long dialogId", "domain", "媒体数据控制器同步草稿与本地缓存。"),
        properties=props(peer=copy.deepcopy(PEER), text=schema_property("string", "草稿文本。", maxLength=4096), topic_id=schema_property("integer", "可选话题 ID。", minimum=0)),
        required=["peer", "text"], read_only=False,
        side_effects=["保存本地并同步云草稿"], readback="telegram.draft.get 返回精确文本",
    ),
    Capability(
        "draft.clear", "清除草稿", "drafts", "清空会话或话题草稿。",
        "write", "write",
        anchor(UI + "Components/ChatActivityEnterView.java", r"clearRichDraft\(\)", "ui", "GUI 支持清空草稿。"),
        anchor(MSG + "MediaDataController.java", r"TL_messages_saveDraft req", "domain", "空草稿通过同一同步请求保存。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "可选话题 ID。", minimum=0)), required=["peer"], read_only=False,
        side_effects=["清除本地和云草稿"], readback="telegram.draft.get 返回 exists=false",
    ),
    Capability(
        "contact.list", "列出 Telegram 联系人", "contacts", "列出云端联系人，不读取 Android 通讯录。",
        "read", "read",
        anchor(UI + "ContactsActivity.java", r"class ContactsActivity", "ui", "GUI 联系人列表入口。"),
        anchor(MSG + "ContactsController.java", r"public ArrayList<TLRPC\.TL_contact> contacts", "domain", "联系人控制器维护云端联系人集合。"),
        properties=props(limit=copy.deepcopy(LIMIT)), required=[], outputs=["联系人 peer", "姓名", "username"],
    ),
    Capability(
        "contact.search", "搜索用户和联系人", "contacts", "按关键词搜索 Telegram 用户、群组和已有联系人。",
        "read", "read",
        anchor(UI + "ContactsActivity.java", r"searching", "ui", "联系人 GUI 提供搜索模式。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_contacts_search", "backend", "MTProto 提供联系人/全局 peer 搜索。"),
        properties=props(query=schema_property("string", "姓名或 username 关键词。", minLength=1), limit=copy.deepcopy(LIMIT)), required=["query"],
        outputs=["匹配 peer 与来源类型"],
    ),
    Capability(
        "contact.blocked_list", "列出已封禁 peer", "contacts", "分页读取当前账号的封禁列表。",
        "read", "read",
        anchor(UI + "PrivacyUsersActivity.java", r"loadBlocked\(\)", "ui", "隐私 GUI 提供封禁列表入口。"),
        anchor(MSG + "MessagesController.java", r"TL_contacts_getBlocked req", "domain", "控制器通过 contacts.getBlocked 同步封禁列表。"),
        properties=props(offset=schema_property("integer", "分页偏移。", minimum=0), limit=copy.deepcopy(LIMIT)), required=[], outputs=["被封禁 peer", "封禁日期", "总数"],
    ),
    Capability(
        "contact.block", "封禁 peer", "contacts", "阻止指定用户或频道继续联系当前账号。",
        "write", "destructive",
        anchor(UI + "DialogsActivity.java", r"blockPeer\(selectedDialog", "ui", "GUI 会话菜单提供封禁。"),
        anchor(MSG + "MessagesController.java", r"public void blockPeer\(long id\)", "domain", "控制器更新服务器与本地 blocked 状态。"),
        properties=props(peer=copy.deepcopy(PEER), _confirm=copy.deepcopy(CONFIRM)), required=["peer", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["改变与外部 peer 的通信权限"], readback="telegram.peer.resolve 返回 blocked=true",
    ),
    Capability(
        "contact.unblock", "解除封禁 peer", "contacts", "恢复指定用户或频道与当前账号的联系权限。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"unblockPeer\(currentUser\.id", "ui", "GUI 聊天页支持解除封禁。"),
        anchor(MSG + "MessagesController.java", r"public void unblockPeer\(long id", "domain", "控制器同步解除封禁。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], read_only=False, open_world=True,
        side_effects=["恢复外部 peer 联系权限"], readback="telegram.peer.resolve 返回 blocked=false",
    ),
    Capability(
        "chat.create_group", "创建普通群组", "chats", "用明确成员列表创建 Telegram 群组。",
        "write", "external",
        anchor(UI + "GroupCreateFinalActivity.java", r"createChat\(", "ui", "GUI 群组创建页调用控制器。"),
        anchor(MSG + "MessagesController.java", r"public int createChat\(", "domain", "控制器实现群组创建和 Updates 处理。"),
        properties=props(title=schema_property("string", "群组标题。", minLength=1, maxLength=128), members=schema_property("array", "初始成员 peer。", minItems=1, maxItems=200, items=copy.deepcopy(PEER))),
        required=["title", "members"], read_only=False, idempotent=False, open_world=True,
        side_effects=["创建外部群组并邀请成员"], readback="telegram.dialog.list 出现返回的 chat peer",
    ),
    Capability(
        "chat.create_channel", "创建频道或超级群组", "chats", "创建广播频道或超级群组并设置标题和简介。",
        "write", "external",
        anchor(UI + "ChannelCreateActivity.java", r"createChat\(", "ui", "GUI 频道创建页进入控制器创建流程。"),
        anchor(MSG + "MessagesController.java", r"public int createChat\(", "domain", "同一控制器按 chatType 构造频道请求。"),
        properties=props(title=schema_property("string", "标题。", minLength=1, maxLength=128), about=schema_property("string", "简介。", maxLength=255), kind=schema_property("string", "channel 为广播频道，supergroup 为超级群组。", enum=["channel", "supergroup"])),
        required=["title", "kind"], read_only=False, idempotent=False, open_world=True,
        side_effects=["创建频道或超级群组"], readback="telegram.dialog.list 出现返回的 channel peer",
    ),
    Capability(
        "chat.get", "读取群组或频道完整资料", "chats", "读取群组、超级群组或频道的简介、人数、权限和置顶摘要。",
        "read", "read",
        anchor(UI + "ProfileActivity.java", r"getChatFull\(", "ui", "GUI 资料页读取完整 chat 资料。"),
        anchor(MSG + "MessagesController.java", r"TL_channels_getFullChannel req", "domain", "控制器使用完整资料请求刷新群组或频道状态。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"],
        outputs=["标题", "简介", "人数与管理员数量", "当前账号权限", "置顶消息 ID"],
    ),
    Capability(
        "chat.members_list", "列出群组或频道成员", "chats", "分页读取群组、超级群组或频道中当前账号有权查看的成员。",
        "read", "read",
        anchor(UI + "ChatUsersActivity.java", r"class ChatUsersActivity", "ui", "GUI 成员管理页展示成员与角色。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_getParticipants", "backend", "频道成员通过受权限控制的 participants API 读取。"),
        properties=props(peer=copy.deepcopy(PEER), offset=schema_property("integer", "分页偏移。", minimum=0), limit=copy.deepcopy(LIMIT), query=schema_property("string", "可选成员姓名或 username 搜索词。", maxLength=256)),
        required=["peer"], outputs=["成员 peer", "显示名", "角色", "加入日期", "总数"],
    ),
    Capability(
        "chat.update_title", "修改群组或频道标题", "chats", "修改有管理权限的 chat 标题。",
        "write", "external",
        anchor(UI + "ChatEditActivity.java", r"changeChatTitle", "ui", "GUI 编辑页修改标题。"),
        anchor(MSG + "MessagesController.java", r"public void changeChatTitle\(", "domain", "控制器区分普通群组和频道并处理 Updates。"),
        properties=props(peer=copy.deepcopy(PEER), title=schema_property("string", "新标题。", minLength=1, maxLength=128)), required=["peer", "title"], read_only=False, open_world=True,
        side_effects=["修改外部 chat 资料"], readback="telegram.peer.resolve 返回新标题",
    ),
    Capability(
        "chat.update_about", "修改群组或频道简介", "chats", "修改有管理权限的 chat 简介。",
        "write", "external",
        anchor(UI + "ChatEditActivity.java", r"updateChatAbout", "ui", "GUI 编辑页修改简介。"),
        anchor(MSG + "MessagesController.java", r"public void updateChatAbout\(", "domain", "控制器发送简介更新并刷新完整资料。"),
        properties=props(peer=copy.deepcopy(PEER), about=schema_property("string", "新简介；空字符串用于清除。", maxLength=255)), required=["peer", "about"], read_only=False, open_world=True,
        side_effects=["修改外部 chat 资料"], readback="telegram.chat.get 返回新简介",
    ),
    Capability(
        "chat.leave", "退出群组或频道", "chats", "让当前账号退出指定群组或频道。",
        "write", "destructive",
        anchor(UI + "ProfileActivity.java", r"deleteParticipantFromChat", "ui", "GUI 资料页退出 chat。"),
        anchor(MSG + "MessagesController.java", r"public void deleteParticipantFromChat\(", "domain", "控制器处理普通群组与频道退出。"),
        properties=props(peer=copy.deepcopy(PEER), _confirm=copy.deepcopy(CONFIRM)), required=["peer", "_confirm"], read_only=False, destructive=True, idempotent=True, open_world=True, confirmation="_confirm",
        side_effects=["退出群组或频道，可能失去历史访问"], readback="telegram.peer.resolve 返回 left=true 或 dialog 不再可见",
    ),
    Capability(
        "chat.join_public", "加入公开频道或群组", "chats", "解析公开 username 并加入对应频道/群组。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"joinChannel\.setOnClickListener", "ui", "GUI 公开 chat 预览支持加入。"),
        anchor(MSG + "MessagesController.java", r"public void addUserToChat\(", "domain", "控制器处理加入频道并同步 Updates。"),
        properties=props(peer=copy.deepcopy(PEER), _confirm=copy.deepcopy(CONFIRM)), required=["peer", "_confirm"], read_only=False, destructive=True, idempotent=True, open_world=True, confirmation="_confirm",
        side_effects=["加入外部频道或群组"], readback="telegram.dialog.list 出现目标 peer 且 left=false",
    ),
    Capability(
        "settings.get", "读取 Agent 友好的本地设置", "settings", "读取白名单内的显示、播放、流媒体与列表设置。",
        "read", "read",
        anchor(UI + "DataSettingsActivity.java", r"class DataSettingsActivity", "ui", "GUI 数据设置入口。"),
        anchor(MSG + "SharedConfig.java", r"public static boolean isAutoplayVideo\(\)", "domain", "SharedConfig 提供稳定本地设置读取。"),
        properties=props(keys=schema_property(
            "array",
            "可选设置键；省略返回全部白名单键。",
            uniqueItems=True,
            items={"type": "string", "enum": SETTING_KEYS},
        )), required=[], preconditions=[],
        outputs=["设置键、类型、当前值、是否需重启"],
    ),
    Capability(
        "settings.set", "修改 Agent 友好的本地设置", "settings", "幂等修改白名单内的本地设置，不允许任意 SharedPreferences 键。",
        "write", "write",
        anchor(UI + "DataSettingsActivity.java", r"SharedConfig\.", "ui", "GUI 设置页调用 SharedConfig setter/toggle。"),
        anchor(MSG + "SharedConfig.java", r"public static void toggleAutoplayVideo\(\)", "domain", "SharedConfig setter 持久化并触发应用更新。"),
        properties=props(values=schema_property(
            "object",
            "键值映射；仅接受下列白名单布尔键。",
            minProperties=1,
            properties={
                key: schema_property("boolean", description)
                for key, description in SETTING_DESCRIPTIONS.items()
            },
            additionalProperties=False,
        )), required=["values"], read_only=False, preconditions=[],
        side_effects=["修改本机 Telegram 设置"], readback="telegram.settings.get 返回精确新值",
    ),
    Capability(
        "profile.update", "修改自己的姓名或简介", "profile", "修改当前账号的 first_name、last_name 或 about。",
        "write", "external",
        anchor(UI + "ChangeNameActivity.java", r"saveName\(", "ui", "GUI 姓名页提交资料修改。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateProfile", "backend", "MTProto 提供资料更新请求。"),
        properties=props(first_name=schema_property("string", "名。", minLength=1, maxLength=64), last_name=schema_property("string", "姓，可为空。", maxLength=64), about=schema_property("string", "个人简介，可为空。", maxLength=70)),
        required=[], read_only=False, open_world=True,
        side_effects=["修改服务器上的公开账号资料"], readback="telegram.account.get_me 返回新资料",
    ),
    Capability(
        "session.list", "列出登录会话", "sessions", "列出当前账号的设备登录会话，不返回授权哈希或秘密。",
        "read", "read",
        anchor(UI + "SessionsActivity.java", r"getAuthorizations", "ui", "GUI 会话页加载授权列表。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class getAuthorizations", "backend", "账号 API 提供会话读取。"),
        properties=props(), required=[], outputs=["会话安全 ID", "设备", "应用版本", "IP/国家摘要", "当前会话标志"],
    ),
    Capability(
        "session.terminate", "终止登录会话", "sessions", "终止指定的非当前设备登录会话。",
        "write", "destructive",
        anchor(UI + "SessionsActivity.java", r"resetAuthorization req", "ui", "GUI 会话页执行单会话终止。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class resetAuthorization", "backend", "账号 API 按授权哈希终止会话。"),
        properties=props(session_id=schema_property("string", "由 session.list 返回的短期安全引用。", minLength=8), _confirm=copy.deepcopy(CONFIRM)),
        required=["session_id", "_confirm"], read_only=False, destructive=True, idempotent=True, confirmation="_confirm",
        side_effects=["远程设备立即退出账号"], readback="telegram.session.list 不再返回目标 session_id",
    ),
]


SYSTEM_BOUNDARIES = [
    ("auth.login", "登录、验证码与注册", "auth", UI + "LoginActivity.java", r"class LoginActivity", "登录需要手机号、验证码、密码或第三方认证，必须由用户完成。"),
    ("calls.start", "语音/视频通话", "calls", UI + "Components/voip/VoIPHelper.java", r"requestPermissions", "通话需要麦克风/摄像头权限和实时用户参与。"),
    ("media.capture", "相机、录像与录音", "media", UI + "Components/ChatAttachAlertPhotoLayout.java", r"Manifest\.permission\.CAMERA", "采集依赖设备传感器、权限与实时视觉操作。"),
    ("files.system_picker", "Android 文件/相册选择器", "files", UI + "Components/ChatAttachAlertPhotoLayout.java", r"ACTION_GET_CONTENT", "系统选择器不能由无界 MCP 静默代替；应使用后续私有暂存文件接口。"),
    ("location.live_device", "设备定位和实时位置", "location", UI + "LocationActivity.java", r"ACCESS_FINE_LOCATION", "设备定位依赖运行时权限、传感器和用户知情。"),
    ("qr.scan", "二维码扫描", "qr", UI + "QrActivity.java", r"Manifest\.permission\.CAMERA", "二维码扫描依赖摄像头与画面。"),
    ("biometric.unlock", "生物识别与本地密码锁", "security", UI + "Components/PasscodeView.java", r"BiometricPrompt", "生物识别必须保留系统可信 UI 和用户在场。"),
    ("payments.execute", "支付、Premium、Stars 与礼物购买", "payments", UI + "PaymentFormActivity.java", r"class PaymentFormActivity", "金融交易和支付确认不应由原型 MCP 自动执行。"),
    ("share.external_sheet", "分享到其他 Android 应用", "platform", UI + "ChatActivity.java", r"Intent\.createChooser", "系统分享面板需要用户选择目标应用。"),
    ("widgets.configure", "Android 桌面小组件配置", "platform", UI + "ChatsWidgetConfigActivity.java", r"class ChatsWidgetConfigActivity", "小组件布局和桌面放置是系统/视觉交互。"),
]


def find_evidence(root: Path, value: Anchor) -> dict[str, Any]:
    path = root / value.path
    if not path.is_file():
        raise RuntimeError(f"Evidence file not found: {value.path}")
    text = path.read_text(encoding="utf-8", errors="replace")
    match = re.search(value.pattern, text)
    if not match:
        raise RuntimeError(f"Evidence pattern not found: {value.path} / {value.pattern}")
    return {
        "path": value.path,
        "line": text.count("\n", 0, match.start()) + 1,
        "symbol": value.pattern,
        "layer": value.layer,
        "proves": value.proves,
    }


def tool_contract(capability: Capability) -> dict[str, Any]:
    properties = copy.deepcopy(capability.properties)
    if capability.confirmation and capability.confirmation not in properties:
        properties[capability.confirmation] = copy.deepcopy(CONFIRM)
    return {
        "name": capability.tool_name,
        "title": capability.title,
        "description": capability.intent + " 返回结构化成功或可操作错误，不返回认证秘密。",
        "tier": capability.tier,
        "input_schema": {
            "type": "object",
            "properties": properties,
            "required": capability.required,
            "additionalProperties": False,
        },
        "read_only": capability.read_only,
        "destructive": capability.destructive,
        "idempotent": capability.idempotent,
        "open_world": capability.open_world,
        "confirmation_argument": capability.confirmation,
        "readback_strategy": capability.readback,
        "verification_exception": None if capability.readback or capability.read_only else "No durable state; verify returned canonical object.",
        "implementation_paths": [IMPLEMENTATION, SERVER],
        "preferred_alternatives": [],
    }


def git_revision(root: Path) -> str | None:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def build_inventory(
    root: Path,
    runtime_evidence: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    runtime_evidence = runtime_evidence or {}
    gui_features: list[dict[str, Any]] = []
    capabilities: list[dict[str, Any]] = []
    for item in CAPABILITIES:
        ui_evidence = find_evidence(root, item.ui)
        callable_evidence = find_evidence(root, item.callable)
        evidence_status = str(
            (runtime_evidence.get(item.tool_name) or {}).get("status", "")
        )
        capability_status = (
            "verified" if evidence_status == "runtime-verified" else "implemented"
        )
        if capability_status == "verified":
            coverage_gap = (
                "MCP 已安装并通过运行时调用；尚未记录同一状态经 GUI 与 MCP 双向操作的等价性证据。"
            )
        elif evidence_status:
            coverage_gap = (
                "MCP 已构建、安装并通过注册/schema 检查；当前运行时证据为 "
                f"{evidence_status}，成功路径仍需登录账号或专用 fixture。"
            )
        else:
            coverage_gap = (
                "MCP 已实现并具有源码锚点；尚未提供安装后的运行时证据。"
            )
        capabilities.append(
            {
                "id": item.id,
                "title": item.title,
                "domain": item.domain,
                "user_intent": item.intent,
                "kind": item.kind,
                "atomicity": item.atomicity,
                "source_evidence": [ui_evidence, callable_evidence],
                "preconditions": item.preconditions,
                "inputs": item.required,
                "outputs": item.outputs,
                "side_effects": item.side_effects,
                "risk": item.risk,
                "dependencies": ["active Telegram account"] if item.preconditions else [],
                "status": capability_status,
                "attempts": 1,
                "tool": tool_contract(item),
                "degradation": None,
            }
        )
        gui_features.append(
            {
                "id": "gui." + item.id,
                "title": item.title,
                "domain": item.domain,
                "evidence": [ui_evidence],
                "capability_ids": [item.id],
                "coverage_status": "partial",
                "gaps": [coverage_gap],
            }
        )

    for cap_id, title, domain, path, pattern, reason in SYSTEM_BOUNDARIES:
        evidence = find_evidence(root, anchor(path, pattern, "platform", reason))
        capabilities.append(
            {
                "id": cap_id,
                "title": title,
                "domain": domain,
                "user_intent": title,
                "kind": "platform",
                "atomicity": "non-automatable",
                "source_evidence": [evidence],
                "preconditions": ["用户在场并完成 Android 系统交互"],
                "inputs": [],
                "outputs": [],
                "side_effects": [],
                "risk": "system",
                "dependencies": ["Android system UI", "human presence"],
                "status": "degraded",
                "attempts": 0,
                "tool": None,
                "degradation": {
                    "category": "system-boundary",
                    "reason": reason,
                    "evidence": [evidence],
                    "alternative_tool": None,
                    "alternative_workflow": "MCP 准备上下文后暂停，由用户在 GUI/系统界面完成。",
                    "human_action": "在模拟器中完成权限、选择或确认。",
                    "retry_condition": "只有在 Android 提供可安全自动化且不绕过可信 UI 的官方 API 后重评。",
                },
            }
        )
        gui_features.append(
            {
                "id": "gui." + cap_id,
                "title": title,
                "domain": domain,
                "evidence": [evidence],
                "capability_ids": [cap_id],
                "coverage_status": "system-boundary",
                "gaps": [reason],
            }
        )

    return {
        "$schema": "../../../../../../C:/Users/ThinkPad/.codex/skills/build-flutter-app-mcp/assets/capability-inventory.schema.json",
        "schema_version": "1.0",
        "run_id": RUN_ID,
        "app": {
            "name": "Telegram Android",
            "repo_root": str(root),
            "flutter_root": "not-applicable-native-android",
            "android_package": "org.telegram.messenger.beta",
            "build_variant": "afatDebug",
            "source_revision": git_revision(root),
        },
        "scope": {
            "included": [
                "Official native Android client GUI and controller-backed user intents",
                "Current-account MTProto operations with Agent-friendly semantic inputs",
                "Safe local settings and explicit high-impact confirmation gates",
            ],
            "excluded": [
                "Raw arbitrary MTProto execution",
                "Server administration not reachable from the Android client",
                "Payment success paths and system-trusted authentication UI",
            ],
            "max_attempts": 3,
        },
        "gui_features": gui_features,
        "capabilities": capabilities,
    }


def canonical(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def runtime_catalog(payload: dict[str, Any]) -> dict[str, Any]:
    """Return the compact, source-generated catalog packaged in the debug APK."""
    return {
        "schema_version": payload["schema_version"],
        "run_id": payload["run_id"],
        "source_revision": payload["app"]["source_revision"],
        "tools": [
            capability["tool"]
            for capability in payload["capabilities"]
            if capability.get("tool") is not None
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("repo", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--catalog-output", type=Path)
    parser.add_argument(
        "--runtime-validation",
        type=Path,
        help="Acceptance report whose per-tool evidence advances implemented tools to verified.",
    )
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = args.repo.resolve()
    runtime_evidence: dict[str, dict[str, Any]] = {}
    if args.runtime_validation is not None:
        validation_path = (
            args.runtime_validation
            if args.runtime_validation.is_absolute()
            else root / args.runtime_validation
        )
        validation = json.loads(validation_path.read_text(encoding="utf-8"))
        raw_evidence = validation.get("tool_evidence") or {}
        if not isinstance(raw_evidence, dict):
            raise SystemExit(
                f"runtime validation tool_evidence must be an object: {validation_path}"
            )
        runtime_evidence = {
            str(name): value
            for name, value in raw_evidence.items()
            if isinstance(value, dict)
        }
    payload = build_inventory(root, runtime_evidence)
    data = canonical(payload)
    output = args.output if args.output.is_absolute() else root / args.output
    catalog_output = None
    catalog_data = None
    if args.catalog_output is not None:
        catalog_output = args.catalog_output if args.catalog_output.is_absolute() else root / args.catalog_output
        catalog_data = canonical(runtime_catalog(payload))
    if args.check:
        if not output.exists() or output.read_bytes() != data:
            raise SystemExit(f"Capability inventory drift: regenerate {output}")
        if catalog_output is not None and (not catalog_output.exists() or catalog_output.read_bytes() != catalog_data):
            raise SystemExit(f"Runtime catalog drift: regenerate {catalog_output}")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        if catalog_output is not None:
            catalog_output.parent.mkdir(parents=True, exist_ok=True)
            catalog_output.write_bytes(catalog_data)
    print(json.dumps({
        "capabilities": len(payload["capabilities"]),
        "planned_tools": len(CAPABILITIES),
        "system_boundaries": len(SYSTEM_BOUNDARIES),
        "implemented": sum(
            item.get("status") == "implemented" for item in payload["capabilities"]
        ),
        "verified": sum(
            item.get("status") == "verified" for item in payload["capabilities"]
        ),
        "sha256": hashlib.sha256(data).hexdigest(),
        "output": str(output),
        "catalog_sha256": hashlib.sha256(catalog_data).hexdigest() if catalog_data is not None else None,
        "catalog_output": str(catalog_output) if catalog_output is not None else None,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
