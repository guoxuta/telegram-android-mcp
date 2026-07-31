package org.telegram.messenger.mcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Semantic, Agent-oriented facade over Telegram's Android controllers. */
final class TelegramMcpService {
    private static final long UI_TIMEOUT_SECONDS = 10;
    private static final long REQUEST_TIMEOUT_SECONDS = 35;
    private static final int MAX_LIMIT = 100;
    private static final String IDEMPOTENCY_PREFS = "telegram_mcp_idempotency";
    private static final int MAX_IDEMPOTENCY_ENTRIES = 512;

    private static final Set<String> SETTINGS = new HashSet<>();
    static {
        Collections.addAll(SETTINGS,
                "autoplay_video", "autoplay_gifs", "stream_media", "stream_all_video",
                "stream_mkv", "save_stream_media", "direct_share", "inapp_camera",
                "raise_to_speak", "raise_to_listen", "sort_contacts_by_name",
                "sort_files_by_name", "three_line_layout");
    }

    private final Map<String, SessionRef> sessionRefs = new ConcurrentHashMap<>();

    JsonObject call(String name, JsonObject arguments) throws McpException {
        switch (name) {
            case "telegram.system.health": return health();
            case "telegram.account.list": return accountList();
            case "telegram.account.get_me": return accountGetMe(arguments);
            case "telegram.peer.resolve": return peerResolve(arguments);
            case "telegram.dialog.list": return dialogList(arguments);
            case "telegram.dialog.archive": return dialogFolder(arguments, 1);
            case "telegram.dialog.unarchive": return dialogFolder(arguments, 0);
            case "telegram.dialog.mute": return dialogMute(arguments, true);
            case "telegram.dialog.unmute": return dialogMute(arguments, false);
            case "telegram.dialog.pin": return dialogPin(arguments, true);
            case "telegram.dialog.unpin": return dialogPin(arguments, false);
            case "telegram.dialog.clear_history": return dialogClearHistory(arguments);
            case "telegram.message.history": return messageHistory(arguments);
            case "telegram.message.get": return messageGet(arguments);
            case "telegram.message.scheduled_list": return messageScheduledList(arguments);
            case "telegram.message.search": return messageSearch(arguments);
            case "telegram.message.send_text": return messageSendText(arguments);
            case "telegram.message.edit_text": return messageEditText(arguments);
            case "telegram.message.delete": return messageDelete(arguments);
            case "telegram.message.forward": return messageForward(arguments);
            case "telegram.message.reaction_set": return messageReactionSet(arguments);
            case "telegram.message.mark_read": return messageMarkRead(arguments);
            case "telegram.message.mark_unread": return messageMarkUnread(arguments);
            case "telegram.message.pin": return messagePin(arguments, false);
            case "telegram.message.unpin": return messagePin(arguments, true);
            case "telegram.draft.get": return draftGet(arguments);
            case "telegram.draft.set": return draftSet(arguments, false);
            case "telegram.draft.clear": return draftSet(arguments, true);
            case "telegram.contact.list": return contactList(arguments);
            case "telegram.contact.search": return contactSearch(arguments);
            case "telegram.contact.blocked_list": return contactBlockedList(arguments);
            case "telegram.contact.block": return contactBlock(arguments, true);
            case "telegram.contact.unblock": return contactBlock(arguments, false);
            case "telegram.chat.create_group": return chatCreateGroup(arguments);
            case "telegram.chat.create_channel": return chatCreateChannel(arguments);
            case "telegram.chat.get": return chatGet(arguments);
            case "telegram.chat.members_list": return chatMembersList(arguments);
            case "telegram.chat.update_title": return chatUpdateTitle(arguments);
            case "telegram.chat.update_about": return chatUpdateAbout(arguments);
            case "telegram.chat.leave": return chatLeave(arguments);
            case "telegram.chat.join_public": return chatJoinPublic(arguments);
            case "telegram.settings.get": return settingsGet(arguments);
            case "telegram.settings.set": return settingsSet(arguments);
            case "telegram.profile.update": return profileUpdate(arguments);
            case "telegram.session.list": return sessionList(arguments);
            case "telegram.session.terminate": return sessionTerminate(arguments);
            default:
                throw new McpException("TOOL_NOT_FOUND", "Unknown Telegram MCP tool: " + name, false, null);
        }
    }

    JsonObject health() {
        JsonObject data = new JsonObject();
        data.addProperty("server", "telegram-android-mcp");
        data.addProperty("version", TelegramMcpServer.SERVER_VERSION);
        data.addProperty("protocol_version", TelegramMcpServer.PROTOCOL_VERSION);
        data.addProperty("package", ApplicationLoader.applicationContext.getPackageName());
        data.addProperty("tool_count", 46);
        data.addProperty("selected_account", UserConfig.selectedAccount);
        data.addProperty("network_online", ApplicationLoader.isNetworkOnline());
        JsonArray accounts = new JsonArray();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            JsonObject item = new JsonObject();
            item.addProperty("account", account);
            item.addProperty("activated", UserConfig.getInstance(account).isClientActivated());
            item.addProperty("connection_state", ConnectionsManager.getInstance(account).getConnectionState());
            accounts.add(item);
        }
        data.add("accounts", accounts);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject accountList() throws McpException {
        JsonArray accounts = uiCall(() -> {
            JsonArray result = new JsonArray();
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                UserConfig config = UserConfig.getInstance(account);
                JsonObject item = new JsonObject();
                item.addProperty("account", account);
                item.addProperty("selected", account == UserConfig.selectedAccount);
                item.addProperty("activated", config.isClientActivated());
                TLRPC.User user = config.getCurrentUser();
                if (user != null) {
                    item.add("user", userJson(user));
                }
                result.add(item);
            }
            return result;
        });
        JsonObject data = new JsonObject();
        data.add("accounts", accounts);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject accountGetMe(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        TLRPC.User user = uiCall(() -> UserConfig.getInstance(account).getCurrentUser());
        if (user == null) {
            throw new McpException("NOT_LOGGED_IN", "Account is not logged in", false, null);
        }
        return TelegramMcpServer.successEnvelope(userJson(user));
    }

    private JsonObject peerResolve(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        return TelegramMcpServer.successEnvelope(peerJson(peer));
    }

