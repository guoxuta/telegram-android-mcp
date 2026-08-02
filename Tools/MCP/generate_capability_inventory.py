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


RUN_ID = "telegram-mcp-20260801"
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
TOPIC_ID = schema_property(
    "integer",
    "论坛超级群组中的目标主题 ID；省略时发送到普通会话或 General 主题。",
    minimum=1,
)
FILE_REF = schema_property(
    "string",
    "由 Telegram MCP 私有暂存区返回的文件引用。",
    pattern=r"^f_[0-9a-f]{64}$",
    minLength=66,
    maxLength=66,
)
UPLOAD_REF = schema_property(
    "string",
    "Opaque resumable-upload reference returned by telegram.file.upload_begin.",
    pattern=r"^u_[0-9a-f]{64}$",
    minLength=66,
    maxLength=66,
)
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

CHAT_PERMISSION_KEYS = [
    "view_messages", "send_messages", "send_media", "send_stickers",
    "send_gifs", "send_games", "send_inline", "embed_links", "send_polls",
    "change_info", "invite_users", "pin_messages", "manage_topics",
    "send_photos", "send_videos", "send_roundvideos", "send_audios",
    "send_voices", "send_docs", "send_plain", "send_reactions",
    "manage_linked_peers",
]
CHAT_ALLOWED_SCHEMA = schema_property(
    "object",
    "仅覆盖明确提供的成员允许项；未提供字段保持服务器现值。",
    minProperties=1,
    properties={key: schema_property("boolean", f"是否允许 {key}。") for key in CHAT_PERMISSION_KEYS},
    additionalProperties=False,
)
CHAT_ADMIN_RIGHT_KEYS = [
    "change_info", "post_messages", "edit_messages", "delete_messages",
    "ban_users", "invite_users", "pin_messages", "add_admins", "anonymous",
    "manage_call", "manage_topics", "post_stories", "edit_stories",
    "delete_stories", "manage_direct_messages", "manage_ranks",
    "manage_linked_peers",
]
CHAT_ADMIN_RIGHTS_SCHEMA = schema_property(
    "object",
    "管理员权限的完整显式布尔映射。",
    minProperties=1,
    properties={key: schema_property("boolean", f"是否授予 {key}。") for key in CHAT_ADMIN_RIGHT_KEYS},
    additionalProperties=False,
)
BUSINESS_RECIPIENTS_SCHEMA = schema_property(
    "object",
    "Business 自动消息目标；至少选择一个会话类别或明确用户。exclude_selected=true 时 users 表示排除项。",
    properties={
        "existing_chats": schema_property("boolean", "现有私聊。", default=False),
        "new_chats": schema_property("boolean", "新私聊。", default=False),
        "contacts": schema_property("boolean", "联系人。", default=False),
        "non_contacts": schema_property("boolean", "非联系人。", default=False),
        "exclude_selected": schema_property("boolean", "users 是排除项而非包含项。", default=False),
        "users": schema_property("array", "明确用户 peer。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
    },
    additionalProperties=False,
)
BUSINESS_BOT_RIGHT_KEYS = [
    "reply", "read_messages", "delete_sent_messages", "delete_received_messages",
    "edit_name", "edit_bio", "edit_profile_photo", "edit_username",
    "view_gifts", "sell_gifts", "change_gift_settings",
    "transfer_and_upgrade_gifts", "transfer_stars", "manage_stories",
]
BUSINESS_BOT_RIGHTS_SCHEMA = schema_property(
    "object",
    "Business Bot 权限的完整替换映射。",
    properties={key: schema_property("boolean", f"是否授予 {key}。") for key in BUSINESS_BOT_RIGHT_KEYS},
    required=BUSINESS_BOT_RIGHT_KEYS,
    additionalProperties=False,
)
BUSINESS_BOT_RECIPIENTS_SCHEMA = schema_property(
    "object",
    "Business Bot 会话范围；users 为包含项，exclude_users 为明确排除项。",
    properties={
        "existing_chats": schema_property("boolean", "现有私聊。", default=False),
        "new_chats": schema_property("boolean", "新私聊。", default=False),
        "contacts": schema_property("boolean", "联系人。", default=False),
        "non_contacts": schema_property("boolean", "非联系人。", default=False),
        "exclude_selected": schema_property("boolean", "users 作为排除集合。", default=False),
        "users": schema_property("array", "明确包含/排除用户。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
        "exclude_users": schema_property("array", "在包含模式下的明确排除用户。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
    },
    additionalProperties=False,
)

OUTPUT_ENVELOPE: dict[str, Any] = {
    "description": "统一 Telegram MCP 结果；ok=true 时包含 data，ok=false 时包含结构化 error。",
    "oneOf": [
        {
            "type": "object",
            "properties": {
                "ok": {"type": "boolean", "const": True},
                "data": {"type": "object"},
            },
            "required": ["ok", "data"],
            "additionalProperties": False,
        },
        {
            "type": "object",
            "properties": {
                "ok": {"type": "boolean", "const": False},
                "error": {
                    "type": "object",
                    "properties": {
                        "code": {"type": "string", "minLength": 1},
                        "message": {"type": "string", "minLength": 1},
                        "retryable": {"type": "boolean"},
                        "details": {},
                    },
                    "required": ["code", "message", "retryable"],
                    "additionalProperties": False,
                },
            },
            "required": ["ok", "error"],
            "additionalProperties": False,
        },
    ],
}

CORE_OUTPUT_DATA_SCHEMAS: dict[str, dict[str, Any]] = {
    "message.send_text": {
        "properties": {
            "message_ids": {"type": "array", "minItems": 1, "items": {"type": "integer"}},
            "operation_id": {"type": "string", "minLength": 1},
            "committed": {"type": "boolean", "const": True},
            "readback": {"type": "object"},
        },
        "required": ["message_ids", "operation_id", "committed", "readback"],
    },
    "file.get": {
        "properties": {
            "file_ref": {"type": "string", "pattern": r"^f_[0-9a-f]{64}$"},
            "size": {"type": "integer", "minimum": 1},
            "sha256": {"type": "string", "pattern": r"^[0-9a-f]{64}$"},
        },
        "required": ["file_ref", "size", "sha256"],
    },
    "file.put_base64": {
        "properties": {
            "file_ref": {"type": "string", "pattern": r"^f_[0-9a-f]{64}$"},
            "size": {"type": "integer", "minimum": 1},
            "sha256": {"type": "string", "pattern": r"^[0-9a-f]{64}$"},
            "operation_id": {"type": "string", "minLength": 1},
            "committed": {"type": "boolean", "const": True},
        },
        "required": ["file_ref", "size", "sha256", "operation_id", "committed"],
    },
    "file.upload_list": {
        "properties": {
            "uploads": {"type": "array", "items": {"type": "object"}},
            "returned_count": {"type": "integer", "minimum": 0},
            "total_count": {"type": "integer", "minimum": 0},
            "next_offset": {"type": "integer", "minimum": 0},
        },
        "required": ["uploads", "returned_count", "total_count", "next_offset"],
    },
    "file.upload_begin": {
        "properties": {
            "upload_ref": {"type": "string", "pattern": r"^u_[0-9a-f]{64}$"},
            "state": {"type": "string"},
            "total_size": {"type": "integer", "minimum": 1},
            "final_present": {"type": "boolean"},
        },
        "required": ["upload_ref", "state", "total_size", "final_present"],
    },
    "file.upload_status": {
        "properties": {
            "upload_ref": {"type": "string", "pattern": r"^u_[0-9a-f]{64}$"},
            "state": {"type": "string"},
            "received_bytes": {"type": "integer", "minimum": 0},
            "remaining_bytes": {"type": "integer", "minimum": 0},
            "final_present": {"type": "boolean"},
        },
        "required": ["upload_ref", "state", "received_bytes", "remaining_bytes", "final_present"],
    },
    "file.upload_append": {
        "properties": {
            "upload_ref": {"type": "string", "pattern": r"^u_[0-9a-f]{64}$"},
            "received_bytes": {"type": "integer", "minimum": 1},
            "chunk_offset": {"type": "integer", "minimum": 0},
            "chunk_size": {"type": "integer", "minimum": 1},
        },
        "required": ["upload_ref", "received_bytes", "chunk_offset", "chunk_size"],
    },
    "file.upload_commit": {
        "properties": {
            "upload_ref": {"type": "string", "pattern": r"^u_[0-9a-f]{64}$"},
            "file_ref": {"type": "string", "pattern": r"^f_[0-9a-f]{64}$"},
            "size": {"type": "integer", "minimum": 1},
            "sha256": {"type": "string", "pattern": r"^[0-9a-f]{64}$"},
        },
        "required": ["upload_ref", "file_ref", "size", "sha256"],
    },
}


def output_schema_for(tool_name: str) -> dict[str, Any]:
    schema = copy.deepcopy(OUTPUT_ENVELOPE)
    normalized_name = tool_name.removeprefix("telegram.")
    data_contract = CORE_OUTPUT_DATA_SCHEMAS.get(normalized_name)
    if data_contract:
        schema["oneOf"][0]["properties"]["data"] = {
            "type": "object",
            **copy.deepcopy(data_contract),
        }
    return schema


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
        "call.history", "读取通话历史", "calls", "从 Telegram 服务器分页读取语音/视频通话记录、方向、时长和结束原因。",
        "read", "read",
        anchor(UI + "CallLogActivity.java", r"TL_inputMessagesFilterPhoneCalls", "ui", "GUI 通话记录页使用电话事件过滤器。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_search extends", "backend", "与官方通话记录页相同的服务器搜索按消息 ID 分页返回通话事件。"),
        properties=props(missed_only=schema_property("boolean", "仅返回未接来电。", default=False), min_date=schema_property("integer", "最早 Unix 秒。", minimum=0), max_date=schema_property("integer", "最晚 Unix 秒。", minimum=0), offset_id=schema_property("integer", "上一页 next_offset_id。", minimum=0), limit=copy.deepcopy(LIMIT)),
        required=[], outputs=["call ID", "peer", "方向", "视频标志", "时长", "结束原因", "下一页游标"],
    ),
    Capability(
        "call.status", "读取当前通话状态", "calls", "读取本应用进程当前 VoIP 服务状态、通话 peer、麦克风静音和视频可用性。",
        "read", "read",
        anchor(UI + "VoIPFragment.java", r"VoIPService\.getSharedInstance", "ui", "GUI 通话页读取当前 VoIP 服务。"),
        anchor(MSG + "voip/VoIPService.java", r"public int getCallState", "domain", "VoIP 服务提供稳定状态接口。"),
        properties=props(), required=[], outputs=["是否活跃", "状态", "peer", "麦克风静音", "视频可用性"],
    ),
    Capability(
        "call.mute_set", "设置当前通话麦克风静音", "calls", "在已有通话中设置麦克风静音状态；不申请权限、不启动采集或发起通话。",
        "write", "system",
        anchor(UI + "VoIPFragment.java", r"setMicMute\(micMute", "ui", "GUI 通话页通过 VoIP 服务切换静音。"),
        anchor(MSG + "voip/VoIPService.java", r"public void setMicMute", "domain", "VoIP 服务应用麦克风静音状态。"),
        properties=props(muted=schema_property("boolean", "目标静音状态。")), required=["muted"], read_only=False,
        side_effects=["改变当前实时通话麦克风发送状态"], readback="telegram.call.status 返回精确 microphone_muted",
    ),
    Capability(
        "call.hang_up", "挂断当前通话", "calls", "挂断本应用进程中的当前 Telegram 通话；不存在通话时幂等成功。",
        "write", "destructive",
        anchor(UI + "VoIPFragment.java", r"getSharedInstance\(\)\.hangUp", "ui", "GUI 通话页调用 VoIP 服务挂断。"),
        anchor(MSG + "voip/VoIPService.java", r"public void hangUp\(Runnable", "domain", "VoIP 服务提供完成回调的挂断操作。"),
        properties=props(_confirm=copy.deepcopy(CONFIRM)), required=["_confirm"], read_only=False, destructive=True, confirmation="_confirm",
        side_effects=["立即结束实时通话"], readback="telegram.call.status 返回 active=false",
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
        "payments.stars_status", "读取 Stars/TON 余额状态", "payments", "从服务器读取当前账号的 Stars 或 TON 余额、首屏流水和订阅摘要；不创建订单或执行购买。",
        "read", "read",
        anchor(UI + "Stars/StarsController.java", r"TL_payments_getStarsStatus req", "ui", "GUI Stars 控制器以服务器状态刷新余额。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stars.java", r"class TL_payments_getStarsStatus", "backend", "payments.getStarsStatus 返回规范余额、流水和订阅状态。"),
        properties=props(ton=schema_property("boolean", "读取 TON 而非 Stars。", default=False)), required=[],
        outputs=["精确余额", "首屏交易", "订阅摘要", "分页游标"],
    ),
    Capability(
        "payments.stars_transactions", "分页读取 Stars/TON 流水", "payments", "按方向、顺序和订阅筛选从服务器分页读取 Stars 或 TON 交易；收据 URL 和 bot payload 保持脱敏。",
        "read", "read",
        anchor(UI + "Stars/StarsController.java", r"TL_payments_getStarsTransactions", "ui", "GUI Stars 控制器分页加载交易。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stars.java", r"class TL_payments_getStarsTransactions", "backend", "payments.getStarsTransactions 提供服务器交易分页。"),
        properties=props(direction=schema_property("string", "交易方向。", enum=["all", "incoming", "outgoing"], default="all"), ascending=schema_property("boolean", "按时间升序。", default=False), ton=schema_property("boolean", "读取 TON 而非 Stars。", default=False), subscription_id=schema_property("string", "可选订阅 ID 过滤。", maxLength=256), offset=schema_property("string", "上一页 next_offset；首页为空。", maxLength=512), limit=copy.deepcopy(LIMIT)),
        required=[], outputs=["交易 ID", "精确金额", "状态和类型", "交易 peer", "下一页游标"],
    ),
    Capability(
        "payments.stars_subscriptions", "分页读取 Stars 订阅", "payments", "从服务器分页读取当前账号的 Stars 订阅、续费价格、到期时间和余额不足状态。",
        "read", "read",
        anchor(UI + "Stars/StarsController.java", r"TL_getStarsSubscriptions req", "ui", "GUI Stars 控制器分页加载订阅。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stars.java", r"class TL_getStarsSubscriptions", "backend", "getStarsSubscriptions 返回服务器订阅状态。"),
        properties=props(offset=schema_property("string", "上一页 subscriptions_next_offset；首页为空。", maxLength=512), missing_balance_only=schema_property("boolean", "仅返回余额不足的订阅。", default=False)),
        required=[], outputs=["订阅 ID", "peer", "续费价格", "到期/取消/余额状态", "下一页游标"],
    ),
    Capability(
        "security.two_step_status", "读取两步验证安全状态", "security", "从服务器读取两步验证、恢复邮箱、安全值和待重置状态；SRP 参数、提示和邮箱掩码保持脱敏。",
        "read", "read",
        anchor(UI + "TwoStepVerificationActivity.java", r"TL_account\.getPassword req", "ui", "GUI 两步验证页读取账号安全状态。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class getPassword extends", "backend", "account.getPassword 返回安全状态和敏感 SRP 材料。"),
        properties=props(), required=[], outputs=["是否启用", "恢复配置", "待重置日期", "敏感字段脱敏证明"],
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
        properties=props(
            limit=copy.deepcopy(LIMIT),
            folder_id=schema_property("integer", "文件夹 ID；0 为主列表，1 为归档。", minimum=0),
            offset_id=schema_property("integer", "上一页最后一条消息 ID；首页为 0。", minimum=0),
            offset_date=schema_property("integer", "上一页最后一条消息的 Unix 秒时间；首页为 0。", minimum=0),
            offset_peer=copy.deepcopy(PEER),
        ),
        required=[], outputs=["对话 peer", "标题", "未读数", "置顶/归档/静音状态", "末条消息摘要"],
    ),
    Capability(
        "dialog.get", "读取精确对话状态", "dialogs", "从服务器读取单个对话的文件夹、置顶、未读和通知状态。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"public long getDialogId\(\)", "ui", "聊天页围绕单个 peer 展示精确对话状态。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getPeerDialogs", "backend", "MTProto 提供单 peer 对话状态读取。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"],
        outputs=["对话 peer", "文件夹", "置顶", "未读状态", "服务端同步状态"],
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
        "notification.peer_get", "读取会话通知设置", "notifications", "从服务器读取会话或论坛话题的通知例外和静音状态。",
        "read", "read",
        anchor(UI + "ProfileNotificationsActivity.java", r"class ProfileNotificationsActivity", "ui", "GUI 提供会话级通知例外设置。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class getNotifySettings", "backend", "MTProto 提供精确通知设置读取。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "可选论坛话题 ID；0 表示整个会话。", minimum=0)),
        required=["peer"], outputs=["静音截止时间", "声音", "预览", "静默", "stories 通知状态"],
    ),
    Capability(
        "notification.peer_set", "精细设置会话通知", "notifications", "按字段更新会话或论坛话题的静音、预览、声音和 Story 通知例外；未提供字段保持服务器现值。",
        "write", "external",
        anchor(UI + "ProfileNotificationsActivity.java", r"class ProfileNotificationsActivity", "ui", "GUI 提供会话级通知例外编辑。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateNotifySettings", "backend", "MTProto 支持按 flags 部分更新通知设置。"),
        properties=props(
            peer=copy.deepcopy(PEER),
            topic_id=schema_property("integer", "可选论坛话题 ID；0 表示整个会话。", minimum=0),
            mute_until=schema_property("integer", "0 取消静音；Unix 秒时间戳或 2147483647 表示静音截止时间。", minimum=0, maximum=2147483647),
            silent=schema_property("boolean", "发送通知时是否静默。"),
            show_previews=schema_property("boolean", "是否显示消息预览。"),
            stories_muted=schema_property("boolean", "是否静音该 peer 的 Story 通知。"),
            stories_hide_sender=schema_property("boolean", "Story 通知是否隐藏发送者。"),
            sound=schema_property("string", "default、none 或 ringtone:<document-id>。", pattern=r"^(default|none|ringtone:[1-9][0-9]*)$", maxLength=32),
        ),
        required=["peer"], read_only=False, open_world=True,
        side_effects=["修改 Telegram 云端会话通知例外"],
        readback="telegram.notification.peer_get 返回全部指定字段的精确服务器值",
    ),
    Capability(
        "notification.global_get", "读取全局通知设置", "notifications", "从服务器读取私聊、群组、频道或 Stories 的全局静音、预览、声音和发送者显示策略。",
        "read", "read",
        anchor(UI + "NotificationsSettingsActivity.java", r"class NotificationsSettingsActivity", "ui", "GUI 通知页展示全局分类。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class getNotifySettings", "backend", "account.getNotifySettings 接受全局 InputNotifyPeer 分类。"),
        properties=props(domain=schema_property("string", "通知分类。", enum=["private", "groups", "channels", "stories"])), required=["domain"],
        outputs=["静音截止", "预览", "声音", "Story 通知策略"],
    ),
    Capability(
        "notification.global_set", "设置全局通知", "notifications", "按字段修改私聊、群组、频道或 Stories 全局通知，服务器独立回读后同步本机 GUI 偏好。",
        "write", "external",
        anchor(MSG + "NotificationsController.java", r"public void updateServerNotificationsSettings\(int type\)", "ui", "GUI 通知设置按分类写入 account.updateNotifySettings。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateNotifySettings", "backend", "account.updateNotifySettings 按 flags 部分更新。"),
        properties=props(domain=schema_property("string", "通知分类。", enum=["private", "groups", "channels", "stories"]), mute_until=schema_property("integer", "非 Stories 分类的静音截止。", minimum=0, maximum=2147483647), show_previews=schema_property("boolean", "非 Stories 分类是否显示预览。"), sound=schema_property("string", "default、none 或 ringtone:<document-id>。", pattern=r"^(default|none|ringtone:[1-9][0-9]*)$", maxLength=32), stories_muted=schema_property("boolean", "Stories 分类是否静音。"), stories_hide_sender=schema_property("boolean", "Stories 通知是否隐藏发送者。"), stories_sound=schema_property("string", "Stories 声音。", pattern=r"^(default|none|ringtone:[1-9][0-9]*)$", maxLength=32)),
        required=["domain"], read_only=False, open_world=True,
        side_effects=["修改账号云端和本机全局通知"], readback="telegram.notification.global_get 返回所有指定字段的精确值",
    ),
    Capability(
        "notification.reactions_get", "读取反应通知设置", "notifications", "读取消息反应、Story 反应和投票通知来源、预览与声音。",
        "read", "read",
        anchor(MSG + "MessagesController.java", r"reloadReactionsNotifySettings", "ui", "消息控制器加载反应通知并同步 GUI 偏好。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class getReactionsNotifySettings", "backend", "服务器返回完整反应通知设置。"),
        properties=props(), required=[], outputs=["消息/Story/投票通知来源", "预览", "声音"],
    ),
    Capability(
        "notification.reactions_set", "设置反应通知", "notifications", "在服务器当前完整对象上按字段修改消息反应、Story 反应、投票通知、预览或声音，避免覆盖未提供字段。",
        "write", "external",
        anchor(MSG + "NotificationsController.java", r"setReactionsNotifySettings req", "ui", "GUI 通知控制器提交完整反应设置。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class setReactionsNotifySettings", "backend", "服务器接受并返回规范反应通知设置。"),
        properties=props(messages=schema_property("string", "消息反应通知来源。", enum=["off", "contacts", "all"]), stories=schema_property("string", "Story 反应通知来源。", enum=["off", "contacts", "all"]), poll_votes=schema_property("string", "投票通知来源。", enum=["off", "contacts", "all"]), show_previews=schema_property("boolean", "显示预览。"), sound=schema_property("string", "default、none 或 ringtone:<document-id>。", pattern=r"^(default|none|ringtone:[1-9][0-9]*)$", maxLength=32)),
        required=[], read_only=False, open_world=True,
        side_effects=["修改账号反应通知并同步本机偏好"], readback="telegram.notification.reactions_get 返回所有指定字段的精确值",
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
        "file.list", "列出 MCP 暂存文件", "files", "列出 APP 私有、大小受限的 MCP 文件暂存区，不暴露任意 Android 路径。",
        "read", "read",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 文档附件入口展示可发送文件。"),
        anchor(IMPLEMENTATION, r"private (?:synchronized )?JsonObject fileList", "adapter", "MCP 仅枚举私有暂存元数据。"),
        properties={"limit": copy.deepcopy(LIMIT)}, required=[], preconditions=[], outputs=["文件引用", "名称", "MIME", "大小", "SHA-256"],
    ),
    Capability(
        "file.get", "读取 MCP 暂存文件元数据", "files", "按稳定 file_ref 读取私有暂存文件的完整性元数据。",
        "read", "read",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 附件入口读取文件元数据。"),
        anchor(IMPLEMENTATION, r"private (?:synchronized )?JsonObject fileGet", "adapter", "MCP 严格解析 file_ref 并验证文件长度。"),
        properties={"file_ref": copy.deepcopy(FILE_REF)}, required=["file_ref"], preconditions=[], outputs=["名称", "MIME", "大小", "SHA-256", "来源"],
    ),
    Capability(
        "file.put_base64", "把内容放入私有暂存区", "files", "把明确提供的 Base64 内容原子写入 APP 私有暂存区。",
        "write", "write",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 发送文件前建立受控文件对象。"),
        anchor(IMPLEMENTATION, r"private (?:synchronized )?JsonObject filePutBase64", "adapter", "MCP 校验大小、哈希并原子落盘。"),
        properties={
            "name": schema_property("string", "不含路径分隔符的文件名。", minLength=1, maxLength=255),
            "mime_type": schema_property("string", "明确 MIME 类型。", minLength=1, maxLength=128),
            "base64": schema_property("string", "最多 512 KiB 解码内容的 Base64；更大文件使用 upload_begin/append/commit。", minLength=1, maxLength=699068),
        }, required=["name", "mime_type", "base64"], read_only=False, preconditions=[],
        side_effects=["在 APP 私有目录创建受限小文件"], readback="telegram.file.get 返回精确大小和 SHA-256",
    ),
    Capability(
        "file.upload_list", "列出可续传上传会话", "files", "列出活动与终态上传会话，便于恢复遗失的 upload_ref。",
        "read", "read",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 展示附件准备状态。"),
        anchor(IMPLEMENTATION, r"private synchronized JsonObject fileUploadList", "adapter", "MCP 枚举 APP 私有上传会话。"),
        properties={
            "limit": copy.deepcopy(LIMIT),
            "offset": schema_property("integer", "分页偏移。", minimum=0, maximum=1000000, default=0),
            "state": schema_property("string", "按会话状态过滤。", enum=["any", "active", "complete", "stale_complete", "cancelled"], default="any"),
        }, required=[], preconditions=[],
        outputs=["upload_ref", "状态", "已接收字节", "最终 file_ref", "分页总数与 next_offset"],
    ),
    Capability(
        "file.upload_begin", "开始可续传文件上传", "files", "创建或恢复 APP 私有分块上传会话。",
        "write", "write",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 支持选择大附件。"),
        anchor(IMPLEMENTATION, r"private synchronized JsonObject fileUploadBegin", "adapter", "MCP 持久化上传会话。"),
        properties={
            "name": schema_property("string", "安全文件名。", minLength=1, maxLength=255),
            "mime_type": schema_property("string", "MIME 类型。", minLength=1, maxLength=128),
            "total_size": schema_property("integer", "完整文件字节数。", minimum=1, maximum=4294967296),
            "sha256": schema_property("string", "完整文件 SHA-256。", pattern=r"^[0-9a-fA-F]{64}$", minLength=64, maxLength=64),
            "reopen_cancelled": schema_property("boolean", "仅显式为 true 时重开相同身份的已取消会话。", default=False),
        }, required=["name", "mime_type", "total_size", "sha256"], read_only=False,
        idempotent=True, preconditions=[], side_effects=["创建或恢复私有上传会话"],
        readback="telegram.file.upload_status 返回精确进度",
    ),
    Capability(
        "file.upload_status", "读取可续传上传状态", "files", "读取上传进度和最终文件引用。",
        "read", "read",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 展示附件准备状态。"),
        anchor(IMPLEMENTATION, r"private synchronized JsonObject fileUploadStatus", "adapter", "MCP 回读 part 文件长度。"),
        properties={"upload_ref": copy.deepcopy(UPLOAD_REF)}, required=["upload_ref"],
        preconditions=[], outputs=["已接收字节", "剩余字节", "是否完成"],
    ),
    Capability(
        "file.upload_append", "追加可续传文件分块", "files", "按精确 offset 追加不超过 512 KiB 的已验证分块。",
        "write", "write",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 支持准备大附件。"),
        anchor(IMPLEMENTATION, r"private synchronized JsonObject fileUploadAppend", "adapter", "MCP fsync 并回读分块摘要。"),
        properties={
            "upload_ref": copy.deepcopy(UPLOAD_REF),
            "offset": schema_property("integer", "精确字节偏移。", minimum=0, maximum=4294967296),
            "base64": schema_property("string", "最大 512 KiB 解码内容。", minLength=1, maxLength=699068),
            "chunk_sha256": schema_property("string", "分块 SHA-256。", pattern=r"^[0-9a-fA-F]{64}$", minLength=64, maxLength=64),
        }, required=["upload_ref", "offset", "base64", "chunk_sha256"],
        read_only=False, idempotent=True, preconditions=[],
        side_effects=["追加私有上传 part"], readback="telegram.file.upload_status 返回落盘长度",
    ),
    Capability(
        "file.upload_commit", "提交可续传文件上传", "files", "验证完整大小和 SHA-256 后原子生成稳定 file_ref。",
        "write", "write",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 提交准备完成的附件。"),
        anchor(IMPLEMENTATION, r"private synchronized JsonObject fileUploadCommit", "adapter", "MCP 流式验证并原子提升 part。"),
        properties={"upload_ref": copy.deepcopy(UPLOAD_REF)}, required=["upload_ref"],
        read_only=False, idempotent=True, preconditions=[],
        side_effects=["提交完整私有暂存文件"], readback="telegram.file.get 返回精确大小和 SHA-256",
    ),
    Capability(
        "file.upload_cancel", "取消可续传文件上传", "files", "删除未提交 part 与会话，保留已提交 file_ref。",
        "write", "destructive",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 可取消附件准备。"),
        anchor(IMPLEMENTATION, r"private synchronized JsonObject fileUploadCancel", "adapter", "MCP 限定删除精确上传引用。"),
        properties={
            "upload_ref": copy.deepcopy(UPLOAD_REF),
            "purge_terminal": schema_property("boolean", "显式删除 complete/cancelled tombstone；会失去后续重放能力。", default=False),
            "_confirm": copy.deepcopy(CONFIRM),
        },
        required=["upload_ref", "_confirm"], read_only=False, destructive=True,
        idempotent=True, confirmation="_confirm", preconditions=[],
        side_effects=["删除私有上传临时状态"], readback="未 purge 时 upload_status 返回 state=cancelled；purge_terminal=true 时返回 STALE_REFERENCE",
    ),
    Capability(
        "file.read_base64", "分块读取暂存文件", "files", "按最多 1 MiB 的窗口读取私有暂存文件，不暴露文件系统路径。",
        "read", "read",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 可预览已选附件。"),
        anchor(IMPLEMENTATION, r"private (?:synchronized )?JsonObject fileReadBase64", "adapter", "MCP 仅按 file_ref 分块返回内容。"),
        properties={"file_ref": copy.deepcopy(FILE_REF), "offset": schema_property("integer", "字节偏移。", minimum=0), "length": schema_property("integer", "读取字节数。", minimum=1, maximum=1048576)},
        required=["file_ref"], preconditions=[], outputs=["Base64 分块", "偏移", "长度", "EOF"],
    ),
    Capability(
        "file.delete", "删除 MCP 暂存文件", "files", "删除 APP 私有 MCP 暂存文件及其元数据。",
        "write", "destructive",
        anchor(UI + "Components/ChatAttachAlertDocumentLayout.java", r"class ChatAttachAlertDocumentLayout", "ui", "GUI 允许移除已选择的附件。"),
        anchor(IMPLEMENTATION, r"private (?:synchronized )?JsonObject fileDelete", "adapter", "MCP 删除严格限定到私有暂存引用。"),
        properties={"file_ref": copy.deepcopy(FILE_REF), "_confirm": copy.deepcopy(CONFIRM)},
        required=["file_ref", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", preconditions=[],
        side_effects=["删除 APP 私有暂存副本"], readback="telegram.file.get 返回 STALE_REFERENCE",
    ),
    Capability(
        "file.download_message", "下载消息附件到暂存区", "files", "下载指定消息的文档、音视频或照片并复制到受限私有暂存区。",
        "write", "write",
        anchor(UI + "ChatActivity.java", r"FileLoader\.getInstance", "ui", "聊天 GUI 通过 FileLoader 下载消息附件。"),
        anchor(MSG + "FileLoader.java", r"public void loadFile\(TLRPC\.Document", "domain", "FileLoader 提供带完成/失败事件的下载状态机。"),
        properties=props(peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID), scheduled=schema_property("boolean", "是否读取定时消息附件。", default=False)),
        required=["peer", "message_id"], read_only=False,
        side_effects=["下载 Telegram 附件并创建私有暂存副本"], readback="telegram.file.get 返回附件大小和 SHA-256",
    ),
    Capability(
        "qr.encode", "生成二维码到暂存区", "qr", "使用 APP 内置 ZXing 将文本离线编码为 PNG，并写入受限私有 MCP 暂存区。",
        "write", "write",
        anchor(UI + "Components/QRCodeBottomSheet.java", r"QRCodeWriter writer", "ui", "Telegram GUI 使用二维码编码器生成邀请二维码。"),
        anchor("TMessagesProj/src/main/java/com/google/zxing/qrcode/QRCodeWriter.java", r"class QRCodeWriter", "domain", "内置 ZXing 编码器将文本生成 QR 位矩阵。"),
        properties={"text": schema_property("string", "需要编码的文本。", minLength=1, maxLength=4096), "size": schema_property("integer", "正方形 PNG 边长像素。", minimum=128, maximum=2048, default=512)},
        required=["text"], read_only=False, preconditions=[],
        side_effects=["在 APP 私有 MCP 暂存区创建 PNG"], readback="telegram.qr.decode_file 返回原始文本",
    ),
    Capability(
        "qr.decode_file", "解码暂存图片中的二维码", "qr", "使用 APP 内置 ZXing 离线解码私有 MCP 暂存区中的图片，不请求摄像头权限。",
        "read", "read",
        anchor(UI + "CameraScanActivity.java", r"qrReader\.decode\(new BinaryBitmap", "ui", "Telegram 扫码 GUI 使用同一 ZXing 解码路径。"),
        anchor("TMessagesProj/src/main/java/com/google/zxing/qrcode/QRCodeReader.java", r"class QRCodeReader", "domain", "内置解码器从像素矩阵返回二维码文本。"),
        properties={"file_ref": copy.deepcopy(FILE_REF)}, required=["file_ref"], preconditions=[],
        outputs=["二维码文本", "格式", "解码尺寸", "源暂存文件摘要"],
    ),
    Capability(
        "message.history", "读取消息历史", "messages", "按 peer 和偏移量分页读取可显示的消息历史。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"TL_messages_getHistory", "ui", "聊天 GUI 通过历史请求加载消息。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getHistory", "backend", "MTProto 提供消息历史读取。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=copy.deepcopy(TOPIC_ID), limit=copy.deepcopy(LIMIT), offset_id=schema_property("integer", "从该消息 ID 之前读取；0 表示最新。", minimum=0)),
        required=["peer"], outputs=["规范化消息列表", "发送者", "时间", "媒体类型", "分页游标"],
    ),
    Capability(
        "message.get", "按 ID 读取消息", "messages", "读取指定 peer 中一组明确消息 ID 的最新服务器对象。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"startLoadFromMessageId", "ui", "GUI 支持围绕指定消息 ID 定位与加载。"),
        anchor("TMessagesProj/src/main/java/org/telegram/messenger/FileRefController.java", r"TL_channels_getMessages req", "domain", "客户端按会话类型选择 channels.getMessages 或 messages.getMessages。"),
        properties=props(
            peer=copy.deepcopy(PEER),
            message_ids=schema_property("array", "消息 ID。", minItems=1, maxItems=100, uniqueItems=True, items={"type": "integer", "minimum": 1}),
            scheduled=schema_property("boolean", "是否在定时消息命名空间读取。", default=False),
        ),
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
        properties=props(query=schema_property("string", "搜索文本。", minLength=1), peer=copy.deepcopy(PEER), topic_id=copy.deepcopy(TOPIC_ID), limit=copy.deepcopy(LIMIT)),
        required=["query"], outputs=["匹配消息与 peer", "分页元数据"],
    ),
    Capability(
        "message.media_search", "筛选共享媒体", "messages", "在精确会话内按照片、视频、文件、音乐、语音、GIF、链接等类型分页搜索。",
        "read", "read",
        anchor(UI + "Components/SharedMediaLayout.java", r"TL_messages_search", "ui", "GUI 共享媒体页使用带 filter 的消息搜索。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_inputMessagesFilterPhotoVideoDocuments", "backend", "MTProto 提供强类型共享媒体过滤器。"),
        properties=props(
            peer=copy.deepcopy(PEER),
            filter=schema_property("string", "媒体过滤器。", enum=["all", "photos", "videos", "photo_video", "photo_video_documents", "documents", "music", "voice", "round_voice", "round_video", "gifs", "links", "pinned", "mentions"], default="photo_video_documents"),
            query=schema_property("string", "可选内容搜索文本。", maxLength=512),
            from_peer=copy.deepcopy(PEER),
            topic_id=copy.deepcopy(TOPIC_ID),
            offset_id=schema_property("integer", "上一页末条消息 ID。", minimum=0),
            limit=copy.deepcopy(LIMIT),
        ), required=["peer"], outputs=["匹配的规范消息", "媒体类型", "分页元数据"],
    ),
    Capability(
        "message.send_text", "发送文本消息", "messages", "向一个 peer 发送纯文本或 Telegram composer Markdown，可选回复、链接预览、静默和定时发送。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"SendMessageParams params = SendMessagesHelper\.SendMessageParams\.of\(message", "ui", "GUI 发送框构造语义发送参数。"),
        anchor(MSG + "SendMessagesHelper.java", r"public void sendMessage\(SendMessageParams", "domain", "发送助手维护本地队列、重试和更新一致性。"),
        properties=props(
            peer=copy.deepcopy(PEER), text=schema_property("string", "UTF-8 文本；不得为空。", minLength=1, maxLength=4096),
            parse_mode=schema_property("string", "plain 保留原文；telegram_markdown 使用 APP 编辑器同款实体解析。", enum=["plain", "telegram_markdown"], default="plain"),
            link_preview=schema_property("boolean", "是否允许链接预览。", default=True),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1),
            topic_id=copy.deepcopy(TOPIC_ID),
            silent=schema_property("boolean", "是否静默发送。", default=False),
            schedule_at=schema_property("string", "可选 ISO-8601 定时时间。", format="date-time"),
            idempotency_key=schema_property("string", "重试去重键；同一发送意图必须复用。", minLength=8, maxLength=128),
        ), required=["peer", "text", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向 Telegram 外部收件人发送消息"], readback="telegram.message.get 按返回 message_id 读回精确规范文本和 entities",
    ),
    Capability(
        "message.send_media", "发送暂存媒体或文件", "messages", "从私有 MCP 暂存区发送单个或最多十个照片、视频或文档。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"prepareSendingMedia\(", "ui", "GUI 通过发送状态机准备相册和文件。"),
        anchor(MSG + "SendMessagesHelper.java", r"public static void prepareSendingMedia", "domain", "发送助手处理媒体分析、上传、队列、重试和 Updates。"),
        properties=props(
            peer=copy.deepcopy(PEER),
            file_refs=schema_property("array", "按发送顺序排列的私有暂存文件引用。", minItems=1, maxItems=10, uniqueItems=True, items=copy.deepcopy(FILE_REF)),
            kind=schema_property("string", "auto 按 MIME 推断；也可强制 photo、video 或 document。", enum=["auto", "photo", "video", "document"], default="auto"),
            caption=schema_property("string", "第一项媒体的 caption。", maxLength=1024),
            caption_parse_mode=schema_property("string", "plain 或 APP composer 同款 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1),
            topic_id=copy.deepcopy(TOPIC_ID),
            silent=schema_property("boolean", "是否静默发送。", default=False),
            spoiler=schema_property("boolean", "是否为媒体添加 spoiler。", default=False),
            schedule_at=schema_property("string", "可选 ISO-8601 定时时间。", format="date-time"),
            idempotency_key=schema_property("string", "媒体发送意图去重键。", minLength=8, maxLength=128),
        ), required=["peer", "file_refs", "idempotency_key"], read_only=False, open_world=True,
        side_effects=["上传并向 Telegram 外部收件人发送媒体或文件"], readback="telegram.message.get 按服务器 message_id 返回非空媒体及精确 caption entities",
    ),
    Capability(
        "message.send_contact", "发送联系人卡片", "messages", "发送包含手机号和姓名的 Telegram 联系人卡片，可回复、静默或定时。",
        "write", "external",
        anchor(UI + "Components/ChatAttachAlert.java", r"TL_messageMediaContact", "ui", "GUI 联系人附件构造 contact media。"),
        anchor(MSG + "SendMessagesHelper.java", r"new TLRPC\.TL_messageMediaContact", "domain", "发送助手以标准消息队列发送联系人卡片。"),
        properties=props(
            peer=copy.deepcopy(PEER), phone_number=schema_property("string", "联系人手机号。", minLength=1, maxLength=64),
            first_name=schema_property("string", "联系人名字。", minLength=1, maxLength=64),
            last_name=schema_property("string", "联系人姓氏。", maxLength=64),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1),
            topic_id=copy.deepcopy(TOPIC_ID),
            silent=schema_property("boolean", "是否静默发送。", default=False),
            schedule_at=schema_property("string", "可选 ISO-8601 定时时间。", format="date-time"),
            idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128),
        ), required=["peer", "phone_number", "first_name", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向外部收件人发送个人联系信息"], readback="telegram.message.get 返回精确 contact media 字段",
    ),
    Capability(
        "message.send_location", "发送位置或地点", "messages", "发送显式经纬度；提供 title 时发送地点卡片，不访问设备定位权限。",
        "write", "external",
        anchor(UI + "LocationActivity.java", r"TL_messageMediaGeo location", "ui", "GUI 将已选坐标转换为位置消息。"),
        anchor(MSG + "SendMessagesHelper.java", r"TL_messageMediaVenue.*TL_messageMediaGeo", "domain", "发送助手处理位置媒体。"),
        properties=props(
            peer=copy.deepcopy(PEER), latitude=schema_property("number", "纬度。", minimum=-90, maximum=90),
            longitude=schema_property("number", "经度。", minimum=-180, maximum=180),
            title=schema_property("string", "可选地点标题。", maxLength=128),
            address=schema_property("string", "可选地点地址；使用时必须有 title。", maxLength=256),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1),
            topic_id=copy.deepcopy(TOPIC_ID),
            silent=schema_property("boolean", "是否静默发送。", default=False),
            schedule_at=schema_property("string", "可选 ISO-8601 定时时间。", format="date-time"),
            idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128),
        ), required=["peer", "latitude", "longitude", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向外部收件人发送显式位置"], readback="telegram.message.get 返回类型和规范化坐标完全匹配的位置媒体",
    ),
    Capability(
        "message.send_dice", "发送 Telegram 骰子", "messages", "发送账号服务器当前支持的骰子/飞镖/球类/老虎机 emoji 并返回服务器随机结果。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"SendMessageParams\.of\(message\.getDiceEmoji\(\)", "ui", "GUI 聊天页通过发送助手重投 Telegram 骰子。"),
        anchor(MSG + "SendMessagesHelper.java", r"TL_inputMediaDice", "domain", "发送助手把支持 emoji 转换为骰子媒体。"),
        properties=props(
            peer=copy.deepcopy(PEER), emoji=schema_property("string", "骰子 emoji；默认 🎲。", minLength=1, maxLength=8, default="🎲"),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1),
            topic_id=copy.deepcopy(TOPIC_ID),
            silent=schema_property("boolean", "是否静默发送。", default=False),
            idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128),
        ), required=["peer", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向外部收件人发送骰子消息"], readback="telegram.message.get 返回精确 emoji 和服务器 value",
    ),
    Capability(
        "message.send_poll", "发送投票或测验", "messages", "发送匿名或实名、单选或多选投票，也可发送带正确答案和解析的测验。",
        "write", "external",
        anchor(UI + "PollCreateActivity.java", r"TL_messageMediaPoll poll", "ui", "GUI 投票创建器构造相同 Poll 模型。"),
        anchor(MSG + "SendMessagesHelper.java", r"public static void prepareSendingPoll", "domain", "发送助手处理投票队列、测验答案和 Updates。"),
        properties=props(
            peer=copy.deepcopy(PEER), question=schema_property("string", "问题。", minLength=1, maxLength=255),
            answers=schema_property("array", "2..10 个唯一答案。", minItems=2, maxItems=10, uniqueItems=True, items={"type": "string", "minLength": 1, "maxLength": 100}),
            anonymous=schema_property("boolean", "是否隐藏投票者。", default=True),
            multiple_choice=schema_property("boolean", "是否允许多选。", default=False),
            quiz=schema_property("boolean", "是否为测验。", default=False),
            correct_answer=schema_property("integer", "测验正确答案的零基索引。", minimum=0, maximum=9),
            solution=schema_property("string", "测验解析。", maxLength=200),
            close_period=schema_property("integer", "0 不自动关闭，或 5..600 秒。", minimum=0, maximum=600, default=0),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1),
            topic_id=copy.deepcopy(TOPIC_ID),
            silent=schema_property("boolean", "是否静默发送。", default=False),
            schedule_at=schema_property("string", "可选 ISO-8601 定时时间。", format="date-time"),
            idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128),
        ), required=["peer", "question", "answers", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向外部会话发送投票或测验"], readback="telegram.message.get 返回精确问题、答案和匿名/多选/测验标志",
    ),
    Capability(
        "message.edit_text", "编辑文本消息", "messages", "编辑当前账号有权修改的文本消息。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"editMessage\(editingMessageObject", "ui", "GUI 编辑框调用发送助手编辑消息。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_editMessage", "backend", "MTProto 提供消息编辑并返回 Updates。"),
        properties=props(peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID), text=schema_property("string", "新文本。", minLength=1, maxLength=4096), parse_mode=schema_property("string", "plain 或 APP composer 同款 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"), link_preview=schema_property("boolean", "可选链接预览策略。"), scheduled=schema_property("boolean", "是否编辑定时消息。", default=False)),
        required=["peer", "message_id", "text"], read_only=False, open_world=True,
        side_effects=["修改已发送消息，收件人可见"], readback="telegram.message.get 读回 message_id 的精确规范文本和 entities",
    ),
    Capability(
        "message.edit_caption", "编辑媒体 caption", "messages", "编辑有权修改的照片、视频、音频或文件 caption，支持 APP composer Markdown 实体。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"editMessage\(editingMessageObject", "ui", "GUI 同一编辑器处理媒体 caption。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_editMessage", "backend", "messages.editMessage 以 message 与 entities 更新 caption。"),
        properties=props(
            peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID),
            caption=schema_property("string", "新 caption；空字符串清除。", maxLength=1024),
            parse_mode=schema_property("string", "plain 或 APP composer 同款 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"),
            scheduled=schema_property("boolean", "是否编辑定时消息。", default=False),
        ), required=["peer", "message_id", "caption"], read_only=False, open_world=True,
        side_effects=["修改已发送媒体 caption"], readback="telegram.message.get 读回精确 caption 和 entities",
    ),
    Capability(
        "message.poll_vote", "投票或修改投票", "messages", "对精确服务器投票按零基答案索引提交选项；空列表用于撤回允许修改的投票。",
        "write", "external",
        anchor(MSG + "SendMessagesHelper.java", r"public int sendVote", "domain", "GUI 投票助手把 PollAnswer option 提交为 sendVote。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_sendVote", "backend", "MTProto 提供强类型投票提交并返回 Updates。"),
        properties=props(
            peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID),
            answer_indices=schema_property("array", "按投票答案顺序的零基索引；可为空。", minItems=0, maxItems=10, uniqueItems=True, items={"type": "integer", "minimum": 0, "maximum": 9}),
        ), required=["peer", "message_id", "answer_indices"], read_only=False, open_world=True,
        side_effects=["向投票作者提交或修改选项"], readback="telegram.message.get 返回精确 chosen 答案集",
    ),
    Capability(
        "message.poll_close", "提前关闭投票", "messages", "对自己有权编辑的投票或测验执行不可逆提前关闭。",
        "write", "destructive",
        anchor(UI + "ChatActivity.java", r"OPTION_STOP_POLL_OR_QUIZ", "ui", "GUI 经确认后把 Poll.closed 编辑为 true。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_editMessage", "backend", "messages.editMessage 使用 inputMediaPoll 关闭投票。"),
        properties=props(peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "message_id", "_confirm"], read_only=False, destructive=True, idempotent=True, confirmation="_confirm", open_world=True,
        side_effects=["提前终止投票，不能重开"], readback="telegram.message.get 返回 poll.closed=true",
    ),
    Capability(
        "message.delete", "删除消息", "messages", "删除指定消息，可选仅自己或所有人。",
        "write", "destructive",
        anchor(UI + "Components/DeleteMessagesBottomSheet.java", r"performDelete\(\)", "ui", "GUI 通过确认面板执行删除。"),
        anchor(MSG + "MessagesController.java", r"public void deleteMessages\(ArrayList<Integer> messages", "domain", "控制器处理频道/普通会话删除与本地存储一致性。"),
        properties=props(peer=copy.deepcopy(PEER), message_ids=schema_property("array", "待删除消息 ID。", minItems=1, maxItems=100, items={"type": "integer", "minimum": 1}), scheduled=schema_property("boolean", "是否删除定时消息。", default=False), for_everyone=schema_property("boolean", "有权限时是否为所有人删除。", default=False), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "message_ids", "_confirm"], read_only=False, destructive=True, idempotent=True, open_world=True, confirmation="_confirm",
        side_effects=["永久删除消息；可能影响所有参与者"], readback="telegram.message.history 不再返回目标 message_id",
    ),
    Capability(
        "message.forward", "转发消息", "messages", "把现有消息转发到另一个 peer。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"forwardMessages", "ui", "聊天 GUI 支持批量转发。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_forwardMessages", "backend", "MTProto 提供带 random_id 的幂等转发。"),
        properties=props(from_peer=copy.deepcopy(PEER), to_peer=copy.deepcopy(PEER), message_ids=schema_property("array", "源消息 ID。", minItems=1, maxItems=100, items={"type": "integer", "minimum": 1}), topic_id=copy.deepcopy(TOPIC_ID), silent=schema_property("boolean", "是否静默转发。", default=False), idempotency_key=schema_property("string", "转发批次去重键。", minLength=8, maxLength=128)),
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
        "bot.button_list", "读取消息交互按钮", "bots", "从精确服务器消息读取 inline/reply 键盘的行列、类型、文本和人机接力要求，不暴露 callback 私有字节。",
        "read", "read",
        anchor(UI + "Cells/ChatMessageCell.java", r"KeyboardButton", "ui", "GUI 消息单元格渲染 Bot 按钮。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"abstract class KeyboardButton", "backend", "MTProto 消息携带结构化键盘按钮。"),
        properties=props(peer=copy.deepcopy(PEER), message_id=copy.deepcopy(MESSAGE_ID)),
        required=["peer", "message_id"], outputs=["按钮行列", "类型", "文本", "URL/查询摘要", "是否需人机接力"],
    ),
    Capability(
        "bot.button_press", "执行消息交互按钮", "bots", "按精确消息与行列执行 callback/game/普通回复按钮或返回可信 UI 接力要求；具有本地去重。",
        "write", "external",
        anchor(MSG + "SendMessagesHelper.java", r"TL_messages_getBotCallbackAnswer", "domain", "发送助手实现 Bot callback 请求和密码边界。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getBotCallbackAnswer", "backend", "MTProto 返回结构化 callback answer。"),
        properties=props(
            peer=copy.deepcopy(PEER),
            message_id=copy.deepcopy(MESSAGE_ID),
            row=schema_property("integer", "从 0 开始的按钮行。", minimum=0, maximum=99),
            column=schema_property("integer", "从 0 开始的按钮列。", minimum=0, maximum=99),
            idempotency_key=schema_property("string", "按钮执行去重键。", minLength=8, maxLength=128),
            _confirm=copy.deepcopy(CONFIRM),
        ),
        required=["peer", "message_id", "row", "column", "idempotency_key", "_confirm"],
        read_only=False, destructive=True, idempotent=True, confirmation="_confirm", open_world=True,
        side_effects=["向 Bot 发送 callback 或发送 reply keyboard 文本；Bot 下游动作可能不可逆"],
        readback="返回强类型 callback answer；任意 Bot 下游业务效果明确标记为未独立验证",
    ),
    Capability(
        "bot.command_list", "列出 Bot 命令", "bots", "从 Bot 完整资料读取命令、说明、简介和隐私政策链接。",
        "read", "read",
        anchor(UI + "bots/BotCommandsMenuView.java", r"info\.commands", "ui", "GUI Bot 命令菜单读取 BotInfo commands。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_bots.java", r"abstract class BotInfo", "backend", "BotInfo 是命令和简介的服务器模型。"),
        properties=props(bot=copy.deepcopy(PEER)), required=["bot"], outputs=["命令", "说明", "ephemeral 标记", "Bot 简介"],
    ),
    Capability(
        "bot.start", "启动 Bot", "bots", "在 Bot 私聊或明确群组上下文发送带 start_param 的幂等 Bot 启动请求。",
        "write", "external",
        anchor(MSG + "MessagesController.java", r"TL_messages_startBot req", "domain", "控制器使用 messages.startBot 启动 Bot。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_startBot", "backend", "MTProto 启动 Bot 并返回 Updates。"),
        properties=props(bot=copy.deepcopy(PEER), peer=copy.deepcopy(PEER), start_param=schema_property("string", "深链启动参数；可为空。", maxLength=512), idempotency_key=schema_property("string", "启动意图去重键。", minLength=8, maxLength=128)),
        required=["bot", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向 Bot 或群组发送启动事件"], readback="telegram.message.get 返回 startBot Updates 中的精确消息 ID",
    ),
    Capability(
        "bot.inline_query", "查询 Inline Bot", "bots", "在明确会话上下文向 Inline Bot 查询结果并返回 query_id、稳定 result_id 和分页 offset。",
        "read", "read",
        anchor(UI + "Adapters/MentionsAdapter.java", r"TL_messages_getInlineBotResults req", "ui", "GUI inline 搜索适配器发起 Bot 查询。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getInlineBotResults", "backend", "MTProto 返回强类型 inline 结果。"),
        properties=props(bot=copy.deepcopy(PEER), peer=copy.deepcopy(PEER), query=schema_property("string", "查询文本。", maxLength=512), offset=schema_property("string", "服务器分页 offset。", maxLength=512)),
        required=["bot", "peer"], outputs=["query_id", "result_id", "结果类型/标题/媒体", "下一页 offset"],
    ),
    Capability(
        "bot.inline_send", "发送 Inline Bot 结果", "bots", "把 inline_query 返回的 query_id 和 result_id 幂等发送到明确会话。",
        "write", "external",
        anchor(MSG + "SendMessagesHelper.java", r"TL_messages_sendInlineBotResult reqSend", "domain", "发送助手使用 inline result 请求维护发送状态。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_sendInlineBotResult", "backend", "MTProto 发送精确 inline 结果。"),
        properties=props(peer=copy.deepcopy(PEER), query_id=schema_property("string", "inline_query 返回的 64 位 query ID。", pattern=r"^[1-9][0-9]{0,18}$", maxLength=19), result_id=schema_property("string", "inline_query 返回的 result ID。", minLength=1, maxLength=512), silent=schema_property("boolean", "是否静默发送。", default=False), idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128)),
        required=["peer", "query_id", "result_id", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向外部会话发送 Bot 生成内容"], readback="telegram.message.get 返回 Updates 中的精确消息 ID",
    ),
    Capability(
        "sticker.favorite_list", "列出收藏贴纸", "stickers", "从服务器读取账号的收藏贴纸文档，64 位 ID 使用字符串。",
        "read", "read",
        anchor(UI + "Components/EmojiView.java", r"sendSticker\(TLRPC\.Document", "ui", "GUI emoji 面板使用贴纸 Document。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getFavedStickers", "backend", "MTProto 提供服务器收藏贴纸全集。"),
        properties=props(offset=schema_property("integer", "零基列表 offset。", minimum=0), limit=copy.deepcopy(LIMIT)), required=[], outputs=["document_id", "MIME/大小", "sticker emoji", "分页状态"],
    ),
    Capability(
        "sticker.favorite_set", "收藏或取消收藏贴纸", "stickers", "从精确服务器消息取得贴纸文档，设置其收藏状态。",
        "write", "write",
        anchor(MSG + "MediaDataController.java", r"TL_messages_faveSticker req", "domain", "APP 收藏贴纸经 faveSticker 同步。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_faveSticker", "backend", "MTProto 提供可逆收藏写入。"),
        properties=props(source_peer=copy.deepcopy(PEER), source_message_id=copy.deepcopy(MESSAGE_ID), saved=schema_property("boolean", "true 收藏，false 取消收藏。")),
        required=["source_peer", "source_message_id", "saved"], read_only=False,
        side_effects=["更改云端收藏贴纸列表"], readback="telegram.sticker.favorite_list 中目标 document_id 存在或缺席",
    ),
    Capability(
        "sticker.send_saved", "发送收藏贴纸", "stickers", "按收藏列表中的 document_id 通过 Telegram 发送助手发送贴纸。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"sendSticker\(TLRPC\.Document sticker", "ui", "GUI 聊天页以 Document 发送贴纸。"),
        anchor(MSG + "SendMessagesHelper.java", r"public void sendSticker\(TLRPC\.Document document", "domain", "发送助手维护贴纸本地队列与服务器 ID。"),
        properties=props(
            peer=copy.deepcopy(PEER), document_id=schema_property("string", "favorite_list 返回的 64 位 document ID。", pattern=r"^[1-9][0-9]{0,18}$", maxLength=19),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1), topic_id=copy.deepcopy(TOPIC_ID), silent=schema_property("boolean", "是否静默发送。", default=False),
            schedule_at=schema_property("string", "可选 ISO-8601 定时时间。", format="date-time"), idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128),
        ), required=["peer", "document_id", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向外部收件人发送贴纸"], readback="telegram.message.get 返回精确 document_id",
    ),
    Capability(
        "sticker.search", "按 emoji 搜索贴纸", "stickers", "从服务器按 emoji 搜索贴纸并返回仅在当前 APP/MCP 生命周期有效的签名 document_ref。",
        "read", "read",
        anchor(UI + "Adapters/StickersSearchAdapter.java", r"TL_messages_getStickers", "ui", "GUI 贴纸搜索通过 messages.getStickers 查询。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getStickers", "backend", "服务器按 emoticon 返回完整 Document。"),
        properties=props(emoji=schema_property("string", "用于搜索的 emoji。", minLength=1, maxLength=32), limit=copy.deepcopy(LIMIT)), required=["emoji"],
        outputs=["短期 document_ref", "document ID", "MIME/大小", "过期语义"],
    ),
    Capability(
        "sticker.send", "发送搜索到的贴纸", "stickers", "使用 sticker.search 返回的短期签名引用经 SendMessagesHelper 发送贴纸，支持回复、静默和定时。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"sendSticker", "ui", "GUI 贴纸面板通过 SendMessagesHelper 发送。"),
        anchor(MSG + "SendMessagesHelper.java", r"public void sendSticker", "domain", "发送 helper 维护本地消息、引用与服务器确认状态。"),
        properties=props(peer=copy.deepcopy(PEER), document_ref=schema_property("string", "sticker.search 返回的短期签名引用。", pattern=r"^d_[0-9a-f]{64}$", minLength=66, maxLength=66), reply_to_message_id=copy.deepcopy(MESSAGE_ID), topic_id=copy.deepcopy(TOPIC_ID), silent=schema_property("boolean", "静默发送。", default=False), schedule_at=schema_property("string", "可选 ISO-8601 UTC 未来时间。", format="date-time"), idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128)),
        required=["peer", "document_ref", "idempotency_key"], read_only=False, open_world=True,
        side_effects=["向目标发送贴纸"], readback="telegram.message.get 返回完全相同的 document ID",
    ),
    Capability(
        "sticker.pack_search", "搜索贴纸或自定义 emoji 包", "stickers", "按关键词搜索服务器贴纸包或自定义 emoji 包，返回稳定 short_name、安装和归档状态。",
        "read", "read",
        anchor(UI + "Adapters/StickersSearchAdapter.java", r"TL_messages_searchStickerSets", "ui", "GUI 贴纸包搜索使用服务器搜索。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_searchStickerSets", "backend", "服务器返回 StickerSetCovered 列表。"),
        properties=props(query=schema_property("string", "搜索关键词。", minLength=1, maxLength=128), emoji_packs=schema_property("boolean", "搜索自定义 emoji 包而非普通贴纸包。", default=False), limit=copy.deepcopy(LIMIT)), required=["query"],
        outputs=["short_name", "标题/数量", "安装/归档状态", "包类型"],
    ),
    Capability(
        "sticker.pack_set", "安装或卸载贴纸包", "stickers", "按公开 short_name 安装或卸载贴纸/emoji 包；卸载要求显式确认并以服务器 getStickerSet 回读。",
        "write", "external",
        anchor(UI + "Adapters/StickersSearchAdapter.java", r"installStickerSet\(pack", "ui", "GUI 搜索结果允许安装/卸载。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_installStickerSet", "backend", "messages.installStickerSet/uninstallStickerSet 修改云端包状态。"),
        properties=props(short_name=schema_property("string", "公开贴纸包 short_name。", minLength=1, maxLength=64), installed=schema_property("boolean", "目标安装状态。"), _confirm=copy.deepcopy(CONFIRM)),
        required=["short_name", "installed"], read_only=False, open_world=True,
        side_effects=["修改账号云端贴纸包列表；卸载时需 _confirm=true"], readback="messages.getStickerSet 返回精确安装标志",
    ),
    Capability(
        "gif.saved_list", "列出已保存 GIF", "gifs", "从服务器读取账号的已保存 GIF 文档。",
        "read", "read",
        anchor(UI + "Components/EmojiView.java", r"public void sendGif", "ui", "GUI GIF 面板使用服务器 Document。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getSavedGifs", "backend", "MTProto 提供已保存 GIF 全集。"),
        properties=props(offset=schema_property("integer", "零基列表 offset。", minimum=0), limit=copy.deepcopy(LIMIT)), required=[], outputs=["document_id", "MIME/大小", "分页状态"],
    ),
    Capability(
        "gif.saved_set", "保存或移除 GIF", "gifs", "从精确服务器消息取得 GIF 文档，设置其云端保存状态。",
        "write", "write",
        anchor(MSG + "MessagesController.java", r"public void saveGif", "domain", "APP 保存 GIF 经 messages.saveGif 同步。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_saveGif", "backend", "MTProto 提供可逆 GIF 保存写入。"),
        properties=props(source_peer=copy.deepcopy(PEER), source_message_id=copy.deepcopy(MESSAGE_ID), saved=schema_property("boolean", "true 保存，false 移除。")),
        required=["source_peer", "source_message_id", "saved"], read_only=False,
        side_effects=["更改云端已保存 GIF 列表"], readback="telegram.gif.saved_list 中目标 document_id 存在或缺席",
    ),
    Capability(
        "gif.send_saved", "发送已保存 GIF", "gifs", "按已保存列表中的 document_id 通过 Telegram 发送助手发送 GIF。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"sendSticker\(result\.document", "ui", "GUI GIF 结果通过文档发送路径进入发送助手。"),
        anchor(MSG + "SendMessagesHelper.java", r"public void sendSticker\(TLRPC\.Document document", "domain", "发送助手处理 GIF/动画文档的本地队列。"),
        properties=props(
            peer=copy.deepcopy(PEER), document_id=schema_property("string", "saved_list 返回的 64 位 document ID。", pattern=r"^[1-9][0-9]{0,18}$", maxLength=19),
            reply_to_message_id=schema_property("integer", "可选回复消息 ID。", minimum=1), topic_id=copy.deepcopy(TOPIC_ID), silent=schema_property("boolean", "是否静默发送。", default=False),
            schedule_at=schema_property("string", "可选 ISO-8601 定时时间。", format="date-time"), idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128),
        ), required=["peer", "document_id", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向外部收件人发送 GIF"], readback="telegram.message.get 返回精确 document_id",
    ),
    Capability(
        "story.list", "列出 Story", "stories", "按 peer 从服务器读取活跃、置顶或归档 Story，返回稳定 story_id 与分页状态。",
        "read", "read",
        anchor(UI + "Stories/StoryViewer.java", r"class StoryViewer", "ui", "GUI Story 查看器按 peer 浏览 Story。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_getPeerStories", "backend", "Stories API 提供活跃、置顶与归档列表。"),
        properties=props(
            peer=copy.deepcopy(PEER),
            mode=schema_property("string", "列表模式。", enum=["active", "pinned", "archive"], default="active"),
            offset_id=schema_property("integer", "上一页末条 story ID；首页为 0。", minimum=0),
            limit=copy.deepcopy(LIMIT),
        ), required=["peer"], outputs=["Story 列表", "最大已读 ID", "置顶顺序", "分页状态"],
    ),
    Capability(
        "story.get", "读取精确 Story", "stories", "从指定 peer 精确读取一条 Story 的 caption、媒体、可见性和计数。",
        "read", "read",
        anchor(UI + "LaunchActivity.java", r"TL_stories_getStoriesByID", "ui", "GUI 深链按 peer 和 ID 精确解析 Story。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_getStoriesByID", "backend", "Stories API 提供精确 ID 读取。"),
        properties=props(peer=copy.deepcopy(PEER), story_id=schema_property("integer", "Story ID。", minimum=1)),
        required=["peer", "story_id"], outputs=["Story 精确对象", "caption entities", "媒体 ID", "反应与浏览计数"],
    ),
    Capability(
        "story.can_send", "查询 Story 发布资格", "stories", "从服务器查询当前账号能否为自己、Bot 或所管理频道继续发布 Story。",
        "read", "read",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories\.TL_stories_canSendStory", "ui", "GUI Story 控制器查询发布额度。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_canSendStory", "backend", "Stories API 返回剩余发布数量。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], outputs=["是否可发布", "剩余数量"],
    ),
    Capability(
        "story.publish", "发布图片或视频 Story", "stories", "从 MCP 私有暂存区发布图片或视频 Story，支持 caption 格式、可见性、时长、置顶和禁止转发。",
        "write", "external",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories\.TL_stories_sendStory", "ui", "GUI Story 控制器提交已上传媒体。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_sendStory", "backend", "Stories API 原子创建 Story。"),
        properties=props(
            peer=copy.deepcopy(PEER), file_ref=copy.deepcopy(FILE_REF),
            media_type=schema_property("string", "按 MIME 自动判断，或显式指定图片/视频。", enum=["auto", "photo", "video"], default="auto"),
            width=schema_property("integer", "视频像素宽度。", minimum=1, maximum=8192), height=schema_property("integer", "视频像素高度。", minimum=1, maximum=8192),
            duration=schema_property("number", "视频时长秒数。", minimum=0.1, maximum=300), no_sound_video=schema_property("boolean", "视频是否无声。", default=False),
            caption=schema_property("string", "Story caption。", maxLength=4096), parse_mode=schema_property("string", "plain 或 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"),
            privacy=schema_property("string", "Story 可见性。", enum=["everyone", "contacts", "close_friends", "selected"], default="everyone"),
            privacy_peers=schema_property("array", "selected 模式允许的用户。", minItems=1, maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
            except_peers=schema_property("array", "everyone/contacts 模式排除的用户。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
            period=schema_property("integer", "展示时长秒数。", enum=[21600, 43200, 86400, 172800], default=86400),
            pinned=schema_property("boolean", "是否同时置顶到资料。", default=False), no_forwards=schema_property("boolean", "是否禁止转发。", default=False),
            idempotency_key=schema_property("string", "发布意图去重键。", minLength=8, maxLength=128),
        ),
        required=["peer", "file_ref", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向 Story 受众发布外部可见内容"], readback="telegram.story.get 返回精确 caption、可见性、媒体类型、时长和标志",
    ),
    Capability(
        "story.edit", "编辑 Story", "stories", "替换自己或可管理 Story 的媒体、caption 或可见性，并按精确 story_id 回读。",
        "write", "external",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories\.TL_stories_editStory", "ui", "GUI Story 控制器提交编辑。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_editStory", "backend", "Stories API 原子编辑 Story。"),
        properties=props(
            peer=copy.deepcopy(PEER), story_id=schema_property("integer", "Story ID。", minimum=1), file_ref=copy.deepcopy(FILE_REF),
            media_type=schema_property("string", "替换媒体的类型。", enum=["auto", "photo", "video"], default="auto"),
            width=schema_property("integer", "替换视频像素宽度。", minimum=1, maximum=8192), height=schema_property("integer", "替换视频像素高度。", minimum=1, maximum=8192),
            duration=schema_property("number", "替换视频时长秒数。", minimum=0.1, maximum=300), no_sound_video=schema_property("boolean", "替换视频是否无声。", default=False),
            caption=schema_property("string", "新 caption；空字符串清除。", maxLength=4096), parse_mode=schema_property("string", "plain 或 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"),
            privacy=schema_property("string", "新可见性。", enum=["everyone", "contacts", "close_friends", "selected"]),
            privacy_peers=schema_property("array", "selected 模式允许的用户。", minItems=1, maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
            except_peers=schema_property("array", "everyone/contacts 模式排除的用户。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
        ),
        required=["peer", "story_id"], read_only=False, open_world=True,
        side_effects=["修改已发布 Story 内容或受众"], readback="telegram.story.get 返回精确新内容与可见性",
    ),
    Capability(
        "story.archive_list", "列出归档 Story", "stories", "分页列出自己或可管理 peer 的归档 Story。",
        "read", "read",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories\.TL_stories_getStoriesArchive", "ui", "GUI Story 控制器读取归档。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_getStoriesArchive", "backend", "Stories API 提供归档分页。"),
        properties=props(peer=copy.deepcopy(PEER), offset_id=schema_property("integer", "分页 Story ID。", minimum=0), limit=copy.deepcopy(LIMIT)), required=["peer"], outputs=["归档 Story", "分页状态"],
    ),
    Capability(
        "story.pinned_list", "列出置顶 Story", "stories", "分页列出 peer 资料页上的置顶 Story 及置顶顺序。",
        "read", "read",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories\.TL_stories_getPinnedStories", "ui", "GUI Story 控制器读取置顶集合。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_getPinnedStories", "backend", "Stories API 提供置顶分页。"),
        properties=props(peer=copy.deepcopy(PEER), offset_id=schema_property("integer", "分页 Story ID。", minimum=0), limit=copy.deepcopy(LIMIT)), required=["peer"], outputs=["置顶 Story", "置顶顺序", "分页状态"],
    ),
    Capability(
        "story.views_list", "列出 Story 浏览者", "stories", "对自己或有管理权的 Story 分页读取浏览者、反应、转发和公开转贴。",
        "read", "read",
        anchor(UI + "Stories/SelfStoryViewsPage.java", r"TL_stories_getStoryViewsList", "ui", "GUI 自己 Story 浏览者页发起同一分页请求。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_getStoryViewsList", "backend", "Stories API 返回强类型浏览者列表。"),
        properties=props(
            peer=copy.deepcopy(PEER), story_id=schema_property("integer", "Story ID。", minimum=1),
            offset=schema_property("string", "服务器分页 offset。", default=""), limit=copy.deepcopy(LIMIT),
            query=schema_property("string", "可选浏览者搜索文本。", maxLength=128),
            contacts_only=schema_property("boolean", "只返回联系人。", default=False),
            reactions_first=schema_property("boolean", "优先反应者。", default=False),
            forwards_first=schema_property("boolean", "优先转发。", default=False),
        ), required=["peer", "story_id"], outputs=["浏览者", "反应", "转发/转贴", "下一页 offset"],
    ),
    Capability(
        "story.mark_read", "标记 Story 已读", "stories", "把指定 peer 的 Story 已读进度推进到精确 story_id。",
        "write", "write",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories_readStories req", "ui", "GUI Story 控制器同步最大已读 ID。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_readStories", "backend", "Stories API 写入最大已读 Story ID。"),
        properties=props(peer=copy.deepcopy(PEER), story_id=schema_property("integer", "最大已读 Story ID。", minimum=1)),
        required=["peer", "story_id"], read_only=False,
        side_effects=["同步 Story 已读回执"], readback="telegram.story.list 返回 max_read_id 不小于目标 ID",
    ),
    Capability(
        "story.reaction_set", "设置 Story 反应", "stories", "对精确 Story 设置普通 emoji、自定义 emoji 反应，或显式清除。",
        "write", "external",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories_sendReaction req", "ui", "GUI Story 反应使用同一控制器路径。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_sendReaction", "backend", "Stories API 返回 Updates 并保存反应。"),
        properties=props(
            peer=copy.deepcopy(PEER), story_id=schema_property("integer", "Story ID。", minimum=1),
            reaction=schema_property("string", "普通 emoji；空且无自定义 ID 时清除。", maxLength=32, default=""),
            custom_emoji_document_id=schema_property("string", "自定义 emoji 64 位 document ID。", pattern=r"^[1-9][0-9]{0,18}$", maxLength=19),
            add_to_recent=schema_property("boolean", "是否加入最近反应。", default=True),
        ), required=["peer", "story_id"], read_only=False, open_world=True,
        side_effects=["向 Story 作者发送反应"], readback="telegram.story.get 返回精确 sent_reaction",
    ),
    Capability(
        "story.hide_peer", "隐藏 peer 的 Story", "stories", "把指定 peer 的 Story 移入隐藏列表，不屏蔽 peer。",
        "write", "write",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories_togglePeerStoriesHidden req", "ui", "GUI Story 控制器切换 peer 隐藏状态。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_togglePeerStoriesHidden", "backend", "Stories API 提供 peer 级隐藏写入。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], read_only=False,
        side_effects=["改变 Story 列表可见分组"], readback="服务器重新读取 peer.stories_hidden=true",
    ),
    Capability(
        "story.unhide_peer", "取消隐藏 peer 的 Story", "stories", "把指定 peer 的 Story 恢复到主列表。",
        "write", "write",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories_togglePeerStoriesHidden req", "ui", "GUI Story 控制器支持取消隐藏。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_togglePeerStoriesHidden", "backend", "Stories API 提供 peer 级隐藏写入。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], read_only=False,
        side_effects=["改变 Story 列表可见分组"], readback="服务器重新读取 peer.stories_hidden=false",
    ),
    Capability(
        "story.pin", "置顶或取消归档 Story", "stories", "把自己或可管理的 Story 放到资料页置顶列表。",
        "write", "external",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories\.togglePinned req", "ui", "GUI Story 控制器切换置顶/归档。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class togglePinned", "backend", "Stories API 返回受影响 Story ID。"),
        properties=props(peer=copy.deepcopy(PEER), story_id=schema_property("integer", "Story ID。", minimum=1)),
        required=["peer", "story_id"], read_only=False, open_world=True,
        side_effects=["修改公开资料 Story 集合"], readback="telegram.story.get 返回 pinned=true",
    ),
    Capability(
        "story.unpin", "归档 Story", "stories", "把自己或可管理的置顶 Story 移入归档。",
        "write", "external",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories\.togglePinned req", "ui", "GUI Story 控制器切换置顶/归档。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class togglePinned", "backend", "Stories API 返回受影响 Story ID。"),
        properties=props(peer=copy.deepcopy(PEER), story_id=schema_property("integer", "Story ID。", minimum=1)),
        required=["peer", "story_id"], read_only=False, open_world=True,
        side_effects=["修改公开资料 Story 集合"], readback="telegram.story.get 返回 pinned=false",
    ),
    Capability(
        "story.delete", "删除 Story", "stories", "删除自己或有删除权限的精确 Story，并以服务器缺席回读验证。",
        "write", "destructive",
        anchor(UI + "Stories/StoriesController.java", r"TL_stories_deleteStories req", "ui", "GUI Story 删除使用同一批量请求。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_stories.java", r"class TL_stories_deleteStories", "backend", "Stories API 按 peer 和 ID 删除。"),
        properties=props(peer=copy.deepcopy(PEER), story_id=schema_property("integer", "Story ID。", minimum=1), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "story_id", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["永久删除 Story 及其公开链接"], readback="telegram.story.get 在精确 peer 下返回 STORY_NOT_FOUND",
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
        properties=props(peer=copy.deepcopy(PEER), text=schema_property("string", "草稿文本。", maxLength=4096), topic_id=schema_property("integer", "可选话题 ID。", minimum=0), replace=schema_property("boolean", "原草稿含富状态时是否明确允许覆盖。", default=False)),
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
        "contact.get", "读取云端联系人", "contacts", "从 Telegram 云端联系人全集精确读取一个用户及其联系人姓名。",
        "read", "read",
        anchor(UI + "ContactsActivity.java", r"class ContactsActivity", "ui", "GUI 联系人资料入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_contacts_getContacts", "backend", "MTProto 返回完整云端联系人集合。"),
        properties=props(user=copy.deepcopy(PEER)), required=["user"],
        outputs=["用户资料", "云端联系人关系", "精确姓名"],
    ),
    Capability(
        "contact.upsert", "新增或修改云端联系人", "contacts", "按用户写入完整联系人姓名；存在时修改，不存在时新增。",
        "write", "external",
        anchor(UI + "ContactAddActivity.java", r"class ContactAddActivity", "ui", "GUI 联系人新增与姓名编辑入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_contacts_addContact", "backend", "contacts.addContact 同时支持新增和修改姓名。"),
        properties=props(
            user=copy.deepcopy(PEER),
            first_name=schema_property("string", "联系人名字。", minLength=1, maxLength=64),
            last_name=schema_property("string", "联系人姓氏；空字符串清除。", maxLength=64, default=""),
            idempotency_key=schema_property("string", "写入意图去重键。", minLength=8, maxLength=128),
        ),
        required=["user", "first_name", "idempotency_key"], read_only=False,
        idempotent=True, open_world=True,
        side_effects=["新增或修改 Telegram 云端联系人"],
        readback="telegram.contact.get 返回精确 first_name 和 last_name",
    ),
    Capability(
        "contact.delete", "删除云端联系人", "contacts", "从 Telegram 云端联系人中永久移除一个用户；不会删除双方聊天。",
        "write", "destructive",
        anchor(UI + "ContactsActivity.java", r"deleteContactsUndoable", "ui", "GUI 提供联系人删除与短暂撤销入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_contacts_deleteContacts", "backend", "contacts.deleteContacts 删除云端关系。"),
        properties=props(user=copy.deepcopy(PEER), _confirm=copy.deepcopy(CONFIRM)),
        required=["user", "_confirm"], read_only=False, destructive=True,
        idempotent=True, confirmation="_confirm", open_world=True,
        side_effects=["永久移除云端联系人关系"],
        readback="telegram.contact.get 返回 CONTACT_NOT_FOUND",
    ),
    Capability(
        "contact.search", "搜索用户和联系人", "contacts", "按关键词搜索 Telegram 用户、群组和已有联系人。",
        "read", "read",
        anchor(UI + "ContactsActivity.java", r"searching", "ui", "联系人 GUI 提供搜索模式。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_contacts_search", "backend", "MTProto 提供联系人/全局 peer 搜索。"),
        properties=props(query=schema_property("string", "姓名或 username 关键词。", minLength=1), limit=copy.deepcopy(LIMIT), include_broadcasts=schema_property("boolean", "是否包含广播频道。", default=True), include_bots=schema_property("boolean", "是否包含机器人。", default=True)), required=["query"],
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
        properties=props(title=schema_property("string", "群组标题。", minLength=1, maxLength=128), members=schema_property("array", "初始成员 peer。", minItems=1, maxItems=200, items=copy.deepcopy(PEER)), idempotency_key=schema_property("string", "创建意图去重键。", minLength=8, maxLength=128)),
        required=["title", "members", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["创建外部群组并邀请成员"], readback="telegram.dialog.list 出现返回的 chat peer",
    ),
    Capability(
        "chat.create_channel", "创建频道、超级群组或论坛", "chats", "创建广播频道、超级群组或启用 forum 标志的论坛并设置标题和简介。",
        "write", "external",
        anchor(UI + "ChannelCreateActivity.java", r"createChat\(", "ui", "GUI 频道创建页进入控制器创建流程。"),
        anchor(MSG + "MessagesController.java", r"public int createChat\(", "domain", "同一控制器按 chatType 构造频道请求。"),
        properties=props(title=schema_property("string", "标题。", minLength=1, maxLength=128), about=schema_property("string", "简介。", maxLength=255), kind=schema_property("string", "channel 为广播频道，supergroup 为超级群组，forum 为论坛超级群组。", enum=["channel", "supergroup", "forum"]), idempotency_key=schema_property("string", "创建意图去重键。", minLength=8, maxLength=128)),
        required=["title", "kind", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["创建频道、超级群组或论坛"], readback="telegram.chat.get 返回目标类型，forum 可继续组合 topic 工具",
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
        "chat.photo_upload", "上传群组或频道头像", "chats", "从 MCP 私有暂存区上传图片并设置为明确群组或频道的头像。",
        "write", "external",
        anchor(UI + "ChatEditActivity.java", r"changeChatAvatar", "ui", "GUI 群组编辑页通过消息控制器更新头像。"),
        anchor(MSG + "MessagesController.java", r"public void changeChatAvatar", "domain", "消息控制器构造群组或频道头像更新请求。"),
        properties=props(peer=copy.deepcopy(PEER), file_ref=copy.deepcopy(FILE_REF), idempotency_key=schema_property("string", "头像更新意图去重键。", minLength=8, maxLength=128)),
        required=["peer", "file_ref", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["上传图片并修改群组或频道公开头像"], readback="telegram.chat.get 返回非零且不同于原值的 photo_id",
    ),
    Capability(
        "chat.photo_clear", "清除群组或频道头像", "chats", "清除明确群组或频道的当前头像。",
        "write", "destructive",
        anchor(UI + "ChatEditActivity.java", r"changeChatAvatar", "ui", "GUI 群组编辑页可清除头像。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_inputChatPhotoEmpty", "backend", "空 InputChatPhoto 清除群组或频道头像。"),
        properties=props(peer=copy.deepcopy(PEER), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["移除群组或频道公开头像"], readback="telegram.chat.get 返回 photo_present=false 和 photo_id=0",
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
        "chat.member_get", "读取精确成员状态", "chats", "从服务器读取某个用户在群组或频道中的成员、封禁、角色和权限状态。",
        "read", "read",
        anchor(UI + "ChatUsersActivity.java", r"class ChatUsersActivity", "ui", "GUI 成员页展示单个成员角色。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_getParticipant", "backend", "MTProto 提供单成员精确读取。"),
        properties=props(peer=copy.deepcopy(PEER), member=copy.deepcopy(PEER)), required=["peer", "member"], outputs=["是否在群", "是否封禁", "角色", "管理员权利", "成员权限"],
    ),
    Capability(
        "chat.member_add", "添加群组或频道成员", "chats", "把明确用户加入有权限管理的群组或频道。",
        "write", "external",
        anchor(UI + "ChatUsersActivity.java", r"addUserToChat", "ui", "GUI 成员页调用控制器添加用户。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_inviteToChannel", "backend", "MTProto 提供频道/超级群组邀请。"),
        properties=props(peer=copy.deepcopy(PEER), member=copy.deepcopy(PEER), history_limit=schema_property("integer", "普通群组可见历史条数。", minimum=0, maximum=100)), required=["peer", "member"], read_only=False, open_world=True,
        side_effects=["邀请或添加外部用户"], readback="telegram.chat.member_get 返回 present=true",
    ),
    Capability(
        "chat.member_remove", "移除或封禁成员", "chats", "从群组/频道移除用户，可选择保留封禁状态。",
        "write", "destructive",
        anchor(UI + "ChatUsersActivity.java", r"deleteParticipantFromChat", "ui", "GUI 成员页移除成员。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_editBanned", "backend", "频道成员移除由 editBanned 语义完成。"),
        properties=props(peer=copy.deepcopy(PEER), member=copy.deepcopy(PEER), ban=schema_property("boolean", "移除后是否保持封禁。", default=False), revoke_history=schema_property("boolean", "普通群组是否撤销该成员历史。", default=False), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "member", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["移除成员，可能封禁并撤销历史"], readback="telegram.chat.member_get 返回 present=false 与精确 banned 状态",
    ),
    Capability(
        "chat.member_admin_set", "设置成员管理员角色", "chats", "提升或撤销成员管理员，并为超级群组/频道设置细粒度权利。",
        "write", "destructive",
        anchor(UI + "ChatRightsEditActivity.java", r"class ChatRightsEditActivity", "ui", "GUI 管理员权利编辑入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_editAdmin", "backend", "MTProto 提供管理员权利写入。"),
        properties=props(peer=copy.deepcopy(PEER), member=copy.deepcopy(PEER), admin=schema_property("boolean", "true 提升，false 撤销。", default=True), rights=copy.deepcopy(CHAT_ADMIN_RIGHTS_SCHEMA), rank=schema_property("string", "可选管理员头衔。", maxLength=16), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "member", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["改变外部用户管理员权限"], readback="telegram.chat.member_get 返回精确角色和权利",
    ),
    Capability(
        "chat.member_restrict", "设置成员限制", "chats", "合并修改超级群组/频道成员的发送、媒体和管理允许项。",
        "write", "destructive",
        anchor(UI + "ChatRightsEditActivity.java", r"class ChatRightsEditActivity", "ui", "GUI 成员限制编辑入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_editBanned", "backend", "MTProto 使用 banned rights 表达成员限制。"),
        properties=props(peer=copy.deepcopy(PEER), member=copy.deepcopy(PEER), allowed=copy.deepcopy(CHAT_ALLOWED_SCHEMA), until_date=schema_property("integer", "限制截止 Unix 时间；0 永久。", minimum=0), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "member", "allowed", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["改变成员可执行动作"], readback="telegram.chat.member_get 返回精确 allowed 与 until_date",
    ),
    Capability(
        "chat.permissions_get", "读取默认成员权限", "chats", "从服务器读取群组或频道的默认成员允许项。",
        "read", "read",
        anchor(UI + "ChatRightsEditActivity.java", r"defaultBannedRights", "ui", "GUI 权限页读取默认封禁权利。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"default_banned_rights", "backend", "Chat 对象携带服务器默认权利。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], outputs=["全部默认允许项", "服务器来源"],
    ),
    Capability(
        "chat.permissions_set", "修改默认成员权限", "chats", "合并修改群组或频道的默认成员允许项。",
        "write", "destructive",
        anchor(UI + "ChatRightsEditActivity.java", r"defaultBannedRights", "ui", "GUI 权限页修改默认权利。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_editChatDefaultBannedRights", "backend", "MTProto 提供默认权利写入。"),
        properties=props(peer=copy.deepcopy(PEER), allowed=copy.deepcopy(CHAT_ALLOWED_SCHEMA), _confirm=copy.deepcopy(CONFIRM)), required=["peer", "allowed", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["改变所有普通成员默认权限"], readback="telegram.chat.permissions_get 返回精确合并结果",
    ),
    Capability(
        "chat.invite_list", "列出邀请链接", "chats", "分页读取当前管理员创建的有效或已撤销邀请链接。",
        "read", "read",
        anchor(UI + "ManageLinksActivity.java", r"class ManageLinksActivity", "ui", "GUI 邀请链接管理入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getExportedChatInvites", "backend", "MTProto 提供邀请链接分页读取。"),
        properties=props(peer=copy.deepcopy(PEER), revoked=schema_property("boolean", "是否读取已撤销链接。", default=False), offset_date=schema_property("integer", "分页日期。", minimum=0), offset_link=schema_property("string", "分页链接。", maxLength=512), limit=copy.deepcopy(LIMIT)), required=["peer"], outputs=["链接", "有效期", "用量", "入群审批", "分页游标"],
    ),
    Capability(
        "chat.invite_create", "创建邀请链接", "chats", "创建具有明确有效期、次数、审批和标题的邀请链接。",
        "write", "external",
        anchor(UI + "LinkEditActivity.java", r"class LinkEditActivity", "ui", "GUI 邀请链接编辑入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_exportChatInvite", "backend", "MTProto 提供邀请链接创建。"),
        properties=props(peer=copy.deepcopy(PEER), expire_date=schema_property("integer", "未来 Unix 时间；0 永久。", minimum=0), usage_limit=schema_property("integer", "使用上限；0 不限。", minimum=0, maximum=100000), request_needed=schema_property("boolean", "是否需要管理员审批。", default=False), title=schema_property("string", "管理员可见标题。", maxLength=32), idempotency_key=schema_property("string", "创建意图去重键。", minLength=8, maxLength=128)),
        required=["peer", "idempotency_key"], read_only=False, open_world=True, side_effects=["创建可加入外部 chat 的链接"], readback="messages.getExportedChatInvite 返回精确链接对象",
    ),
    Capability(
        "chat.invite_revoke", "撤销邀请链接", "chats", "撤销一个明确邀请链接并验证其 revoked 状态。",
        "write", "destructive",
        anchor(UI + "ManageLinksActivity.java", r"revokeLink", "ui", "GUI 邀请链接页支持撤销。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_editExportedChatInvite", "backend", "MTProto 提供邀请链接撤销。"),
        properties=props(peer=copy.deepcopy(PEER), link=schema_property("string", "待撤销的完整邀请链接。", minLength=12, maxLength=512), _confirm=copy.deepcopy(CONFIRM)), required=["peer", "link", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["使邀请链接永久失效"], readback="messages.getExportedChatInvite 返回 revoked=true",
    ),
    Capability(
        "chat.join_request_list", "列出待审批入群申请", "chats", "分页读取群组或频道尚待处理的加入申请。",
        "read", "read",
        anchor(UI + "MemberRequestsActivity.java", r"class MemberRequestsActivity", "ui", "GUI 入群申请管理入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getChatInviteImporters", "backend", "MTProto 以 requested 过滤待审批申请。"),
        properties=props(peer=copy.deepcopy(PEER), query=schema_property("string", "可选申请人搜索词。", maxLength=256), offset_date=schema_property("integer", "分页日期。", minimum=0), offset_user=copy.deepcopy(PEER), limit=copy.deepcopy(LIMIT)), required=["peer"], outputs=["申请用户", "日期", "自述", "分页游标"],
    ),
    Capability(
        "chat.join_request_decide", "审批入群申请", "chats", "批准或拒绝一个明确用户的待处理入群申请。",
        "write", "destructive",
        anchor(UI + "Delegates/MemberRequestsDelegate.java", r"hideChatJoinRequest", "ui", "GUI 对单个申请执行批准或拒绝。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_hideChatJoinRequest", "backend", "MTProto 提供单申请决策。"),
        properties=props(peer=copy.deepcopy(PEER), user=copy.deepcopy(PEER), approve=schema_property("boolean", "true 批准，false 拒绝。"), _confirm=copy.deepcopy(CONFIRM)), required=["peer", "user", "approve", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["批准用户加入或永久移除当前申请"], readback="telegram.chat.join_request_list 不再包含该用户",
    ),
    Capability(
        "chat.admin_log", "读取管理员日志", "chats", "分页读取频道或超级群组的服务端管理员事件摘要。",
        "read", "read",
        anchor(UI + "ChannelAdminLogActivity.java", r"class ChannelAdminLogActivity", "ui", "GUI 管理员日志入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_getAdminLog", "backend", "MTProto 提供管理员日志分页。"),
        properties=props(peer=copy.deepcopy(PEER), query=schema_property("string", "事件搜索文本。", maxLength=256), max_id=schema_property("integer", "最大事件 ID。", minimum=0), min_id=schema_property("integer", "最小事件 ID。", minimum=0), limit=copy.deepcopy(LIMIT)), required=["peer"], outputs=["事件 ID", "时间", "操作者", "动作类型", "下一页 ID"],
    ),
    Capability(
        "chat.username_set", "设置 chat 公开用户名", "chats", "设置或清除频道/超级群组公开 username。",
        "write", "external",
        anchor(UI + "ChannelCreateActivity.java", r"updateUsername", "ui", "GUI 频道公开链接设置入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_updateUsername", "backend", "MTProto 提供 chat username 写入。"),
        properties=props(peer=copy.deepcopy(PEER), username=schema_property("string", "新 username；空字符串清除。", maxLength=32)), required=["peer", "username"], read_only=False, open_world=True,
        side_effects=["改变 chat 的公开链接"], readback="telegram.chat.get 返回精确 username",
    ),
    Capability(
        "chat.slow_mode_set", "设置超级群组慢速模式", "chats", "把超级群组慢速模式设为 Telegram 支持的明确秒数。",
        "write", "external",
        anchor(UI + "ChatUsersActivity.java", r"selectedSlowmode", "ui", "GUI 群组权限页提供慢速模式。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_toggleSlowMode", "backend", "MTProto 提供慢速模式写入。"),
        properties=props(peer=copy.deepcopy(PEER), seconds=schema_property("integer", "0、10、30、60、300、900 或 3600。", enum=[0, 10, 30, 60, 300, 900, 3600])), required=["peer", "seconds"], read_only=False, open_world=True,
        side_effects=["改变普通成员消息发送频率"], readback="telegram.chat.get 返回精确 slow_mode_seconds",
    ),
    Capability(
        "chat.auto_delete_set", "设置 chat 自动删除", "chats", "设置群组或频道新消息自动删除周期；0 关闭。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"ttl_period", "ui", "GUI chat 设置展示自动删除周期。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_setHistoryTTL", "backend", "MTProto 提供 chat history TTL 写入。"),
        properties=props(peer=copy.deepcopy(PEER), seconds=schema_property("integer", "自动删除秒数；0 关闭。", minimum=0, maximum=31536000)), required=["peer", "seconds"], read_only=False, open_world=True,
        side_effects=["使后续消息按周期自动删除"], readback="telegram.chat.get 返回精确 auto_delete_seconds",
    ),
    Capability(
        "chat.reactions_get", "读取 chat 反应策略", "chats", "从服务器完整资料读取群组或频道允许的表情反应、数量上限和付费反应状态。",
        "read", "read",
        anchor(UI + "ChatReactionsEditActivity.java", r"class ChatReactionsEditActivity", "ui", "GUI 提供 chat 反应策略编辑入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_chatReactionsSome", "backend", "MTProto 完整资料使用 ChatReactions 表达允许策略。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"],
        outputs=["none/all/some/default 模式", "允许的反应", "数量上限", "付费反应状态", "服务器来源"],
    ),
    Capability(
        "chat.reactions_set", "设置 chat 反应策略", "chats", "设置群组或频道允许的全部、部分或禁用反应，并可设置自定义表情、数量上限和付费反应。",
        "write", "external",
        anchor(UI + "ChatReactionsEditActivity.java", r"class ChatReactionsEditActivity", "ui", "GUI 提供 chat 反应策略编辑入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_setChatAvailableReactions", "backend", "MTProto 原子写入 chat 反应策略。"),
        properties=props(
            peer=copy.deepcopy(PEER),
            mode=schema_property("string", "none、all 或 some。", enum=["none", "all", "some"]),
            reactions=schema_property("array", "some 模式的唯一 emoji 或 custom:<document_id> 字符串。", maxItems=100, uniqueItems=True, items=schema_property("string", "emoji 或 custom:<document_id>。", minLength=1, maxLength=64)),
            allow_custom=schema_property("boolean", "all 模式是否允许任意自定义 emoji。", default=False),
            limit=schema_property("integer", "可选反应数量上限。", minimum=0, maximum=100),
            paid_enabled=schema_property("boolean", "可选付费反应状态。"),
        ),
        required=["peer", "mode"], read_only=False, open_world=True,
        side_effects=["改变成员可使用的消息反应"], readback="telegram.chat.reactions_get 返回语义等价的策略、上限和付费状态",
    ),
    Capability(
        "chat.signatures_set", "设置频道消息签名", "chats", "设置广播频道消息签名及签名资料展示。",
        "write", "external",
        anchor(UI + "ChatUsersActivity.java", r"toggleChannelSignatures", "ui", "GUI 权限页保存频道签名设置。"),
        anchor(MSG + "MessagesController.java", r"TL_channels_toggleSignatures", "domain", "控制器通过 MTProto 写入签名与资料标记。"),
        properties=props(peer=copy.deepcopy(PEER), enabled=schema_property("boolean", "是否显示消息签名。"), profiles=schema_property("boolean", "是否显示签名者资料；仅 enabled=true 有效。", default=False)),
        required=["peer", "enabled"], read_only=False, open_world=True,
        side_effects=["改变频道新消息的署名方式"], readback="telegram.chat.get 返回精确 signatures 与 signature_profiles",
    ),
    Capability(
        "chat.linked_set", "设置频道讨论组", "chats", "把广播频道链接到一个超级群组，或用空 group_peer 解除链接。",
        "write", "destructive",
        anchor(UI + "ChatLinkActivity.java", r"TL_channels_setDiscussionGroup", "ui", "GUI 讨论组页链接或解除频道与超级群组。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_setDiscussionGroup", "backend", "MTProto 原子写入讨论组链接。"),
        properties=props(peer=copy.deepcopy(PEER), group_peer=schema_property("string", "超级群组引用；空字符串解除链接。", maxLength=256), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "group_peer", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["改变频道评论入口和两个 chat 的链接关系"], readback="telegram.chat.get 返回精确 linked_chat_id",
    ),
    Capability(
        "chat.anti_spam_set", "设置超级群组反垃圾", "chats", "启用或关闭 Telegram 原生超级群组反垃圾系统。",
        "write", "external",
        anchor(UI + "ChatUsersActivity.java", r"TL_channels_toggleAntiSpam", "ui", "GUI 权限页切换原生反垃圾。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_toggleAntiSpam", "backend", "MTProto 提供反垃圾开关。"),
        properties=props(peer=copy.deepcopy(PEER), enabled=schema_property("boolean", "目标反垃圾状态。")), required=["peer", "enabled"], read_only=False, open_world=True,
        side_effects=["改变 Telegram 自动处理疑似垃圾消息的行为"], readback="telegram.chat.get 返回精确 anti_spam",
    ),
    Capability(
        "chat.participants_hidden_set", "设置隐藏超级群组成员", "chats", "启用或关闭普通成员不可见完整成员列表。",
        "write", "external",
        anchor(UI + "ChatUsersActivity.java", r"TL_channels_toggleParticipantsHidden", "ui", "GUI 权限页切换成员隐藏。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_channels_toggleParticipantsHidden", "backend", "MTProto 提供成员隐藏开关。"),
        properties=props(peer=copy.deepcopy(PEER), enabled=schema_property("boolean", "目标隐藏状态。")), required=["peer", "enabled"], read_only=False, open_world=True,
        side_effects=["改变普通成员对成员列表的可见性"], readback="telegram.chat.get 返回精确 participants_hidden",
    ),
    Capability(
        "chat.history_visible_set", "设置新成员历史可见性", "chats", "设置新加入超级群组的成员能否看到加入前的消息历史。",
        "write", "external",
        anchor(UI + "ChatEditActivity.java", r"toggleChannelInvitesHistory", "ui", "GUI 编辑页保存历史可见性。"),
        anchor(MSG + "MessagesController.java", r"TL_channels_togglePreHistoryHidden", "domain", "控制器将可见性转换为 prehistory hidden 标记。"),
        properties=props(peer=copy.deepcopy(PEER), visible=schema_property("boolean", "新成员是否可见加入前历史。")), required=["peer", "visible"], read_only=False, open_world=True,
        side_effects=["改变新成员可见的历史消息范围"], readback="telegram.chat.get 返回精确 history_visible",
    ),
    Capability(
        "chat.boost_status", "读取 chat 助力状态", "chats", "读取频道或超级群组的助力等级、计数、下一等级、受众和当前账号助力槽。",
        "read", "read",
        anchor(UI + "BoostsActivity.java", r"getBoostsStats", "ui", "GUI 助力页加载完整助力状态。"),
        anchor(MSG + "ChannelBoostsController.java", r"TL_premium_getBoostsStatus", "domain", "控制器调用服务器助力状态接口。"),
        properties=props(peer=copy.deepcopy(PEER)), required=["peer"], outputs=["等级", "助力计数", "下一等级门槛", "受众", "助力链接", "我的槽位"],
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
        "chat.delete_owned", "删除自己创建的群组或频道", "chats", "仅当当前账号是 creator 时永久删除目标群组、频道、超级群组或论坛。",
        "write", "destructive",
        anchor(UI + "ProfileActivity.java", r"delete_group", "ui", "GUI 资料页为拥有者提供删除群组入口。"),
        anchor(MSG + "MessagesController.java", r"TL_channels_deleteChannel req", "domain", "控制器对 creator 使用 channels.deleteChannel。"),
        properties=props(peer=copy.deepcopy(PEER), _confirm=copy.deepcopy(CONFIRM)), required=["peer", "_confirm"], read_only=False, destructive=True, idempotent=False, open_world=True, confirmation="_confirm",
        side_effects=["永久删除当前账号拥有的群组或频道及其内容"], readback="独立 telegram.chat.get 不再能够读取目标",
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
        "topic.list", "列出论坛主题", "topics", "分页列出论坛超级群组的主题、未读和置顶状态。",
        "read", "read",
        anchor(UI + "TopicsFragment.java", r"class TopicsFragment", "ui", "GUI 论坛主题列表入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_forum.java", r"class TL_messages_getForumTopics", "backend", "MTProto 提供论坛主题分页读取。"),
        properties=props(peer=copy.deepcopy(PEER), query=schema_property("string", "可选主题搜索词。", maxLength=256), offset_date=schema_property("integer", "分页日期偏移。", minimum=0), offset_id=schema_property("integer", "分页消息偏移。", minimum=0), offset_topic=schema_property("integer", "分页主题偏移。", minimum=0), limit=copy.deepcopy(LIMIT)),
        required=["peer"], outputs=["主题 ID", "标题", "未读数", "关闭/隐藏/置顶状态", "分页游标"],
    ),
    Capability(
        "topic.get", "读取论坛主题", "topics", "按 peer 与 topic_id 从服务器读取精确主题状态。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"getTopicId\(\)", "ui", "GUI 聊天页按 topic_id 定位论坛主题。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_forum.java", r"class TL_messages_getForumTopicsByID", "backend", "MTProto 提供按 ID 精确读取主题。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "论坛主题 ID。", minimum=1)), required=["peer", "topic_id"],
        outputs=["主题标题", "图标", "未读", "关闭/隐藏/置顶状态"],
    ),
    Capability(
        "topic.create", "创建论坛主题", "topics", "在有权限的论坛超级群组中创建主题。",
        "write", "external",
        anchor(UI + "TopicCreateFragment.java", r"TL_messages_createForumTopic", "ui", "GUI 主题创建页提交创建请求。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_forum.java", r"class TL_messages_createForumTopic", "backend", "MTProto 提供带 random_id 的主题创建。"),
        properties=props(peer=copy.deepcopy(PEER), title=schema_property("string", "主题标题。", minLength=1, maxLength=128), icon_color=schema_property("integer", "可选 RGB 图标色。", minimum=0, maximum=16777215), icon_emoji_id=schema_property("integer", "可选自定义 emoji document ID。", minimum=0), idempotency_key=schema_property("string", "创建意图去重键。", minLength=8, maxLength=128)),
        required=["peer", "title", "idempotency_key"], read_only=False, open_world=True,
        side_effects=["创建外部可见论坛主题"], readback="telegram.topic.get 返回新 topic_id 与精确标题",
    ),
    Capability(
        "topic.update", "编辑论坛主题", "topics", "原子修改主题标题、图标、关闭或隐藏状态。",
        "write", "external",
        anchor(UI + "TopicCreateFragment.java", r"TL_messages_editForumTopic", "ui", "GUI 主题编辑页提交编辑请求。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_forum.java", r"class TL_messages_editForumTopic", "backend", "MTProto 提供主题字段编辑。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "论坛主题 ID。", minimum=1), title=schema_property("string", "可选新标题。", minLength=1, maxLength=128), icon_emoji_id=schema_property("integer", "可选 emoji ID；0 清除。", minimum=0), closed=schema_property("boolean", "可选关闭或重开。"), hidden=schema_property("boolean", "可选隐藏或显示。")),
        required=["peer", "topic_id"], read_only=False, open_world=True,
        side_effects=["修改外部可见论坛主题"], readback="telegram.topic.get 返回全部指定字段",
    ),
    Capability(
        "topic.pin", "置顶论坛主题", "topics", "在论坛主题列表中置顶指定主题。",
        "write", "external",
        anchor(MSG + "TopicsController.java", r"public void pinTopic", "domain", "主题控制器维护置顶顺序。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_forum.java", r"class TL_messages_updatePinnedForumTopic", "backend", "MTProto 提供主题置顶写入。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "论坛主题 ID。", minimum=1)), required=["peer", "topic_id"], read_only=False, open_world=True,
        side_effects=["改变论坛主题置顶状态"], readback="telegram.topic.get 返回 pinned=true",
    ),
    Capability(
        "topic.unpin", "取消置顶论坛主题", "topics", "取消指定论坛主题的置顶状态。",
        "write", "external",
        anchor(MSG + "TopicsController.java", r"public void pinTopic", "domain", "主题控制器支持取消置顶。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_forum.java", r"class TL_messages_updatePinnedForumTopic", "backend", "同一请求幂等取消置顶。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "论坛主题 ID。", minimum=1)), required=["peer", "topic_id"], read_only=False, open_world=True,
        side_effects=["改变论坛主题置顶状态"], readback="telegram.topic.get 返回 pinned=false",
    ),
    Capability(
        "topic.delete", "删除论坛主题", "topics", "删除主题及其消息历史；General 主题不可删除。",
        "write", "destructive",
        anchor(MSG + "TopicsController.java", r"private void deleteTopic", "domain", "主题控制器按 affectedHistory offset 收敛删除。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_forum.java", r"class TL_messages_deleteTopicHistory", "backend", "MTProto 提供主题历史删除。"),
        properties=props(peer=copy.deepcopy(PEER), topic_id=schema_property("integer", "论坛主题 ID；必须大于 1。", minimum=2), _confirm=copy.deepcopy(CONFIRM)),
        required=["peer", "topic_id", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["永久删除主题及消息历史"], readback="telegram.topic.get 返回 TOPIC_NOT_FOUND",
    ),
    Capability(
        "folder.list", "列出自定义聊天文件夹", "folders", "从服务器读取默认及自定义聊天文件夹、完整筛选条件和排序。",
        "read", "read",
        anchor(UI + "FiltersSetupActivity.java", r"class FiltersSetupActivity", "ui", "GUI 文件夹列表和排序入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getDialogFilters", "backend", "MTProto 返回云端文件夹定义。"),
        properties=props(), required=[], outputs=["文件夹 ID", "标题", "分类规则", "包含/排除/置顶 peer", "排序"],
    ),
    Capability(
        "folder.get", "读取聊天文件夹", "folders", "按稳定 folder_id 精确读取一个服务器聊天文件夹。",
        "read", "read",
        anchor(UI + "FilterCreateActivity.java", r"class FilterCreateActivity", "ui", "GUI 文件夹编辑入口。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getDialogFilters", "backend", "同一服务器列表可按 ID 精确回读。"),
        properties=props(folder_id=schema_property("integer", "自定义文件夹 ID。", minimum=2, maximum=255)),
        required=["folder_id"], outputs=["完整筛选规则与 peer 列表"],
    ),
    Capability(
        "folder.upsert", "创建或完整替换聊天文件夹", "folders", "创建自定义文件夹，或在 replace=true 时原子替换指定文件夹的完整筛选规则。",
        "write", "external",
        anchor(UI + "FilterCreateActivity.java", r"TL_messages_updateDialogFilter req", "ui", "GUI 文件夹编辑页提交完整过滤器。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_updateDialogFilter", "backend", "MTProto 写入完整文件夹过滤器。"),
        properties=props(
            folder_id=schema_property("integer", "可选稳定自定义文件夹 ID；省略时分配空闲 ID。", minimum=2, maximum=255),
            title=schema_property("string", "文件夹标题；Telegram 客户端最多允许 12 个 UTF-16 代码单元。", minLength=1, maxLength=12),
            emoticon=schema_property("string", "可选文件夹 emoji。", maxLength=16),
            color=schema_property("integer", "可选 Telegram 文件夹颜色索引。", minimum=0, maximum=7),
            contacts=schema_property("boolean", "包含联系人。", default=False),
            non_contacts=schema_property("boolean", "包含非联系人。", default=False),
            groups=schema_property("boolean", "包含群组。", default=False),
            broadcasts=schema_property("boolean", "包含频道。", default=False),
            bots=schema_property("boolean", "包含机器人。", default=False),
            exclude_muted=schema_property("boolean", "排除静音会话。", default=False),
            exclude_read=schema_property("boolean", "排除已读会话。", default=False),
            exclude_archived=schema_property("boolean", "排除已归档会话。", default=False),
            include_peers=schema_property("array", "显式包含 peer。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
            exclude_peers=schema_property("array", "显式排除 peer。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
            pinned_peers=schema_property("array", "文件夹内置顶 peer；会自动合并到包含列表。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
            replace=schema_property("boolean", "已有 folder_id 时必须显式允许完整替换。", default=False),
            idempotency_key=schema_property("string", "写入意图去重键。", minLength=8, maxLength=128),
        ),
        required=["title", "idempotency_key"], read_only=False, idempotent=True,
        open_world=True, side_effects=["创建或替换云端聊天文件夹"],
        readback="telegram.folder.get 返回服务器完整规则",
    ),
    Capability(
        "folder.delete", "删除聊天文件夹", "folders", "永久删除一个自定义文件夹；不会删除其中的聊天。",
        "write", "destructive",
        anchor(UI + "FilterCreateActivity.java", r"TL_messages_updateDialogFilter req", "ui", "GUI 通过省略 filter 删除文件夹。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_updateDialogFilter", "backend", "MTProto 删除文件夹定义。"),
        properties=props(folder_id=schema_property("integer", "自定义文件夹 ID。", minimum=2, maximum=255), _confirm=copy.deepcopy(CONFIRM)),
        required=["folder_id", "_confirm"], read_only=False, destructive=True,
        idempotent=True, confirmation="_confirm", open_world=True,
        side_effects=["永久删除云端文件夹定义"], readback="telegram.folder.get 返回 FOLDER_NOT_FOUND",
    ),
    Capability(
        "folder.reorder", "重排聊天文件夹", "folders", "用包含全部自定义文件夹且无重复的 ID 列表原子更新排序。",
        "write", "external",
        anchor(UI + "FiltersSetupActivity.java", r"TL_messages_updateDialogFiltersOrder req", "ui", "GUI 拖动排序后提交完整顺序。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_updateDialogFiltersOrder", "backend", "MTProto 更新完整文件夹顺序。"),
        properties=props(folder_ids=schema_property("array", "全部自定义文件夹 ID 的目标顺序。", minItems=1, maxItems=254, uniqueItems=True, items={"type": "integer", "minimum": 2, "maximum": 255})),
        required=["folder_ids"], read_only=False, open_world=True,
        side_effects=["改变云端聊天文件夹排序"], readback="telegram.folder.list 返回精确顺序",
    ),
    Capability(
        "proxy.list", "列出本机代理配置", "proxy", "列出 Telegram 本机代理、选中/启用状态和连通性摘要；密码与 MTProto secret 永不回传。",
        "read", "read",
        anchor(UI + "ProxyListActivity.java", r"SharedConfig\.loadProxyList\(\)", "ui", "GUI 代理页从 SharedConfig 加载代理列表。"),
        anchor(MSG + "SharedConfig.java", r"public static void loadProxyList\(\)", "domain", "SharedConfig 持久化代理列表和当前选择。"),
        properties={}, required=[], preconditions=[], outputs=["稳定代理引用", "类型和地址", "选中/启用状态", "脱敏凭据存在标志"],
    ),
    Capability(
        "proxy.upsert", "添加或修改代理", "proxy", "添加 SOCKS5/MTProto 代理，或按稳定 proxy_id 修改现有代理；不会自动启用新代理且不回传凭据。",
        "write", "write",
        anchor(UI + "ProxySettingsActivity.java", r"SharedConfig\.addProxy\(currentProxyInfo\)", "ui", "GUI 代理编辑页保存 SharedConfig.ProxyInfo。"),
        anchor(MSG + "SharedConfig.java", r"public static ProxyInfo addProxy", "domain", "SharedConfig 对精确代理配置去重并持久化。"),
        properties={
            "proxy_id": schema_property("string", "修改时传 proxy.list 返回的稳定引用；省略则添加。", minLength=3, maxLength=80),
            "type": schema_property("string", "代理类型。", enum=["socks5", "mtproto"]),
            "address": schema_property("string", "代理主机名或 IP。", minLength=1, maxLength=255),
            "port": schema_property("integer", "代理端口。", minimum=1, maximum=65535),
            "username": schema_property("string", "SOCKS5 用户名；可为空。", maxLength=255),
            "password": schema_property("string", "SOCKS5 密码；仅写入，不回传。", maxLength=255),
            "secret": schema_property("string", "MTProto secret；仅写入，不回传。", maxLength=512),
        }, required=["type", "address", "port"], read_only=False, preconditions=[],
        side_effects=["修改本机 Telegram 代理列表"], readback="telegram.proxy.list 返回新稳定引用与脱敏配置",
    ),
    Capability(
        "proxy.select", "选择并启停代理", "proxy", "选择已有代理并精确设置消息连接与通话代理开关；MTProto 代理不允许用于通话。",
        "write", "system",
        anchor(UI + "ProxyListActivity.java", r"ConnectionsManager\.setProxySettings\(useProxySettings", "ui", "GUI 代理页持久化选择并立即应用连接设置。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java", r"static native void native_setProxySettings", "backend", "连接层立即应用全局代理设置。"),
        properties={"proxy_id": schema_property("string", "proxy.list 返回的稳定引用。", minLength=3, maxLength=80), "enabled": schema_property("boolean", "是否启用所选代理。"), "for_calls": schema_property("boolean", "是否也将 SOCKS5 用于通话。", default=False)},
        required=["proxy_id", "enabled"], read_only=False, preconditions=[],
        side_effects=["可能立即改变 Telegram 网络连接路径"], readback="telegram.proxy.list 返回精确 current_proxy_id、enabled 和 for_calls",
    ),
    Capability(
        "proxy.delete", "删除代理", "proxy", "从本机列表删除指定代理；删除当前代理会同时安全禁用代理连接。",
        "write", "destructive",
        anchor(UI + "ProxyListActivity.java", r"SharedConfig\.deleteProxy\(info\)", "ui", "GUI 代理页删除选定代理。"),
        anchor(MSG + "SharedConfig.java", r"public static boolean deleteProxy", "domain", "SharedConfig 删除代理并同步确认列表与当前代理偏好持久化。"),
        properties={"proxy_id": schema_property("string", "proxy.list 返回的稳定引用。", minLength=3, maxLength=80), "_confirm": copy.deepcopy(CONFIRM)},
        required=["proxy_id", "_confirm"], read_only=False, destructive=True, idempotent=False, confirmation="_confirm", preconditions=[],
        side_effects=["永久删除本机代理配置；当前代理会被禁用"], readback="telegram.proxy.list 不再包含 proxy_id",
    ),
    Capability(
        "storage.stats", "读取 Telegram 缓存统计", "storage", "按照片、视频、文档、音乐、语音、Stories、贴纸、临时文件、日志和 MCP 暂存区扫描本机缓存大小；不暴露文件路径。",
        "read", "read",
        anchor(UI + "CacheControlActivity.java", r"private static long getDirectorySize", "ui", "GUI 存储页按媒体目录计算缓存。"),
        anchor(MSG + "FileLoader.java", r"public static final int MEDIA_DIR_CACHE", "domain", "FileLoader 定义 Telegram 受管媒体目录。"),
        properties={}, required=[], preconditions=[], outputs=["分类字节/文件数", "设备可用空间", "扫描失败数"],
    ),
    Capability(
        "storage.cache_clear", "清理选定 Telegram 缓存", "storage", "停止当前下载后清理明确选择的缓存分类；mcp_staging 会同时清除文件目录与分片上传会话，保留草稿目录，刷新 FileLoader/媒体索引，并返回前后独立扫描。",
        "write", "destructive",
        anchor(UI + "CacheControlActivity.java", r"private void cleanupFoldersInternal", "ui", "GUI 存储页按同一媒体分类清理目录并刷新加载器。"),
        anchor(MSG + "FileLoader.java", r"public void clearFilePaths\(\)", "domain", "FileLoader 提供全量清理后的路径数据库失效接口。"),
        properties=props(categories=schema_property("array", "要清理的明确分类。", minItems=1, maxItems=11, uniqueItems=True, items={"type": "string", "enum": ["photos", "videos", "documents", "music", "voice", "stories", "stickers", "other", "temp", "logs", "mcp_staging"]}), _confirm=copy.deepcopy(CONFIRM)),
        required=["categories", "_confirm"], read_only=False, destructive=True, idempotent=True, confirmation="_confirm", preconditions=[],
        side_effects=["删除本机缓存文件并取消当前文件下载"], readback="telegram.storage.stats 返回独立清理后分类大小",
    ),
    Capability(
        "network.usage", "读取本机流量统计", "network", "按移动网络、Wi-Fi 和漫游读取当前账号在本机的消息、媒体、文件和通话流量计数。",
        "read", "read",
        anchor(UI + "DataUsageActivity.java", r"StatsController\.getInstance", "ui", "GUI 流量页读取 StatsController。"),
        anchor(MSG + "StatsController.java", r"public long getSentBytesCount", "domain", "StatsController 提供持久化的分类发送/接收计数。"),
        properties=props(), required=[], outputs=["网络分类", "收发字节和条数", "通话时长", "统计重置日期"],
    ),
    Capability(
        "network.usage_reset", "重置本机流量统计", "network", "显式确认后仅重置指定账号和网络类型的本机流量计数，不影响 Telegram 云端内容。",
        "write", "destructive",
        anchor(UI + "DataUsageActivity.java", r"resetStats\(adapter\.currentType\)", "ui", "GUI 流量页允许按网络类型重置。"),
        anchor(MSG + "StatsController.java", r"public void resetStats\(int networkType\)", "domain", "StatsController 原子清零分类计数并安排持久化。"),
        properties=props(network=schema_property("string", "要重置的网络类型。", enum=["mobile", "wifi", "roaming"]), _confirm=copy.deepcopy(CONFIRM)),
        required=["network", "_confirm"], read_only=False, destructive=True, idempotent=True, confirmation="_confirm",
        side_effects=["不可恢复地清零本机流量历史"], readback="telegram.network.usage 返回该网络 total=0",
    ),
    Capability(
        "settings.get", "读取 Agent 友好的本地设置", "settings", "读取白名单内的显示、播放、流媒体与列表设置。",
        "read", "read",
        anchor(UI + "DataSettingsActivity.java", r"class DataSettingsActivity", "ui", "GUI 数据设置入口。"),
        anchor(MSG + "SharedConfig.java", r"public static boolean isAutoplayVideo\(\)", "domain", "SharedConfig 提供稳定本地设置读取。"),
        properties={"keys": schema_property(
            "array",
            "可选设置键；省略返回全部白名单键。",
            uniqueItems=True,
            items={"type": "string", "enum": SETTING_KEYS},
        )}, required=[], preconditions=[],
        outputs=["设置键、类型、当前值、是否需重启"],
    ),
    Capability(
        "settings.set", "修改 Agent 友好的本地设置", "settings", "幂等修改白名单内的本地设置，不允许任意 SharedPreferences 键。",
        "write", "write",
        anchor(UI + "DataSettingsActivity.java", r"SharedConfig\.", "ui", "GUI 设置页调用 SharedConfig setter/toggle。"),
        anchor(MSG + "SharedConfig.java", r"public static void toggleAutoplayVideo\(\)", "domain", "SharedConfig setter 持久化并触发应用更新。"),
        properties={"values": schema_property(
            "object",
            "键值映射；仅接受下列白名单布尔键。",
            minProperties=1,
            properties={
                key: schema_property("boolean", description)
                for key, description in SETTING_DESCRIPTIONS.items()
            },
            additionalProperties=False,
        )}, required=["values"], read_only=False, preconditions=[],
        side_effects=["修改本机 Telegram 设置"], readback="telegram.settings.get 返回精确新值",
    ),
    Capability(
        "settings.auto_download_get", "读取自动下载策略", "settings", "读取移动网络、Wi-Fi 和漫游的当前预设、媒体上限、聊天类型掩码及预加载行为。",
        "read", "read",
        anchor(UI + "DataAutoDownloadActivity.java", r"getCurrentMobilePreset\(\)", "ui", "GUI 自动下载页读取 DownloadController 预设。"),
        anchor(MSG + "DownloadController.java", r"public Preset getCurrentMobilePreset", "domain", "DownloadController 合成各网络当前有效预设。"),
        properties=props(), required=[], outputs=["每网络预设", "媒体/聊天类型掩码", "大小上限", "预加载选项"],
    ),
    Capability(
        "settings.auto_download_set", "设置自动下载预设", "settings", "将单个网络精确设为关闭、低、中或高预设，持久化本机选择并沿用 Telegram GUI 的云端预设同步路径。",
        "write", "external",
        anchor(UI + "DataAutoDownloadActivity.java", r"savePresetToServer\(currentType\)", "ui", "GUI 离开自动下载页时同步当前网络预设。"),
        anchor(MSG + "DownloadController.java", r"public void savePresetToServer\(int type\)", "domain", "DownloadController 将有效预设转换为 account.saveAutoDownloadSettings。"),
        properties=props(network=schema_property("string", "网络类型。", enum=["mobile", "wifi", "roaming"]), preset=schema_property("string", "目标内置预设。", enum=["off", "low", "medium", "high"])),
        required=["network", "preset"], read_only=False, open_world=True,
        side_effects=["改变本机与账号同步的自动下载策略"], readback="telegram.settings.auto_download_get 返回精确目标预设",
    ),
    Capability(
        "profile.get", "读取自己的完整资料", "profile", "从服务器读取当前账号的姓名、简介、用户名、生日和公开资料状态。",
        "read", "read",
        anchor(UI + "ProfileActivity.java", r"getUserFull\(", "ui", "GUI 资料页读取完整用户资料。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_users_getFullUser", "backend", "MTProto 提供当前用户完整资料读取。"),
        properties=props(), required=[], outputs=["姓名", "简介", "用户名", "生日", "资料状态"],
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
        "profile.username_set", "设置公开用户名", "profile", "设置或清除当前账号的公开 username，并执行服务器读回。",
        "write", "external",
        anchor(UI + "ChangeUsernameActivity.java", r"updateUsername", "ui", "GUI 用户名页提交公开用户名。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateUsername", "backend", "账号 API 提供用户名更新。"),
        properties=props(username=schema_property("string", "新 username；空字符串用于清除。", maxLength=32)),
        required=["username"], read_only=False, open_world=True,
        side_effects=["修改公开用户名和链接"], readback="telegram.profile.get 返回精确 username",
    ),
    Capability(
        "profile.birthday_set", "设置生日", "profile", "设置或清除当前账号生日；清除时省略年月日并传 clear=true。",
        "write", "external",
        anchor(UI + "ProfileActivity.java", r"TL_account\.updateBirthday", "ui", "GUI 资料页提交生日资料。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateBirthday", "backend", "账号 API 提供生日更新。"),
        properties=props(
            year=schema_property("integer", "年份。", minimum=1900, maximum=9999),
            month=schema_property("integer", "月份。", minimum=1, maximum=12),
            day=schema_property("integer", "日期。", minimum=1, maximum=31),
            clear=schema_property("boolean", "是否清除生日。", default=False),
        ),
        required=[], read_only=False, open_world=True,
        side_effects=["修改账号生日资料"], readback="telegram.profile.get 返回精确生日或无生日",
    ),
    Capability(
        "profile.emoji_status_set", "设置账号 emoji status", "profile", "设置带可选到期时间的自定义 emoji status，或显式清除；64 位 document ID 使用字符串避免精度丢失。",
        "write", "external",
        anchor(UI + "bots/SetupEmojiStatusSheet.java", r"TL_account\.updateEmojiStatus", "ui", "GUI emoji status 面板提交同一请求。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateEmojiStatus", "backend", "account.updateEmojiStatus 写入账号状态。"),
        properties=props(
            document_id=schema_property("string", "自定义 emoji 文档 ID；非 clear 时必填。", pattern=r"^[1-9][0-9]{0,18}$", maxLength=19),
            until=schema_property("string", "可选未来 ISO-8601 UTC 到期时间。", format="date-time"),
            clear=schema_property("boolean", "是否清除当前 emoji status。", default=False),
        ),
        required=[], read_only=False, open_world=True,
        side_effects=["修改账号公开 emoji status"],
        readback="telegram.profile.get 返回精确 document_id 和 until 或空状态",
    ),
    Capability(
        "profile.photo_list", "列出历史头像", "profile", "从服务器分页列出当前账号的头像历史，并以字符串返回 64 位 photo ID。",
        "read", "read",
        anchor(UI + "ProfileActivity.java", r"getDialogPhotos", "ui", "GUI 资料页加载历史头像。"),
        anchor(MSG + "MessagesController.java", r"TL_photos_getUserPhotos", "domain", "控制器从服务器分页读取用户头像。"),
        properties=props(offset=schema_property("integer", "分页偏移。", minimum=0, maximum=100000), max_id=schema_property("string", "可选最大 photo ID。", pattern=r"^[1-9][0-9]{0,18}$", maxLength=19), limit=copy.deepcopy(LIMIT)),
        required=[], outputs=["photo ID", "日期", "是否视频头像", "尺寸数量", "分页状态"],
    ),
    Capability(
        "profile.photo_upload", "上传并设置账号头像", "profile", "从 MCP 私有暂存区上传图片并将其设置为当前账号头像。",
        "write", "external",
        anchor(UI + "SettingsActivity.java", r"TL_photos_uploadProfilePhoto", "ui", "GUI 设置页上传账号头像。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_photos_uploadProfilePhoto", "backend", "MTProto 上传并设置资料照片。"),
        properties=props(file_ref=copy.deepcopy(FILE_REF), idempotency_key=schema_property("string", "头像上传意图去重键。", minLength=8, maxLength=128)), required=["file_ref", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["上传图片并修改公开账号头像"], readback="telegram.profile.get 返回上传响应的精确 profile_photo_id",
    ),
    Capability(
        "profile.photo_set", "切换到历史头像", "profile", "把 photo_list 返回的一张历史头像设置为当前账号头像。",
        "write", "external",
        anchor(UI + "PhotoViewer.java", r"TL_photos_updateProfilePhoto", "ui", "GUI 图片查看器可将历史头像设为当前。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_photos_updateProfilePhoto", "backend", "MTProto 设置既有资料照片。"),
        properties=props(photo_id=schema_property("string", "photo_list 返回的 64 位 photo ID。", pattern=r"^[1-9][0-9]{0,18}$", maxLength=19)), required=["photo_id"], read_only=False, open_world=True,
        side_effects=["修改公开账号头像"], readback="telegram.profile.get 返回精确 profile_photo_id",
    ),
    Capability(
        "profile.photo_clear", "清除当前头像", "profile", "清除当前账号头像但不删除头像历史。",
        "write", "destructive",
        anchor(UI + "ProfileActivity.java", r"TL_photos_updateProfilePhoto", "ui", "GUI 资料页可清除或替换当前头像。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_photos_updateProfilePhoto", "backend", "空 InputPhoto 清除当前头像。"),
        properties=props(_confirm=copy.deepcopy(CONFIRM)), required=["_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["移除公开当前头像"], readback="telegram.profile.get 返回 profile_photo_present=false 和 profile_photo_id=0",
    ),
    Capability(
        "profile.photo_delete", "永久删除历史头像", "profile", "永久删除一张明确 photo ID 的账号历史头像。",
        "write", "destructive",
        anchor(UI + "PhotoViewer.java", r"deleteUserPhoto", "ui", "GUI 图片查看器可删除历史头像。"),
        anchor(MSG + "MessagesController.java", r"TL_photos_deletePhotos", "domain", "控制器永久删除指定资料照片。"),
        properties=props(photo_id=schema_property("string", "photo_list 返回的 64 位 photo ID。", pattern=r"^[1-9][0-9]{0,18}$", maxLength=19), _confirm=copy.deepcopy(CONFIRM)),
        required=["photo_id", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["永久删除头像历史中的照片"], readback="telegram.profile.photo_list 不再包含目标 photo_id",
    ),
    Capability(
        "quick_reply.list", "列出快捷回复", "quick_reply", "从服务器列出快捷回复的稳定 ID、名称、顺序、首条消息和消息数量。",
        "read", "read",
        anchor(UI + "Business/QuickRepliesActivity.java", r"class QuickRepliesActivity", "ui", "GUI 快捷回复管理页展示服务器快捷回复。"),
        anchor(UI + "Business/QuickRepliesController.java", r"TL_messages_getQuickReplies req", "domain", "快捷回复控制器从服务器读取完整列表。"),
        properties=props(), required=[], outputs=["shortcut ID", "名称", "顺序", "首条消息", "消息数量"],
    ),
    Capability(
        "quick_reply.get", "读取快捷回复内容", "quick_reply", "按稳定 shortcut ID 或精确名称读取快捷回复及其独立消息空间中的全部消息；省略选择器时返回紧凑快捷回复列表以便发现。",
        "read", "read",
        anchor(UI + "ChatActivity.java", r"MODE_QUICK_REPLIES", "ui", "GUI 使用独立快捷回复聊天模式展示模板消息。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_getQuickReplyMessages", "backend", "MTProto 读取指定快捷回复的服务器消息。"),
        properties=props(shortcut_id=schema_property("integer", "list 返回的 shortcut ID。", minimum=1), shortcut=schema_property("string", "精确快捷回复名称；与 shortcut_id 二选一。", minLength=1, maxLength=32)),
        required=[], outputs=["快捷回复元数据", "规范消息列表"],
    ),
    Capability(
        "quick_reply.create_text", "创建文本快捷回复", "quick_reply", "通过 Telegram 自身发送状态机创建快捷回复及其首条格式化文本消息；hello/away 可供 Business 自动消息使用。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"params\.quick_reply_shortcut =", "ui", "GUI 发送框把新快捷回复名称交给发送状态机。"),
        anchor(MSG + "SendMessagesHelper.java", r"quick_reply_shortcut = quick_reply_shortcut", "domain", "Telegram 发送帮助器写入新快捷回复消息。"),
        properties=props(shortcut=schema_property("string", "1..32 个 Telegram 支持字符的快捷回复名称。", minLength=1, maxLength=32), text=schema_property("string", "首条消息文本。", minLength=1, maxLength=4096), parse_mode=schema_property("string", "plain 或 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"), link_preview=schema_property("boolean", "是否允许链接预览。", default=True), idempotency_key=schema_property("string", "创建意图去重键。", minLength=8, maxLength=128)),
        required=["shortcut", "text", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["创建服务器快捷回复及首条模板消息"], readback="telegram.quick_reply.get 返回精确 shortcut 和消息文本/entities",
    ),
    Capability(
        "quick_reply.message_add_text", "追加快捷回复文本", "quick_reply", "通过 Telegram 自身发送状态机向既有快捷回复追加一条格式化文本消息。",
        "write", "external",
        anchor(UI + "Components/ChatActivityEnterView.java", r"params\.quick_reply_shortcut_id =", "ui", "GUI 发送框把既有 shortcut ID 交给发送状态机。"),
        anchor(MSG + "SendMessagesHelper.java", r"quick_reply_shortcut_id = quick_reply_shortcut_id", "domain", "Telegram 发送帮助器向既有快捷回复追加消息。"),
        properties=props(shortcut_id=schema_property("integer", "list 返回的 shortcut ID。", minimum=1), text=schema_property("string", "新增消息文本。", minLength=1, maxLength=4096), parse_mode=schema_property("string", "plain 或 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"), link_preview=schema_property("boolean", "是否允许链接预览。", default=True), idempotency_key=schema_property("string", "追加意图去重键。", minLength=8, maxLength=128)),
        required=["shortcut_id", "text", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向服务器快捷回复追加模板消息"], readback="telegram.quick_reply.get 返回新增消息的稳定 ID、文本和 entities",
    ),
    Capability(
        "quick_reply.message_edit_text", "编辑快捷回复文本", "quick_reply", "在独立快捷回复消息空间内编辑一条明确 ID 的格式化文本消息。",
        "write", "external",
        anchor(UI + "ChatActivity.java", r"chatMode == MODE_QUICK_REPLIES", "ui", "GUI 快捷回复模式支持消息编辑状态。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"public int quick_reply_shortcut_id;", "backend", "编辑消息请求携带 shortcut ID 以定位独立消息空间。"),
        properties=props(shortcut_id=schema_property("integer", "目标 shortcut ID。", minimum=1), message_id=copy.deepcopy(MESSAGE_ID), text=schema_property("string", "替换后的消息文本。", minLength=1, maxLength=4096), parse_mode=schema_property("string", "plain 或 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"), link_preview=schema_property("boolean", "是否允许链接预览。", default=True)),
        required=["shortcut_id", "message_id", "text"], read_only=False, open_world=True,
        side_effects=["修改快捷回复模板内容"], readback="telegram.quick_reply.get 返回精确 message ID、文本和 entities",
    ),
    Capability(
        "quick_reply.message_delete", "删除快捷回复消息", "quick_reply", "从指定快捷回复中永久删除一至多条明确 ID 的模板消息。",
        "write", "destructive",
        anchor(MSG + "MessagesController.java", r"TL_messages_deleteQuickReplyMessages req", "domain", "Telegram 消息控制器删除快捷回复消息。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_deleteQuickReplyMessages", "backend", "MTProto 按 shortcut ID 和消息 ID 删除模板消息。"),
        properties=props(shortcut_id=schema_property("integer", "目标 shortcut ID。", minimum=1), message_ids=schema_property("array", "要删除的模板消息 ID。", minItems=1, maxItems=100, uniqueItems=True, items=copy.deepcopy(MESSAGE_ID)), _confirm=copy.deepcopy(CONFIRM)),
        required=["shortcut_id", "message_ids", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["永久删除快捷回复模板消息"], readback="telegram.quick_reply.get 不再包含目标消息；删空时 list 不再包含 shortcut",
    ),
    Capability(
        "quick_reply.rename", "重命名快捷回复", "quick_reply", "按稳定 shortcut ID 修改普通快捷回复名称；保留 hello/away Business 名称。",
        "write", "external",
        anchor(UI + "Business/QuickRepliesController.java", r"TL_messages_editQuickReplyShortcut req", "ui", "GUI 控制器提交快捷回复重命名。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_editQuickReplyShortcut", "backend", "MTProto 按稳定 ID 重命名快捷回复。"),
        properties=props(shortcut_id=schema_property("integer", "目标 shortcut ID。", minimum=1), shortcut=schema_property("string", "新名称。", minLength=1, maxLength=32)),
        required=["shortcut_id", "shortcut"], read_only=False, open_world=True,
        side_effects=["修改快捷回复名称"], readback="telegram.quick_reply.get 返回精确新名称",
    ),
    Capability(
        "quick_reply.reorder", "重排快捷回复", "quick_reply", "以完整、无重复的 shortcut ID 列表替换服务器快捷回复顺序。",
        "write", "external",
        anchor(UI + "Business/QuickRepliesController.java", r"TL_messages_reorderQuickReplies req", "ui", "GUI 控制器提交完整快捷回复顺序。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_reorderQuickReplies", "backend", "MTProto 按完整 ID 序列重排。"),
        properties=props(shortcut_ids=schema_property("array", "包含所有当前 shortcut ID 的新顺序。", minItems=1, maxItems=100, uniqueItems=True, items=schema_property("integer", "shortcut ID。", minimum=1)), replace=schema_property("boolean", "确认完整替换顺序。", const=True)),
        required=["shortcut_ids", "replace"], read_only=False, open_world=True,
        side_effects=["修改快捷回复显示顺序"], readback="telegram.quick_reply.list 返回完全相同的 shortcut_ids 顺序",
    ),
    Capability(
        "quick_reply.send", "发送快捷回复", "quick_reply", "把快捷回复的全部或指定模板消息发送到明确 peer，并返回每条新消息的稳定 ID。",
        "write", "external",
        anchor(UI + "Business/QuickRepliesController.java", r"TL_messages_sendQuickReplyMessages req", "ui", "GUI 控制器发送快捷回复到目标会话。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_sendQuickReplyMessages", "backend", "MTProto 原子发送选定模板消息。"),
        properties=props(peer=copy.deepcopy(PEER), shortcut_id=schema_property("integer", "目标 shortcut ID。", minimum=1), message_ids=schema_property("array", "可选模板消息子集；省略时发送全部。", minItems=1, maxItems=100, uniqueItems=True, items=copy.deepcopy(MESSAGE_ID)), idempotency_key=schema_property("string", "发送意图去重键。", minLength=8, maxLength=128)),
        required=["peer", "shortcut_id", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["向外部会话发送一条或多条消息"], readback="telegram.message.get 返回每个新消息 ID，且内容指纹与模板一致",
    ),
    Capability(
        "quick_reply.delete", "删除快捷回复", "quick_reply", "永久删除一个普通快捷回复及其全部模板消息；Business hello/away 必须由对应设置管理。",
        "write", "destructive",
        anchor(UI + "Business/QuickRepliesController.java", r"TL_messages_deleteQuickReplyShortcut req", "ui", "GUI 控制器删除快捷回复。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"class TL_messages_deleteQuickReplyShortcut", "backend", "MTProto 按稳定 ID 删除完整快捷回复。"),
        properties=props(shortcut_id=schema_property("integer", "目标 shortcut ID。", minimum=1), _confirm=copy.deepcopy(CONFIRM)),
        required=["shortcut_id", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["永久删除快捷回复及其模板消息"], readback="telegram.quick_reply.list 不再包含目标 shortcut ID",
    ),
    Capability(
        "business.get", "读取 Business 资料", "business", "从自己的完整服务器资料读取 Business 简介、位置、营业时间、欢迎消息和离开消息状态。",
        "read", "read",
        anchor(UI + "ProfileActivity.java", r"business_work_hours", "ui", "GUI 资料页展示 Business 字段。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java", r"business_intro", "backend", "UserFull 携带服务器 Business 设置。"),
        properties=props(), required=[], outputs=["简介", "位置", "营业时间", "欢迎/离开消息摘要", "Premium 状态"],
    ),
    Capability(
        "business.intro_set", "设置 Business 简介", "business", "设置或清除 Business 欢迎页标题和说明；贴纸保持为空以避免不透明文档引用。",
        "write", "external",
        anchor(UI + "Business/BusinessIntroActivity.java", r"updateBusinessIntro req", "ui", "GUI Business 简介页提交更新。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateBusinessIntro", "backend", "账号 API 写入 Business 简介。"),
        properties=props(title=schema_property("string", "简介标题。", maxLength=32), description=schema_property("string", "简介说明。", maxLength=70), clear=schema_property("boolean", "是否清除。", default=False)), required=[], read_only=False, open_world=True,
        side_effects=["修改账号公开 Business 简介"], readback="telegram.business.get 返回精确 intro",
    ),
    Capability(
        "business.location_set", "设置 Business 位置", "business", "设置或清除 Business 地址，并可同时设置明确经纬度，不读取设备定位。",
        "write", "external",
        anchor(UI + "Business/LocationActivity.java", r"updateBusinessLocation req", "ui", "GUI Business 位置页提交更新。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateBusinessLocation", "backend", "账号 API 写入地址与坐标。"),
        properties=props(address=schema_property("string", "地址文本。", minLength=1, maxLength=96), latitude=schema_property("number", "明确纬度。", minimum=-90, maximum=90), longitude=schema_property("number", "明确经度。", minimum=-180, maximum=180), clear=schema_property("boolean", "是否清除。", default=False)), required=[], read_only=False, open_world=True,
        side_effects=["修改账号公开 Business 地址"], readback="telegram.business.get 返回精确 location",
    ),
    Capability(
        "business.hours_set", "设置 Business 营业时间", "business", "设置 IANA 时区和一周分钟轴上的已排序非重叠开放区间，或清除营业时间。",
        "write", "external",
        anchor(UI + "Business/OpeningHoursActivity.java", r"updateBusinessWorkHours req", "ui", "GUI 营业时间页提交更新。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateBusinessWorkHours", "backend", "账号 API 写入强类型一周开放区间。"),
        properties=props(timezone_id=schema_property("string", "IANA 时区 ID。", minLength=1, maxLength=64), weekly_open=schema_property("array", "一周内开放区间，分钟 0..10080。", minItems=1, maxItems=64, items=schema_property("object", "开放区间。", properties={"start_minute": schema_property("integer", "开始分钟。", minimum=0, maximum=10079), "end_minute": schema_property("integer", "结束分钟。", minimum=1, maximum=10080)}, required=["start_minute", "end_minute"], additionalProperties=False)), clear=schema_property("boolean", "是否清除。", default=False)),
        required=[], read_only=False, open_world=True, side_effects=["修改账号公开营业时间"], readback="telegram.business.get 返回精确时区和开放区间",
    ),
    Capability(
        "business.greeting_set", "设置 Business 欢迎消息", "business", "启用或停用欢迎消息；启用时绑定名为 hello 的快捷回复、无活动天数和明确收件人策略。",
        "write", "external",
        anchor(UI + "Business/GreetMessagesActivity.java", r"updateBusinessGreetingMessage req", "ui", "GUI 欢迎消息页提交服务器设置。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateBusinessGreetingMessage", "backend", "账号 API 写入欢迎消息设置。"),
        properties=props(enabled=schema_property("boolean", "是否启用。"), shortcut_id=schema_property("integer", "名为 hello 的快捷回复 ID；启用时必填。", minimum=1), no_activity_days=schema_property("integer", "触发前无活动天数。", enum=[7, 14, 21, 28], default=7), recipients=copy.deepcopy(BUSINESS_RECIPIENTS_SCHEMA)),
        required=["enabled"], read_only=False, open_world=True,
        side_effects=["修改对新会话自动发送的 Business 欢迎消息"], readback="telegram.business.get 返回精确 shortcut、天数和 recipients；停用时对象为空",
    ),
    Capability(
        "business.away_set", "设置 Business 离开消息", "business", "启用或停用离开消息；启用时绑定名为 away 的快捷回复、发送日程、在线状态条件和收件人策略。",
        "write", "external",
        anchor(UI + "Business/AwayMessagesActivity.java", r"updateBusinessAwayMessage req", "ui", "GUI 离开消息页提交服务器设置。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateBusinessAwayMessage", "backend", "账号 API 写入离开消息设置。"),
        properties=props(enabled=schema_property("boolean", "是否启用。"), shortcut_id=schema_property("integer", "名为 away 的快捷回复 ID；启用时必填。", minimum=1), offline_only=schema_property("boolean", "仅在账号离线时发送。", default=False), schedule=schema_property("string", "发送日程。", enum=["always", "outside_work_hours", "custom"], default="always"), start_date=schema_property("integer", "custom 日程开始 Unix 秒。", minimum=1), end_date=schema_property("integer", "custom 日程结束 Unix 秒。", minimum=1), recipients=copy.deepcopy(BUSINESS_RECIPIENTS_SCHEMA)),
        required=["enabled"], read_only=False, open_world=True,
        side_effects=["修改自动发送的 Business 离开消息"], readback="telegram.business.get 返回精确 shortcut、日程、offline_only 和 recipients；停用时对象为空",
    ),
    Capability(
        "business.bot_list", "列出已连接 Business Bot", "business", "从服务器列出账号连接的 Business Bot、完整权限、会话范围和连接摘要。",
        "read", "read",
        anchor(UI + "Business/ChatbotsActivity.java", r"BusinessChatbotController", "ui", "GUI Business Bot 页加载连接状态。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class getConnectedBots", "backend", "账号 API 返回连接 Bot 全集。"),
        properties=props(), required=[], outputs=["Bot peer", "完整 rights", "recipients", "设备和连接时间摘要"],
    ),
    Capability(
        "business.bot_set", "连接或更新 Business Bot", "business", "连接支持 Business 的 Bot，或完整替换同一 Bot 的 14 项权限与会话范围；不同 Bot 必须先显式删除。",
        "write", "external",
        anchor(UI + "Business/ChatbotsActivity.java", r"updateConnectedBot req", "ui", "GUI Business Bot 页提交连接和权限。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateConnectedBot", "backend", "账号 API 写入 Bot、权限和收件人范围。"),
        properties=props(bot=copy.deepcopy(PEER), rights=copy.deepcopy(BUSINESS_BOT_RIGHTS_SCHEMA), recipients=copy.deepcopy(BUSINESS_BOT_RECIPIENTS_SCHEMA), replace=schema_property("boolean", "确认完整替换权限和范围。", const=True)),
        required=["bot", "rights", "recipients", "replace"], read_only=False, open_world=True,
        side_effects=["授权外部 Bot 代表账号读取或修改选定业务对象"], readback="telegram.business.bot_list 返回精确 bot ID、14 项 rights 和 recipients",
    ),
    Capability(
        "business.bot_delete", "断开 Business Bot", "business", "断开明确 Bot 与当前账号的 Business 连接并撤销其授权。",
        "write", "destructive",
        anchor(UI + "Business/ChatbotsActivity.java", r"req\.deleted = true", "ui", "GUI Business Bot 页显式断开旧 Bot。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class updateConnectedBot", "backend", "deleted 标志撤销 Business Bot 连接。"),
        properties=props(bot=copy.deepcopy(PEER), _confirm=copy.deepcopy(CONFIRM)),
        required=["bot", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["撤销外部 Bot 的 Business 访问"], readback="telegram.business.bot_list 不再包含目标 bot ID",
    ),
    Capability(
        "business.link_list", "列出 Business 聊天链接", "business", "从服务器列出账号的 Business 聊天链接、预填消息、标题和访问次数。",
        "read", "read",
        anchor(UI + "Business/BusinessLinksController.java", r"getBusinessChatLinks req", "ui", "GUI Business 链接控制器加载服务器列表。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class getBusinessChatLinks", "backend", "账号 API 返回 Business 链接全集。"),
        properties=props(), required=[], outputs=["链接", "slug", "预填消息及 entities", "标题", "访问次数"],
    ),
    Capability(
        "business.link_create", "创建 Business 聊天链接", "business", "创建带格式化预填消息和可选标题的幂等 Business 聊天链接。",
        "write", "external",
        anchor(UI + "Business/BusinessLinksController.java", r"createBusinessChatLink req", "ui", "GUI Business 链接控制器创建链接。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class createBusinessChatLink", "backend", "账号 API 返回创建后的规范链接。"),
        properties=props(message=schema_property("string", "打开链接时的预填消息。", maxLength=4096), parse_mode=schema_property("string", "plain 或 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"), title=schema_property("string", "管理标题。", maxLength=32), idempotency_key=schema_property("string", "创建意图去重键。", minLength=8, maxLength=128)), required=["message", "idempotency_key"], read_only=False, idempotent=True, open_world=True,
        side_effects=["创建可公开分享的聊天链接"], readback="telegram.business.link_list 返回精确 slug、消息、entities 和标题",
    ),
    Capability(
        "business.link_edit", "编辑 Business 聊天链接", "business", "按稳定 slug 修改 Business 链接的预填消息和标题。",
        "write", "external",
        anchor(UI + "Business/BusinessLinksController.java", r"editBusinessChatLink req", "ui", "GUI Business 链接控制器编辑链接。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class editBusinessChatLink", "backend", "账号 API 按 slug 编辑链接。"),
        properties=props(slug=schema_property("string", "link_list 返回的 slug。", minLength=1, maxLength=128), message=schema_property("string", "新预填消息。", maxLength=4096), parse_mode=schema_property("string", "plain 或 telegram_markdown。", enum=["plain", "telegram_markdown"], default="plain"), title=schema_property("string", "新管理标题。", maxLength=32)), required=["slug", "message"], read_only=False, open_world=True,
        side_effects=["修改公开 Business 链接行为"], readback="telegram.business.link_list 返回精确新内容",
    ),
    Capability(
        "business.link_delete", "删除 Business 聊天链接", "business", "按稳定 slug 永久删除一个 Business 聊天链接。",
        "write", "destructive",
        anchor(UI + "Business/BusinessLinksController.java", r"deleteBusinessChatLink req", "ui", "GUI Business 链接控制器删除链接。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class deleteBusinessChatLink", "backend", "账号 API 按 slug 删除链接。"),
        properties=props(slug=schema_property("string", "link_list 返回的 slug。", minLength=1, maxLength=128), _confirm=copy.deepcopy(CONFIRM)), required=["slug", "_confirm"], read_only=False, destructive=True, confirmation="_confirm", open_world=True,
        side_effects=["永久使该 Business 链接失效"], readback="telegram.business.link_list 不再包含 slug",
    ),
    Capability(
        "privacy.get", "读取账号隐私规则", "privacy", "按隐私域从服务器读取基础策略、用户/群组例外及 Premium、联系人、Bot 规则。",
        "read", "read",
        anchor(UI + "PrivacyControlActivity.java", r"class PrivacyControlActivity", "ui", "GUI 隐私控制页展示同一规则模型。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class getPrivacy", "backend", "account.getPrivacy 返回服务器规范规则。"),
        properties=props(key=schema_property("string", "隐私域。", enum=["last_seen", "chat_invites", "calls", "phone_p2p", "profile_photo", "forwards", "phone_number", "added_by_phone", "voice_messages", "about", "birthday", "star_gifts_auto_save", "no_paid_messages", "saved_music"])),
        required=["key"], outputs=["基础策略", "允许/拒绝 peer", "Premium/联系人/Bot 规则"],
    ),
    Capability(
        "privacy.set", "完整替换账号隐私规则", "privacy", "用 Agent 友好的基础策略和显式例外完整替换一个隐私域；必须传 replace=true。",
        "write", "external",
        anchor(UI + "PrivacyControlActivity.java", r"TL_account\.setPrivacy req", "ui", "GUI 以完整规则列表提交隐私修改。"),
        anchor("TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_account.java", r"class setPrivacy", "backend", "account.setPrivacy 写入完整规则并返回规范结果。"),
        properties=props(
            key=schema_property("string", "隐私域。", enum=["last_seen", "chat_invites", "calls", "phone_p2p", "profile_photo", "forwards", "phone_number", "added_by_phone", "voice_messages", "about", "birthday", "star_gifts_auto_save", "no_paid_messages", "saved_music"]),
            base=schema_property("string", "基础可见性。", enum=["everybody", "contacts", "nobody"]),
            allow_peers=schema_property("array", "始终允许的用户或群组参与者。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
            disallow_peers=schema_property("array", "始终拒绝的用户或群组参与者。", maxItems=100, uniqueItems=True, items=copy.deepcopy(PEER)),
            allow_close_friends=schema_property("boolean", "允许 Close Friends。", default=False),
            allow_premium=schema_property("boolean", "允许 Premium 用户。", default=False),
            disallow_contacts=schema_property("boolean", "显式拒绝联系人。", default=False),
            bots=schema_property("string", "Bot/Mini App 例外。", enum=["inherit", "allow", "disallow"], default="inherit"),
            replace=schema_property("boolean", "确认完整替换而非字段补丁。", const=True),
        ),
        required=["key", "base", "replace"], read_only=False, open_world=True,
        side_effects=["改变账号资料、通话或联系路径的服务器隐私可见性"],
        readback="telegram.privacy.get 返回语义完全一致的规范规则",
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
    ("qr.scan", "摄像头二维码扫描", "qr", UI + "QrActivity.java", r"Manifest\.permission\.CAMERA", "现场扫码依赖摄像头、运行时权限与实时画面；已有图片应改用 qr.decode_file。"),
    ("biometric.unlock", "生物识别与本地密码锁", "security", UI + "Components/PasscodeView.java", r"BiometricPrompt", "生物识别必须保留系统可信 UI 和用户在场。"),
    ("payments.execute", "最终支付、Premium、Stars 与礼物购买确认", "payments", UI + "PaymentFormActivity.java", r"class PaymentFormActivity", "余额、流水和订阅状态可由 MCP 读取；最终金融交易、商店购买和强认证必须保留可信用户确认。"),
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
    tier = capability.tier
    if tier == "preferred":
        tier = (
            "advanced"
            if capability.destructive or capability.risk in {"external", "system"}
            else "preferred"
            if capability.read_only
            else "standard"
        )
    return {
        "name": capability.tool_name,
        "title": capability.title,
        "description": capability.intent + " 返回结构化成功或可操作错误，不返回认证秘密。",
        "tier": tier,
        "input_schema": {
            "type": "object",
            "properties": properties,
            "required": capability.required,
            "additionalProperties": False,
        },
        "output_schema": output_schema_for(capability.tool_name),
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