    private JsonObject dialogList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int folderId = optionalInt(args, "folder_id", 0, 0, 1);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        JsonArray items = uiCall(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(controller.getDialogs(folderId));
            MediaDataController media = MediaDataController.getInstance(account);
            JsonArray result = new JsonArray();
            for (TLRPC.Dialog dialog : dialogs) {
                if (result.size() >= limit) break;
                if (dialog instanceof TLRPC.TL_dialogFolder) continue;
                JsonObject item = new JsonObject();
                item.addProperty("peer", canonicalPeer(controller, dialog.id));
                item.addProperty("dialog_id", Long.toString(dialog.id));
                item.addProperty("title", peerTitle(controller, dialog.id));
                item.addProperty("folder_id", dialog.folder_id);
                item.addProperty("pinned", dialog.pinned);
                item.addProperty("muted", controller.isDialogMuted(dialog.id, 0));
                item.addProperty("unread_count", dialog.unread_count);
                item.addProperty("unread_mark", dialog.unread_mark);
                item.addProperty("top_message_id", dialog.top_message);
                item.addProperty("last_message_date", dialog.last_message_date);
                TLRPC.DraftMessage draft = media.getDraft(dialog.id, 0);
                boolean hasDraft = draft != null
                        && !(draft instanceof TLRPC.TL_draftMessageEmpty)
                        && (!TextUtils.isEmpty(draft.message) || draft.reply_to != null || draft.media != null);
                item.addProperty("has_draft", hasDraft);
                item.addProperty("draft_text", hasDraft && draft.message != null ? draft.message : "");
                item.addProperty("draft_date", hasDraft ? draft.date : 0);
                result.add(item);
            }
            return result;
        });
        JsonObject data = new JsonObject();
        data.addProperty("folder_id", folderId);
        data.addProperty("source", "synchronized_controller_cache");
        data.add("dialogs", items);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject dialogFolder(JsonObject args, int folderId) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int result = uiCall(() -> MessagesController.getInstance(account)
                .addDialogToFolder(peer.dialogId, folderId, -1, 0));
        if (result == 0) {
            throw new McpException("DIALOG_NOT_LOADED",
                    "Target dialog is not present in the synchronized controller cache", true, peerJson(peer));
        }
        JsonObject data = peerJson(peer);
        data.addProperty("folder_id", folderId);
        data.addProperty("accepted", true);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject dialogMute(JsonObject args, boolean mute) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        uiCall(() -> {
            org.telegram.messenger.NotificationsController.getInstance(account)
                    .muteDialog(peer.dialogId, topicId, mute);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.addProperty("muted", mute);
        data.addProperty("topic_id", topicId);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject dialogPin(JsonObject args, boolean pin) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        boolean changed = uiCall(() -> MessagesController.getInstance(account)
                .pinDialog(peer.dialogId, pin, peer.inputPeer, 0));
        if (!changed) {
            throw new McpException("DIALOG_NOT_LOADED",
                    "Target dialog is not present in the synchronized controller cache", true, peerJson(peer));
        }
        JsonObject data = peerJson(peer);
        data.addProperty("pinned", pin);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject dialogClearHistory(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        boolean forEveryone = optionalBoolean(args, "for_everyone", false);
        uiCall(() -> {
            MessagesController.getInstance(account).deleteDialog(peer.dialogId, 1, forEveryone);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.addProperty("for_everyone", forEveryone);
        data.addProperty("accepted", true);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageHistory(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        int offsetId = optionalInt(args, "offset_id", 0, 0, Integer.MAX_VALUE);
        TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = peer.inputPeer;
        request.offset_id = offsetId;
        request.limit = limit;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Messages)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Messages response = (TLRPC.messages_Messages) outcome.response;
        cachePeers(account, response.users, response.chats);
        return messagesEnvelope(account, peer, response.messages, limit);
    }

    private JsonObject messageGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        ArrayList<Integer> ids = requiredIntArray(
                args, "message_ids", 1, 100, 1, Integer.MAX_VALUE);
        TLObject request;
        if (peer.chat != null && ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_getMessages value = new TLRPC.TL_channels_getMessages();
            value.channel = MessagesController.getInputChannel(peer.chat);
            value.id.addAll(ids);
            request = value;
        } else {
            TLRPC.TL_messages_getMessages value = new TLRPC.TL_messages_getMessages();
            value.id.addAll(ids);
            request = value;
        }
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Messages)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Messages response = (TLRPC.messages_Messages) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonObject envelope = messagesEnvelope(account, peer, response.messages, ids.size());
        envelope.getAsJsonObject("data").add("requested_message_ids", intArray(ids));
        return envelope;
    }

    private JsonObject messageScheduledList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        TLRPC.TL_messages_getScheduledHistory request = new TLRPC.TL_messages_getScheduledHistory();
        request.peer = peer.inputPeer;
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Messages)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Messages response = (TLRPC.messages_Messages) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonObject envelope = messagesEnvelope(account, peer, response.messages, response.messages.size());
        envelope.getAsJsonObject("data").addProperty("scheduled", true);
        return envelope;
    }

    private JsonObject messageSearch(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String query = requiredString(args, "query", 1, 512);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        PeerRef peer = null;
        TLRPC.messages_Messages response;
        if (hasNonEmptyString(args, "peer")) {
            peer = resolvePeer(account, args.get("peer").getAsString());
            TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
            request.peer = peer.inputPeer;
            request.q = query;
            request.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            request.limit = limit;
            request.saved_reaction = null;
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.messages_Messages)) {
                throw unexpectedResponse(outcome.response);
            }
            response = (TLRPC.messages_Messages) outcome.response;
        } else {
            TLRPC.TL_messages_searchGlobal request = new TLRPC.TL_messages_searchGlobal();
            request.q = query;
            request.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            request.offset_peer = new TLRPC.TL_inputPeerEmpty();
            request.limit = limit;
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.messages_Messages)) {
                throw unexpectedResponse(outcome.response);
            }
            response = (TLRPC.messages_Messages) outcome.response;
        }
        cachePeers(account, response.users, response.chats);
        JsonObject envelope = messagesEnvelope(account, peer, response.messages, limit);
        envelope.getAsJsonObject("data").addProperty("query", query);
        return envelope;
    }

    private synchronized JsonObject messageSendText(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        String text = requiredString(args, "text", 1, 4096);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = sha256Hex(peer.dialogId + "\n" + text + "\n" +
                optionalInt(args, "reply_to_message_id", 0, 0, Integer.MAX_VALUE) + "\n" +
                optionalBoolean(args, "silent", false) + "\n" + optionalString(args, "schedule_at", ""));
        JsonObject replay = idempotencyReplay(account, "send", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }

        TLRPC.TL_messages_sendMessage request = new TLRPC.TL_messages_sendMessage();
        request.peer = peer.inputPeer;
        request.message = text;
        request.random_id = deterministicLong(account + ":send:" + key);
        request.silent = optionalBoolean(args, "silent", false);
        int replyId = optionalInt(args, "reply_to_message_id", 0, 0, Integer.MAX_VALUE);
        if (replyId != 0) {
            TLRPC.TL_inputReplyToMessage reply = new TLRPC.TL_inputReplyToMessage();
            reply.reply_to_msg_id = replyId;
            request.reply_to = reply;
            request.flags |= 1;
        }
        String schedule = optionalString(args, "schedule_at", "");
        if (!schedule.isEmpty()) {
            long epoch;
            try {
                epoch = Instant.parse(schedule).getEpochSecond();
            } catch (Throwable error) {
                throw new McpException("INVALID_ARGUMENT", "schedule_at must be ISO-8601 UTC time", false, null);
            }
            if (epoch <= ConnectionsManager.getInstance(account).getCurrentTime() || epoch > Integer.MAX_VALUE) {
                throw new McpException("INVALID_ARGUMENT", "schedule_at must be a future representable time", false, null);
            }
            request.schedule_date = (int) epoch;
            request.flags |= 1 << 10;
        }
        RequestOutcome outcome = request(account, request);
        processUpdates(account, outcome.response);
        JsonObject data = peerJson(peer);
        data.addProperty("random_id", Long.toString(request.random_id));
        data.add("message_ids", extractMessageIds(outcome.response));
        data.addProperty("scheduled", request.schedule_date != 0);
        data.addProperty("idempotent_replay", false);
        storeIdempotency(account, "send", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageEditText(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        String text = requiredString(args, "text", 1, 4096);
        TLRPC.TL_messages_editMessage request = new TLRPC.TL_messages_editMessage();
        request.peer = peer.inputPeer;
        request.id = messageId;
        request.message = text;
        request.flags |= 1 << 11;
        RequestOutcome outcome = request(account, request);
        processUpdates(account, outcome.response);
        JsonObject data = peerJson(peer);
        data.addProperty("message_id", messageId);
        data.addProperty("text", text);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        ArrayList<Integer> ids = requiredIntArray(args, "message_ids", 1, 100, 1, Integer.MAX_VALUE);
        boolean forEveryone = optionalBoolean(args, "for_everyone", false);
        uiCall(() -> {
            MessagesController.getInstance(account).deleteMessages(
                    ids, null, null, peer.dialogId, 0, forEveryone, 0);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.add("message_ids", intArray(ids));
        data.addProperty("for_everyone", forEveryone);
        data.addProperty("accepted", true);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject messageForward(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef from = resolvePeer(account, requiredString(args, "from_peer", 1, 256));
        PeerRef to = resolvePeer(account, requiredString(args, "to_peer", 1, 256));
        ArrayList<Integer> ids = requiredIntArray(args, "message_ids", 1, 100, 1, Integer.MAX_VALUE);
        String key = requiredString(args, "idempotency_key", 8, 128);
        boolean silent = optionalBoolean(args, "silent", false);
        String payloadHash = sha256Hex(
                from.dialogId + "\n" + to.dialogId + "\n" + ids + "\n" + silent);
        JsonObject replay = idempotencyReplay(account, "forward", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }

        TLRPC.TL_messages_forwardMessages request = new TLRPC.TL_messages_forwardMessages();
        request.from_peer = from.inputPeer;
        request.to_peer = to.inputPeer;
        request.id.addAll(ids);
        request.silent = silent;
        for (int index = 0; index < ids.size(); index++) {
            request.random_id.add(deterministicLong(account + ":forward:" + key + ":" + index));
        }
        RequestOutcome outcome = request(account, request);
        processUpdates(account, outcome.response);
        JsonObject data = new JsonObject();
        data.add("from", peerJson(from));
        data.add("to", peerJson(to));
        data.add("source_message_ids", intArray(ids));
        data.add("message_ids", extractMessageIds(outcome.response));
        data.addProperty("idempotent_replay", false);
        storeIdempotency(account, "forward", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageReactionSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        String reaction = requiredString(args, "reaction", 0, 32);
        TLRPC.TL_messages_sendReaction request = new TLRPC.TL_messages_sendReaction();
        request.peer = peer.inputPeer;
        request.msg_id = messageId;
        request.add_to_recent = optionalBoolean(args, "add_to_recent", true) && !reaction.isEmpty();
        if (!reaction.isEmpty()) {
            TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
            emoji.emoticon = reaction;
            request.reaction.add(emoji);
            request.flags |= 1;
        }
        RequestOutcome outcome = request(account, request);
        processUpdates(account, outcome.response);
        JsonObject data = peerJson(peer);
        data.addProperty("message_id", messageId);
        data.addProperty("reaction", reaction);
        data.addProperty("removed", reaction.isEmpty());
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageMarkRead(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int maxId = requiredInt(args, "max_message_id", 1, Integer.MAX_VALUE);
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        uiCall(() -> {
            MessagesController.getInstance(account).markDialogAsRead(
                    peer.dialogId, maxId, 0, 0, false, topicId, 0, true, 0);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.addProperty("max_message_id", maxId);
        data.addProperty("topic_id", topicId);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageMarkUnread(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        uiCall(() -> {
            MessagesController.getInstance(account).markDialogAsUnread(peer.dialogId, peer.inputPeer, 0);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.addProperty("unread_mark", true);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messagePin(JsonObject args, boolean unpin) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        boolean notify = optionalBoolean(args, "notify", false);
        uiCall(() -> {
            MessagesController.getInstance(account).pinMessage(
                    peer.chat, peer.user, messageId, unpin, false, notify);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.addProperty("message_id", messageId);
        data.addProperty("pinned", !unpin);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject draftGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        TLRPC.DraftMessage draft = uiCall(() -> MediaDataController.getInstance(account)
                .getDraft(peer.dialogId, topicId));
        JsonObject data = peerJson(peer);
        data.addProperty("topic_id", topicId);
        boolean exists = draft != null
                && !(draft instanceof TLRPC.TL_draftMessageEmpty)
                && (!TextUtils.isEmpty(draft.message) || draft.reply_to != null || draft.media != null);
        data.addProperty("exists", exists);
        data.addProperty("text", exists && draft.message != null ? draft.message : "");
        data.addProperty("date", exists ? draft.date : 0);
        data.addProperty("no_webpage", exists && draft.no_webpage);
        data.addProperty("has_media", exists && draft.media != null);
        data.addProperty("reply_to_message_id",
                exists && draft.reply_to != null ? draft.reply_to.reply_to_msg_id : 0);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject draftSet(JsonObject args, boolean clear) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        String text = clear ? "" : requiredString(args, "text", 0, 4096);
        uiCall(() -> {
            MediaDataController.getInstance(account).saveDraft(
                    peer.dialogId, topicId, text, new ArrayList<>(), null,
                    null, null, 0, false, clear);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.addProperty("topic_id", topicId);
        data.addProperty("text", text);
        data.addProperty("cleared", clear);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int limit = optionalInt(args, "limit", 100, 1, MAX_LIMIT);
        JsonArray contacts = uiCall(() -> {
            JsonArray result = new JsonArray();
            ContactsController controller = ContactsController.getInstance(account);
            MessagesController messages = MessagesController.getInstance(account);
            for (TLRPC.TL_contact contact : new ArrayList<>(controller.contacts)) {
                if (result.size() >= limit) break;
                TLRPC.User user = messages.getUser(contact.user_id);
                if (user != null) result.add(userJson(user));
            }
            return result;
        });
        JsonObject data = new JsonObject();
        data.add("contacts", contacts);
        data.addProperty("source", "synchronized_controller_cache");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactSearch(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String query = requiredString(args, "query", 1, 256).toLowerCase(Locale.ROOT);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        JsonArray contacts = uiCall(() -> {
            JsonArray result = new JsonArray();
            ContactsController controller = ContactsController.getInstance(account);
            MessagesController messages = MessagesController.getInstance(account);
            for (TLRPC.TL_contact contact : new ArrayList<>(controller.contacts)) {
                TLRPC.User user = messages.getUser(contact.user_id);
                if (user == null) continue;
                String haystack = (UserObject.getUserName(user) + " " +
                        (user.username == null ? "" : user.username)).toLowerCase(Locale.ROOT);
                if (haystack.contains(query)) result.add(userJson(user));
                if (result.size() >= limit) break;
            }
            return result;
        });
        JsonObject data = new JsonObject();
        data.addProperty("query", query);
        data.add("contacts", contacts);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactBlockedList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int offset = optionalInt(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        TLRPC.TL_contacts_getBlocked request = new TLRPC.TL_contacts_getBlocked();
        request.offset = offset;
        request.limit = limit;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.contacts_Blocked)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.contacts_Blocked response = (TLRPC.contacts_Blocked) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonArray blocked = new JsonArray();
        for (TLRPC.TL_peerBlocked value : response.blocked) {
            long dialogId = MessageObject.getPeerId(value.peer_id);
            PeerRef peer = localPeer(account, dialogId, "blocked");
            JsonObject item = peerJson(peer);
            item.addProperty("date", value.date);
            blocked.add(item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("offset", offset);
        data.addProperty("limit", limit);
        data.addProperty("count", response instanceof TLRPC.TL_contacts_blockedSlice
                ? response.count : response.blocked.size());
        data.add("blocked", blocked);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactBlock(JsonObject args, boolean block) throws McpException {
        int account = requireActiveAccount(args);
        if (block) requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        TLObject request;
        if (block) {
            TLRPC.TL_contacts_block value = new TLRPC.TL_contacts_block();
            value.id = peer.inputPeer;
            request = value;
        } else {
            TLRPC.TL_contacts_unblock value = new TLRPC.TL_contacts_unblock();
            value.id = peer.inputPeer;
            request = value;
        }
        request(account, request);
        uiCall(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            boolean currentlyBlocked = controller.blockePeers.indexOfKey(peer.dialogId) >= 0;
            if (block && !currentlyBlocked) {
                controller.blockePeers.put(peer.dialogId, 1);
                if (controller.totalBlockedCount >= 0) controller.totalBlockedCount++;
            } else if (!block && currentlyBlocked) {
                controller.blockePeers.delete(peer.dialogId);
                if (controller.totalBlockedCount > 0) controller.totalBlockedCount--;
            }
            NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.blockedUsersDidLoad);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.addProperty("blocked", block);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatCreateGroup(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String title = requiredString(args, "title", 1, 128);
        JsonArray members = requiredArray(args, "members", 1, 200);
        TLRPC.TL_messages_createChat request = new TLRPC.TL_messages_createChat();
        request.title = title;
        for (JsonElement member : members) {
            if (!member.isJsonPrimitive() || !member.getAsJsonPrimitive().isString()) {
                throw new McpException("INVALID_ARGUMENT", "members must contain peer strings", false, null);
            }
            PeerRef peer = resolvePeer(account, member.getAsString());
            if (peer.user == null) {
                throw new McpException("INVALID_ARGUMENT", "Group members must resolve to users", false, peerJson(peer));
            }
            request.users.add(MessagesController.getInstance(account).getInputUser(peer.user));
        }
        RequestOutcome outcome = request(account, request);
        processUpdates(account, outcome.response);
        JsonObject data = new JsonObject();
        data.addProperty("title", title);
        data.add("created_chats", chatsFromResponse(outcome.response));
        if (outcome.response instanceof TLRPC.TL_messages_invitedUsers) {
            TLRPC.TL_messages_invitedUsers value = (TLRPC.TL_messages_invitedUsers) outcome.response;
            data.addProperty("missing_invitees", value.missing_invitees.size());
        }
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatCreateChannel(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String title = requiredString(args, "title", 1, 128);
        String about = optionalString(args, "about", "");
        if (about.length() > 255) invalid("about exceeds 255 characters");
        String kind = requiredString(args, "kind", 1, 32);
        if (!"channel".equals(kind) && !"supergroup".equals(kind)) {
            invalid("kind must be channel or supergroup");
        }
        TLRPC.TL_channels_createChannel request = new TLRPC.TL_channels_createChannel();
        request.title = title;
        request.about = about;
        request.broadcast = "channel".equals(kind);
        request.megagroup = "supergroup".equals(kind);
        RequestOutcome outcome = request(account, request);
        processUpdates(account, outcome.response);
        JsonObject data = new JsonObject();
        data.addProperty("title", title);
        data.addProperty("kind", kind);
        data.add("created_chats", chatsFromResponse(outcome.response));
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        TLObject request;
        if (ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_getFullChannel value = new TLRPC.TL_channels_getFullChannel();
            value.channel = MessagesController.getInputChannel(peer.chat);
            request = value;
        } else {
            TLRPC.TL_messages_getFullChat value = new TLRPC.TL_messages_getFullChat();
            value.chat_id = peer.chat.id;
            request = value;
        }
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_chatFull)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_chatFull response = (TLRPC.TL_messages_chatFull) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonObject data = peerJson(localPeer(account, peer.dialogId, "full_chat"));
        TLRPC.ChatFull full = response.full_chat;
        data.addProperty("about", full.about == null ? "" : full.about);
        data.addProperty("participants_count", full.participants_count);
        data.addProperty("admins_count", full.admins_count);
        data.addProperty("banned_count", full.banned_count);
        data.addProperty("kicked_count", full.kicked_count);
        data.addProperty("online_count", full.online_count);
        data.addProperty("can_view_participants", full.can_view_participants);
        data.addProperty("can_set_username", full.can_set_username);
        data.addProperty("can_set_stickers", full.can_set_stickers);
        data.addProperty("can_view_stats", full.can_view_stats);
        data.addProperty("has_scheduled", full.has_scheduled);
        data.addProperty("pinned_message_id", full.pinned_msg_id);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatMembersList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        int offset = optionalInt(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        String query = optionalString(args, "query", "").trim().toLowerCase(Locale.ROOT);
        JsonArray members = new JsonArray();
        int total;

        if (ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_getParticipants request = new TLRPC.TL_channels_getParticipants();
            request.channel = MessagesController.getInputChannel(peer.chat);
            if (query.isEmpty()) {
                request.filter = new TLRPC.TL_channelParticipantsRecent();
            } else {
                TLRPC.TL_channelParticipantsSearch search = new TLRPC.TL_channelParticipantsSearch();
                search.q = query;
                request.filter = search;
            }
            request.offset = offset;
            request.limit = limit;
            request.hash = 0;
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.channels_ChannelParticipants)) {
                throw unexpectedResponse(outcome.response);
            }
            TLRPC.channels_ChannelParticipants response =
                    (TLRPC.channels_ChannelParticipants) outcome.response;
            cachePeers(account, response.users, response.chats);
            total = response.count;
            for (TLRPC.ChannelParticipant participant : response.participants) {
                long dialogId = participant.peer == null
                        ? participant.user_id : MessageObject.getPeerId(participant.peer);
                if (dialogId == 0) continue;
                JsonObject item = peerJson(localPeer(account, dialogId, "participant"));
                item.addProperty("role", participantRole(participant));
                item.addProperty("date", participant.date);
                item.addProperty("rank", participant.rank == null ? "" : participant.rank);
                members.add(item);
            }
        } else {
            TLRPC.TL_messages_getFullChat request = new TLRPC.TL_messages_getFullChat();
            request.chat_id = peer.chat.id;
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.TL_messages_chatFull)) {
                throw unexpectedResponse(outcome.response);
            }
            TLRPC.TL_messages_chatFull response = (TLRPC.TL_messages_chatFull) outcome.response;
            cachePeers(account, response.users, response.chats);
            ArrayList<TLRPC.ChatParticipant> participants = response.full_chat.participants == null
                    ? new ArrayList<>() : response.full_chat.participants.participants;
            total = participants.size();
            int skipped = 0;
            for (TLRPC.ChatParticipant participant : participants) {
                TLRPC.User user = MessagesController.getInstance(account).getUser(participant.user_id);
                String haystack = user == null ? "" :
                        (UserObject.getUserName(user) + " " +
                                (user.username == null ? "" : user.username)).toLowerCase(Locale.ROOT);
                if (!query.isEmpty() && !haystack.contains(query)) continue;
                if (skipped++ < offset) continue;
                if (members.size() >= limit) break;
                JsonObject item = peerJson(localPeer(account, participant.user_id, "participant"));
                item.addProperty("role", participantRole(participant));
                item.addProperty("date", participant.date);
                item.addProperty("rank", participant.rank == null ? "" : participant.rank);
                members.add(item);
            }
        }
        JsonObject data = peerJson(peer);
        data.addProperty("offset", offset);
        data.addProperty("limit", limit);
        data.addProperty("count", total);
        data.add("members", members);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatUpdateTitle(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        String title = requiredString(args, "title", 1, 128);
        if (peer.chat == null) invalid("peer must be a group or channel");
        TLObject request;
        if (ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_editTitle value = new TLRPC.TL_channels_editTitle();
            value.channel = MessagesController.getInputChannel(peer.chat);
            value.title = title;
            request = value;
        } else {
            TLRPC.TL_messages_editChatTitle value = new TLRPC.TL_messages_editChatTitle();
            value.chat_id = peer.chat.id;
            value.title = title;
            request = value;
        }
        RequestOutcome outcome = request(account, request);
        processUpdates(account, outcome.response);
        uiCall(() -> { peer.chat.title = title; return null; });
        JsonObject data = peerJson(peer);
        data.addProperty("title", title);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatUpdateAbout(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        String about = requiredString(args, "about", 0, 255);
        TLRPC.TL_messages_editChatAbout request = new TLRPC.TL_messages_editChatAbout();
        request.peer = peer.inputPeer;
        request.about = about;
        request(account, request);
        JsonObject data = peerJson(peer);
        data.addProperty("about", about);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatLeave(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        TLObject request;
        if (ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_leaveChannel value = new TLRPC.TL_channels_leaveChannel();
            value.channel = MessagesController.getInputChannel(peer.chat);
            request = value;
        } else {
            TLRPC.TL_messages_deleteChatUser value = new TLRPC.TL_messages_deleteChatUser();
            value.chat_id = peer.chat.id;
            value.user_id = new TLRPC.TL_inputUserSelf();
            request = value;
        }
        RequestOutcome outcome = request(account, request);
        processUpdates(account, outcome.response);
        JsonObject data = peerJson(peer);
        data.addProperty("left", true);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatJoinPublic(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        String raw = requiredString(args, "peer", 1, 256);
        if (!raw.startsWith("@") && !raw.matches("[A-Za-z][A-Za-z0-9_]{3,}")) {
            invalid("join_public requires a public username so the destination is explicit");
        }
        PeerRef peer = resolvePeer(account, raw);
        if (peer.chat == null || !ChatObject.isChannel(peer.chat)) {
            invalid("Public destination must resolve to a channel or supergroup");
        }
        TLRPC.TL_channels_joinChannel request = new TLRPC.TL_channels_joinChannel();
        request.channel = MessagesController.getInputChannel(peer.chat);
        RequestOutcome outcome = request(account, request);
        if (outcome.response instanceof TLRPC.TL_chatInviteJoinResultWebView) {
            throw new McpException("HUMAN_INTERACTION_REQUIRED",
                    "Telegram requires a web confirmation flow for this destination", false, peerJson(peer));
        }
        processUpdates(account, outcome.response);
        JsonObject data = peerJson(peer);
        data.addProperty("joined", true);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject settingsGet(JsonObject args) throws McpException {
        requireActiveAccount(args);
        Set<String> keys = requestedSettingKeys(args);
        JsonObject values = uiCall(() -> {
            JsonObject result = new JsonObject();
            for (String key : keys) result.addProperty(key, getSetting(key));
            return result;
        });
        JsonObject data = new JsonObject();
        data.add("values", values);
        JsonArray allowed = new JsonArray();
        ArrayList<String> sorted = new ArrayList<>(SETTINGS);
        Collections.sort(sorted);
        for (String key : sorted) allowed.add(key);
        data.add("allowed_keys", allowed);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject settingsSet(JsonObject args) throws McpException {
        requireActiveAccount(args);
        if (!args.has("values") || !args.get("values").isJsonObject()) invalid("values must be an object");
        JsonObject values = args.getAsJsonObject("values");
        if (values.size() == 0) invalid("values must not be empty");
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            if (!SETTINGS.contains(entry.getKey())) invalid("Unknown setting key: " + entry.getKey());
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean()) {
                invalid("Setting " + entry.getKey() + " must be boolean");
            }
        }
        JsonObject applied = uiCall(() -> {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                boolean desired = entry.getValue().getAsBoolean();
                if (getSetting(entry.getKey()) != desired) toggleSetting(entry.getKey());
                result.addProperty(entry.getKey(), getSetting(entry.getKey()));
            }
            return result;
        });
        JsonObject data = new JsonObject();
        data.add("values", applied);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject profileUpdate(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        TL_account.updateProfile request = new TL_account.updateProfile();
        if (args.has("first_name")) {
            request.first_name = requiredString(args, "first_name", 1, 64);
            request.flags |= 1;
        }
        if (args.has("last_name")) {
            request.last_name = requiredString(args, "last_name", 0, 64);
            request.flags |= 2;
        }
        if (args.has("about")) {
            request.about = requiredString(args, "about", 0, 70);
            request.flags |= 4;
        }
        if (request.flags == 0) invalid("Provide at least one of first_name, last_name, or about");
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.User)) throw unexpectedResponse(outcome.response);
        TLRPC.User user = (TLRPC.User) outcome.response;
        uiCall(() -> {
            MessagesController.getInstance(account).putUser(user, false);
            UserConfig.getInstance(account).setCurrentUser(user);
            UserConfig.getInstance(account).saveConfig(true);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
            return null;
        });
        return TelegramMcpServer.successEnvelope(userJson(user));
    }

    private JsonObject sessionList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        long now = System.currentTimeMillis();
        sessionRefs.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        RequestOutcome outcome = request(account, new TL_account.getAuthorizations());
        if (!(outcome.response instanceof TL_account.authorizations)) throw unexpectedResponse(outcome.response);
        TL_account.authorizations response = (TL_account.authorizations) outcome.response;
        long expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
        JsonArray sessions = new JsonArray();
        for (TLRPC.TL_authorization authorization : response.authorizations) {
            String ref = UUID.randomUUID().toString().replace("-", "");
            sessionRefs.put(ref, new SessionRef(account, authorization.hash, expiresAt, authorization.current));
            JsonObject item = new JsonObject();
            item.addProperty("session_id", ref);
            item.addProperty("current", authorization.current);
            item.addProperty("official_app", authorization.official_app);
            item.addProperty("device_model", authorization.device_model);
            item.addProperty("platform", authorization.platform);
            item.addProperty("system_version", authorization.system_version);
            item.addProperty("app_name", authorization.app_name);
            item.addProperty("app_version", authorization.app_version);
            item.addProperty("date_created", authorization.date_created);
            item.addProperty("date_active", authorization.date_active);
            item.addProperty("country", authorization.country);
            item.addProperty("region", authorization.region);
            sessions.add(item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("authorization_ttl_days", response.authorization_ttl_days);
        data.addProperty("reference_ttl_seconds", 600);
        data.add("sessions", sessions);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject sessionTerminate(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        String reference = requiredString(args, "session_id", 8, 128);
        SessionRef session = sessionRefs.remove(reference);
        if (session == null || session.account != account || session.expiresAt < System.currentTimeMillis()) {
            throw new McpException("STALE_REFERENCE",
                    "session_id is unknown or expired; call telegram.session.list again", false, null);
        }
        if (session.current) {
            throw new McpException("CURRENT_SESSION_PROTECTED",
                    "The current Android session cannot be terminated through this tool", false, null);
        }
        TL_account.resetAuthorization request = new TL_account.resetAuthorization();
        request.hash = session.hash;
        request(account, request);
        JsonObject data = new JsonObject();
        data.addProperty("session_id", reference);
        data.addProperty("terminated", true);
        return TelegramMcpServer.successEnvelope(data);
    }

    private PeerRef resolvePeer(int account, String raw) throws McpException {
        String value = raw.trim();
        if (value.isEmpty()) invalid("peer must not be empty");
        if ("saved".equalsIgnoreCase(value)) {
            long id = UserConfig.getInstance(account).getClientUserId();
            return localPeer(account, id, "saved");
        }
        if (value.startsWith("user:")) return localPeer(account, parsePositiveId(value, "user:"), "user");
        if (value.startsWith("chat:")) return localPeer(account, -parsePositiveId(value, "chat:"), "chat");
        if (value.startsWith("channel:")) return localPeer(account, -parsePositiveId(value, "channel:"), "channel");
        if (value.startsWith("dialog:")) {
            try {
                long dialogId = Long.parseLong(value.substring("dialog:".length()));
                if (dialogId == 0) invalid("dialog id must not be zero");
                return localPeer(account, dialogId, "dialog");
            } catch (NumberFormatException error) {
                invalid("Invalid dialog peer reference");
            }
        }
        String username = value.startsWith("@") ? value.substring(1) : value;
        if (!username.matches("[A-Za-z][A-Za-z0-9_]{3,}")) {
            invalid("Unsupported peer reference. Use saved, @username, user:, chat:, channel:, or dialog:");
        }
        TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
        request.username = username;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_contacts_resolvedPeer)) throw unexpectedResponse(outcome.response);
        TLRPC.TL_contacts_resolvedPeer response = (TLRPC.TL_contacts_resolvedPeer) outcome.response;
        cachePeers(account, response.users, response.chats);
        long dialogId = MessageObject.getPeerId(response.peer);
        return localPeer(account, dialogId, "username");
    }

    private PeerRef localPeer(int account, long dialogId, String source) throws McpException {
        return uiCall(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            TLRPC.User user = dialogId > 0 ? controller.getUser(dialogId) : null;
            TLRPC.Chat chat = dialogId < 0 ? controller.getChat(-dialogId) : null;
            if (dialogId > 0 && user == null) {
                throw new McpException("PEER_NOT_CACHED",
                        "User ID is not in the local access-hash cache; resolve by username first", true, null);
            }
            if (dialogId < 0 && chat == null) {
                throw new McpException("PEER_NOT_CACHED",
                        "Chat/channel ID is not in the local access-hash cache; resolve by username first", true, null);
            }
            TLRPC.InputPeer inputPeer = controller.getInputPeer(dialogId);
            if (inputPeer instanceof TLRPC.TL_inputPeerEmpty) {
                throw new McpException("PEER_UNAVAILABLE", "Telegram could not construct an input peer", true, null);
            }
            return new PeerRef(account, dialogId, source, inputPeer, user, chat);
        });
    }

    private RequestOutcome request(int account, TLObject request) throws McpException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TLObject> response = new AtomicReference<>();
        AtomicReference<TLRPC.TL_error> error = new AtomicReference<>();
        AtomicInteger requestId = new AtomicInteger();
        uiCall(() -> {
            requestId.set(ConnectionsManager.getInstance(account).sendRequest(request, (result, requestError) -> {
                response.set(result);
                error.set(requestError);
                latch.countDown();
            }));
            return null;
        });
        try {
            if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ConnectionsManager.getInstance(account).cancelRequest(requestId.get(), true);
                throw new McpException("TIMEOUT", "Telegram request timed out", true, null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("INTERRUPTED", "Telegram request was interrupted", true, null);
        }
        if (error.get() != null) throw serverError(error.get());
        if (response.get() == null) throw new McpException("EMPTY_RESPONSE", "Telegram returned no response", true, null);
        return new RequestOutcome(response.get());
    }

    private void processUpdates(int account, TLObject response) throws McpException {
        TLRPC.Updates updates = null;
        if (response instanceof TLRPC.Updates) {
            updates = (TLRPC.Updates) response;
        } else if (response instanceof TLRPC.TL_messages_invitedUsers) {
            updates = ((TLRPC.TL_messages_invitedUsers) response).updates;
        } else if (response instanceof TLRPC.TL_chatInviteJoinResultOk) {
            updates = ((TLRPC.TL_chatInviteJoinResultOk) response).updates;
        }
        if (updates != null) {
            TLRPC.Updates finalUpdates = updates;
            uiCall(() -> {
                MessagesController.getInstance(account).processUpdates(finalUpdates, false);
                return null;
            });
        }
    }

    private void cachePeers(int account, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) throws McpException {
        uiCall(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            controller.putUsers(users, false);
            controller.putChats(chats, false);
            return null;
        });
    }

    private JsonObject messagesEnvelope(
            int account,
            PeerRef requestedPeer,
            ArrayList<TLRPC.Message> messages,
            int limit) {
        JsonArray items = new JsonArray();
        int nextOffset = 0;
        for (TLRPC.Message message : messages) {
            items.add(messageJson(account, message));
            nextOffset = message.id;
        }
        JsonObject data = new JsonObject();
        if (requestedPeer != null) data.add("peer", peerJson(requestedPeer));
        data.add("messages", items);
        data.addProperty("limit", limit);
        data.addProperty("next_offset_id", nextOffset);
        return TelegramMcpServer.successEnvelope(data);
    }

    private static JsonObject messageJson(int account, TLRPC.Message message) {
        JsonObject item = new JsonObject();
        long dialogId = MessageObject.getDialogId(message);
        item.addProperty("peer", canonicalPeer(MessagesController.getInstance(account), dialogId));
        item.addProperty("dialog_id", Long.toString(dialogId));
        item.addProperty("message_id", message.id);
        item.addProperty("date", message.date);
        item.addProperty("outgoing", message.out);
        item.addProperty("pinned", message.pinned);
        item.addProperty("scheduled", message.from_scheduled);
        item.addProperty("sender_dialog_id", Long.toString(MessageObject.getPeerId(message.from_id)));
        item.addProperty("text", message.message == null ? "" : message.message);
        item.addProperty("media_type", message.media == null ? "none" : message.media.getClass().getSimpleName());
        item.addProperty("reply_to_message_id",
                message.reply_to == null ? 0 : message.reply_to.reply_to_msg_id);
        JsonArray reactions = new JsonArray();
        if (message.reactions != null) {
            for (TLRPC.ReactionCount value : message.reactions.results) {
                JsonObject reaction = new JsonObject();
                reaction.addProperty("count", value.count);
                reaction.addProperty("chosen", value.chosen);
                reaction.addProperty("chosen_order", value.chosen ? value.chosen_order : -1);
                if (value.reaction instanceof TLRPC.TL_reactionEmoji) {
                    reaction.addProperty("type", "emoji");
                    reaction.addProperty("value", ((TLRPC.TL_reactionEmoji) value.reaction).emoticon);
                } else if (value.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                    reaction.addProperty("type", "custom_emoji");
                    reaction.addProperty("value", Long.toString(
                            ((TLRPC.TL_reactionCustomEmoji) value.reaction).document_id));
                } else if (value.reaction instanceof TLRPC.TL_reactionPaid) {
                    reaction.addProperty("type", "paid");
                    reaction.addProperty("value", "paid");
                } else {
                    reaction.addProperty("type", "unknown");
                    reaction.addProperty("value", "");
                }
                reactions.add(reaction);
            }
        }
        item.add("reactions", reactions);
        return item;
    }

    private JsonArray extractMessageIds(TLObject response) {
        JsonArray ids = new JsonArray();
        if (response instanceof TLRPC.TL_updateShortSentMessage) {
            ids.add(((TLRPC.TL_updateShortSentMessage) response).id);
            return ids;
        }
        if (response instanceof TLRPC.TL_updateShortMessage) {
            ids.add(((TLRPC.TL_updateShortMessage) response).id);
            return ids;
        }
        if (response instanceof TLRPC.TL_updateShortChatMessage) {
            ids.add(((TLRPC.TL_updateShortChatMessage) response).id);
            return ids;
        }
        TLRPC.Updates updates = null;
        if (response instanceof TLRPC.Updates) updates = (TLRPC.Updates) response;
        else if (response instanceof TLRPC.TL_messages_invitedUsers) updates = ((TLRPC.TL_messages_invitedUsers) response).updates;
        if (updates == null) return ids;
        for (TLRPC.Update update : updates.updates) {
            if (update instanceof TL_update.TL_updateNewMessage) {
                ids.add(((TL_update.TL_updateNewMessage) update).message.id);
            } else if (update instanceof TL_update.TL_updateNewChannelMessage) {
                ids.add(((TL_update.TL_updateNewChannelMessage) update).message.id);
            } else if (update instanceof TL_update.TL_updateMessageID) {
                ids.add(((TL_update.TL_updateMessageID) update).id);
            }
        }
        return ids;
    }

    private JsonArray chatsFromResponse(TLObject response) {
        TLRPC.Updates updates = null;
        if (response instanceof TLRPC.Updates) updates = (TLRPC.Updates) response;
        else if (response instanceof TLRPC.TL_messages_invitedUsers) updates = ((TLRPC.TL_messages_invitedUsers) response).updates;
        JsonArray chats = new JsonArray();
        if (updates != null) for (TLRPC.Chat chat : updates.chats) chats.add(chatJson(chat));
        return chats;
    }

    private static JsonObject userJson(TLRPC.User user) {
        JsonObject result = new JsonObject();
        result.addProperty("peer", "user:" + user.id);
        result.addProperty("dialog_id", Long.toString(user.id));
        result.addProperty("user_id", Long.toString(user.id));
        result.addProperty("display_name", UserObject.getUserName(user));
        result.addProperty("first_name", user.first_name == null ? "" : user.first_name);
        result.addProperty("last_name", user.last_name == null ? "" : user.last_name);
        result.addProperty("username", user.username == null ? "" : user.username);
        result.addProperty("self", user.self);
        result.addProperty("contact", user.contact);
        result.addProperty("bot", user.bot);
        result.addProperty("premium", user.premium);
        result.addProperty("deleted", user.deleted);
        return result;
    }

    private static JsonObject chatJson(TLRPC.Chat chat) {
        JsonObject result = new JsonObject();
        boolean channel = ChatObject.isChannel(chat);
        result.addProperty("peer", (channel ? "channel:" : "chat:") + chat.id);
        result.addProperty("dialog_id", Long.toString(-chat.id));
        result.addProperty("chat_id", Long.toString(chat.id));
        result.addProperty("title", chat.title == null ? "" : chat.title);
        result.addProperty("username", chat.username == null ? "" : chat.username);
        result.addProperty("channel", channel);
        result.addProperty("broadcast", chat.broadcast);
        result.addProperty("megagroup", chat.megagroup);
        result.addProperty("creator", chat.creator);
        result.addProperty("left", chat.left);
        result.addProperty("kicked", chat.kicked);
        result.addProperty("participants_count", chat.participants_count);
        return result;
    }

    private static JsonObject peerJson(PeerRef peer) {
        JsonObject result = peer.user != null ? userJson(peer.user) : chatJson(peer.chat);
        result.addProperty("source", peer.source);
        result.addProperty("blocked", MessagesController.getInstance(peer.account)
                .blockePeers.indexOfKey(peer.dialogId) >= 0);
        return result;
    }

    private static String canonicalPeer(MessagesController controller, long dialogId) {
        if (dialogId > 0) return "user:" + dialogId;
        TLRPC.Chat chat = controller.getChat(-dialogId);
        return (ChatObject.isChannel(chat) ? "channel:" : "chat:") + (-dialogId);
    }

    private static String peerTitle(MessagesController controller, long dialogId) {
        if (dialogId > 0) {
            TLRPC.User user = controller.getUser(dialogId);
            return user == null ? "" : UserObject.getUserName(user);
        }
        TLRPC.Chat chat = controller.getChat(-dialogId);
        return chat == null || chat.title == null ? "" : chat.title;
    }

    private static String participantRole(Object participant) {
        String type = participant == null
                ? ""
                : participant.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (type.contains("creator")) return "creator";
        if (type.contains("admin") || type.contains("moderator")) return "administrator";
        if (type.contains("banned") || type.contains("kicked")) return "restricted";
        if (type.contains("left")) return "left";
        return "member";
    }

    private Set<String> requestedSettingKeys(JsonObject args) throws McpException {
        if (!args.has("keys")) return new HashSet<>(SETTINGS);
        JsonArray values = requiredArray(args, "keys", 0, SETTINGS.size());
        Set<String> result = new HashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) invalid("keys must contain strings");
            String key = value.getAsString();
            if (!SETTINGS.contains(key)) invalid("Unknown setting key: " + key);
            result.add(key);
        }
        return result;
    }

    private static boolean getSetting(String key) {
        switch (key) {
            case "autoplay_video": return SharedConfig.isAutoplayVideo();
            case "autoplay_gifs": return SharedConfig.isAutoplayGifs();
            case "stream_media": return SharedConfig.streamMedia;
            case "stream_all_video": return SharedConfig.streamAllVideo;
            case "stream_mkv": return SharedConfig.streamMkv;
            case "save_stream_media": return SharedConfig.saveStreamMedia;
            case "direct_share": return SharedConfig.directShare;
            case "inapp_camera": return SharedConfig.inappCamera;
            case "raise_to_speak": return SharedConfig.raiseToSpeak;
            case "raise_to_listen": return SharedConfig.raiseToListen;
            case "sort_contacts_by_name": return SharedConfig.sortContactsByName;
            case "sort_files_by_name": return SharedConfig.sortFilesByName;
            case "three_line_layout": return SharedConfig.useThreeLinesLayout;
            default: return false;
        }
    }

    private static void toggleSetting(String key) {
        switch (key) {
            case "autoplay_video": SharedConfig.toggleAutoplayVideo(); break;
            case "autoplay_gifs": SharedConfig.toggleAutoplayGifs(); break;
            case "stream_media": SharedConfig.toggleStreamMedia(); break;
            case "stream_all_video": SharedConfig.toggleStreamAllVideo(); break;
            case "stream_mkv": SharedConfig.toggleStreamMkv(); break;
            case "save_stream_media": SharedConfig.toggleSaveStreamMedia(); break;
            case "direct_share": SharedConfig.toggleDirectShare(); break;
            case "inapp_camera": SharedConfig.toggleInappCamera(); break;
            case "raise_to_speak": SharedConfig.toggleRaiseToSpeak(); break;
            case "raise_to_listen": SharedConfig.toggleRaiseToListen(); break;
            case "sort_contacts_by_name": SharedConfig.toggleSortContactsByName(); break;
            case "sort_files_by_name": SharedConfig.toggleSortFilesByName(); break;
            case "three_line_layout": SharedConfig.setUseThreeLinesLayout(!SharedConfig.useThreeLinesLayout); break;
        }
    }

    private JsonObject idempotencyReplay(int account, String operation, String key, String payloadHash) throws McpException {
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences(IDEMPOTENCY_PREFS, Context.MODE_PRIVATE);
        String slot = operation + ":" + sha256Hex(account + ":" + key);
        String stored = prefs.getString(slot, null);
        if (stored == null) return null;
        try {
            JsonObject value = JsonParser.parseString(stored).getAsJsonObject();
            if (!payloadHash.equals(value.get("payload_hash").getAsString())) {
                throw new McpException("IDEMPOTENCY_CONFLICT",
                        "idempotency_key was already used with different arguments", false, null);
            }
            return value.getAsJsonObject("result").deepCopy();
        } catch (McpException error) {
            throw error;
        } catch (Throwable error) {
            prefs.edit().remove(slot).apply();
            return null;
        }
    }

    private void storeIdempotency(int account, String operation, String key, String payloadHash, JsonObject result) {
        SharedPreferences preferences = ApplicationLoader.applicationContext
                .getSharedPreferences(IDEMPOTENCY_PREFS, Context.MODE_PRIVATE);
        if (preferences.getAll().size() >= MAX_IDEMPOTENCY_ENTRIES) {
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                long createdAt = 0;
                try {
                    JsonObject value = JsonParser.parseString(String.valueOf(entry.getValue())).getAsJsonObject();
                    createdAt = value.has("created_at") ? value.get("created_at").getAsLong() : 0;
                } catch (Throwable ignore) {
                    // Corrupt and legacy entries are the safest eviction candidates.
                }
                if (createdAt < oldestTime) {
                    oldestTime = createdAt;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                preferences.edit().remove(oldestKey).apply();
            }
        }
        JsonObject stored = new JsonObject();
        stored.addProperty("payload_hash", payloadHash);
        stored.addProperty("created_at", System.currentTimeMillis());
        stored.add("result", result.deepCopy());
        preferences.edit()
                .putString(operation + ":" + sha256Hex(account + ":" + key), stored.toString())
                .apply();
    }

    private int requireActiveAccount(JsonObject args) throws McpException {
        int account = args.has("account") ? requiredInt(args, "account", 0, UserConfig.MAX_ACCOUNT_COUNT - 1)
                : UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            JsonObject details = new JsonObject();
            details.addProperty("account", account);
            throw new McpException("NOT_LOGGED_IN",
                    "Telegram account slot is not activated; complete login in the Android GUI", false, details);
        }
        return account;
    }

    private static void requireConfirm(JsonObject args) throws McpException {
        if (!args.has("_confirm") || !args.get("_confirm").isJsonPrimitive()
                || !args.get("_confirm").getAsJsonPrimitive().isBoolean()
                || !args.get("_confirm").getAsBoolean()) {
            throw new McpException("CONFIRMATION_REQUIRED",
                    "Set _confirm to the literal boolean true after reviewing the target and effect", false, null);
        }
    }

    private static <T> T uiCall(Callable<T> callable) throws McpException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return callable.call(); }
            catch (McpException error) { throw error; }
            catch (Throwable error) { throw new McpException("UI_OPERATION_FAILED", error.getMessage(), true, null); }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AndroidUtilities.runOnUIThread(() -> {
            try { result.set(callable.call()); }
            catch (Throwable throwable) { error.set(throwable); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new McpException("UI_TIMEOUT", "Telegram UI-thread operation timed out", true, null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("INTERRUPTED", "Telegram operation was interrupted", true, null);
        }
        if (error.get() instanceof McpException) throw (McpException) error.get();
        if (error.get() != null) throw new McpException("UI_OPERATION_FAILED",
                error.get().getMessage() == null ? "UI operation failed" : error.get().getMessage(), true, null);
        return result.get();
    }

    private static McpException serverError(TLRPC.TL_error error) {
        JsonObject details = new JsonObject();
        details.addProperty("telegram_code", error.code);
        details.addProperty("telegram_error", error.text == null ? "UNKNOWN" : error.text);
        boolean retryable = error.code == 420 || error.code >= 500
                || (error.text != null && (error.text.startsWith("FLOOD_WAIT_") || error.text.contains("TIMEOUT")));
        if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
            try { details.addProperty("retry_after_seconds", Integer.parseInt(error.text.substring(11))); }
            catch (Throwable ignore) { }
        }
        return new McpException("TELEGRAM_ERROR",
                error.text == null ? "Telegram rejected the request" : error.text, retryable, details);
    }

    private static McpException unexpectedResponse(TLObject response) {
        JsonObject details = new JsonObject();
        details.addProperty("response_type", response == null ? "null" : response.getClass().getSimpleName());
        return new McpException("UNEXPECTED_RESPONSE", "Telegram returned an unexpected response type", true, details);
    }

    private static String requiredString(JsonObject args, String key, int min, int max) throws McpException {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isString()) {
            invalid(key + " must be a string");
        }
        String value = args.get(key).getAsString();
        if (value.length() < min || value.length() > max) invalid(key + " length must be " + min + ".." + max);
        return value;
    }

    private static String optionalString(JsonObject args, String key, String fallback) throws McpException {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        if (!args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isString()) invalid(key + " must be a string");
        return args.get(key).getAsString();
    }

    private static boolean hasNonEmptyString(JsonObject args, String key) {
        return args.has(key) && args.get(key).isJsonPrimitive()
                && args.get(key).getAsJsonPrimitive().isString() && !args.get(key).getAsString().trim().isEmpty();
    }

    private static boolean optionalBoolean(JsonObject args, String key, boolean fallback) throws McpException {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        if (!args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isBoolean()) invalid(key + " must be boolean");
        return args.get(key).getAsBoolean();
    }

    private static int requiredInt(JsonObject args, String key, int min, int max) throws McpException {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isNumber()) invalid(key + " must be an integer");
        try {
            int value = args.get(key).getAsInt();
            if (value < min || value > max) invalid(key + " must be " + min + ".." + max);
            return value;
        } catch (NumberFormatException error) {
            invalid(key + " must be an integer");
            return 0;
        }
    }

    private static int optionalInt(JsonObject args, String key, int fallback, int min, int max) throws McpException {
        return args.has(key) ? requiredInt(args, key, min, max) : fallback;
    }

    private static long optionalLong(JsonObject args, String key, long fallback, long min, long max) throws McpException {
        if (!args.has(key)) return fallback;
        try {
            long value = args.get(key).getAsLong();
            if (value < min || value > max) invalid(key + " is out of range");
            return value;
        } catch (Throwable error) {
            invalid(key + " must be an integer");
            return fallback;
        }
    }

    private static JsonArray requiredArray(JsonObject args, String key, int min, int max) throws McpException {
        if (!args.has(key) || !args.get(key).isJsonArray()) invalid(key + " must be an array");
        JsonArray value = args.getAsJsonArray(key);
        if (value.size() < min || value.size() > max) invalid(key + " size must be " + min + ".." + max);
        return value;
    }

    private static ArrayList<Integer> requiredIntArray(JsonObject args, String key, int minItems, int maxItems, int min, int max) throws McpException {
        JsonArray array = requiredArray(args, key, minItems, maxItems);
        ArrayList<Integer> result = new ArrayList<>();
        for (JsonElement value : array) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) invalid(key + " must contain integers");
            int number = value.getAsInt();
            if (number < min || number > max) invalid(key + " contains an out-of-range value");
            result.add(number);
        }
        return result;
    }

    private static JsonArray intArray(ArrayList<Integer> values) {
        JsonArray result = new JsonArray();
        for (Integer value : values) result.add(value);
        return result;
    }

    private static long parsePositiveId(String value, String prefix) throws McpException {
        try {
            long id = Long.parseLong(value.substring(prefix.length()));
            if (id <= 0) invalid("Peer ID must be positive after " + prefix);
            return id;
        } catch (NumberFormatException error) {
            invalid("Invalid peer ID after " + prefix);
            return 0;
        }
    }

    private static void invalid(String message) throws McpException {
        throw new McpException("INVALID_ARGUMENT", message, false, null);
    }

    private static long deterministicLong(String value) {
        byte[] digest = sha256(value);
        long result = 0;
        for (int index = 0; index < 8; index++) result = (result << 8) | (digest[index] & 0xffL);
        return result == 0 ? 1 : result;
    }

    private static String sha256Hex(String value) {
        byte[] digest = sha256(value);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte item : digest) builder.append(String.format(Locale.US, "%02x", item & 0xff));
        return builder.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) {
            throw new IllegalStateException(error);
        }
    }

    static final class McpException extends Exception {
        final String code;
        final boolean retryable;
        final JsonElement details;

        McpException(String code, String message, boolean retryable, JsonElement details) {
            super(message == null || message.isEmpty() ? code : message);
            this.code = code;
            this.retryable = retryable;
            this.details = details;
        }
    }

    private static final class PeerRef {
        final int account;
        final long dialogId;
        final String source;
        final TLRPC.InputPeer inputPeer;
        final TLRPC.User user;
        final TLRPC.Chat chat;

        PeerRef(int account, long dialogId, String source, TLRPC.InputPeer inputPeer,
                TLRPC.User user, TLRPC.Chat chat) {
            this.account = account;
            this.dialogId = dialogId;
            this.source = source;
            this.inputPeer = inputPeer;
            this.user = user;
            this.chat = chat;
        }
    }

    private static final class RequestOutcome {
        final TLObject response;
        RequestOutcome(TLObject response) { this.response = response; }
    }

    private static final class SessionRef {
        final int account;
        final long hash;
        final long expiresAt;
        final boolean current;
        SessionRef(int account, long hash, long expiresAt, boolean current) {
            this.account = account;
            this.hash = hash;
            this.expiresAt = expiresAt;
            this.current = current;
        }
    }
}
