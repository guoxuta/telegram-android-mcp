package org.telegram.messenger.mcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Looper;
import android.system.Os;
import android.text.TextUtils;
import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.StatsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.tgnet.tl.TL_forum;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.Components.poll.PollSendParams;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Semantic, Agent-oriented facade over Telegram's Android controllers. */
final class TelegramMcpService {
    private static final long UI_TIMEOUT_SECONDS = 10;
    private static final long REQUEST_TIMEOUT_SECONDS = 35;
    private static final long MEDIA_TIMEOUT_SECONDS = 120;
    private static final int MAX_LIMIT = 100;
    private static final long MAX_STAGED_FILE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_INLINE_FILE_BYTES = 512 * 1024;
    private static final int MAX_UPLOAD_CHUNK_BYTES = 512 * 1024;
    private static final int MAX_FILE_READ_BYTES = 1024 * 1024;
    private static final String IDEMPOTENCY_PREFS = "telegram_mcp_idempotency";
    private static final String STAGED_FILE_PREFS = "telegram_mcp_staged_files";
    private static final String UPLOAD_SESSION_PREFS = "telegram_mcp_upload_sessions";
    private static final int MAX_IDEMPOTENCY_ENTRIES = 512;
    private static final Object IDEMPOTENCY_LOCK = new Object();
    private static final ConcurrentHashMap<String, String> ACTIVE_IDEMPOTENCY =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<IdempotencyContext> CURRENT_IDEMPOTENCY =
            new ThreadLocal<>();
    private static final Set<String> STORAGE_CATEGORIES = new HashSet<>();

    private static final Set<String> SETTINGS = new HashSet<>();
    static {
        Collections.addAll(SETTINGS,
                "autoplay_video", "autoplay_gifs", "stream_media", "stream_all_video",
                "stream_mkv", "save_stream_media", "direct_share", "inapp_camera",
                "raise_to_speak", "raise_to_listen", "sort_contacts_by_name",
                "sort_files_by_name", "three_line_layout");
        Collections.addAll(STORAGE_CATEGORIES,
                "photos", "videos", "documents", "music", "voice",
                "stories", "stickers", "other", "temp", "logs",
                "mcp_staging");
    }

    private static final String SESSION_TERMINATION_PREFS = "telegram_mcp_terminated_sessions";

    private final String referenceSecret;
    private final int toolCount;
    private final ConcurrentHashMap<String, TLRPC.Document> transientDocuments =
            new ConcurrentHashMap<>();

    TelegramMcpService(String referenceSecret, int toolCount) {
        this.referenceSecret = referenceSecret;
        this.toolCount = toolCount;
    }

    JsonObject call(String name, JsonObject arguments) throws McpException {
        switch (name) {
            case "telegram.system.health": return health();
            case "telegram.call.history": return callHistory(arguments);
            case "telegram.call.status": return callStatus(arguments);
            case "telegram.call.mute_set": return callMuteSet(arguments);
            case "telegram.call.hang_up": return callHangUp(arguments);
            case "telegram.account.list": return accountList();
            case "telegram.account.get_me": return accountGetMe(arguments);
            case "telegram.payments.stars_status": return starsStatus(arguments);
            case "telegram.payments.stars_transactions": return starsTransactions(arguments);
            case "telegram.payments.stars_subscriptions": return starsSubscriptions(arguments);
            case "telegram.security.two_step_status": return twoStepStatus(arguments);
            case "telegram.peer.resolve": return peerResolve(arguments);
            case "telegram.dialog.list": return dialogList(arguments);
            case "telegram.dialog.get": return dialogGet(arguments);
            case "telegram.dialog.archive": return dialogFolder(arguments, 1);
            case "telegram.dialog.unarchive": return dialogFolder(arguments, 0);
            case "telegram.dialog.mute": return dialogMute(arguments, true);
            case "telegram.dialog.unmute": return dialogMute(arguments, false);
            case "telegram.notification.peer_get": return notificationPeerGet(arguments);
            case "telegram.notification.peer_set": return notificationPeerSet(arguments);
            case "telegram.notification.global_get": return notificationGlobalGet(arguments);
            case "telegram.notification.global_set": return notificationGlobalSet(arguments);
            case "telegram.notification.reactions_get": return notificationReactionsGet(arguments);
            case "telegram.notification.reactions_set": return notificationReactionsSet(arguments);
            case "telegram.dialog.pin": return dialogPin(arguments, true);
            case "telegram.dialog.unpin": return dialogPin(arguments, false);
            case "telegram.dialog.clear_history": return dialogClearHistory(arguments);
            case "telegram.file.list": return fileList(arguments);
            case "telegram.file.get": return fileGet(arguments);
            case "telegram.file.put_base64": return filePutBase64(arguments);
            case "telegram.file.upload_list": return fileUploadList(arguments);
            case "telegram.file.upload_begin": return fileUploadBegin(arguments);
            case "telegram.file.upload_status": return fileUploadStatus(arguments);
            case "telegram.file.upload_append": return fileUploadAppend(arguments);
            case "telegram.file.upload_commit": return fileUploadCommit(arguments);
            case "telegram.file.upload_cancel": return fileUploadCancel(arguments);
            case "telegram.file.read_base64": return fileReadBase64(arguments);
            case "telegram.file.delete": return fileDelete(arguments);
            case "telegram.file.download_message": return fileDownloadMessage(arguments);
            case "telegram.qr.encode": return qrEncode(arguments);
            case "telegram.qr.decode_file": return qrDecodeFile(arguments);
            case "telegram.message.history": return messageHistory(arguments);
            case "telegram.message.get": return messageGet(arguments);
            case "telegram.message.scheduled_list": return messageScheduledList(arguments);
            case "telegram.message.search": return messageSearch(arguments);
            case "telegram.message.media_search": return messageMediaSearch(arguments);
            case "telegram.message.send_text": return messageSendText(arguments);
            case "telegram.message.send_media": return messageSendMedia(arguments);
            case "telegram.message.send_contact": return messageSendContact(arguments);
            case "telegram.message.send_location": return messageSendLocation(arguments);
            case "telegram.message.send_dice": return messageSendDice(arguments);
            case "telegram.message.send_poll": return messageSendPoll(arguments);
            case "telegram.message.edit_text": return messageEditText(arguments);
            case "telegram.message.edit_caption": return messageEditCaption(arguments);
            case "telegram.message.poll_vote": return messagePollVote(arguments);
            case "telegram.message.poll_close": return messagePollClose(arguments);
            case "telegram.message.delete": return messageDelete(arguments);
            case "telegram.message.forward": return messageForward(arguments);
            case "telegram.message.reaction_set": return messageReactionSet(arguments);
            case "telegram.message.mark_read": return messageMarkRead(arguments);
            case "telegram.message.mark_unread": return messageMarkUnread(arguments);
            case "telegram.message.pin": return messagePin(arguments, false);
            case "telegram.message.unpin": return messagePin(arguments, true);
            case "telegram.bot.button_list": return botButtonList(arguments);
            case "telegram.bot.button_press": return botButtonPress(arguments);
            case "telegram.bot.command_list": return botCommandList(arguments);
            case "telegram.bot.start": return botStart(arguments);
            case "telegram.bot.inline_query": return botInlineQuery(arguments);
            case "telegram.bot.inline_send": return botInlineSend(arguments);
            case "telegram.sticker.favorite_list": return savedDocumentList(arguments, true);
            case "telegram.sticker.favorite_set": return savedDocumentSet(arguments, true);
            case "telegram.sticker.send_saved": return sendSavedDocument(arguments, true);
            case "telegram.sticker.search": return stickerSearch(arguments);
            case "telegram.sticker.send": return stickerSend(arguments);
            case "telegram.sticker.pack_search": return stickerPackSearch(arguments);
            case "telegram.sticker.pack_set": return stickerPackSet(arguments);
            case "telegram.gif.saved_list": return savedDocumentList(arguments, false);
            case "telegram.gif.saved_set": return savedDocumentSet(arguments, false);
            case "telegram.gif.send_saved": return sendSavedDocument(arguments, false);
            case "telegram.story.list": return storyList(arguments);
            case "telegram.story.get": return storyGet(arguments);
            case "telegram.story.can_send": return storyCanSend(arguments);
            case "telegram.story.publish": return storyPublish(arguments);
            case "telegram.story.edit": return storyEdit(arguments);
            case "telegram.story.archive_list": return storyCollectionList(arguments, true);
            case "telegram.story.pinned_list": return storyCollectionList(arguments, false);
            case "telegram.story.views_list": return storyViewsList(arguments);
            case "telegram.story.mark_read": return storyMarkRead(arguments);
            case "telegram.story.reaction_set": return storyReactionSet(arguments);
            case "telegram.story.hide_peer": return storyHidePeer(arguments, true);
            case "telegram.story.unhide_peer": return storyHidePeer(arguments, false);
            case "telegram.story.pin": return storyPin(arguments, true);
            case "telegram.story.unpin": return storyPin(arguments, false);
            case "telegram.story.delete": return storyDelete(arguments);
            case "telegram.draft.get": return draftGet(arguments);
            case "telegram.draft.set": return draftSet(arguments, false);
            case "telegram.draft.clear": return draftSet(arguments, true);
            case "telegram.contact.list": return contactList(arguments);
            case "telegram.contact.get": return contactGet(arguments);
            case "telegram.contact.search": return contactSearch(arguments);
            case "telegram.contact.upsert": return contactUpsert(arguments);
            case "telegram.contact.delete": return contactDelete(arguments);
            case "telegram.contact.blocked_list": return contactBlockedList(arguments);
            case "telegram.contact.block": return contactBlock(arguments, true);
            case "telegram.contact.unblock": return contactBlock(arguments, false);
            case "telegram.chat.create_group": return chatCreateGroup(arguments);
            case "telegram.chat.create_channel": return chatCreateChannel(arguments);
            case "telegram.chat.get": return chatGet(arguments);
            case "telegram.chat.photo_upload": return chatPhotoUpload(arguments);
            case "telegram.chat.photo_clear": return chatPhotoClear(arguments);
            case "telegram.chat.members_list": return chatMembersList(arguments);
            case "telegram.chat.member_get": return chatMemberGet(arguments);
            case "telegram.chat.member_add": return chatMemberAdd(arguments);
            case "telegram.chat.member_remove": return chatMemberRemove(arguments);
            case "telegram.chat.member_admin_set": return chatMemberAdminSet(arguments);
            case "telegram.chat.member_restrict": return chatMemberRestrict(arguments);
            case "telegram.chat.permissions_get": return chatPermissionsGet(arguments);
            case "telegram.chat.permissions_set": return chatPermissionsSet(arguments);
            case "telegram.chat.invite_list": return chatInviteList(arguments);
            case "telegram.chat.invite_create": return chatInviteCreate(arguments);
            case "telegram.chat.invite_revoke": return chatInviteRevoke(arguments);
            case "telegram.chat.join_request_list": return chatJoinRequestList(arguments);
            case "telegram.chat.join_request_decide": return chatJoinRequestDecide(arguments);
            case "telegram.chat.admin_log": return chatAdminLog(arguments);
            case "telegram.chat.username_set": return chatUsernameSet(arguments);
            case "telegram.chat.slow_mode_set": return chatSlowModeSet(arguments);
            case "telegram.chat.auto_delete_set": return chatAutoDeleteSet(arguments);
            case "telegram.chat.reactions_get": return chatReactionsGet(arguments);
            case "telegram.chat.reactions_set": return chatReactionsSet(arguments);
            case "telegram.chat.signatures_set": return chatSignaturesSet(arguments);
            case "telegram.chat.linked_set": return chatLinkedSet(arguments);
            case "telegram.chat.anti_spam_set": return chatBooleanSetting(arguments, "anti_spam");
            case "telegram.chat.participants_hidden_set": return chatBooleanSetting(arguments, "participants_hidden");
            case "telegram.chat.history_visible_set": return chatBooleanSetting(arguments, "history_visible");
            case "telegram.chat.boost_status": return chatBoostStatus(arguments);
            case "telegram.chat.update_title": return chatUpdateTitle(arguments);
            case "telegram.chat.update_about": return chatUpdateAbout(arguments);
            case "telegram.chat.leave": return chatLeave(arguments);
            case "telegram.chat.delete_owned": return chatDeleteOwned(arguments);
            case "telegram.chat.join_public": return chatJoinPublic(arguments);
            case "telegram.topic.list": return topicList(arguments);
            case "telegram.topic.get": return topicGet(arguments);
            case "telegram.topic.create": return topicCreate(arguments);
            case "telegram.topic.update": return topicUpdate(arguments);
            case "telegram.topic.pin": return topicPin(arguments, true);
            case "telegram.topic.unpin": return topicPin(arguments, false);
            case "telegram.topic.delete": return topicDelete(arguments);
            case "telegram.folder.list": return folderList(arguments);
            case "telegram.folder.get": return folderGet(arguments);
            case "telegram.folder.upsert": return folderUpsert(arguments);
            case "telegram.folder.delete": return folderDelete(arguments);
            case "telegram.folder.reorder": return folderReorder(arguments);
            case "telegram.proxy.list": return proxyList(arguments);
            case "telegram.proxy.upsert": return proxyUpsert(arguments);
            case "telegram.proxy.select": return proxySelect(arguments);
            case "telegram.proxy.delete": return proxyDelete(arguments);
            case "telegram.storage.stats": return storageStats(arguments);
            case "telegram.storage.cache_clear": return storageCacheClear(arguments);
            case "telegram.network.usage": return networkUsage(arguments);
            case "telegram.network.usage_reset": return networkUsageReset(arguments);
            case "telegram.settings.get": return settingsGet(arguments);
            case "telegram.settings.set": return settingsSet(arguments);
            case "telegram.settings.auto_download_get": return autoDownloadGet(arguments);
            case "telegram.settings.auto_download_set": return autoDownloadSet(arguments);
            case "telegram.profile.get": return profileGet(arguments);
            case "telegram.profile.update": return profileUpdate(arguments);
            case "telegram.profile.username_set": return profileUsernameSet(arguments);
            case "telegram.profile.birthday_set": return profileBirthdaySet(arguments);
            case "telegram.profile.emoji_status_set": return profileEmojiStatusSet(arguments);
            case "telegram.profile.photo_list": return profilePhotoList(arguments);
            case "telegram.profile.photo_upload": return profilePhotoUpload(arguments);
            case "telegram.profile.photo_set": return profilePhotoSet(arguments);
            case "telegram.profile.photo_clear": return profilePhotoClear(arguments);
            case "telegram.profile.photo_delete": return profilePhotoDelete(arguments);
            case "telegram.quick_reply.list": return quickReplyList(arguments);
            case "telegram.quick_reply.get": return quickReplyGet(arguments);
            case "telegram.quick_reply.create_text": return quickReplyCreateText(arguments);
            case "telegram.quick_reply.message_add_text": return quickReplyMessageAddText(arguments);
            case "telegram.quick_reply.message_edit_text": return quickReplyMessageEditText(arguments);
            case "telegram.quick_reply.message_delete": return quickReplyMessageDelete(arguments);
            case "telegram.quick_reply.rename": return quickReplyRename(arguments);
            case "telegram.quick_reply.reorder": return quickReplyReorder(arguments);
            case "telegram.quick_reply.send": return quickReplySend(arguments);
            case "telegram.quick_reply.delete": return quickReplyDelete(arguments);
            case "telegram.business.get": return businessGet(arguments);
            case "telegram.business.intro_set": return businessIntroSet(arguments);
            case "telegram.business.location_set": return businessLocationSet(arguments);
            case "telegram.business.hours_set": return businessHoursSet(arguments);
            case "telegram.business.greeting_set": return businessGreetingSet(arguments);
            case "telegram.business.away_set": return businessAwaySet(arguments);
            case "telegram.business.bot_list": return businessBotList(arguments);
            case "telegram.business.bot_set": return businessBotSet(arguments);
            case "telegram.business.bot_delete": return businessBotDelete(arguments);
            case "telegram.business.link_list": return businessLinkList(arguments);
            case "telegram.business.link_create": return businessLinkCreate(arguments);
            case "telegram.business.link_edit": return businessLinkEdit(arguments);
            case "telegram.business.link_delete": return businessLinkDelete(arguments);
            case "telegram.privacy.get": return privacyGet(arguments);
            case "telegram.privacy.set": return privacySet(arguments);
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
        data.addProperty("tool_count", toolCount);
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

    private JsonObject callHistory(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = new TLRPC.TL_inputPeerEmpty();
        request.q = "";
        TLRPC.TL_inputMessagesFilterPhoneCalls filter =
                new TLRPC.TL_inputMessagesFilterPhoneCalls();
        filter.missed = optionalBoolean(args, "missed_only", false);
        request.filter = filter;
        request.min_date = optionalInt(args, "min_date", 0, 0, Integer.MAX_VALUE);
        request.max_date = optionalInt(args, "max_date", 0, 0, Integer.MAX_VALUE);
        request.offset_id = optionalInt(
                args, "offset_id", 0, 0, Integer.MAX_VALUE);
        request.add_offset = 0;
        request.limit = limit;
        request.max_id = 0;
        request.min_id = 0;
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Messages)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Messages response = (TLRPC.messages_Messages) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonArray calls = new JsonArray();
        TLRPC.Message last = null;
        for (TLRPC.Message message : response.messages) {
            if (!(message.action instanceof TLRPC.TL_messageActionPhoneCall)) continue;
            calls.add(callHistoryJson(account, message));
            last = message;
        }
        JsonObject data = new JsonObject();
        data.add("calls", calls);
        data.addProperty("count", response.count > 0 ? response.count : calls.size());
        data.addProperty("returned", calls.size());
        data.addProperty("next_offset_id", last == null ? 0 : last.id);
        data.addProperty("source", "telegram_server_messages.search.phone_calls");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject callStatus(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        return TelegramMcpServer.successEnvelope(callStatusData(account));
    }

    private JsonObject callMuteSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean muted = requiredBoolean(args, "muted");
        VoIPService service = VoIPService.getSharedInstance();
        if (service == null) {
            throw new McpException("PRECONDITION_FAILED",
                    "There is no active Telegram call in this app process",
                    false, callStatusData(account));
        }
        requireCallAccount(service, account);
        if (service.isMicMute() != muted) {
            uiCall(() -> {
                VoIPService current = VoIPService.getSharedInstance();
                if (current == null) {
                    throw new McpException("OUTCOME_UNKNOWN",
                            "Call ended before microphone state could be changed",
                            false, callStatusData(account));
                }
                requireCallAccount(current, account);
                current.setMicMute(muted, false, true);
                return null;
            });
        }
        JsonObject readback = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            readback = callStatusData(account);
            if (readback.get("active").getAsBoolean()
                    && readback.get("microphone_muted").getAsBoolean() == muted) {
                String operationId = "call-mute-" + account + "-" + muted;
                addWriteEvidence(readback, operationId, true, true, true, false,
                        "telegram.call.status", accountArguments(account));
                return TelegramMcpServer.successEnvelope(readback);
            }
            sleepReadback("Call microphone-state readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Active call microphone state did not match the requested value",
                true, readback);
    }

    private JsonObject callHangUp(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        VoIPService service = VoIPService.getSharedInstance();
        if (service == null) {
            JsonObject data = callStatusData(account);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        requireCallAccount(service, account);
        CountDownLatch latch = new CountDownLatch(1);
        uiCall(() -> {
            VoIPService current = VoIPService.getSharedInstance();
            if (current == null) {
                latch.countDown();
            } else {
                requireCallAccount(current, account);
                current.hangUp(latch::countDown);
            }
            return null;
        });
        try {
            if (!latch.await(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new McpException("OUTCOME_UNKNOWN",
                        "Call hang-up callback did not complete before timeout; read status before retrying",
                        false, unknownOutcomeDetails("call-hang-up-" + account,
                        "telegram.call.status", accountArguments(account)));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("OUTCOME_UNKNOWN",
                    "Call hang-up wait was interrupted; read status before retrying",
                    false, unknownOutcomeDetails("call-hang-up-" + account,
                    "telegram.call.status", accountArguments(account)));
        }
        JsonObject readback = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            readback = callStatusData(account);
            if (!readback.get("active").getAsBoolean()) {
                readback.addProperty("idempotent_replay", false);
                addWriteEvidence(readback, "call-hang-up-" + account,
                        true, true, true, false,
                        "telegram.call.status", accountArguments(account));
                return TelegramMcpServer.successEnvelope(readback);
            }
            sleepReadback("Call hang-up readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Telegram call service remains active after hang-up",
                true, readback);
    }

    private static JsonObject callStatusData(int account) {
        JsonObject data = new JsonObject();
        data.addProperty("scope", "process_global_voip_service");
        VoIPService service = VoIPService.getSharedInstance();
        int state = service == null ? VoIPService.STATE_ENDED : service.getCallState();
        boolean active = service != null
                && state != VoIPService.STATE_ENDED
                && state != VoIPService.STATE_FAILED;
        int callAccount = active ? service.getAccount() : account;
        data.addProperty("account", callAccount);
        data.addProperty("requested_account", account);
        data.addProperty("account_matches", !active || callAccount == account);
        data.addProperty("active", active);
        data.addProperty("state", callStateName(state));
        data.addProperty("state_code", state);
        data.addProperty("microphone_muted", active && service.isMicMute());
        data.addProperty("video_available", active && service.isVideoAvailable());
        if (active && service.getUser() != null) {
            data.add("peer", userJson(service.getUser()));
        } else if (active && service.getChat() != null) {
            data.add("peer", chatJson(service.getChat()));
        }
        data.addProperty("source", "VoIPService.getSharedInstance");
        return data;
    }

    private static void requireCallAccount(VoIPService service, int requestedAccount)
            throws McpException {
        if (service != null && service.getAccount() != requestedAccount) {
            JsonObject details = callStatusData(requestedAccount);
            throw new McpException("ACCOUNT_MISMATCH",
                    "The active call belongs to a different Telegram account slot",
                    false, details);
        }
    }

    private static String callStateName(int state) {
        if (state == VoIPService.STATE_HANGING_UP) return "hanging_up";
        if (state == VoIPService.STATE_EXCHANGING_KEYS) return "exchanging_keys";
        if (state == VoIPService.STATE_WAITING) return "waiting";
        if (state == VoIPService.STATE_REQUESTING) return "requesting";
        if (state == VoIPService.STATE_WAITING_INCOMING) return "waiting_incoming";
        if (state == VoIPService.STATE_RINGING) return "ringing";
        if (state == VoIPService.STATE_BUSY) return "busy";
        if (state == VoIPService.STATE_WAIT_INIT) return "wait_init";
        if (state == VoIPService.STATE_WAIT_INIT_ACK) return "wait_init_ack";
        if (state == VoIPService.STATE_ESTABLISHED) return "established";
        if (state == VoIPService.STATE_FAILED) return "failed";
        if (state == VoIPService.STATE_RECONNECTING) return "reconnecting";
        if (state == VoIPService.STATE_CREATING) return "creating";
        if (state == VoIPService.STATE_ENDED) return "ended";
        return "unknown";
    }

    private static JsonObject callHistoryJson(int account, TLRPC.Message message) {
        JsonObject data = messageJson(account, message);
        TLRPC.TL_messageActionPhoneCall action =
                (TLRPC.TL_messageActionPhoneCall) message.action;
        data.addProperty("call_id", Long.toString(action.call_id));
        data.addProperty("video", action.video);
        data.addProperty("duration_seconds", action.duration);
        data.addProperty("direction", message.out ? "outgoing" : "incoming");
        data.addProperty("discard_reason", callDiscardReason(action.reason));
        data.addProperty("missed",
                action.reason instanceof TLRPC.TL_phoneCallDiscardReasonMissed);
        return data;
    }

    private static String callDiscardReason(TLRPC.PhoneCallDiscardReason reason) {
        if (reason == null) return "none";
        if (reason instanceof TLRPC.TL_phoneCallDiscardReasonHangup) return "hangup";
        if (reason instanceof TLRPC.TL_phoneCallDiscardReasonBusy) return "busy";
        if (reason instanceof TLRPC.TL_phoneCallDiscardReasonMissed) return "missed";
        if (reason instanceof TLRPC.TL_phoneCallDiscardReasonDisconnect) return "disconnect";
        if (reason instanceof TLRPC.TL_phoneCallDiscardReasonMigrateConferenceCall) {
            return "migrate_conference_call";
        }
        return "unknown";
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

    private JsonObject starsStatus(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean ton = optionalBoolean(args, "ton", false);
        TL_stars.TL_payments_getStarsStatus request =
                new TL_stars.TL_payments_getStarsStatus();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.ton = ton;
        TL_stars.StarsStatus status = requireStarsStatus(
                account, request(account, request).response);
        JsonObject data = starsStatusJson(account, status, ton);
        data.addProperty("source", "telegram_server_payments.getStarsStatus");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject starsTransactions(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String direction = optionalString(args, "direction", "all");
        if (!"all".equals(direction)
                && !"incoming".equals(direction)
                && !"outgoing".equals(direction)) {
            invalid("direction must be all, incoming, or outgoing");
        }
        TL_stars.TL_payments_getStarsTransactions request =
                new TL_stars.TL_payments_getStarsTransactions();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.inbound = "incoming".equals(direction);
        request.outbound = "outgoing".equals(direction);
        request.ascending = optionalBoolean(args, "ascending", false);
        request.ton = optionalBoolean(args, "ton", false);
        request.offset = optionalString(args, "offset", "");
        request.limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        if (hasNonEmptyString(args, "subscription_id")) {
            request.subscription_id = requiredString(
                    args, "subscription_id", 1, 256);
        }
        TL_stars.StarsStatus status = requireStarsStatus(
                account, request(account, request).response);
        JsonObject data = starsStatusJson(account, status, request.ton);
        data.addProperty("direction", direction);
        data.addProperty("ascending", request.ascending);
        data.addProperty("source", "telegram_server_payments.getStarsTransactions");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject starsSubscriptions(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        TL_stars.TL_getStarsSubscriptions request =
                new TL_stars.TL_getStarsSubscriptions();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.offset = optionalString(args, "offset", "");
        request.missing_balance = optionalBoolean(args, "missing_balance_only", false);
        TL_stars.StarsStatus status = requireStarsStatus(
                account, request(account, request).response);
        JsonObject data = starsStatusJson(account, status, false);
        data.addProperty("missing_balance_only", request.missing_balance);
        data.addProperty("source", "telegram_server_payments.getStarsSubscriptions");
        return TelegramMcpServer.successEnvelope(data);
    }

    private TL_stars.StarsStatus requireStarsStatus(int account, TLObject response)
            throws McpException {
        if (!(response instanceof TL_stars.StarsStatus)) {
            throw unexpectedResponse(response);
        }
        TL_stars.StarsStatus status = (TL_stars.StarsStatus) response;
        cachePeers(account, status.users, status.chats);
        return status;
    }

    private JsonObject starsStatusJson(
            int account, TL_stars.StarsStatus status, boolean ton) {
        JsonObject data = new JsonObject();
        data.addProperty("account", account);
        data.addProperty("asset", ton ? "TON" : "STARS");
        data.add("balance", starsAmountJson(status.balance));
        data.addProperty("history_has_more", (status.flags & 1) != 0);
        data.addProperty("next_offset",
                status.next_offset == null ? "" : status.next_offset);
        data.addProperty("subscriptions_have_more", (status.flags & 4) != 0);
        data.addProperty("subscriptions_next_offset",
                status.subscriptions_next_offset == null
                        ? "" : status.subscriptions_next_offset);
        data.addProperty("subscriptions_missing_balance",
                Long.toString(status.subscriptions_missing_balance));
        JsonArray transactions = new JsonArray();
        for (TL_stars.StarsTransaction transaction : status.history) {
            transactions.add(starsTransactionJson(account, transaction));
        }
        data.add("transactions", transactions);
        JsonArray subscriptions = new JsonArray();
        for (TL_stars.StarsSubscription subscription : status.subscriptions) {
            subscriptions.add(starsSubscriptionJson(account, subscription));
        }
        data.add("subscriptions", subscriptions);
        return data;
    }

    private static JsonObject starsAmountJson(TL_stars.StarsAmount amount) {
        JsonObject data = new JsonObject();
        if (amount == null) {
            data.addProperty("asset", "UNKNOWN");
            data.addProperty("units", "0");
            data.addProperty("nanos", 0);
            return data;
        }
        data.addProperty("asset",
                amount instanceof TL_stars.TL_starsTonAmount ? "TON" : "STARS");
        data.addProperty("units", Long.toString(amount.amount));
        data.addProperty("nanos", amount.nanos);
        return data;
    }

    private JsonObject starsTransactionJson(
            int account, TL_stars.StarsTransaction transaction) {
        JsonObject data = new JsonObject();
        data.addProperty("transaction_id", transaction.id == null ? "" : transaction.id);
        data.add("amount", starsAmountJson(transaction.amount));
        data.addProperty("date", transaction.date);
        data.addProperty("title", transaction.title == null ? "" : transaction.title);
        data.addProperty("description",
                transaction.description == null ? "" : transaction.description);
        data.addProperty("refund", transaction.refund);
        data.addProperty("pending", transaction.pending);
        data.addProperty("failed", transaction.failed);
        data.addProperty("gift", transaction.gift);
        data.addProperty("reaction", transaction.reaction);
        data.addProperty("subscription", transaction.subscription);
        data.addProperty("paid_message", transaction.paid_message);
        data.addProperty("premium_gift", transaction.premium_gift);
        data.addProperty("business_transfer", transaction.business_transfer);
        data.addProperty("message_id", transaction.msg_id);
        data.addProperty("has_transaction_url",
                !TextUtils.isEmpty(transaction.transaction_url));
        data.addProperty("peer_type", starsTransactionPeerType(transaction.peer));
        if (transaction.peer instanceof TL_stars.TL_starsTransactionPeer
                && transaction.peer.peer != null) {
            long dialogId = MessageObject.getPeerId(transaction.peer.peer);
            data.addProperty("peer", canonicalPeer(
                    MessagesController.getInstance(account), dialogId));
            data.addProperty("peer_title", peerTitle(
                    MessagesController.getInstance(account), dialogId));
        }
        return data;
    }

    private static String starsTransactionPeerType(
            TL_stars.StarsTransactionPeer peer) {
        if (peer instanceof TL_stars.TL_starsTransactionPeer) return "peer";
        if (peer instanceof TL_stars.TL_starsTransactionPeerAppStore) return "app_store";
        if (peer instanceof TL_stars.TL_starsTransactionPeerPlayMarket) return "play_market";
        if (peer instanceof TL_stars.TL_starsTransactionPeerFragment) return "fragment";
        if (peer instanceof TL_stars.TL_starsTransactionPeerPremiumBot) return "premium_bot";
        if (peer instanceof TL_stars.TL_starsTransactionPeerAds) return "ads";
        if (peer instanceof TL_stars.TL_starsTransactionPeerAPI) return "api";
        if (peer instanceof TL_stars.TL_starsTransactionPeerUnsupported) return "unsupported";
        return "unknown";
    }

    private static JsonObject starsSubscriptionJson(
            int account, TL_stars.StarsSubscription subscription) {
        JsonObject data = new JsonObject();
        data.addProperty("subscription_id",
                subscription.id == null ? "" : subscription.id);
        data.addProperty("canceled", subscription.canceled);
        data.addProperty("can_refill", subscription.can_refulfill);
        data.addProperty("missing_balance", subscription.missing_balance);
        data.addProperty("bot_canceled", subscription.bot_canceled);
        data.addProperty("until_date", subscription.until_date);
        data.addProperty("title", subscription.title == null ? "" : subscription.title);
        data.addProperty("has_invite", !TextUtils.isEmpty(subscription.chat_invite_hash));
        if (subscription.pricing != null) {
            JsonObject pricing = new JsonObject();
            pricing.addProperty("period_seconds", subscription.pricing.period);
            pricing.addProperty("stars", Long.toString(subscription.pricing.amount));
            data.add("pricing", pricing);
        }
        if (subscription.peer != null) {
            long dialogId = MessageObject.getPeerId(subscription.peer);
            data.addProperty("peer", canonicalPeer(
                    MessagesController.getInstance(account), dialogId));
            data.addProperty("peer_title", peerTitle(
                    MessagesController.getInstance(account), dialogId));
        }
        return data;
    }

    private JsonObject twoStepStatus(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        RequestOutcome outcome = request(account, new TL_account.getPassword());
        if (!(outcome.response instanceof TL_account.Password)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_account.Password password = (TL_account.Password) outcome.response;
        JsonObject data = new JsonObject();
        data.addProperty("account", account);
        data.addProperty("enabled", password.has_password);
        data.addProperty("recovery_email_configured", password.has_recovery);
        data.addProperty("secure_values_present", password.has_secure_values);
        data.addProperty("pending_reset_date", password.pending_reset_date);
        data.addProperty("login_email_configured",
                !TextUtils.isEmpty(password.login_email_pattern));
        data.addProperty("sensitive_srp_material_redacted", true);
        data.addProperty("source", "telegram_server_account.getPassword");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject peerResolve(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        return TelegramMcpServer.successEnvelope(peerJson(peer));
    }

    private JsonObject dialogList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int folderId = optionalInt(args, "folder_id", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        int offsetId = optionalInt(args, "offset_id", 0, 0, Integer.MAX_VALUE);
        int offsetDate = optionalInt(args, "offset_date", 0, 0, Integer.MAX_VALUE);
        TLRPC.InputPeer offsetPeer = new TLRPC.TL_inputPeerEmpty();
        if (hasNonEmptyString(args, "offset_peer")) {
            offsetPeer = resolvePeer(account, args.get("offset_peer").getAsString()).inputPeer;
        } else if (offsetId != 0 || offsetDate != 0) {
            invalid("offset_peer is required when offset_id or offset_date is non-zero");
        }

        TLRPC.TL_messages_getDialogs request = new TLRPC.TL_messages_getDialogs();
        request.flags |= 1 << 1;
        request.folder_id = folderId;
        request.offset_id = offsetId;
        request.offset_date = offsetDate;
        request.offset_peer = offsetPeer;
        request.limit = limit;
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Dialogs)
                || outcome.response instanceof TLRPC.TL_messages_dialogsNotModified) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Dialogs response = (TLRPC.messages_Dialogs) outcome.response;
        cachePeers(account, response.users, response.chats);

        Map<Long, Integer> lastDates = new HashMap<>();
        for (TLRPC.Message message : response.messages) {
            long dialogId = MessageObject.getDialogId(message);
            Integer current = lastDates.get(dialogId);
            if (current == null || message.date > current) {
                lastDates.put(dialogId, message.date);
            }
        }
        JsonArray items = new JsonArray();
        TLRPC.Dialog lastDialog = null;
        for (TLRPC.Dialog dialog : response.dialogs) {
            if (dialog instanceof TLRPC.TL_dialogFolder || dialog.peer == null) continue;
            dialog.id = MessageObject.getPeerId(dialog.peer);
            lastDialog = dialog;
            JsonObject item = new JsonObject();
            item.addProperty("peer", canonicalPeer(MessagesController.getInstance(account), dialog.id));
            item.addProperty("dialog_id", Long.toString(dialog.id));
            item.addProperty("title", peerTitle(MessagesController.getInstance(account), dialog.id));
            item.addProperty("folder_id", dialog.folder_id);
            item.addProperty("pinned", dialog.pinned);
            item.addProperty("muted", isNotifySettingsMuted(account, dialog.notify_settings));
            item.addProperty("unread_count", dialog.unread_count);
            item.addProperty("unread_mark", dialog.unread_mark);
            item.addProperty("top_message_id", dialog.top_message);
            item.addProperty("last_message_date", lastDates.getOrDefault(dialog.id, 0));
            TLRPC.DraftMessage draft = dialog.draft;
            boolean hasDraft = draft != null
                    && !(draft instanceof TLRPC.TL_draftMessageEmpty)
                    && (!TextUtils.isEmpty(draft.message) || draft.reply_to != null || draft.media != null);
            item.addProperty("has_draft", hasDraft);
            item.addProperty("draft_text", hasDraft && draft.message != null ? draft.message : "");
            item.addProperty("draft_date", hasDraft ? draft.date : 0);
            items.add(item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("folder_id", folderId);
        data.addProperty("source", "telegram_server_messages.getDialogs");
        data.addProperty("sync_state", "fresh");
        data.addProperty("complete", !(response instanceof TLRPC.TL_messages_dialogsSlice)
                || response.dialogs.size() >= response.count);
        data.addProperty("total_count", response instanceof TLRPC.TL_messages_dialogsSlice
                ? response.count : response.dialogs.size());
        data.addProperty("stale_after_seconds", 0);
        JsonObject next = new JsonObject();
        if (lastDialog != null && items.size() >= limit) {
            next.addProperty("offset_id", lastDialog.top_message);
            next.addProperty("offset_date", lastDates.getOrDefault(lastDialog.id, 0));
            next.addProperty("offset_peer",
                    canonicalPeer(MessagesController.getInstance(account), lastDialog.id));
        }
        data.add("next_cursor", next);
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
        TLRPC.Dialog readback = waitForDialogFolder(account, peer, folderId);
        JsonObject data = peerJson(peer);
        data.addProperty("folder_id", readback.folder_id);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        addWriteEvidence(data,
                "dialog-folder-" + account + "-" + peer.dialogId + "-" + folderId,
                true, true, true, false,
                "telegram.dialog.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject dialogGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        TLRPC.Dialog dialog = fetchPeerDialog(account, peer);
        JsonObject data = peerJson(peer);
        data.addProperty("folder_id", dialog.folder_id);
        data.addProperty("pinned", dialog.pinned);
        data.addProperty("unread_count", dialog.unread_count);
        data.addProperty("unread_mark", dialog.unread_mark);
        data.addProperty("unread_mentions_count", dialog.unread_mentions_count);
        data.addProperty("unread_reactions_count", dialog.unread_reactions_count);
        data.addProperty("top_message_id", dialog.top_message);
        data.addProperty("read_inbox_max_id", dialog.read_inbox_max_id);
        data.addProperty("read_outbox_max_id", dialog.read_outbox_max_id);
        data.addProperty("muted", isNotifySettingsMuted(account, dialog.notify_settings));
        data.addProperty("source", "telegram_server_messages.getPeerDialogs");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject dialogMute(JsonObject args, boolean mute) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireNotificationPeer(peer);
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        uiCall(() -> {
            org.telegram.messenger.NotificationsController.getInstance(account)
                    .muteDialog(peer.dialogId, topicId, mute);
            return null;
        });
        TLRPC.PeerNotifySettings serverSettings = waitForMuteState(
                account, peer, topicId, mute);
        JsonObject data = peerJson(peer);
        data.addProperty("muted", isNotifySettingsMuted(account, serverSettings));
        data.addProperty("topic_id", topicId);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.addProperty("topic_id", topicId);
        addWriteEvidence(data,
                "dialog-mute-" + account + "-" + peer.dialogId + "-" + topicId + "-" + mute,
                true, true, true, false,
                "telegram.notification.peer_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject notificationPeerGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireNotificationPeer(peer);
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        TLRPC.PeerNotifySettings settings = fetchPeerNotifySettings(account, peer, topicId);
        JsonObject data = notificationSettingsJson(account, peer, topicId, settings);
        data.addProperty("source", "telegram_server_account.getNotifySettings");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject notificationPeerSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireNotificationPeer(peer);
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        String[] mutable = {
                "mute_until", "silent", "show_previews", "stories_muted",
                "stories_hide_sender", "sound"
        };
        boolean hasMutation = false;
        for (String key : mutable) hasMutation |= args.has(key);
        if (!hasMutation) {
            throw new McpException("INVALID_ARGUMENT",
                    "At least one notification field must be supplied", false, null);
        }
        TLRPC.PeerNotifySettings before =
                fetchPeerNotifySettings(account, peer, topicId);
        if (notificationSettingsMatch(args, before)) {
            JsonObject data = notificationSettingsJson(
                    account, peer, topicId, before);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }

        TLRPC.TL_inputPeerNotifySettings settings = new TLRPC.TL_inputPeerNotifySettings();
        if (args.has("show_previews")) {
            settings.flags |= 1;
            settings.show_previews = requiredBoolean(args, "show_previews");
        }
        if (args.has("silent")) {
            settings.flags |= 2;
            settings.silent = requiredBoolean(args, "silent");
        }
        if (args.has("mute_until")) {
            settings.flags |= 4;
            settings.mute_until = requiredInt(args, "mute_until", 0, Integer.MAX_VALUE);
        }
        if (args.has("sound")) {
            settings.flags |= 8;
            String sound = requiredString(args, "sound", 1, 32);
            settings.sound = inputNotificationSound(sound);
        }
        if (args.has("stories_muted")) {
            settings.flags |= 64;
            settings.stories_muted = requiredBoolean(args, "stories_muted");
        }
        if (args.has("stories_hide_sender")) {
            settings.flags |= 128;
            settings.stories_hide_sender = requiredBoolean(args, "stories_hide_sender");
        }

        TL_account.updateNotifySettings request = new TL_account.updateNotifySettings();
        request.peer = inputNotifyPeer(peer, topicId);
        request.settings = settings;
        String operationId = "notification-peer-set-" + account + "-"
                + peer.dialogId + "-" + topicId + "-" + UUID.randomUUID();
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.addProperty("topic_id", topicId);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.notification.peer_get", readbackArgs).response,
                "account.updateNotifySettings");

        TLRPC.PeerNotifySettings readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = fetchPeerNotifySettings(account, peer, topicId);
            if (notificationSettingsMatch(args, readback)) break;
            readback = null;
            sleepReadback("Notification-setting readback was interrupted");
        }
        if (readback == null) {
            JsonObject details = peerJson(peer);
            details.addProperty("topic_id", topicId);
            details.add("requested", args.deepCopy());
            throw new McpException("READBACK_FAILED",
                    "Notification settings did not reach the requested server state", true,
                    details);
        }
        uiCall(() -> {
            NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.notificationsSettingsUpdated);
            return null;
        });
        JsonObject data = notificationSettingsJson(account, peer, topicId, readback);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.notification.peer_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject notificationGlobalGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String domain = requiredString(args, "domain", 1, 16);
        TLRPC.PeerNotifySettings settings = fetchGlobalNotifySettings(account, domain);
        JsonObject data = globalNotificationJson(account, domain, settings);
        data.addProperty("source", "telegram_server_account.getNotifySettings");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject notificationGlobalSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String domain = requiredString(args, "domain", 1, 16);
        inputGlobalNotifyPeer(domain);
        boolean stories = "stories".equals(domain);
        String[] generalFields = {"mute_until", "show_previews", "sound"};
        String[] storyFields = {"stories_muted", "stories_hide_sender", "stories_sound"};
        boolean mutation = false;
        for (String field : generalFields) mutation |= args.has(field);
        for (String field : storyFields) mutation |= args.has(field);
        if (!mutation) invalid("At least one global notification field is required");
        if (stories) {
            for (String field : generalFields) {
                if (args.has(field)) invalid(field + " is not valid for stories domain");
            }
        } else {
            for (String field : storyFields) {
                if (args.has(field)) invalid(field + " is only valid for stories domain");
            }
        }
        TLRPC.TL_inputPeerNotifySettings settings =
                new TLRPC.TL_inputPeerNotifySettings();
        if (args.has("show_previews")) {
            settings.flags |= 1;
            settings.show_previews = requiredBoolean(args, "show_previews");
        }
        if (args.has("mute_until")) {
            settings.flags |= 4;
            settings.mute_until = requiredInt(
                    args, "mute_until", 0, Integer.MAX_VALUE);
        }
        if (args.has("sound")) {
            settings.flags |= 8;
            settings.sound = inputNotificationSound(
                    requiredString(args, "sound", 1, 32));
        }
        if (args.has("stories_muted")) {
            settings.flags |= 64;
            settings.stories_muted = requiredBoolean(args, "stories_muted");
        }
        if (args.has("stories_hide_sender")) {
            settings.flags |= 128;
            settings.stories_hide_sender =
                    requiredBoolean(args, "stories_hide_sender");
        }
        if (args.has("stories_sound")) {
            settings.flags |= 256;
            settings.stories_sound = inputNotificationSound(
                    requiredString(args, "stories_sound", 1, 32));
        }
        TL_account.updateNotifySettings request =
                new TL_account.updateNotifySettings();
        request.peer = inputGlobalNotifyPeer(domain);
        request.settings = settings;
        String operationId = "notification-global-set-" + account + "-"
                + domain + "-" + UUID.randomUUID();
        JsonObject readbackArgs = accountArguments(account);
        readbackArgs.addProperty("domain", domain);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.notification.global_get", readbackArgs).response,
                "account.updateNotifySettings(global)");
        TLRPC.PeerNotifySettings readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = fetchGlobalNotifySettings(account, domain);
            if (globalNotificationMatches(args, domain, readback)) break;
            readback = null;
            sleepReadback("Global notification readback was interrupted");
        }
        if (readback == null) {
            throw new McpException("READBACK_FAILED",
                    "Global notification settings did not match server readback",
                    true, readbackArgs);
        }
        uiCall(() -> {
            applyGlobalNotificationPreferences(account, domain, args);
            return null;
        });
        JsonObject data = globalNotificationJson(account, domain, readback);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.notification.global_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject notificationReactionsGet(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        TL_account.TL_reactionsNotifySettings settings =
                fetchReactionNotifySettings(account);
        JsonObject data = reactionNotificationJson(account, settings);
        data.addProperty("source",
                "telegram_server_account.getReactionsNotifySettings");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject notificationReactionsSet(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        String[] fields = {
                "messages", "stories", "poll_votes", "show_previews", "sound"
        };
        boolean mutation = false;
        for (String field : fields) mutation |= args.has(field);
        if (!mutation) invalid("At least one reaction notification field is required");
        TL_account.TL_reactionsNotifySettings settings =
                fetchReactionNotifySettings(account);
        if (args.has("messages")) {
            settings.messages_notify_from = reactionNotifyFrom(
                    requiredString(args, "messages", 1, 16));
            settings.flags = setFlag(settings.flags, 1,
                    settings.messages_notify_from != null);
        }
        if (args.has("stories")) {
            settings.stories_notify_from = reactionNotifyFrom(
                    requiredString(args, "stories", 1, 16));
            settings.flags = setFlag(settings.flags, 2,
                    settings.stories_notify_from != null);
        }
        if (args.has("poll_votes")) {
            settings.poll_votes_notify_from = reactionNotifyFrom(
                    requiredString(args, "poll_votes", 1, 16));
            settings.flags = setFlag(settings.flags, 4,
                    settings.poll_votes_notify_from != null);
        }
        if (args.has("show_previews")) {
            settings.show_previews = requiredBoolean(args, "show_previews");
        }
        if (args.has("sound")) {
            settings.sound = inputNotificationSound(
                    requiredString(args, "sound", 1, 32));
        }
        if (settings.sound == null) {
            settings.sound = new TLRPC.TL_notificationSoundDefault();
        }
        TL_account.setReactionsNotifySettings request =
                new TL_account.setReactionsNotifySettings();
        request.settings = settings;
        String operationId = "notification-reactions-set-" + account
                + "-" + UUID.randomUUID();
        JsonObject readbackArgs = accountArguments(account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.notification.reactions_get", readbackArgs);
        if (!(outcome.response instanceof TL_account.TL_reactionsNotifySettings)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_account.TL_reactionsNotifySettings readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = fetchReactionNotifySettings(account);
            if (reactionNotificationMatches(args, readback)) break;
            readback = null;
            sleepReadback("Reaction notification readback was interrupted");
        }
        if (readback == null) {
            throw new McpException("READBACK_FAILED",
                    "Reaction notification settings did not match server readback",
                    true, readbackArgs);
        }
        TL_account.TL_reactionsNotifySettings confirmedReadback = readback;
        uiCall(() -> {
            applyReactionNotificationPreferences(account, confirmedReadback);
            return null;
        });
        JsonObject data = reactionNotificationJson(account, readback);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.notification.reactions_get", readbackArgs);
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
        TLRPC.Dialog readback = waitForDialogPinned(account, peer, pin);
        JsonObject data = peerJson(peer);
        data.addProperty("pinned", readback.pinned);
        addWriteEvidence(data,
                "dialog-pin-" + account + "-" + peer.dialogId + "-" + pin,
                true, true, true, false,
                "telegram.dialog.get", peerReadbackArguments(account, peer));
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject dialogClearHistory(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        boolean forEveryone = optionalBoolean(args, "for_everyone", false);
        fetchPeerDialog(account, peer);
        String operationId = "clear-history-" + account + "-" + peer.dialogId;
        uiCall(() -> {
            MessagesController.getInstance(account).deleteDialog(peer.dialogId, 1, forEveryone);
            return null;
        });
        waitForEmptyHistory(account, peer, operationId);
        JsonObject data = peerJson(peer);
        data.addProperty("for_everyone", forEveryone);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.addProperty("limit", 1);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.history", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileList(JsonObject args) throws McpException {
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        SharedPreferences preferences = stagedFilePreferences();
        ArrayList<JsonObject> values = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            try {
                JsonObject metadata = JsonParser.parseString(
                        String.valueOf(entry.getValue())).getAsJsonObject();
                File file = stagedFileFromMetadata(metadata);
                if (file.exists() && file.isFile()) values.add(metadata);
            } catch (Throwable ignore) {
                // Corrupt metadata is omitted; file.get reports a precise stale reference.
            }
        }
        values.sort((left, right) -> Long.compare(
                right.get("created_at").getAsLong(), left.get("created_at").getAsLong()));
        JsonArray files = new JsonArray();
        for (int index = 0; index < Math.min(limit, values.size()); index++) {
            files.add(stagedFileJson(values.get(index)));
        }
        JsonObject data = new JsonObject();
        data.add("files", files);
        data.addProperty("count", values.size());
        data.addProperty("limit", limit);
        data.addProperty("scope", "app_private_mcp_staging");
        data.addProperty("max_file_bytes", MAX_STAGED_FILE_BYTES);
        data.addProperty("max_inline_base64_bytes", MAX_INLINE_FILE_BYTES);
        data.addProperty("max_upload_chunk_bytes", MAX_UPLOAD_CHUNK_BYTES);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileGet(JsonObject args) throws McpException {
        StagedFile staged = requireStagedFile(requiredString(args, "file_ref", 66, 66));
        return TelegramMcpServer.successEnvelope(stagedFileJson(staged.metadata));
    }

    private synchronized JsonObject filePutBase64(JsonObject args) throws McpException {
        String name = safeFileName(requiredString(args, "name", 1, 255));
        String mimeType = requiredString(args, "mime_type", 1, 128);
        String encoded = requiredString(args, "base64", 1,
                ((MAX_INLINE_FILE_BYTES + 2) / 3) * 4 + 16);
        byte[] bytes;
        try {
            bytes = Base64.decode(encoded, Base64.DEFAULT);
        } catch (Throwable error) {
            throw new McpException("INVALID_ARGUMENT", "base64 is not valid", false, null);
        }
        if (bytes.length == 0 || bytes.length > MAX_INLINE_FILE_BYTES) {
            invalid("Decoded inline file must contain 1 to "
                    + MAX_INLINE_FILE_BYTES
                    + " bytes; use telegram.file.upload_begin/append/commit "
                    + "for larger files");
        }
        JsonObject metadata = stageBytes(bytes, name, mimeType, "mcp_base64");
        verifyStagedFileDigest(metadata);
        JsonObject data = stagedFileJson(metadata);
        addWriteEvidence(data, "file-stage-" + metadata.get("file_ref").getAsString(),
                true, true, true, true, "telegram.file.get",
                fileRefArguments(metadata.get("file_ref").getAsString()));
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileUploadBegin(JsonObject args)
            throws McpException {
        String name = safeFileName(requiredString(args, "name", 1, 255));
        String mimeType = requiredString(args, "mime_type", 1, 128);
        boolean reopenCancelled = optionalBoolean(
                args, "reopen_cancelled", false);
        long totalSize = requiredLong(
                args, "total_size", 1, MAX_STAGED_FILE_BYTES);
        String digest = requiredString(args, "sha256", 64, 64)
                .toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            invalid("sha256 must contain exactly 64 hexadecimal characters");
        }
        String finalReference = stagedFileReference(digest, name, mimeType);
        String uploadReference = "u_" + sha256Hex(referenceSecret + ":upload:"
                + finalReference + ":" + totalSize);

        SharedPreferences preferences = uploadSessionPreferences();
        String existing = preferences.getString(uploadReference, null);
        File part = uploadPartFile(uploadReference);
        boolean reopenedCancelled = false;
        if (existing != null) {
            UploadSession session = parseUploadSession(uploadReference, existing);
            requireUploadIdentity(session.metadata, name, mimeType,
                    totalSize, digest, finalReference);
            String state = uploadSessionState(session);
            boolean finalPresent = uploadFinalExists(session);
            if ("cancelled".equals(state) && !reopenCancelled) {
                JsonObject data = uploadSessionData(session, finalPresent);
                data.addProperty("idempotent_replay", true);
                data.addProperty("reopen_cancelled_required", true);
                return TelegramMcpServer.successEnvelope(data);
            }
            if (("cancelled".equals(state) && reopenCancelled)
                    || ("complete".equals(state) && !finalPresent)) {
                if (!preferences.edit().remove(uploadReference).commit()) {
                    throw new McpException("PERSISTENCE_FAILED",
                            "Could not reopen the terminal upload session",
                            true, uploadSessionData(session, finalPresent));
                }
                reopenedCancelled = "cancelled".equals(state);
                existing = null;
            }
        }

        try {
            StagedFile staged = requireStagedFile(finalReference);
            verifyStagedFileDigest(staged.metadata);
            boolean cleanupPending = persistCompletedUploadTombstone(
                    uploadReference, name, mimeType,
                    totalSize, digest, finalReference);
            JsonObject data = uploadSessionData(uploadReference, part,
                    name, mimeType, totalSize, digest, finalReference, true);
            data.addProperty("session_state", "complete");
            data.add("file", stagedFileJson(staged.metadata));
            data.addProperty("idempotent_replay", true);
            data.addProperty("reopened_cancelled", reopenedCancelled);
            data.addProperty("upload_session_cleanup_pending", cleanupPending);
            addWriteEvidence(data,
                    "file-upload-begin-" + uploadReference.substring(2, 26),
                    true, true, true, true,
                    "telegram.file.upload_status",
                    uploadRefArguments(uploadReference));
            return TelegramMcpServer.successEnvelope(data);
        } catch (McpException error) {
            if (!"STALE_REFERENCE".equals(error.code)) throw error;
        }

        if (existing != null) {
            UploadSession session = parseUploadSession(uploadReference, existing);
            if (!part.exists() || !part.isFile() || part.length() > totalSize) {
                throw new McpException("UPLOAD_STATE_CORRUPT",
                        "Upload session file is missing or has an invalid length",
                        false, uploadSessionData(session, false));
            }
            JsonObject data = uploadSessionData(session, false);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }

        boolean createdNow = false;
        try {
            if (!part.exists()) {
                createdNow = part.createNewFile();
                if (!createdNow) throw new IOException("Could not create upload part");
            }
        } catch (IOException error) {
            throw new McpException("FILE_IO_ERROR",
                    "Could not create the private upload session file", true, null);
        }
        if (!part.isFile() || part.length() > totalSize) {
            throw new McpException("UPLOAD_STATE_CORRUPT",
                    "Orphan upload part has an invalid state", false, null);
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("upload_ref", uploadReference);
        metadata.addProperty("name", name);
        metadata.addProperty("mime_type", mimeType);
        metadata.addProperty("total_size", totalSize);
        metadata.addProperty("sha256", digest);
        metadata.addProperty("final_file_ref", finalReference);
        metadata.addProperty("part_name", part.getName());
        metadata.addProperty("state", "active");
        metadata.addProperty("created_at", System.currentTimeMillis());
        if (!preferences.edit().putString(uploadReference, metadata.toString()).commit()) {
            boolean rolledBack = !createdNow || part.delete();
            throw new McpException(
                    rolledBack ? "PERSISTENCE_FAILED" : "OUTCOME_UNKNOWN",
                    "Could not persist the upload-session metadata",
                    rolledBack, uploadSessionData(uploadReference, part,
                    name, mimeType, totalSize, digest, finalReference, false));
        }
        JsonObject data = uploadSessionData(new UploadSession(
                uploadReference, metadata, part), false);
        data.addProperty("idempotent_replay", false);
        data.addProperty("reopened_cancelled", reopenedCancelled);
        addWriteEvidence(data, "file-upload-begin-" + uploadReference.substring(2, 26),
                true, true, true, true,
                "telegram.file.upload_status", uploadRefArguments(uploadReference));
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileUploadList(JsonObject args)
            throws McpException {
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        int offset = optionalInt(args, "offset", 0, 0, 1_000_000);
        String stateFilter = optionalString(args, "state", "any")
                .toLowerCase(Locale.ROOT);
        if (!("any".equals(stateFilter) || "active".equals(stateFilter)
                || "complete".equals(stateFilter)
                || "stale_complete".equals(stateFilter)
                || "cancelled".equals(stateFilter))) {
            invalid("state must be any, active, complete, stale_complete, or cancelled");
        }
        JsonArray sessions = new JsonArray();
        ArrayList<UploadSession> values = new ArrayList<>();
        int corruptCount = 0;
        for (Map.Entry<String, ?> entry
                : uploadSessionPreferences().getAll().entrySet()) {
            try {
                values.add(parseUploadSession(entry.getKey(),
                        String.valueOf(entry.getValue())));
            } catch (McpException ignore) {
                // Exact status reports corrupt records; list stays bounded and usable.
                corruptCount++;
            }
        }
        values.sort((left, right) -> Long.compare(
                right.metadata.get("created_at").getAsLong(),
                left.metadata.get("created_at").getAsLong()));
        ArrayList<JsonObject> filtered = new ArrayList<>();
        for (UploadSession session : values) {
            JsonObject item = uploadSessionData(session, uploadFinalExists(session));
            if ("any".equals(stateFilter)
                    || stateFilter.equals(item.get("state").getAsString())) {
                filtered.add(item);
            }
        }
        int end = Math.min(filtered.size(), offset + limit);
        for (int index = Math.min(offset, filtered.size()); index < end; index++) {
            sessions.add(filtered.get(index));
        }
        JsonObject data = new JsonObject();
        data.add("uploads", sessions);
        data.addProperty("count", sessions.size());
        data.addProperty("returned_count", sessions.size());
        data.addProperty("total_count", filtered.size());
        data.addProperty("corrupt_count", corruptCount);
        data.addProperty("limit", limit);
        data.addProperty("offset", offset);
        data.addProperty("next_offset", end);
        data.addProperty("has_more", end < filtered.size());
        data.addProperty("state_filter", stateFilter);
        data.addProperty("scope", "app_private_chunk_uploads");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileUploadStatus(JsonObject args)
            throws McpException {
        String uploadReference = requiredUploadReference(args);
        UploadSession session = requireUploadSession(uploadReference, false);
        JsonObject data = uploadSessionData(session, uploadFinalExists(session));
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileUploadAppend(JsonObject args)
            throws McpException {
        String uploadReference = requiredUploadReference(args);
        UploadSession session = requireUploadSession(uploadReference, true);
        long totalSize = session.metadata.get("total_size").getAsLong();
        long offset = requiredLong(args, "offset", 0, totalSize);
        String expectedChunkDigest = requiredString(args, "chunk_sha256", 64, 64)
                .toLowerCase(Locale.ROOT);
        if (!expectedChunkDigest.matches("[0-9a-f]{64}")) {
            invalid("chunk_sha256 must contain exactly 64 hexadecimal characters");
        }
        String encoded = requiredString(args, "base64", 1,
                ((MAX_UPLOAD_CHUNK_BYTES + 2) / 3) * 4 + 16);
        byte[] chunk;
        try {
            chunk = Base64.decode(encoded, Base64.DEFAULT);
        } catch (Throwable error) {
            throw new McpException("INVALID_ARGUMENT",
                    "base64 is not valid", false, null);
        }
        if (chunk.length == 0 || chunk.length > MAX_UPLOAD_CHUNK_BYTES) {
            invalid("Decoded upload chunk must contain 1 to "
                    + MAX_UPLOAD_CHUNK_BYTES + " bytes");
        }
        String actualChunkDigest = sha256Hex(chunk);
        if (!expectedChunkDigest.equals(actualChunkDigest)) {
            throw new McpException("FILE_INTEGRITY_ERROR",
                    "chunk_sha256 did not match the decoded chunk", false, null);
        }
        if (chunk.length > totalSize - offset) {
            invalid("Upload chunk exceeds the declared total_size");
        }

        long current = session.part.length();
        if (offset < current) {
            if (chunk.length <= current - offset
                    && expectedChunkDigest.equals(sha256FileRange(
                    session.part, offset, chunk.length))) {
                JsonObject data = uploadSessionData(session, false);
                data.addProperty("chunk_offset", offset);
                data.addProperty("chunk_size", chunk.length);
                data.addProperty("idempotent_replay", true);
                return TelegramMcpServer.successEnvelope(data);
            }
            throw uploadOffsetConflict(offset, current);
        }
        if (offset != current) throw uploadOffsetConflict(offset, current);

        boolean writeCompleted = false;
        try (RandomAccessFile output = new RandomAccessFile(session.part, "rw")) {
            output.seek(offset);
            output.write(chunk);
            output.getFD().sync();
            writeCompleted = true;
        } catch (IOException error) {
            boolean rolledBack = truncateUploadPart(session.part, offset);
            throw new McpException(
                    rolledBack ? "FILE_IO_ERROR" : "OUTCOME_UNKNOWN",
                    "Could not durably append the upload chunk",
                    rolledBack, uploadSessionData(session, false));
        }
        if (!writeCompleted || session.part.length() != offset + chunk.length
                || !expectedChunkDigest.equals(sha256FileRange(
                session.part, offset, chunk.length))) {
            boolean rolledBack = truncateUploadPart(session.part, offset);
            throw new McpException(
                    rolledBack ? "READBACK_FAILED" : "OUTCOME_UNKNOWN",
                    "Upload chunk failed exact on-disk readback verification",
                    rolledBack, uploadSessionData(session, false));
        }
        JsonObject data = uploadSessionData(session, false);
        data.addProperty("chunk_offset", offset);
        data.addProperty("chunk_size", chunk.length);
        data.addProperty("chunk_sha256", expectedChunkDigest);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, "file-upload-append-" + uploadReference.substring(2, 18)
                        + "-" + offset,
                true, true, true, true,
                "telegram.file.upload_status", uploadRefArguments(uploadReference));
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileUploadCommit(JsonObject args)
            throws McpException {
        String uploadReference = requiredUploadReference(args);
        UploadSession session = requireUploadSession(uploadReference, false);
        if ("cancelled".equals(uploadSessionState(session))) {
            throw new McpException("PRECONDITION_FAILED",
                    "Cancelled upload sessions cannot be committed", false,
                    uploadSessionData(session, false));
        }
        String finalReference = session.metadata.get("final_file_ref").getAsString();
        try {
            StagedFile existing = requireStagedFile(finalReference);
            verifyStagedFileDigest(existing.metadata);
            boolean tombstonePersisted = markUploadTerminal(session, "complete");
            boolean partRemoved = !session.part.exists() || session.part.delete();
            JsonObject data = stagedFileJson(existing.metadata);
            data.addProperty("upload_ref", uploadReference);
            data.addProperty("upload_session_cleanup_pending",
                    !tombstonePersisted || !partRemoved);
            data.addProperty("idempotent_replay", true);
            addWriteEvidence(data,
                    "file-upload-commit-" + uploadReference.substring(2, 26),
                    true, true, true, true,
                    "telegram.file.get", fileRefArguments(finalReference));
            return TelegramMcpServer.successEnvelope(data);
        } catch (McpException error) {
            if (!"STALE_REFERENCE".equals(error.code)) throw error;
        }

        long expectedSize = session.metadata.get("total_size").getAsLong();
        if (!session.part.exists() || session.part.length() != expectedSize) {
            throw new McpException("PRECONDITION_FAILED",
                    "Upload is incomplete; append through total_size before commit",
                    false, uploadSessionData(session, false));
        }
        String expectedDigest = session.metadata.get("sha256").getAsString();
        String actualDigest = sha256File(session.part);
        if (!expectedDigest.equals(actualDigest)) {
            throw new McpException("FILE_INTEGRITY_ERROR",
                    "Complete upload SHA-256 did not match the declared digest",
                    false, uploadSessionData(session, false));
        }

        String name = session.metadata.get("name").getAsString();
        String mimeType = session.metadata.get("mime_type").getAsString();
        String storedName = finalReference + safeExtension(name);
        File target = new File(stagingDirectory(), storedName);
        boolean createdNow = false;
        if (target.exists()) {
            if (target.length() != expectedSize
                    || !expectedDigest.equals(sha256File(target))) {
                throw new McpException("FILE_INTEGRITY_ERROR",
                        "Existing final staged file conflicts with the upload digest",
                        false, uploadSessionData(session, false));
            }
        } else {
            if (!session.part.renameTo(target)) {
                throw new McpException("FILE_IO_ERROR",
                        "Could not atomically promote the completed upload", true,
                        uploadSessionData(session, false));
            }
            createdNow = true;
        }

        JsonObject metadata = new JsonObject();
        metadata.addProperty("file_ref", finalReference);
        metadata.addProperty("name", name);
        metadata.addProperty("mime_type", mimeType);
        metadata.addProperty("size", expectedSize);
        metadata.addProperty("sha256", expectedDigest);
        metadata.addProperty("created_at", System.currentTimeMillis());
        metadata.addProperty("source", "mcp_chunk_upload");
        metadata.addProperty("stored_name", storedName);
        if (!stagedFilePreferences().edit()
                .putString(finalReference, metadata.toString()).commit()) {
            boolean rolledBack = !createdNow || target.renameTo(session.part);
            throw new McpException(
                    rolledBack ? "PERSISTENCE_FAILED" : "OUTCOME_UNKNOWN",
                    "Could not persist completed staged-file metadata",
                    rolledBack, uploadSessionData(session, false));
        }
        verifyStagedFileDigest(metadata);
        boolean tombstonePersisted = markUploadTerminal(session, "complete");
        boolean partRemoved = !session.part.exists() || session.part.delete();
        JsonObject data = stagedFileJson(metadata);
        data.addProperty("upload_ref", uploadReference);
        data.addProperty("upload_session_cleanup_pending",
                !tombstonePersisted || !partRemoved);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, "file-upload-commit-" + uploadReference.substring(2, 26),
                true, true, true, true,
                "telegram.file.get", fileRefArguments(finalReference));
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileUploadCancel(JsonObject args)
            throws McpException {
        requireConfirm(args);
        String uploadReference = requiredUploadReference(args);
        boolean purgeTerminal = optionalBoolean(args, "purge_terminal", false);
        UploadSession session = requireUploadSession(uploadReference, false);
        if ("cancelled".equals(uploadSessionState(session))) {
            boolean finalPresent = uploadFinalExists(session);
            if (purgeTerminal) {
                if (!uploadSessionPreferences().edit()
                        .remove(uploadReference).commit()) {
                    throw new McpException("PERSISTENCE_FAILED",
                            "Could not purge the cancelled upload tombstone",
                            true, uploadSessionData(session, finalPresent));
                }
                JsonObject purged = new JsonObject();
                purged.addProperty("upload_ref", uploadReference);
                purged.addProperty("cancelled", true);
                purged.addProperty("final_file_preserved", finalPresent);
                purged.addProperty("terminal_purged", true);
                addWriteEvidence(purged,
                        "file-upload-cancel-" + uploadReference.substring(2, 26),
                        true, true, true, true,
                        "telegram.file.upload_status",
                        uploadRefArguments(uploadReference));
                purged.addProperty("readback_expected_error", "STALE_REFERENCE");
                return TelegramMcpServer.successEnvelope(purged);
            }
            JsonObject replay = uploadSessionData(session, finalPresent);
            replay.addProperty("cancelled", true);
            replay.addProperty("final_file_preserved", finalPresent);
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        if (uploadFinalExists(session)) {
            if (!markUploadTerminal(session, "complete")) {
                throw new McpException("PERSISTENCE_FAILED",
                        "Could not persist the completed upload tombstone",
                        true, uploadSessionData(session, true));
            }
            boolean purged = false;
            if (purgeTerminal) {
                purged = uploadSessionPreferences().edit()
                        .remove(uploadReference).commit();
                if (!purged) {
                    throw new McpException("PERSISTENCE_FAILED",
                            "Could not purge the completed upload tombstone",
                            true, uploadSessionData(session, true));
                }
            }
            JsonObject replay = uploadSessionData(session, true);
            replay.addProperty("cancelled", false);
            replay.addProperty("already_committed", true);
            replay.addProperty("final_file_preserved", true);
            replay.addProperty("terminal_purged", purged);
            replay.addProperty("idempotent_replay", true);
            if (purged) {
                addWriteEvidence(replay,
                        "file-upload-cancel-" + uploadReference.substring(2, 26),
                        true, true, true, true,
                        "telegram.file.upload_status",
                        uploadRefArguments(uploadReference));
                replay.addProperty("readback_expected_error", "STALE_REFERENCE");
            }
            return TelegramMcpServer.successEnvelope(replay);
        }
        if (session.part.exists() && !session.part.delete()) {
            throw new McpException("FILE_IO_ERROR",
                    "Could not delete the private upload part", true,
                    uploadSessionData(session, false));
        }
        if (!markUploadTerminal(session, "cancelled")) {
            throw new McpException("PERSISTENCE_FAILED",
                    "Upload part was deleted but cancellation tombstone must be retried",
                    true, uploadSessionData(session, false));
        }
        boolean purged = purgeTerminal && uploadSessionPreferences().edit()
                .remove(uploadReference).commit();
        if (purgeTerminal && !purged) {
            throw new McpException("PERSISTENCE_FAILED",
                    "Upload was cancelled but terminal cleanup must be retried",
                    true, uploadSessionData(session, false));
        }
        JsonObject data = new JsonObject();
        data.addProperty("upload_ref", uploadReference);
        data.addProperty("cancelled", true);
        data.addProperty("final_file_preserved", uploadFinalExists(session));
        data.addProperty("terminal_purged", purged);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, "file-upload-cancel-" + uploadReference.substring(2, 26),
                true, true, true, true,
                "telegram.file.upload_status", uploadRefArguments(uploadReference));
        if (purged) {
            data.addProperty("readback_expected_error", "STALE_REFERENCE");
        }
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileReadBase64(JsonObject args) throws McpException {
        StagedFile staged = requireStagedFile(requiredString(args, "file_ref", 66, 66));
        long size = staged.file.length();
        long offset = optionalLong(args, "offset", 0, 0, size - 1);
        int length = optionalInt(args, "length",
                (int) Math.min(MAX_FILE_READ_BYTES, size - offset),
                1, MAX_FILE_READ_BYTES);
        int actual = (int) Math.min(length, size - offset);
        byte[] bytes = new byte[actual];
        try (FileInputStream input = new FileInputStream(staged.file)) {
            long skipped = 0;
            while (skipped < offset) {
                long value = input.skip(offset - skipped);
                if (value <= 0) throw new IOException("Unable to seek staged file");
                skipped += value;
            }
            int read = 0;
            while (read < actual) {
                int value = input.read(bytes, read, actual - read);
                if (value < 0) break;
                read += value;
            }
            if (read != actual) throw new IOException("Unexpected end of staged file");
        } catch (IOException error) {
            throw new McpException("FILE_IO_ERROR", "Could not read staged file", true, null);
        }
        JsonObject data = stagedFileJson(staged.metadata);
        data.addProperty("offset", offset);
        data.addProperty("length", actual);
        data.addProperty("eof", offset + actual >= size);
        data.addProperty("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileDelete(JsonObject args) throws McpException {
        requireConfirm(args);
        String reference = requiredString(args, "file_ref", 66, 66);
        if (!reference.matches("f_[0-9a-f]{64}")) {
            invalid("file_ref must be a private staged-file reference");
        }
        SharedPreferences preferences = stagedFilePreferences();
        String raw = preferences.getString(reference, null);
        if (raw == null) {
            JsonObject replay = new JsonObject();
            replay.addProperty("file_ref", reference);
            replay.addProperty("deleted", true);
            replay.addProperty("idempotent_replay", true);
            addWriteEvidence(replay, "file-delete-" + reference,
                    true, true, true, true,
                    "telegram.file.get", fileRefArguments(reference));
            return TelegramMcpServer.successEnvelope(replay);
        }
        JsonObject metadata;
        try {
            metadata = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Throwable error) {
            throw new McpException("FILE_INTEGRITY_ERROR",
                    "Staged-file metadata is corrupt", false, null);
        }
        if (!reference.equals(metadata.get("file_ref").getAsString())) {
            throw new McpException("FILE_INTEGRITY_ERROR",
                    "Staged-file metadata reference does not match", false, null);
        }
        File file = stagedFileFromMetadata(metadata);
        boolean fileWasPresent = file.exists();
        if (file.exists() && !file.delete() && file.exists()) {
            throw new McpException("FILE_IO_ERROR", "Could not delete staged file", true,
                    stagedFileJson(metadata));
        }
        if (!preferences.edit().remove(reference).commit()) {
            JsonObject details = stagedFileJson(metadata);
            details.addProperty("file_deleted", !file.exists());
            throw new McpException("PERSISTENCE_FAILED",
                    "The file is absent but metadata cleanup must be retried",
                    true, details);
        }
        assertStagedFileAbsent(reference);
        JsonObject data = stagedFileJson(metadata);
        data.addProperty("deleted", true);
        data.addProperty("idempotent_replay", !fileWasPresent);
        addWriteEvidence(data, "file-delete-" + reference,
                true, true, true, true,
                "telegram.file.get", fileRefArguments(reference));
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject fileDownloadMessage(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        boolean scheduled = optionalBoolean(args, "scheduled", false);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message message = fetchExactMessages(account, peer, ids, scheduled, true).get(0);
        TLRPC.Document document = MessageObject.getDocument(message);
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (document == null && !(media instanceof TLRPC.TL_messageMediaPhoto)) {
            throw new McpException("NO_DOWNLOADABLE_ATTACHMENT",
                    "Message does not contain a document, audio, video, or photo attachment",
                    false, messageJson(account, message));
        }
        File source = FileLoader.getInstance(account).getPathToMessage(message);
        if (!source.exists() || !source.isFile() || source.length() == 0) {
            source = downloadMessageAttachment(account, message);
        }
        String name;
        String mimeType;
        if (document != null) {
            name = FileLoader.getDocumentFileName(document);
            if (TextUtils.isEmpty(name)) name = "message-" + messageId + ".bin";
            mimeType = TextUtils.isEmpty(document.mime_type)
                    ? "application/octet-stream" : document.mime_type;
        } else {
            name = "message-" + messageId + ".jpg";
            mimeType = "image/jpeg";
        }
        JsonObject metadata = stageExistingFile(source, safeFileName(name), mimeType,
                "telegram_message:" + peer.dialogId + ":" + messageId);
        verifyStagedFileDigest(metadata);
        JsonObject data = stagedFileJson(metadata);
        data.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        data.addProperty("message_id", messageId);
        data.addProperty("scheduled", scheduled);
        addWriteEvidence(data,
                "file-download-" + account + "-" + peer.dialogId + "-" + messageId,
                true, true, true, true,
                "telegram.file.get", fileRefArguments(metadata.get("file_ref").getAsString()));
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject qrEncode(JsonObject args) throws McpException {
        String text = requiredString(args, "text", 1, 4096);
        int size = optionalInt(args, "size", 512, 128, 2048);
        Bitmap bitmap = null;
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            bitmap = new QRCodeWriter().encode(text, size, size, hints, null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new McpException("QR_ENCODE_FAILED",
                        "Android could not encode the QR bitmap as PNG", true, null);
            }
            JsonObject metadata = stageBytes(
                    output.toByteArray(), "qr-code.png", "image/png", "mcp_qr_encode");
            JsonObject decoded = qrDecodeFile(
                    fileRefArguments(metadata.get("file_ref").getAsString()));
            String decodedText = decoded.getAsJsonObject("data")
                    .get("text").getAsString();
            if (!text.equals(decodedText)) {
                throw new McpException("READBACK_FAILED",
                        "The staged QR image did not decode to the original text",
                        false, null);
            }
            JsonObject data = stagedFileJson(metadata);
            data.addProperty("width", bitmap.getWidth());
            data.addProperty("height", bitmap.getHeight());
            data.addProperty("content_sha256", sha256Hex(text));
            data.addProperty("encoder", "embedded_zxing");
            addWriteEvidence(data,
                    "qr-encode-" + metadata.get("file_ref").getAsString(),
                    true, true, true, true,
                    "telegram.qr.decode_file",
                    fileRefArguments(metadata.get("file_ref").getAsString()));
            return TelegramMcpServer.successEnvelope(data);
        } catch (McpException error) {
            throw error;
        } catch (Exception error) {
            throw new McpException("QR_ENCODE_FAILED",
                    "The embedded QR encoder rejected the content", false, null);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private synchronized JsonObject qrDecodeFile(JsonObject args) throws McpException {
        StagedFile staged = requireStagedFile(requiredString(args, "file_ref", 66, 66));
        String mimeType = staged.metadata.get("mime_type").getAsString();
        if (!mimeType.toLowerCase(Locale.US).startsWith("image/")) {
            invalid("qr.decode_file requires a staged image MIME type");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(staged.file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new McpException("INVALID_IMAGE",
                    "The staged file is not a decodable image", false,
                    stagedFileJson(staged.metadata));
        }
        long sourcePixels = (long) bounds.outWidth * bounds.outHeight;
        if (sourcePixels > 100_000_000L) {
            throw new McpException("IMAGE_TOO_LARGE",
                    "The decoded image dimensions exceed the QR safety limit", false,
                    stagedFileJson(staged.metadata));
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / options.inSampleSize > 4096) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(staged.file.getAbsolutePath(), options);
        if (bitmap == null) {
            throw new McpException("INVALID_IMAGE",
                    "The staged image could not be decoded", false,
                    stagedFileJson(staged.metadata));
        }
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource luminance = new RGBLuminanceSource(width, height, pixels);
            Result result;
            try {
                result = new QRCodeReader().decode(
                        new BinaryBitmap(new HybridBinarizer(luminance)));
            } catch (Exception error) {
                JsonObject details = stagedFileJson(staged.metadata);
                details.addProperty("decoded_width", width);
                details.addProperty("decoded_height", height);
                throw new McpException("QR_NOT_FOUND",
                        "No valid QR code was found in the staged image", false, details);
            }
            JsonObject data = new JsonObject();
            data.addProperty("text", result.getText());
            data.addProperty("format", result.getBarcodeFormat().toString());
            data.addProperty("decoded_width", width);
            data.addProperty("decoded_height", height);
            data.addProperty("source_width", bounds.outWidth);
            data.addProperty("source_height", bounds.outHeight);
            data.addProperty("sample_size", options.inSampleSize);
            data.addProperty("decoder", "embedded_zxing");
            data.add("source_file", stagedFileJson(staged.metadata));
            return TelegramMcpServer.successEnvelope(data);
        } finally {
            bitmap.recycle();
        }
    }

    private JsonObject messageHistory(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        int offsetId = optionalInt(args, "offset_id", 0, 0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        TLObject request;
        if (topicId != 0) {
            resolveTopicTopMessage(account, peer, topicId);
            TLRPC.TL_messages_getReplies replies = new TLRPC.TL_messages_getReplies();
            replies.peer = peer.inputPeer;
            replies.msg_id = topicId;
            replies.offset_id = offsetId;
            replies.limit = limit;
            request = replies;
        } else {
            TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
            history.peer = peer.inputPeer;
            history.offset_id = offsetId;
            history.limit = limit;
            request = history;
        }
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Messages)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Messages response = (TLRPC.messages_Messages) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonObject envelope = messagesEnvelope(account, peer, response.messages, limit);
        envelope.getAsJsonObject("data").addProperty("topic_id", topicId);
        envelope.getAsJsonObject("data").addProperty("source", topicId == 0
                ? "telegram_server_messages.getHistory"
                : "telegram_server_messages.getReplies");
        return envelope;
    }

    private JsonObject messageGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        ArrayList<Integer> ids = requiredIntArray(
                args, "message_ids", 1, 100, 1, Integer.MAX_VALUE);
        boolean scheduled = optionalBoolean(args, "scheduled", false);
        ArrayList<TLRPC.Message> messages = fetchExactMessages(account, peer, ids, scheduled, true);
        JsonObject envelope = messagesEnvelope(account, peer, messages, ids.size());
        envelope.getAsJsonObject("data").add("requested_message_ids", intArray(ids));
        envelope.getAsJsonObject("data").addProperty("scheduled", scheduled);
        envelope.getAsJsonObject("data").addProperty("source", scheduled
                ? "telegram_server_messages.getScheduledMessages"
                : peer.chat != null && ChatObject.isChannel(peer.chat)
                ? "telegram_server_channels.getMessages"
                : "telegram_server_messages.getMessages");
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
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        PeerRef peer = null;
        TLRPC.messages_Messages response;
        if (hasNonEmptyString(args, "peer")) {
            peer = resolvePeer(account, args.get("peer").getAsString());
            if (topicId != 0) resolveTopicTopMessage(account, peer, topicId);
            TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
            request.peer = peer.inputPeer;
            request.q = query;
            request.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            request.limit = limit;
            request.saved_reaction = null;
            if (topicId != 0) {
                request.flags |= 2;
                request.top_msg_id = topicId;
            }
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.messages_Messages)) {
                throw unexpectedResponse(outcome.response);
            }
            response = (TLRPC.messages_Messages) outcome.response;
        } else {
            if (topicId != 0) invalid("topic_id requires peer");
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
        envelope.getAsJsonObject("data").addProperty("topic_id", topicId);
        return envelope;
    }

    private JsonObject messageMediaSearch(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        String filterName = optionalString(args, "filter", "photo_video_documents");
        String query = optionalString(args, "query", "");
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        int offsetId = optionalInt(args, "offset_id", 0, 0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        if (topicId != 0) resolveTopicTopMessage(account, peer, topicId);
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = peer.inputPeer;
        request.q = query;
        request.filter = messageMediaFilter(filterName);
        request.offset_id = offsetId;
        request.limit = limit;
        request.saved_reaction = null;
        if (topicId != 0) {
            request.flags |= 2;
            request.top_msg_id = topicId;
        }
        if (hasNonEmptyString(args, "from_peer")) {
            PeerRef from = resolvePeer(account, args.get("from_peer").getAsString());
            request.from_id = from.inputPeer;
        }
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Messages)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Messages response = (TLRPC.messages_Messages) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonObject envelope = messagesEnvelope(account, peer, response.messages, limit);
        JsonObject data = envelope.getAsJsonObject("data");
        data.addProperty("filter", filterName);
        data.addProperty("query", query);
        data.addProperty("offset_id", offsetId);
        data.addProperty("topic_id", topicId);
        data.addProperty("source", "telegram_server_messages.search_filtered");
        return envelope;
    }

    private synchronized JsonObject messageSendText(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "message.send_text.request.v3", args);
        JsonObject replay = idempotencyReplay(account, "send", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "text");
        FormattedText formatted = parseFormattedText(
                account, requiredString(args, "text", 1, 4096),
                optionalString(args, "parse_mode", "plain"));
        String text = formatted.text;
        boolean linkPreview = optionalBoolean(args, "link_preview", true);
        String operationId = "send-" + sha256Hex(account + ":" + key).substring(0, 24);
        boolean silent = optionalBoolean(args, "silent", false);
        int replyId = optionalInt(args, "reply_to_message_id", 0, 0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        MessageObject explicitReply = null;
        if (replyId != 0) {
            ArrayList<Integer> replyIds = new ArrayList<>();
            replyIds.add(replyId);
            explicitReply = messageObjects(account,
                    fetchExactMessages(account, peer, replyIds, false, true)).get(0);
        }
        MessageObject replyToTop = resolveTopicTopMessage(account, peer, topicId);
        MessageObject reply = explicitReply == null ? replyToTop : explicitReply;
        int scheduleDate = 0;
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
            scheduleDate = (int) epoch;
        }
        if (MessagesController.getInstance(account).getSendPaidMessagesStars(peer.dialogId) > 0) {
            throw new McpException("HUMAN_INTERACTION_REQUIRED",
                    "This peer requires a paid-message confirmation in Telegram's trusted UI", false,
                    peerJson(peer));
        }

        SendResult sendResult = sendTextViaHelper(
                account, peer, text, formatted.entities, linkPreview,
                reply, replyToTop, silent, scheduleDate, key, operationId);
        ArrayList<Integer> readbackIds = new ArrayList<>();
        readbackIds.add(sendResult.messageId);
        ArrayList<TLRPC.Message> readback = fetchExactMessages(
                account, peer, readbackIds, scheduleDate != 0, true);
        if (readback.size() != 1 || !text.equals(readback.get(0).message)
                || !messageEntitiesJson(formatted.entities).equals(
                messageEntitiesJson(readback.get(0).entities))) {
            JsonObject details = new JsonObject();
            details.addProperty("operation_id", operationId);
            details.addProperty("message_id", sendResult.messageId);
            throw new McpException("READBACK_FAILED",
                    "Sent message text did not match the independent server readback", true, details);
        }
        requireSentTopicMatches(account, readback.get(0), topicId);
        JsonObject data = peerJson(peer);
        data.addProperty("random_id", Long.toString(sendResult.message.random_id));
        JsonArray messageIds = new JsonArray();
        messageIds.add(sendResult.messageId);
        data.add("message_ids", messageIds);
        data.addProperty("scheduled", scheduleDate != 0);
        data.addProperty("text", text);
        data.add("entities", messageEntitiesJson(formatted.entities));
        data.addProperty("link_preview", linkPreview);
        data.addProperty("topic_id", topicId);
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        readbackArgs.add("message_ids", messageIds.deepCopy());
        readbackArgs.addProperty("scheduled", scheduleDate != 0);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        storeIdempotency(account, "send", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject messageSendMedia(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "message.send_media.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "send_media", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        JsonArray references = requiredArray(args, "file_refs", 1, 10);
        String kind = optionalString(args, "kind", "auto");
        if (!kind.equals("auto") && !kind.equals("photo")
                && !kind.equals("video") && !kind.equals("document")) {
            invalid("kind must be auto, photo, video, or document");
        }
        String rawCaption = optionalString(args, "caption", "");
        FormattedText formattedCaption = rawCaption.isEmpty()
                ? new FormattedText("", new ArrayList<>())
                : parseFormattedText(account, rawCaption,
                optionalString(args, "caption_parse_mode", "plain"));
        String caption = formattedCaption.text;
        if (caption.length() > 1024) invalid("caption exceeds 1024 characters");
        boolean silent = optionalBoolean(args, "silent", false);
        boolean spoiler = optionalBoolean(args, "spoiler", false);
        int replyId = optionalInt(args, "reply_to_message_id", 0, 0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        int scheduleDate = parseScheduleAt(account, optionalString(args, "schedule_at", ""));
        ArrayList<StagedFile> stagedFiles = new ArrayList<>();
        for (JsonElement value : references) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                invalid("file_refs must contain staged file reference strings");
            }
            StagedFile staged = requireStagedFile(value.getAsString());
            stagedFiles.add(staged);
            String effectiveKind = kind;
            if ("auto".equals(effectiveKind)) {
                String mime = staged.metadata.get("mime_type").getAsString();
                effectiveKind = mime.startsWith("image/") ? "photo"
                        : mime.startsWith("video/") ? "video" : "document";
            }
            requireCanSend(peer, effectiveKind);
        }
        MessageObject explicitReply = null;
        if (replyId != 0) {
            ArrayList<Integer> replyIds = new ArrayList<>();
            replyIds.add(replyId);
            explicitReply = messageObjects(account,
                    fetchExactMessages(account, peer, replyIds, false, true)).get(0);
        }
        MessageObject replyToTop = resolveTopicTopMessage(account, peer, topicId);
        MessageObject reply = explicitReply == null ? replyToTop : explicitReply;
        if (MessagesController.getInstance(account).getSendPaidMessagesStars(peer.dialogId) > 0) {
            throw new McpException("HUMAN_INTERACTION_REQUIRED",
                    "This peer requires paid-message confirmation in Telegram's trusted UI",
                    false, peerJson(peer));
        }
        String operationId = "send-media-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        ArrayList<Integer> sentIds = sendMediaViaHelper(account, peer, stagedFiles,
                kind, caption, formattedCaption.entities, reply, replyToTop,
                silent, scheduleDate, spoiler, operationId);
        ArrayList<TLRPC.Message> readback = fetchExactMessages(
                account, peer, sentIds, scheduleDate != 0, true);
        if (readback.size() != sentIds.size()) {
            throw new McpException("READBACK_FAILED",
                    "Not all sent media messages were returned by exact server readback",
                    true, intArray(sentIds));
        }
        for (TLRPC.Message message : readback) {
            if (message.media == null || message.media instanceof TLRPC.TL_messageMediaEmpty) {
                throw new McpException("READBACK_FAILED",
                        "A sent media message read back without media", true,
                        messageJson(account, message));
            }
            requireSentTopicMatches(account, message, topicId);
        }
        if (!caption.isEmpty()) {
            TLRPC.Message captionMessage = null;
            for (TLRPC.Message message : readback) {
                if (caption.equals(message.message)) {
                    captionMessage = message;
                    break;
                }
            }
            if (captionMessage == null || !messageEntitiesJson(formattedCaption.entities)
                    .equals(messageEntitiesJson(captionMessage.entities))) {
                throw new McpException("READBACK_FAILED",
                        "Media caption or entities did not match exact server readback",
                        true, messagesEnvelope(account, peer, readback, readback.size()));
            }
        }
        JsonObject data = peerJson(peer);
        data.add("message_ids", intArray(sentIds));
        data.addProperty("media_count", sentIds.size());
        data.addProperty("kind", kind);
        data.addProperty("caption", caption);
        data.add("caption_entities", messageEntitiesJson(formattedCaption.entities));
        data.addProperty("scheduled", scheduleDate != 0);
        data.addProperty("topic_id", topicId);
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.add("message_ids", intArray(sentIds));
        readbackArgs.addProperty("scheduled", scheduleDate != 0);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        storeIdempotency(account, "send_media", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject messageSendContact(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "message.send_contact.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "send_contact", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "document");
        String phone = requiredString(args, "phone_number", 1, 64).trim();
        String firstName = requiredString(args, "first_name", 1, 64).trim();
        String lastName = optionalString(args, "last_name", "").trim();
        if (phone.isEmpty() || firstName.isEmpty()) {
            invalid("phone_number and first_name must not be blank");
        }
        int replyId = optionalInt(args, "reply_to_message_id", 0, 0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        boolean silent = optionalBoolean(args, "silent", false);
        int scheduleDate = parseScheduleAt(account, optionalString(args, "schedule_at", ""));
        MessageObject replyToTop = resolveTopicTopMessage(account, peer, topicId);
        MessageObject explicitReply = resolveReplyMessage(account, peer, replyId);
        MessageObject reply = explicitReply == null ? replyToTop : explicitReply;
        requireNoPaidMessageConfirmation(account, peer);
        TLRPC.TL_user contact = new TLRPC.TL_user();
        contact.id = 0;
        contact.phone = phone;
        contact.first_name = firstName;
        contact.last_name = lastName;
        String operationId = "send-contact-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        SendResult sent = sendStructuredViaHelper(account, peer, scheduleDate != 0,
                operationId,
                value -> value.media instanceof TLRPC.TL_messageMediaContact
                        && phone.equals(value.media.phone_number)
                        && firstName.equals(value.media.first_name)
                        && lastName.equals(value.media.last_name == null
                        ? "" : value.media.last_name),
                () -> {
                    HashMap<String, String> params = mcpSendParams(key, operationId);
                    SendMessagesHelper.SendMessageParams send =
                            SendMessagesHelper.SendMessageParams.of(
                                    contact, peer.dialogId, reply, replyToTop, null, params,
                                    !silent, scheduleDate, 0);
                    SendMessagesHelper.getInstance(account).sendMessage(send);
                    return null;
                });
        TLRPC.Message readback = exactSentMessage(
                account, peer, sent.messageId, scheduleDate != 0);
        if (!(readback.media instanceof TLRPC.TL_messageMediaContact)
                || !phone.equals(readback.media.phone_number)
                || !firstName.equals(readback.media.first_name)
                || !lastName.equals(readback.media.last_name == null
                ? "" : readback.media.last_name)) {
            throw new McpException("READBACK_FAILED",
                    "Sent contact card did not match exact server readback", true,
                    messageJson(account, readback));
        }
        requireSentTopicMatches(account, readback, topicId);
        JsonObject data = messageJson(account, readback);
        data.addProperty("idempotent_replay", false);
        addSentMessageEvidence(data, operationId, account, peer,
                sent.messageId, scheduleDate != 0);
        storeIdempotency(account, "send_contact", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject messageSendLocation(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "message.send_location.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "send_location", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "document");
        double latitude = requiredDouble(args, "latitude", -90.0, 90.0);
        double longitude = requiredDouble(args, "longitude", -180.0, 180.0);
        latitude = AndroidUtilities.fixLocationCoord(latitude);
        longitude = AndroidUtilities.fixLocationCoord(longitude);
        String title = optionalString(args, "title", "").trim();
        String address = optionalString(args, "address", "").trim();
        if (!address.isEmpty() && title.isEmpty()) {
            invalid("title is required when address is supplied");
        }
        int replyId = optionalInt(args, "reply_to_message_id", 0, 0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        boolean silent = optionalBoolean(args, "silent", false);
        int scheduleDate = parseScheduleAt(account, optionalString(args, "schedule_at", ""));
        MessageObject replyToTop = resolveTopicTopMessage(account, peer, topicId);
        MessageObject explicitReply = resolveReplyMessage(account, peer, replyId);
        MessageObject reply = explicitReply == null ? replyToTop : explicitReply;
        requireNoPaidMessageConfirmation(account, peer);
        TLRPC.MessageMedia location;
        if (title.isEmpty()) {
            TLRPC.TL_messageMediaGeo geo = new TLRPC.TL_messageMediaGeo();
            geo.geo = new TLRPC.TL_geoPoint();
            geo.geo.lat = latitude;
            geo.geo._long = longitude;
            location = geo;
        } else {
            TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
            venue.geo = new TLRPC.TL_geoPoint();
            venue.geo.lat = latitude;
            venue.geo._long = longitude;
            venue.title = title;
            venue.address = address;
            venue.provider = "telegram-mcp";
            venue.venue_id = "";
            venue.venue_type = "";
            location = venue;
        }
        final double expectedLatitude = latitude;
        final double expectedLongitude = longitude;
        final String expectedTitle = title;
        String operationId = "send-location-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        SendResult sent = sendStructuredViaHelper(account, peer, scheduleDate != 0,
                operationId,
                value -> locationMessageMatches(value, expectedLatitude,
                        expectedLongitude, expectedTitle),
                () -> {
                    SendMessagesHelper.SendMessageParams send =
                            SendMessagesHelper.SendMessageParams.of(
                                    location, peer.dialogId, reply, replyToTop, null,
                                    mcpSendParams(key, operationId), !silent,
                                    scheduleDate, 0);
                    SendMessagesHelper.getInstance(account).sendMessage(send);
                    return null;
                });
        TLRPC.Message readback = exactSentMessage(
                account, peer, sent.messageId, scheduleDate != 0);
        if (!locationMessageMatches(readback, expectedLatitude,
                expectedLongitude, expectedTitle)) {
            throw new McpException("READBACK_FAILED",
                    "Sent location did not match exact server readback", true,
                    messageJson(account, readback));
        }
        requireSentTopicMatches(account, readback, topicId);
        JsonObject data = messageJson(account, readback);
        data.addProperty("latitude", expectedLatitude);
        data.addProperty("longitude", expectedLongitude);
        data.addProperty("venue", !expectedTitle.isEmpty());
        data.addProperty("idempotent_replay", false);
        addSentMessageEvidence(data, operationId, account, peer,
                sent.messageId, scheduleDate != 0);
        storeIdempotency(account, "send_location", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject messageSendDice(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "message.send_dice.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "send_dice", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "sticker");
        String emoji = optionalString(args, "emoji", "🎲");
        String normalizedEmoji = emoji.replace("\ufe0f", "");
        if (!MessagesController.getInstance(account).diceEmojies.contains(normalizedEmoji)) {
            JsonObject details = new JsonObject();
            JsonArray supported = new JsonArray();
            for (String value : MessagesController.getInstance(account).diceEmojies) {
                supported.add(value);
            }
            details.add("supported", supported);
            throw new McpException("INVALID_ARGUMENT",
                    "emoji is not a Telegram dice emoji for this account", false, details);
        }
        int replyId = optionalInt(args, "reply_to_message_id", 0, 0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        boolean silent = optionalBoolean(args, "silent", false);
        MessageObject replyToTop = resolveTopicTopMessage(account, peer, topicId);
        MessageObject explicitReply = resolveReplyMessage(account, peer, replyId);
        MessageObject reply = explicitReply == null ? replyToTop : explicitReply;
        requireNoPaidMessageConfirmation(account, peer);
        String operationId = "send-dice-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        SendResult sent = sendStructuredViaHelper(account, peer, false, operationId,
                value -> value.media instanceof TLRPC.TL_messageMediaDice
                        && normalizedEmoji.equals(
                        ((TLRPC.TL_messageMediaDice) value.media)
                                .emoticon.replace("\ufe0f", "")),
                () -> {
                    SendMessagesHelper.SendMessageParams send =
                            SendMessagesHelper.SendMessageParams.of(
                                    emoji, peer.dialogId, reply, replyToTop, null, true,
                                    null, null, mcpSendParams(key, operationId),
                                    !silent, 0, 0, null, false);
                    SendMessagesHelper.getInstance(account).sendMessage(send);
                    return null;
                });
        TLRPC.Message readback = exactSentMessage(account, peer, sent.messageId, false);
        if (!(readback.media instanceof TLRPC.TL_messageMediaDice)) {
            throw new McpException("READBACK_FAILED",
                    "Sent dice did not match exact server readback", true,
                    messageJson(account, readback));
        }
        requireSentTopicMatches(account, readback, topicId);
        TLRPC.TL_messageMediaDice diceReadback =
                (TLRPC.TL_messageMediaDice) readback.media;
        if (diceReadback.emoticon == null || !normalizedEmoji.equals(
                diceReadback.emoticon.replace("\ufe0f", ""))) {
            throw new McpException("READBACK_FAILED",
                    "Sent dice did not match exact server readback", true,
                    messageJson(account, readback));
        }
        JsonObject data = messageJson(account, readback);
        data.addProperty("emoji", diceReadback.emoticon);
        data.addProperty("value", diceReadback.value);
        data.addProperty("idempotent_replay", false);
        addSentMessageEvidence(data, operationId, account, peer, sent.messageId, false);
        storeIdempotency(account, "send_dice", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject messageSendPoll(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "message.send_poll.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "send_poll", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "poll");
        String question = requiredString(args, "question", 1, 255).trim();
        if (question.isEmpty()) invalid("question must not be blank");
        JsonArray answerValues = requiredArray(args, "answers", 2, 10);
        ArrayList<String> answers = new ArrayList<>();
        for (JsonElement value : answerValues) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                invalid("answers must contain strings");
            }
            String answer = value.getAsString().trim();
            if (answer.isEmpty() || answer.length() > 100) {
                invalid("each poll answer must contain 1..100 characters");
            }
            if (answers.contains(answer)) invalid("poll answers must be unique");
            answers.add(answer);
        }
        boolean multipleChoice = optionalBoolean(args, "multiple_choice", false);
        boolean quiz = optionalBoolean(args, "quiz", false);
        if (multipleChoice && quiz) {
            invalid("multiple_choice and quiz cannot both be true");
        }
        int correctAnswer = -1;
        if (quiz) {
            correctAnswer = requiredInt(args, "correct_answer", 0, answers.size() - 1);
        } else if (args.has("correct_answer")) {
            invalid("correct_answer is only valid for quiz polls");
        }
        String solution = optionalString(args, "solution", "");
        if (!quiz && !solution.isEmpty()) invalid("solution is only valid for quiz polls");
        if (solution.length() > 200) invalid("solution exceeds 200 characters");
        boolean anonymous = optionalBoolean(args, "anonymous", true);
        int closePeriod = optionalInt(args, "close_period", 0, 0, 600);
        if (closePeriod != 0 && closePeriod < 5) {
            invalid("close_period must be 0 or 5..600 seconds");
        }
        int replyId = optionalInt(args, "reply_to_message_id", 0, 0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        boolean silent = optionalBoolean(args, "silent", false);
        int scheduleDate = parseScheduleAt(account, optionalString(args, "schedule_at", ""));
        MessageObject replyToTop = resolveTopicTopMessage(account, peer, topicId);
        MessageObject explicitReply = resolveReplyMessage(account, peer, replyId);
        MessageObject reply = explicitReply == null ? replyToTop : explicitReply;
        requireNoPaidMessageConfirmation(account, peer);
        TLRPC.TL_messageMediaPoll poll = new TLRPC.TL_messageMediaPoll();
        poll.poll = new TLRPC.TL_poll();
        poll.poll.multiple_choice = multipleChoice;
        poll.poll.quiz = quiz;
        poll.poll.public_voters = !anonymous;
        poll.poll.question = textWithEntities(account, question);
        if (closePeriod != 0) {
            poll.poll.flags |= 16;
            poll.poll.close_period = closePeriod;
        }
        for (int index = 0; index < answers.size(); index++) {
            TLRPC.TL_pollAnswer answer = new TLRPC.TL_pollAnswer();
            answer.text = textWithEntities(account, answers.get(index));
            answer.option = new byte[]{(byte) (48 + index)};
            poll.poll.answers.add(answer);
        }
        poll.results = new TLRPC.TL_pollResults();
        if (!solution.isEmpty()) {
            TLRPC.TL_textWithEntities solutionValue = textWithEntities(account, solution);
            poll.results.solution = solutionValue.text;
            poll.results.solution_entities = solutionValue.entities;
            poll.results.flags |= 16;
        }
        ArrayList<Integer> correctAnswers = null;
        if (quiz) {
            correctAnswers = new ArrayList<>();
            correctAnswers.add(correctAnswer);
        }
        PollSendParams pollParams = new PollSendParams(
                null, poll, Utilities.random.nextLong(), null, null, correctAnswers);
        String operationId = "send-poll-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        SendResult sent = sendStructuredViaHelper(account, peer, scheduleDate != 0,
                operationId,
                value -> pollMessageMatches(value, question, answers,
                        multipleChoice, quiz, anonymous),
                () -> {
                    SendMessagesHelper.SendMessageParams send =
                            SendMessagesHelper.SendMessageParams.of(
                                    poll, peer.dialogId, reply, replyToTop,
                                    null, mcpSendParams(key, operationId),
                                    !silent, scheduleDate, 0);
                    send.invert_media = true;
                    send.pollSendParams = pollParams;
                    SendMessagesHelper.getInstance(account).sendMessage(send);
                    return null;
                });
        TLRPC.Message readback = exactSentMessage(
                account, peer, sent.messageId, scheduleDate != 0);
        if (!pollMessageMatches(readback, question, answers,
                multipleChoice, quiz, anonymous)) {
            throw new McpException("READBACK_FAILED",
                    "Sent poll did not match exact server readback", true,
                    messageJson(account, readback));
        }
        requireSentTopicMatches(account, readback, topicId);
        JsonObject data = messageJson(account, readback);
        data.add("poll", pollJson(
                ((TLRPC.TL_messageMediaPoll) readback.media).poll));
        data.addProperty("idempotent_replay", false);
        addSentMessageEvidence(data, operationId, account, peer,
                sent.messageId, scheduleDate != 0);
        storeIdempotency(account, "send_poll", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageEditText(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        FormattedText formatted = parseFormattedText(
                account, requiredString(args, "text", 1, 4096),
                optionalString(args, "parse_mode", "plain"));
        String text = formatted.text;
        boolean scheduled = optionalBoolean(args, "scheduled", false);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message existing = fetchExactMessages(
                account, peer, ids, scheduled, true).get(0);
        if (!MessageObject.canEditMessage(account, existing, peer.chat, scheduled)) {
            JsonObject details = messageJson(account, existing);
            details.addProperty("scheduled", scheduled);
            throw new McpException("PERMISSION_DENIED",
                    "Telegram's message permission model does not allow this edit", false, details);
        }
        String operationId = "edit-" + account + "-" + peer.dialogId + "-" + messageId;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        readbackArgs.add("message_ids", intArray(ids));
        readbackArgs.addProperty("scheduled", scheduled);
        TLRPC.TL_messages_editMessage request = new TLRPC.TL_messages_editMessage();
        request.peer = peer.inputPeer;
        request.id = messageId;
        request.message = text;
        request.flags |= 1 << 11;
        request.entities.addAll(formatted.entities);
        request.flags |= 1 << 3;
        if (args.has("link_preview")) {
            request.no_webpage = !requiredBoolean(args, "link_preview");
        }
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.message.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.Message readback = waitForExactMessage(
                account, peer, messageId, scheduled, text, true);
        if (!messageEntitiesJson(formatted.entities).equals(
                messageEntitiesJson(readback.entities))) {
            throw new McpException("READBACK_FAILED",
                    "Edited message entities did not match exact server readback", true,
                    messageJson(account, readback));
        }
        JsonObject data = peerJson(peer);
        data.addProperty("message_id", messageId);
        data.addProperty("text", readback.message == null ? "" : readback.message);
        data.add("entities", messageEntitiesJson(readback.entities));
        data.addProperty("scheduled", scheduled);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageEditCaption(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        String rawCaption = requiredString(args, "caption", 0, 1024);
        FormattedText formatted = rawCaption.isEmpty()
                ? new FormattedText("", new ArrayList<>())
                : parseFormattedText(account, rawCaption,
                optionalString(args, "parse_mode", "plain"));
        if (formatted.text.length() > 1024) invalid("caption exceeds 1024 characters");
        boolean scheduled = optionalBoolean(args, "scheduled", false);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message existing = fetchExactMessages(
                account, peer, ids, scheduled, true).get(0);
        TLRPC.Document document = MessageObject.getDocument(existing);
        if (document == null && !(existing.media instanceof TLRPC.TL_messageMediaPhoto)) {
            throw new McpException("INVALID_MESSAGE_TYPE",
                    "Only photo, video, audio, or document messages have editable captions",
                    false, messageJson(account, existing));
        }
        if (!MessageObject.canEditMessage(account, existing, peer.chat, scheduled)) {
            JsonObject details = messageJson(account, existing);
            details.addProperty("scheduled", scheduled);
            throw new McpException("PERMISSION_DENIED",
                    "Telegram's message permission model does not allow editing this caption",
                    false, details);
        }
        String operationId = "edit-caption-" + account + "-"
                + peer.dialogId + "-" + messageId;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.add("message_ids", intArray(ids));
        readbackArgs.addProperty("scheduled", scheduled);
        TLRPC.TL_messages_editMessage request = new TLRPC.TL_messages_editMessage();
        request.peer = peer.inputPeer;
        request.id = messageId;
        request.message = formatted.text;
        request.flags |= 1 << 11;
        request.entities.addAll(formatted.entities);
        request.flags |= 1 << 3;
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.message.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.Message readback = waitForExactMessage(
                account, peer, messageId, scheduled, formatted.text, true);
        if (!messageEntitiesJson(formatted.entities).equals(
                messageEntitiesJson(readback.entities))) {
            throw new McpException("READBACK_FAILED",
                    "Edited caption entities did not match exact server readback", true,
                    messageJson(account, readback));
        }
        JsonObject data = messageJson(account, readback);
        data.addProperty("caption", readback.message == null ? "" : readback.message);
        data.addProperty("scheduled", scheduled);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messagePollVote(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message existing = fetchExactMessages(account, peer, ids, false, true).get(0);
        if (!(existing.media instanceof TLRPC.TL_messageMediaPoll)) {
            throw new McpException("INVALID_MESSAGE_TYPE",
                    "message_id is not a poll or quiz", false,
                    messageJson(account, existing));
        }
        TLRPC.TL_messageMediaPoll media =
                (TLRPC.TL_messageMediaPoll) existing.media;
        if (media.poll == null || media.poll.closed) {
            throw new McpException("POLL_CLOSED",
                    "The poll is already closed", false,
                    messageJson(account, existing));
        }
        JsonArray requested = requiredArray(args, "answer_indices", 0, 10);
        ArrayList<Integer> indices = new ArrayList<>();
        HashSet<Integer> unique = new HashSet<>();
        for (JsonElement value : requested) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                invalid("answer_indices must contain integers");
            }
            int index;
            try {
                index = value.getAsInt();
            } catch (Throwable error) {
                invalid("answer_indices must contain integers");
                return null;
            }
            if (index < 0 || index >= media.poll.answers.size()) {
                invalid("answer index is outside the poll answer range");
            }
            if (!unique.add(index)) invalid("answer_indices must be unique");
            indices.add(index);
        }
        if (!media.poll.multiple_choice && indices.size() > 1) {
            invalid("This poll allows at most one selected answer");
        }
        Collections.sort(indices);
        TLRPC.TL_messages_sendVote request = new TLRPC.TL_messages_sendVote();
        request.peer = peer.inputPeer;
        request.msg_id = messageId;
        for (Integer index : indices) {
            request.options.add(media.poll.answers.get(index).option);
        }
        String operationId = "poll-vote-" + account + "-"
                + peer.dialogId + "-" + messageId + "-"
                + sha256Hex(indices.toString()).substring(0, 12);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.add("message_ids", intArray(ids));
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.message.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.Message readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = fetchExactMessages(account, peer, ids, false, true).get(0);
            if (pollChosenIndices(readback).equals(indices)) break;
            sleepReadback("Poll vote readback was interrupted");
        }
        if (readback == null || !pollChosenIndices(readback).equals(indices)) {
            throw new McpException("READBACK_FAILED",
                    "Chosen poll answers did not match exact server readback", true,
                    readback == null ? null : messageJson(account, readback));
        }
        JsonObject data = messageJson(account, readback);
        data.add("answer_indices", intArray(indices));
        data.add("poll", pollMediaJson((TLRPC.TL_messageMediaPoll) readback.media));
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messagePollClose(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message existing = fetchExactMessages(account, peer, ids, false, true).get(0);
        if (!(existing.media instanceof TLRPC.TL_messageMediaPoll)) {
            throw new McpException("INVALID_MESSAGE_TYPE",
                    "message_id is not a poll or quiz", false,
                    messageJson(account, existing));
        }
        TLRPC.TL_messageMediaPoll media =
                (TLRPC.TL_messageMediaPoll) existing.media;
        if (media.poll == null || media.poll.closed) {
            JsonObject data = messageJson(account, existing);
            data.addProperty("closed", true);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        if (!MessageObject.canEditMessage(account, existing, peer.chat, false)) {
            throw new McpException("PERMISSION_DENIED",
                    "Telegram's permission model does not allow closing this poll",
                    false, messageJson(account, existing));
        }
        TLRPC.TL_inputMediaPoll poll = new TLRPC.TL_inputMediaPoll();
        poll.poll = new TLRPC.TL_poll();
        poll.poll.id = media.poll.id;
        poll.poll.question = media.poll.question;
        poll.poll.answers = media.poll.answers;
        poll.poll.closed = true;
        TLRPC.TL_messages_editMessage request = new TLRPC.TL_messages_editMessage();
        request.peer = peer.inputPeer;
        request.id = messageId;
        request.media = poll;
        request.flags |= 1 << 14;
        String operationId = "poll-close-" + account + "-"
                + peer.dialogId + "-" + messageId;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.add("message_ids", intArray(ids));
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.message.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.Message readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = fetchExactMessages(account, peer, ids, false, true).get(0);
            if (readback.media instanceof TLRPC.TL_messageMediaPoll
                    && ((TLRPC.TL_messageMediaPoll) readback.media).poll.closed) break;
            sleepReadback("Poll closed-state readback was interrupted");
        }
        if (readback == null || !(readback.media instanceof TLRPC.TL_messageMediaPoll)
                || !((TLRPC.TL_messageMediaPoll) readback.media).poll.closed) {
            throw new McpException("READBACK_FAILED",
                    "Poll did not become closed in exact server readback", true,
                    readback == null ? null : messageJson(account, readback));
        }
        JsonObject data = messageJson(account, readback);
        data.addProperty("closed", true);
        data.addProperty("idempotent_replay", false);
        data.add("poll", pollMediaJson((TLRPC.TL_messageMediaPoll) readback.media));
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        ArrayList<Integer> ids = requiredIntArray(args, "message_ids", 1, 100, 1, Integer.MAX_VALUE);
        boolean forEveryone = optionalBoolean(args, "for_everyone", false);
        boolean scheduled = optionalBoolean(args, "scheduled", false);
        ArrayList<TLRPC.Message> existing = fetchExactMessages(
                account, peer, ids, scheduled, true);
        for (TLRPC.Message message : existing) {
            if (!MessageObject.canDeleteMessage(account, scheduled, message, peer.chat)) {
                JsonObject details = messageJson(account, message);
                details.addProperty("scheduled", scheduled);
                throw new McpException("PERMISSION_DENIED",
                        "Telegram's message permission model does not allow deleting every requested message",
                        false, details);
            }
        }
        String operationId = "delete-" + account + "-" + peer.dialogId + "-"
                + sha256Hex(ids.toString()).substring(0, 16);
        uiCall(() -> {
            MessagesController.getInstance(account).deleteMessages(
                    ids, null, null, peer.dialogId, 0, forEveryone, scheduled ? 1 : 0);
            return null;
        });
        waitForMessagesAbsent(account, peer, ids, scheduled, operationId);
        JsonObject data = peerJson(peer);
        data.add("message_ids", intArray(ids));
        data.addProperty("for_everyone", forEveryone);
        data.addProperty("scheduled", scheduled);
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        readbackArgs.add("message_ids", intArray(ids));
        readbackArgs.addProperty("scheduled", scheduled);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject messageForward(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "message.forward.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "forward", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef from = resolvePeer(account, requiredString(args, "from_peer", 1, 256));
        PeerRef to = resolvePeer(account, requiredString(args, "to_peer", 1, 256));
        ArrayList<Integer> ids = requiredIntArray(args, "message_ids", 1, 100, 1, Integer.MAX_VALUE);
        boolean silent = optionalBoolean(args, "silent", false);
        int topicId = optionalInt(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        ArrayList<TLRPC.Message> sourceMessages = fetchExactMessages(
                account, from, ids, false, true);
        ArrayList<MessageObject> sourceObjects = messageObjects(account, sourceMessages);
        for (MessageObject message : sourceObjects) {
            if (!message.canForwardMessage()) {
                throw new McpException("PERMISSION_DENIED",
                        "One or more requested messages cannot be forwarded", false,
                        messageJson(account, message.messageOwner));
            }
        }
        if (MessagesController.getInstance(account).getSendPaidMessagesStars(to.dialogId) > 0) {
            throw new McpException("HUMAN_INTERACTION_REQUIRED",
                    "The destination requires a paid-message confirmation in Telegram's trusted UI",
                    false, peerJson(to));
        }
        MessageObject replyToTop = resolveTopicTopMessage(account, to, topicId);
        String operationId = "forward-" + sha256Hex(account + ":" + key).substring(0, 24);
        ArrayList<Integer> destinationIds = forwardViaHelper(
                account, to, sourceObjects, replyToTop, silent, operationId);
        ArrayList<TLRPC.Message> readbackMessages = fetchExactMessages(
                account, to, destinationIds, false, true);
        if (readbackMessages.size() != ids.size()) {
            throw new McpException("READBACK_FAILED",
                    "Forwarded message count did not match exact server readback", true, null);
        }
        for (TLRPC.Message message : readbackMessages) {
            requireSentTopicMatches(account, message, topicId);
        }
        JsonObject data = new JsonObject();
        data.add("from", peerJson(from));
        data.add("to", peerJson(to));
        data.add("source_message_ids", intArray(ids));
        data.add("message_ids", intArray(destinationIds));
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), to.dialogId));
        readbackArgs.add("message_ids", intArray(destinationIds));
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        storeIdempotency(account, "forward", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageReactionSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        String reaction = requiredString(args, "reaction", 0, 32);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        fetchExactMessages(account, peer, ids, false, true);
        String operationId = "reaction-" + account + "-" + peer.dialogId + "-" + messageId;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        readbackArgs.add("message_ids", intArray(ids));
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
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.message.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.Message readback = waitForReactionState(
                account, peer, messageId, reaction);
        JsonObject data = peerJson(peer);
        data.addProperty("message_id", messageId);
        data.addProperty("reaction", reaction);
        data.addProperty("removed", reaction.isEmpty());
        data.add("server_message", messageJson(account, readback));
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageMarkRead(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int maxId = requiredInt(args, "max_message_id", 1, Integer.MAX_VALUE);
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        String operationId = "mark-read-" + account + "-" + peer.dialogId + "-" + topicId;
        uiCall(() -> {
            MessagesController.getInstance(account).markDialogAsRead(
                    peer.dialogId, maxId, 0, 0, false, topicId, 0, true, 0);
            return null;
        });
        int unreadCount = waitForReadState(account, peer, maxId, topicId);
        JsonObject data = peerJson(peer);
        data.addProperty("max_message_id", maxId);
        data.addProperty("topic_id", topicId);
        data.addProperty("unread_count", unreadCount);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.dialog.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messageMarkUnread(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        String operationId = "mark-unread-" + account + "-" + peer.dialogId;
        uiCall(() -> {
            MessagesController.getInstance(account).markDialogAsUnread(peer.dialogId, peer.inputPeer, 0);
            return null;
        });
        TLRPC.Dialog readback = waitForDialogUnreadMark(account, peer, true);
        JsonObject data = peerJson(peer);
        data.addProperty("unread_mark", readback.unread_mark);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.dialog.get", peerReadbackArguments(account, peer));
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject messagePin(JsonObject args, boolean unpin) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        boolean notify = optionalBoolean(args, "notify", false);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message before = fetchExactMessages(
                account, peer, ids, false, true).get(0);
        if (before.pinned == !unpin) {
            JsonObject replay = peerJson(peer);
            replay.addProperty("message_id", messageId);
            replay.addProperty("pinned", before.pinned);
            replay.add("server_message", messageJson(account, before));
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        String operationId = (unpin ? "unpin-" : "pin-")
                + account + "-" + peer.dialogId + "-" + messageId;
        uiCall(() -> {
            MessagesController.getInstance(account).pinMessage(
                    peer.chat, peer.user, messageId, unpin, false, notify);
            return null;
        });
        TLRPC.Message readback = waitForPinnedState(
                account, peer, messageId, !unpin);
        JsonObject data = peerJson(peer);
        data.addProperty("message_id", messageId);
        data.addProperty("pinned", !unpin);
        data.add("server_message", messageJson(account, readback));
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        readbackArgs.add("message_ids", intArray(ids));
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject botButtonList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message message = fetchExactMessages(account, peer, ids, false, true).get(0);
        JsonObject data = peerJson(peer);
        data.addProperty("message_id", messageId);
        data.add("buttons", keyboardButtonsJson(message.reply_markup));
        data.addProperty("button_count", countKeyboardButtons(message.reply_markup));
        data.addProperty("source", "telegram_server_exact_message_reply_markup");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject botButtonPress(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "bot.button_press.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "bot_button_press", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int messageId = requiredInt(args, "message_id", 1, Integer.MAX_VALUE);
        int row = requiredInt(args, "row", 0, 99);
        int column = requiredInt(args, "column", 0, 99);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message message = fetchExactMessages(account, peer, ids, false, true).get(0);
        TLRPC.KeyboardButton button = keyboardButtonAt(message.reply_markup, row, column);
        if (button instanceof TLRPC.TL_keyboardButton) {
            requireCanSend(peer, "text");
            requireNoPaidMessageConfirmation(account, peer);
            String text = button.text == null ? "" : button.text;
            String operationId = "bot-button-text-" + sha256Hex(
                    account + ":" + key).substring(0, 24);
            SendResult sent = sendTextViaHelper(account, peer, text,
                    new ArrayList<>(), true, null, false, 0, key, operationId);
            TLRPC.Message readbackMessage = exactSentMessage(
                    account, peer, sent.messageId, false);
            if (!text.equals(readbackMessage.message)) {
                throw new McpException("READBACK_FAILED",
                        "Reply-keyboard text did not match exact server readback",
                        true, messageJson(account, readbackMessage));
            }
            JsonObject data = peerJson(peer);
            JsonArray messageIds = new JsonArray();
            messageIds.add(sent.messageId);
            data.add("message_ids", messageIds);
            data.addProperty("text", text);
            data.add("button", keyboardButtonJson(button, row, column));
            data.addProperty("performed", "reply_keyboard_text_sent");
            data.addProperty("idempotent_replay", false);
            addSentMessageEvidence(data, operationId, account, peer,
                    sent.messageId, false);
            storeIdempotency(account, "bot_button_press", key, payloadHash, data);
            return TelegramMcpServer.successEnvelope(data);
        }

        if (button instanceof TLRPC.TL_keyboardButtonCopy) {
            JsonObject data = keyboardButtonJson(button, row, column);
            data.addProperty("copy_text", ((TLRPC.TL_keyboardButtonCopy) button).copy_text);
            data.addProperty("performed", "copy_value_returned");
            data.addProperty("idempotent_replay", false);
            addWriteEvidence(data,
                    "bot-button-copy-" + account + "-" + messageId + "-" + row + "-" + column,
                    true, true, false, false,
                    "telegram.bot.button_list", botButtonReadbackArguments(
                            account, peer, messageId));
            storeIdempotency(account, "bot_button_press", key, payloadHash, data);
            return TelegramMcpServer.successEnvelope(data);
        }
        if (button instanceof TLRPC.TL_keyboardButtonCallback
                || button instanceof TLRPC.TL_keyboardButtonGame) {
            if (button.requires_password) {
                JsonObject details = keyboardButtonJson(button, row, column);
                details.addProperty("required_step", "telegram_two_step_password_srp");
                throw new McpException("HUMAN_INTERACTION_REQUIRED",
                        "This callback requires Telegram two-step password verification",
                        false, details);
            }
            TLRPC.TL_messages_getBotCallbackAnswer request =
                    new TLRPC.TL_messages_getBotCallbackAnswer();
            request.peer = peer.inputPeer;
            request.msg_id = messageId;
            request.game = button instanceof TLRPC.TL_keyboardButtonGame;
            if (button.data != null) {
                request.flags |= 1;
                request.data = button.data;
            }
            String operationId = "bot-button-press-" + sha256Hex(
                    account + ":" + key).substring(0, 24);
            JsonObject readbackArgs = botButtonReadbackArguments(account, peer, messageId);
            RequestOutcome outcome = writeRequest(account, request, operationId,
                    "telegram.bot.button_list", readbackArgs);
            if (!(outcome.response instanceof TLRPC.TL_messages_botCallbackAnswer)) {
                throw unexpectedResponse(outcome.response);
            }
            TLRPC.TL_messages_botCallbackAnswer answer =
                    (TLRPC.TL_messages_botCallbackAnswer) outcome.response;
            JsonObject data = keyboardButtonJson(button, row, column);
            data.addProperty("callback_acknowledged", true);
            data.addProperty("alert", answer.alert);
            data.addProperty("message", answer.message == null ? "" : answer.message);
            data.addProperty("url", answer.url == null ? "" : answer.url);
            data.addProperty("native_ui", answer.native_ui);
            data.addProperty("cache_time", answer.cache_time);
            data.addProperty("downstream_effect_verified", false);
            data.addProperty("operation_id", operationId);
            data.addProperty("acknowledged", true);
            data.addProperty("committed", true);
            data.addProperty("locally_applied", false);
            data.addProperty("readback_verified", false);
            data.addProperty("persistence_verified", false);
            data.addProperty("outcome", "callback_answer_received");
            JsonObject readback = new JsonObject();
            readback.addProperty("tool", "telegram.bot.button_list");
            readback.add("arguments", readbackArgs);
            data.add("readback", readback);
            data.addProperty("idempotent_replay", false);
            storeIdempotency(account, "bot_button_press", key, payloadHash, data);
            return TelegramMcpServer.successEnvelope(data);
        }

        JsonObject details = keyboardButtonJson(button, row, column);
        if (button instanceof TLRPC.TL_keyboardButtonBuy) {
            details.addProperty("required_step", "trusted_payment_confirmation");
        } else if (button instanceof TLRPC.TL_keyboardButtonRequestPhone) {
            details.addProperty("required_step", "share_phone_confirmation");
        } else if (button instanceof TLRPC.TL_keyboardButtonRequestGeoLocation) {
            details.addProperty("required_step", "location_permission_and_share_confirmation");
        } else if (button instanceof TLRPC.TL_keyboardButtonRequestPoll
                || button instanceof TLRPC.TL_keyboardButtonRequestPeer) {
            details.addProperty("required_step", "trusted_object_picker");
        } else {
            details.addProperty("required_step", "open_url_webview_or_inline_handoff");
        }
        throw new McpException("HUMAN_INTERACTION_REQUIRED",
                "This button requires a trusted Telegram UI or browser handoff",
                false, details);
    }

    private JsonObject botCommandList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef bot = resolvePeer(account, requiredString(args, "bot", 1, 256));
        if (bot.user == null || !bot.user.bot) invalid("bot must resolve to a bot user");
        TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
        request.id = MessagesController.getInstance(account).getInputUser(bot.user);
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_users_userFull)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_users_userFull response = (TLRPC.TL_users_userFull) outcome.response;
        cachePeers(account, response.users, response.chats);
        TL_bots.BotInfo info = response.full_user.bot_info;
        JsonObject data = peerJson(bot);
        data.addProperty("description", info == null || info.description == null
                ? "" : info.description);
        data.addProperty("privacy_policy_url",
                info == null || info.privacy_policy_url == null
                        ? "" : info.privacy_policy_url);
        JsonArray commands = new JsonArray();
        if (info != null) {
            for (TLRPC.BotCommand command : info.commands) {
                JsonObject item = new JsonObject();
                item.addProperty("command", command.command == null ? "" : command.command);
                item.addProperty("description",
                        command.description == null ? "" : command.description);
                item.addProperty("ephemeral", command.ephemeral);
                commands.add(item);
            }
        }
        data.add("commands", commands);
        data.addProperty("source", "users.getFullUser.bot_info");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject botStart(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash("bot.start.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "bot_start", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef bot = resolvePeer(account, requiredString(args, "bot", 1, 256));
        if (bot.user == null || !bot.user.bot) invalid("bot must resolve to a bot user");
        String targetToken = optionalString(args, "peer", "");
        PeerRef target = targetToken.isEmpty() ? bot : resolvePeer(account, targetToken);
        String startParam = optionalString(args, "start_param", "");
        if (startParam.length() > 512) invalid("start_param exceeds 512 characters");
        requireNoPaidMessageConfirmation(account, target);
        TLRPC.TL_messages_startBot request = new TLRPC.TL_messages_startBot();
        request.bot = MessagesController.getInstance(account).getInputUser(bot.user);
        request.peer = target.inputPeer;
        request.random_id = deterministicLong(account + ":bot-start:" + key);
        request.start_param = startParam;
        String operationId = "bot-start-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        JsonObject timeoutReadback = peerReadbackArguments(account, target);
        timeoutReadback.addProperty("limit", 20);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.message.history", timeoutReadback);
        processUpdates(account, outcome.response);
        JsonArray ids = extractMessageIds(outcome.response);
        if (ids.size() == 0) {
            throw new McpException("MISSING_CREATED_OBJECT",
                    "messages.startBot returned no message ID; inspect history before retrying",
                    true, unknownOutcomeDetails(operationId,
                    "telegram.message.history", timeoutReadback));
        }
        JsonObject data = peerJson(target);
        data.add("bot", peerJson(bot));
        data.addProperty("start_param", startParam);
        data.add("message_ids", ids);
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = peerReadbackArguments(account, target);
        readbackArgs.add("message_ids", ids.deepCopy());
        readbackArgs.addProperty("scheduled", false);
        ArrayList<Integer> messageIds = jsonIntList(ids);
        fetchExactMessages(account, target, messageIds, false, true);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        storeIdempotency(account, "bot_start", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject botInlineQuery(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef bot = resolvePeer(account, requiredString(args, "bot", 1, 256));
        if (bot.user == null || !bot.user.bot) invalid("bot must resolve to a bot user");
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        TLRPC.TL_messages_getInlineBotResults request =
                new TLRPC.TL_messages_getInlineBotResults();
        request.bot = MessagesController.getInstance(account).getInputUser(bot.user);
        request.peer = peer.inputPeer;
        request.query = optionalString(args, "query", "");
        if (request.query.length() > 512) invalid("query exceeds 512 characters");
        request.offset = optionalString(args, "offset", "");
        if (request.offset.length() > 512) invalid("offset exceeds 512 characters");
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_BotResults)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_BotResults response =
                (TLRPC.messages_BotResults) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        JsonObject data = peerJson(peer);
        data.add("bot", peerJson(bot));
        data.addProperty("query", request.query);
        data.addProperty("query_id", Long.toString(response.query_id));
        data.addProperty("gallery", response.gallery);
        data.addProperty("cache_time", response.cache_time);
        data.addProperty("next_offset",
                response.next_offset == null ? "" : response.next_offset);
        JsonArray results = new JsonArray();
        for (TLRPC.BotInlineResult result : response.results) {
            results.add(botInlineResultJson(result));
        }
        data.add("results", results);
        data.addProperty("source", "messages.getInlineBotResults");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject botInlineSend(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "bot.inline_send.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "bot_inline_send", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "text");
        requireNoPaidMessageConfirmation(account, peer);
        long queryId = requiredPositiveLongString(args, "query_id");
        String resultId = requiredString(args, "result_id", 1, 512);
        boolean silent = optionalBoolean(args, "silent", false);
        TLRPC.TL_messages_sendInlineBotResult request =
                new TLRPC.TL_messages_sendInlineBotResult();
        request.peer = peer.inputPeer;
        request.random_id = deterministicLong(account + ":bot-inline:" + key);
        request.query_id = queryId;
        request.id = resultId;
        request.silent = silent;
        String operationId = "bot-inline-send-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        JsonObject timeoutReadback = peerReadbackArguments(account, peer);
        timeoutReadback.addProperty("limit", 20);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.message.history", timeoutReadback);
        processUpdates(account, outcome.response);
        JsonArray ids = extractMessageIds(outcome.response);
        if (ids.size() == 0) {
            throw new McpException("MISSING_CREATED_OBJECT",
                    "messages.sendInlineBotResult returned no message ID; inspect history before retrying",
                    true, unknownOutcomeDetails(operationId,
                    "telegram.message.history", timeoutReadback));
        }
        ArrayList<Integer> messageIds = jsonIntList(ids);
        ArrayList<TLRPC.Message> messages = fetchExactMessages(
                account, peer, messageIds, false, true);
        JsonObject data = peerJson(peer);
        data.addProperty("query_id", Long.toString(queryId));
        data.addProperty("result_id", resultId);
        data.add("message_ids", ids);
        JsonArray readbackMessages = new JsonArray();
        for (TLRPC.Message message : messages) {
            readbackMessages.add(messageJson(account, message));
        }
        data.add("server_messages", readbackMessages);
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.add("message_ids", ids.deepCopy());
        readbackArgs.addProperty("scheduled", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        storeIdempotency(account, "bot_inline_send", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject savedDocumentList(JsonObject args, boolean stickers)
            throws McpException {
        int account = requireActiveAccount(args);
        int offset = optionalInt(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        ArrayList<TLRPC.Document> documents = fetchSavedDocuments(account, stickers);
        JsonArray items = new JsonArray();
        int end = Math.min(documents.size(), offset + limit);
        for (int index = offset; index < end; index++) {
            items.add(documentJson(documents.get(index)));
        }
        JsonObject data = new JsonObject();
        data.addProperty("kind", stickers ? "favorite_stickers" : "saved_gifs");
        data.addProperty("total_count", documents.size());
        data.addProperty("offset", offset);
        data.addProperty("limit", limit);
        data.addProperty("next_offset", end < documents.size() ? end : 0);
        data.add("documents", items);
        data.addProperty("source", stickers
                ? "telegram_server_messages.getFavedStickers"
                : "telegram_server_messages.getSavedGifs");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject savedDocumentSet(JsonObject args, boolean stickers)
            throws McpException {
        int account = requireActiveAccount(args);
        PeerRef source = resolvePeer(account,
                requiredString(args, "source_peer", 1, 256));
        int messageId = requiredInt(args, "source_message_id", 1, Integer.MAX_VALUE);
        boolean saved = requiredBoolean(args, "saved");
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        TLRPC.Message message = fetchExactMessages(
                account, source, ids, false, true).get(0);
        TLRPC.Document document = MessageObject.getDocument(message);
        if (document == null || stickers && !MessageObject.isStickerDocument(document)
                || !stickers && !MessageObject.isGifDocument(document)) {
            throw new McpException("INVALID_MESSAGE_TYPE",
                    stickers
                            ? "Source message does not contain a Telegram sticker"
                            : "Source message does not contain a Telegram GIF",
                    false, messageJson(account, message));
        }
        TLObject request;
        if (stickers) {
            TLRPC.TL_messages_faveSticker value = new TLRPC.TL_messages_faveSticker();
            value.id = MessagesController.getInstance(account).getInputDocument(document);
            value.unfave = !saved;
            request = value;
        } else {
            TLRPC.TL_messages_saveGif value = new TLRPC.TL_messages_saveGif();
            value.id = MessagesController.getInstance(account).getInputDocument(document);
            value.unsave = !saved;
            request = value;
        }
        String operationId = (stickers ? "sticker-favorite-" : "gif-saved-")
                + account + "-" + document.id + "-" + saved;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("limit", MAX_LIMIT);
        requireBoolTrue(writeRequest(account, request, operationId,
                stickers ? "telegram.sticker.favorite_list"
                        : "telegram.gif.saved_list", readbackArgs).response,
                stickers ? "messages.faveSticker" : "messages.saveGif");
        boolean present = false;
        for (int attempt = 0; attempt < 12; attempt++) {
            present = containsDocument(fetchSavedDocuments(account, stickers), document.id);
            if (present == saved) break;
            sleepReadback("Saved media readback was interrupted");
        }
        if (present != saved) {
            JsonObject details = documentJson(document);
            details.addProperty("expected_saved", saved);
            details.addProperty("actual_saved", present);
            throw new McpException("READBACK_FAILED",
                    "Saved media state did not match server list readback", true, details);
        }
        JsonObject data = documentJson(document);
        data.addProperty("saved", saved);
        data.add("source_message", messageJson(account, message));
        addWriteEvidence(data, operationId, true, true, true, false,
                stickers ? "telegram.sticker.favorite_list"
                        : "telegram.gif.saved_list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject sendSavedDocument(
            JsonObject args, boolean stickers) throws McpException {
        int account = requireActiveAccount(args);
        String operation = stickers ? "send_saved_sticker" : "send_saved_gif";
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "message.send_saved_media.request.v3:" + operation, args);
        JsonObject replay = idempotencyReplay(
                account, operation, key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "sticker");
        long documentId = requiredPositiveLongString(args, "document_id");
        TLRPC.Document document = null;
        for (TLRPC.Document value : fetchSavedDocuments(account, stickers)) {
            if (value.id == documentId) {
                document = value;
                break;
            }
        }
        if (document == null) {
            JsonObject details = new JsonObject();
            details.addProperty("document_id", Long.toString(documentId));
            details.addProperty("kind", stickers ? "favorite_sticker" : "saved_gif");
            throw new McpException("DOCUMENT_NOT_FOUND",
                    "document_id is not present in the current server-backed saved list",
                    false, details);
        }
        int replyId = optionalInt(args, "reply_to_message_id", 0,
                0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0,
                0, Integer.MAX_VALUE);
        boolean silent = optionalBoolean(args, "silent", false);
        int scheduleDate = parseScheduleAt(account,
                optionalString(args, "schedule_at", ""));
        MessageObject replyToTop = resolveTopicTopMessage(account, peer, topicId);
        MessageObject explicitReply = resolveReplyMessage(account, peer, replyId);
        MessageObject reply = explicitReply == null ? replyToTop : explicitReply;
        requireNoPaidMessageConfirmation(account, peer);
        String operationId = operation + "-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        TLRPC.Document finalDocument = document;
        SendResult sent = sendStructuredViaHelper(
                account, peer, scheduleDate != 0, operationId,
                value -> {
                    TLRPC.Document sentDocument = MessageObject.getDocument(value);
                    return sentDocument != null && sentDocument.id == documentId;
                },
                () -> {
                    if (!(finalDocument instanceof TLRPC.TL_document)) {
                        throw new McpException("UNSUPPORTED_MEDIA",
                                "Saved document cannot be sent by the local helper",
                                false, null);
                    }
                    SendMessagesHelper.SendMessageParams send =
                            SendMessagesHelper.SendMessageParams.of(
                                    (TLRPC.TL_document) finalDocument,
                                    null, null, peer.dialogId, reply, replyToTop,
                                    null, null, null,
                                    mcpSendParams(key, operationId), !silent,
                                    scheduleDate, 0, 0, null, null, false);
                    SendMessagesHelper.getInstance(account).sendMessage(send);
                    return null;
                });
        TLRPC.Message readback = exactSentMessage(
                account, peer, sent.messageId, scheduleDate != 0);
        TLRPC.Document sentDocument = MessageObject.getDocument(readback);
        if (sentDocument == null || sentDocument.id != documentId) {
            throw new McpException("READBACK_FAILED",
                    "Sent saved media document did not match exact server readback",
                    true, messageJson(account, readback));
        }
        requireSentTopicMatches(account, readback, topicId);
        JsonObject data = messageJson(account, readback);
        data.add("document", documentJson(sentDocument));
        data.addProperty("kind", stickers ? "sticker" : "gif");
        data.addProperty("idempotent_replay", false);
        addSentMessageEvidence(data, operationId, account, peer,
                sent.messageId, scheduleDate != 0);
        storeIdempotency(account, operation, key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject stickerSearch(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String emoji = requiredString(args, "emoji", 1, 32);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        TLRPC.TL_messages_getStickers request =
                new TLRPC.TL_messages_getStickers();
        request.emoticon = emoji;
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_stickers)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_stickers response =
                (TLRPC.TL_messages_stickers) outcome.response;
        JsonArray documents = new JsonArray();
        int count = Math.min(limit, response.stickers.size());
        for (int index = 0; index < count; index++) {
            TLRPC.Document document = response.stickers.get(index);
            String reference = cacheTransientDocument(document);
            JsonObject item = documentJson(document);
            item.addProperty("document_ref", reference);
            item.addProperty("expires_on_app_restart", true);
            documents.add(item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("emoji", emoji);
        data.addProperty("total_count", response.stickers.size());
        data.add("documents", documents);
        data.addProperty("source", "telegram_server_messages.getStickers");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject stickerSend(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "sticker.send_search_result.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "send_searched_sticker", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account,
                requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "sticker");
        String reference = requiredString(args, "document_ref", 3, 80);
        TLRPC.Document document = transientDocuments.get(reference);
        if (document == null || !MessageObject.isStickerDocument(document)) {
            JsonObject details = new JsonObject();
            details.addProperty("document_ref", reference);
            details.addProperty("refresh_tool", "telegram.sticker.search");
            throw new McpException("STALE_REFERENCE",
                    "Sticker reference expired or does not belong to this MCP server",
                    false, details);
        }
        int replyId = optionalInt(args, "reply_to_message_id", 0,
                0, Integer.MAX_VALUE);
        int topicId = optionalInt(args, "topic_id", 0,
                0, Integer.MAX_VALUE);
        boolean silent = optionalBoolean(args, "silent", false);
        int scheduleDate = parseScheduleAt(account,
                optionalString(args, "schedule_at", ""));
        MessageObject replyToTop = resolveTopicTopMessage(account, peer, topicId);
        MessageObject explicitReply = resolveReplyMessage(account, peer, replyId);
        MessageObject reply = explicitReply == null ? replyToTop : explicitReply;
        requireNoPaidMessageConfirmation(account, peer);
        String operationId = "send-searched-sticker-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        SendResult sent = sendStructuredViaHelper(
                account, peer, scheduleDate != 0, operationId,
                value -> {
                    TLRPC.Document sentDocument = MessageObject.getDocument(value);
                    return sentDocument != null && sentDocument.id == document.id;
                },
                () -> {
                    if (!(document instanceof TLRPC.TL_document)) {
                        throw new McpException("UNSUPPORTED_MEDIA",
                                "Sticker document cannot be sent by the local helper",
                                false, null);
                    }
                    SendMessagesHelper.SendMessageParams send =
                            SendMessagesHelper.SendMessageParams.of(
                                    (TLRPC.TL_document) document,
                                    null, null, peer.dialogId, reply, replyToTop,
                                    null, null, null,
                                    mcpSendParams(key, operationId), !silent,
                                    scheduleDate, 0, 0, null, null, false);
                    SendMessagesHelper.getInstance(account).sendMessage(send);
                    return null;
                });
        TLRPC.Message readback = exactSentMessage(
                account, peer, sent.messageId, scheduleDate != 0);
        TLRPC.Document sentDocument = MessageObject.getDocument(readback);
        if (sentDocument == null || sentDocument.id != document.id) {
            throw new McpException("READBACK_FAILED",
                    "Sent sticker did not match exact server readback",
                    true, messageJson(account, readback));
        }
        requireSentTopicMatches(account, readback, topicId);
        JsonObject data = messageJson(account, readback);
        data.add("document", documentJson(sentDocument));
        data.addProperty("document_ref", reference);
        data.addProperty("idempotent_replay", false);
        addSentMessageEvidence(data, operationId, account, peer,
                sent.messageId, scheduleDate != 0);
        storeIdempotency(account, "send_searched_sticker", key,
                payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject stickerPackSearch(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String query = requiredString(args, "query", 1, 128);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        boolean emojiPacks = optionalBoolean(args, "emoji_packs", false);
        TLObject searchRequest;
        if (emojiPacks) {
            TLRPC.TL_messages_searchEmojiStickerSets value =
                    new TLRPC.TL_messages_searchEmojiStickerSets();
            value.q = query;
            value.hash = 0;
            value.exclude_featured = false;
            searchRequest = value;
        } else {
            TLRPC.TL_messages_searchStickerSets value =
                    new TLRPC.TL_messages_searchStickerSets();
            value.q = query;
            value.hash = 0;
            value.exclude_featured = false;
            searchRequest = value;
        }
        RequestOutcome outcome = request(account, searchRequest);
        if (!(outcome.response instanceof TLRPC.TL_messages_foundStickerSets)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_foundStickerSets response =
                (TLRPC.TL_messages_foundStickerSets) outcome.response;
        JsonArray sets = new JsonArray();
        int count = Math.min(limit, response.sets.size());
        for (int index = 0; index < count; index++) {
            sets.add(stickerSetJson(response.sets.get(index).set));
        }
        JsonObject data = new JsonObject();
        data.addProperty("query", query);
        data.addProperty("emoji_packs", emojiPacks);
        data.addProperty("total_count", response.sets.size());
        data.add("sets", sets);
        data.addProperty("source", emojiPacks
                ? "telegram_server_messages.searchEmojiStickerSets"
                : "telegram_server_messages.searchStickerSets");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject stickerPackSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String shortName = requiredString(args, "short_name", 1, 64);
        boolean installed = requiredBoolean(args, "installed");
        TLRPC.TL_messages_stickerSet before = fetchStickerSet(account, shortName);
        boolean current = stickerSetInstalled(before.set);
        if (current == installed) {
            JsonObject data = stickerSetJson(before.set);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TLRPC.TL_inputStickerSetShortName input =
                new TLRPC.TL_inputStickerSetShortName();
        input.short_name = shortName;
        TLObject setRequest;
        if (installed) {
            TLRPC.TL_messages_installStickerSet value =
                    new TLRPC.TL_messages_installStickerSet();
            value.stickerset = input;
            value.archived = false;
            setRequest = value;
        } else {
            requireConfirm(args);
            TLRPC.TL_messages_uninstallStickerSet value =
                    new TLRPC.TL_messages_uninstallStickerSet();
            value.stickerset = input;
            setRequest = value;
        }
        String operationId = "sticker-pack-set-" + account + "-"
                + shortName + "-" + installed;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("query", shortName);
        readbackArgs.addProperty("limit", 20);
        RequestOutcome outcome = writeRequest(account, setRequest, operationId,
                "telegram.sticker.pack_search", readbackArgs);
        if (installed) {
            if (!(outcome.response instanceof
                    TLRPC.messages_StickerSetInstallResult)) {
                throw unexpectedResponse(outcome.response);
            }
        } else {
            requireBoolTrue(outcome.response, "messages.uninstallStickerSet");
        }
        TLRPC.TL_messages_stickerSet readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            TLRPC.TL_messages_stickerSet value =
                    fetchStickerSet(account, shortName);
            if (stickerSetInstalled(value.set) == installed) {
                readback = value;
                break;
            }
            sleepReadback("Sticker-pack readback was interrupted");
        }
        if (readback == null) {
            throw new McpException("READBACK_FAILED",
                    "Sticker pack install state did not match server readback",
                    true, stickerSetJson(before.set));
        }
        JsonObject data = stickerSetJson(readback.set);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.sticker.pack_search", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private TLRPC.TL_messages_stickerSet fetchStickerSet(
            int account, String shortName) throws McpException {
        TLRPC.TL_inputStickerSetShortName input =
                new TLRPC.TL_inputStickerSetShortName();
        input.short_name = shortName;
        TLRPC.TL_messages_getStickerSet request =
                new TLRPC.TL_messages_getStickerSet();
        request.stickerset = input;
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_stickerSet)
                || outcome.response instanceof TLRPC.TL_messages_stickerSetNotModified) {
            throw unexpectedResponse(outcome.response);
        }
        return (TLRPC.TL_messages_stickerSet) outcome.response;
    }

    private static boolean stickerSetInstalled(TLRPC.StickerSet set) {
        return set != null && ((set.flags & 1) != 0 || set.installed_date != 0
                || set.installed);
    }

    private static JsonObject stickerSetJson(TLRPC.StickerSet set) {
        JsonObject data = new JsonObject();
        data.addProperty("set_id", set == null ? "0" : Long.toString(set.id));
        data.addProperty("title", set == null || set.title == null ? "" : set.title);
        data.addProperty("short_name",
                set == null || set.short_name == null ? "" : set.short_name);
        data.addProperty("count", set == null ? 0 : set.count);
        data.addProperty("installed", stickerSetInstalled(set));
        data.addProperty("archived", set != null && set.archived);
        data.addProperty("official", set != null && set.official);
        data.addProperty("masks", set != null && set.masks);
        data.addProperty("emoji_pack", set != null && set.emojis);
        data.addProperty("text_color", set != null && set.text_color);
        return data;
    }

    private String cacheTransientDocument(TLRPC.Document document) {
        String value = "document:" + document.id + ":" + document.access_hash
                + ":" + sha256Hex(document.file_reference == null
                ? new byte[0] : document.file_reference);
        String reference;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(referenceSecret.getBytes(StandardCharsets.US_ASCII),
                    "HmacSHA256"));
            reference = "d_" + hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable error) {
            reference = "d_" + sha256Hex(referenceSecret + ":" + value);
        }
        if (transientDocuments.size() >= 512) transientDocuments.clear();
        transientDocuments.put(reference, document);
        return reference;
    }

    private JsonObject storyList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        String mode = optionalString(args, "mode", "active");
        int offsetId = optionalInt(args, "offset_id", 0, 0, Integer.MAX_VALUE);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        ArrayList<TL_stories.StoryItem> stories;
        int total;
        int maxReadId = 0;
        JsonArray pinnedToTop = new JsonArray();

        if ("active".equals(mode)) {
            TL_stories.TL_stories_peerStories response = fetchPeerStories(account, peer);
            stories = response.stories == null
                    ? new ArrayList<>() : response.stories.stories;
            maxReadId = response.stories == null ? 0 : response.stories.max_read_id;
            total = stories.size();
            if (offsetId > 0) {
                ArrayList<TL_stories.StoryItem> filtered = new ArrayList<>();
                for (TL_stories.StoryItem story : stories) {
                    if (story.id < offsetId) filtered.add(story);
                }
                stories = filtered;
            }
        } else if ("pinned".equals(mode) || "archive".equals(mode)) {
            TLObject request;
            if ("pinned".equals(mode)) {
                TL_stories.TL_stories_getPinnedStories value =
                        new TL_stories.TL_stories_getPinnedStories();
                value.peer = peer.inputPeer;
                value.offset_id = offsetId;
                value.limit = limit;
                request = value;
            } else {
                TL_stories.TL_stories_getStoriesArchive value =
                        new TL_stories.TL_stories_getStoriesArchive();
                value.peer = peer.inputPeer;
                value.offset_id = offsetId;
                value.limit = limit;
                request = value;
            }
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TL_stories.TL_stories_stories)) {
                throw unexpectedResponse(outcome.response);
            }
            TL_stories.TL_stories_stories response =
                    (TL_stories.TL_stories_stories) outcome.response;
            cachePeers(account, response.users, response.chats);
            stories = response.stories;
            total = response.count;
            for (Integer id : response.pinned_to_top) pinnedToTop.add(id);
        } else {
            invalid("mode must be active, pinned, or archive");
            return null;
        }

        JsonArray items = new JsonArray();
        int nextOffset = 0;
        int count = 0;
        for (TL_stories.StoryItem story : stories) {
            if (count++ >= limit) break;
            items.add(storyJson(account, peer, story));
            nextOffset = story.id;
        }
        PeerRef refreshed = localPeer(account, peer.dialogId, "stories_server");
        JsonObject data = peerJson(refreshed);
        data.addProperty("mode", mode);
        data.addProperty("total_count", total);
        data.addProperty("max_read_id", maxReadId);
        data.addProperty("limit", limit);
        data.addProperty("next_offset_id", nextOffset);
        data.add("pinned_to_top", pinnedToTop);
        data.add("stories", items);
        data.addProperty("source", "telegram_server_stories");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject storyGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int storyId = requiredInt(args, "story_id", 1, Integer.MAX_VALUE);
        TL_stories.StoryItem story = fetchExactStory(account, peer, storyId, true);
        return TelegramMcpServer.successEnvelope(storyJson(account, peer, story));
    }

    private JsonObject storyCanSend(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        TL_stories.TL_stories_canSendStory request =
                new TL_stories.TL_stories_canSendStory();
        request.peer = peer.inputPeer;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TL_stories.canSendStoryCount)) {
            throw unexpectedResponse(outcome.response);
        }
        JsonObject data = peerJson(peer);
        int remains = ((TL_stories.canSendStoryCount) outcome.response).count_remains;
        data.addProperty("count_remaining", remains);
        data.addProperty("can_send", remains > 0);
        data.addProperty("source", "stories.canSendStory");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject storyPublish(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "story.publish.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "story_publish", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanPublishStory(account, peer);
        String fileRef = requiredString(args, "file_ref", 66, 66);
        String captionValue = optionalString(args, "caption", "");
        FormattedText caption = captionValue.isEmpty()
                ? new FormattedText("", new ArrayList<>())
                : parseFormattedText(account, captionValue,
                optionalString(args, "parse_mode", "plain"));
        if (caption.text.length() > 4096) invalid("story caption exceeds 4096 characters");
        int period = optionalInt(args, "period", 86_400, 21_600, 172_800);
        if (period != 21_600 && period != 43_200
                && period != 86_400 && period != 172_800) {
            invalid("period must be 21600, 43200, 86400, or 172800 seconds");
        }
        ArrayList<TLRPC.InputPrivacyRule> privacy = storyPrivacyRules(account, args);
        TL_stories.TL_stories_canSendStory canSend =
                new TL_stories.TL_stories_canSendStory();
        canSend.peer = peer.inputPeer;
        RequestOutcome allowed = request(account, canSend);
        if (!(allowed.response instanceof TL_stories.canSendStoryCount)) {
            throw unexpectedResponse(allowed.response);
        }
        if (((TL_stories.canSendStoryCount) allowed.response).count_remains <= 0) {
            throw new McpException("STORY_LIMIT_REACHED",
                    "Telegram reports no remaining story publish slots", false,
                    peerJson(peer));
        }
        StagedFile staged = requireStagedFile(fileRef);
        TLRPC.InputMedia media = storyUploadedMedia(account, args, staged);
        TL_stories.TL_stories_sendStory request =
                new TL_stories.TL_stories_sendStory();
        request.peer = peer.inputPeer;
        request.media = media;
        request.privacy_rules.addAll(privacy);
        request.random_id = deterministicLong(account + ":story:" + key);
        request.pinned = optionalBoolean(args, "pinned", false);
        request.noforwards = optionalBoolean(args, "no_forwards", false);
        request.flags |= 8;
        request.period = period;
        if (!caption.text.isEmpty()) {
            request.flags |= 1;
            request.caption = caption.text;
            if (!caption.entities.isEmpty()) {
                request.flags |= 2;
                request.entities.addAll(caption.entities);
            }
        }
        String operationId = "story-publish-"
                + sha256Hex(account + ":" + peer.dialogId + ":" + key).substring(0, 24);
        JsonObject timeoutReadback = peerReadbackArguments(account, peer);
        timeoutReadback.addProperty("mode", request.pinned ? "pinned" : "active");
        timeoutReadback.addProperty("limit", MAX_LIMIT);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.story.list", timeoutReadback);
        TL_stories.StoryItem acknowledged = extractUpdatedStory(
                outcome.response, peer.dialogId, 0);
        processUpdates(account, outcome.response);
        if (acknowledged == null) {
            throw new McpException("MISSING_CREATED_OBJECT",
                    "stories.sendStory returned no updateStory object; inspect story.list before retrying",
                    true, unknownOutcomeDetails(operationId,
                    "telegram.story.list", timeoutReadback));
        }
        TL_stories.StoryItem readback = waitForStoryContent(
                account, peer, acknowledged.id, caption, args, true);
        JsonObject data = storyJson(account, peer, readback);
        data.addProperty("idempotent_replay", false);
        data.add("source_file", stagedFileJson(staged.metadata));
        JsonObject readbackArgs = storyReadbackArguments(account, peer, readback.id);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.story.get", readbackArgs);
        storeIdempotency(account, "story_publish", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject storyEdit(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int storyId = requiredInt(args, "story_id", 1, Integer.MAX_VALUE);
        TL_stories.StoryItem before = fetchExactStory(account, peer, storyId, true);
        if (!canManageStory(account, peer, before, false)) {
            throw new McpException("PERMISSION_DENIED",
                    "You cannot edit this story", false,
                    storyJson(account, peer, before));
        }
        boolean replaceMedia = args.has("file_ref");
        boolean replaceCaption = args.has("caption");
        boolean replacePrivacy = args.has("privacy");
        if (!replaceMedia && !replaceCaption && !replacePrivacy) {
            invalid("Provide file_ref, caption, or privacy to edit");
        }
        FormattedText caption = null;
        if (replaceCaption) {
            String value = requiredString(args, "caption", 0, 4096);
            caption = value.isEmpty()
                    ? new FormattedText("", new ArrayList<>())
                    : parseFormattedText(account, value,
                    optionalString(args, "parse_mode", "plain"));
        }
        TL_stories.TL_stories_editStory request =
                new TL_stories.TL_stories_editStory();
        request.peer = peer.inputPeer;
        request.id = storyId;
        StagedFile staged = null;
        if (replaceMedia) {
            staged = requireStagedFile(requiredString(args, "file_ref", 66, 66));
            request.media = storyUploadedMedia(account, args, staged);
            request.flags |= 1;
        }
        if (replaceCaption) {
            request.caption = caption.text;
            request.entities.addAll(caption.entities);
            request.flags |= 2;
        }
        if (replacePrivacy) {
            request.privacy_rules.addAll(storyPrivacyRules(account, args));
            request.flags |= 4;
        }
        String operationId = "story-edit-" + account + "-"
                + peer.dialogId + "-" + storyId + "-" + UUID.randomUUID();
        JsonObject readbackArgs = storyReadbackArguments(account, peer, storyId);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.story.get", readbackArgs);
        processUpdates(account, outcome.response);
        FormattedText expectedCaption = caption == null
                ? new FormattedText(before.caption == null ? "" : before.caption,
                before.entities == null ? new ArrayList<>() : before.entities)
                : caption;
        TL_stories.StoryItem readback = waitForStoryContent(
                account, peer, storyId, expectedCaption, args, replaceMedia);
        JsonObject data = storyJson(account, peer, readback);
        if (staged != null) data.add("source_file", stagedFileJson(staged.metadata));
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.story.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject storyCollectionList(JsonObject args, boolean archive)
            throws McpException {
        JsonObject delegated = args.deepCopy();
        delegated.addProperty("mode", archive ? "archive" : "pinned");
        return storyList(delegated);
    }

    private JsonObject storyViewsList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int storyId = requiredInt(args, "story_id", 1, Integer.MAX_VALUE);
        TL_stories.StoryItem story = fetchExactStory(account, peer, storyId, true);
        if (!canManageStory(account, peer, story, false)) {
            throw new McpException("PERMISSION_DENIED",
                    "Story viewers are available only for your own or administrable stories",
                    false, storyJson(account, peer, story));
        }
        TL_stories.TL_stories_getStoryViewsList request =
                new TL_stories.TL_stories_getStoryViewsList();
        request.peer = peer.inputPeer;
        request.id = storyId;
        request.offset = optionalString(args, "offset", "");
        request.limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        request.just_contacts = optionalBoolean(args, "contacts_only", false);
        request.reactions_first = optionalBoolean(args, "reactions_first", false);
        request.forwards_first = optionalBoolean(args, "forwards_first", false);
        String query = optionalString(args, "query", "").trim();
        if (!query.isEmpty()) {
            request.flags |= 2;
            request.q = query;
        }
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TL_stories.StoryViewsList)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_stories.StoryViewsList response =
                (TL_stories.StoryViewsList) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonArray views = new JsonArray();
        for (TL_stories.StoryView view : response.views) {
            JsonObject item = new JsonObject();
            item.addProperty("type", view.getClass().getSimpleName());
            item.addProperty("date", view.date);
            item.addProperty("blocked", view.blocked);
            item.addProperty("blocked_my_stories_from", view.blocked_my_stories_from);
            if (view.user_id != 0) {
                TLRPC.User user = MessagesController.getInstance(account).getUser(view.user_id);
                if (user != null) item.add("peer", userJson(user));
                else item.addProperty("user_id", Long.toString(view.user_id));
            }
            if (view.reaction != null) item.add("reaction", reactionJson(view.reaction));
            if (view.message != null) item.add("forward", messageJson(account, view.message));
            if (view.peer_id != null) {
                item.addProperty("repost_peer_id",
                        Long.toString(MessageObject.getPeerId(view.peer_id)));
            }
            if (view.story != null) {
                long repostDialog = view.peer_id == null
                        ? peer.dialogId : MessageObject.getPeerId(view.peer_id);
                try {
                    item.add("repost_story", storyJson(account,
                            localPeer(account, repostDialog, "story_repost"), view.story));
                } catch (McpException ignored) {
                    item.addProperty("repost_story_id", view.story.id);
                }
            }
            views.add(item);
        }
        JsonObject data = storyJson(account, peer, story);
        data.addProperty("count", response.count);
        data.addProperty("views_count", response.views_count);
        data.addProperty("forwards_count", response.forwards_count);
        data.addProperty("reactions_count", response.reactions_count);
        data.addProperty("next_offset", response.next_offset == null ? "" : response.next_offset);
        data.add("views", views);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject storyMarkRead(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int storyId = requiredInt(args, "story_id", 1, Integer.MAX_VALUE);
        if (peer.dialogId == UserConfig.getInstance(account).getClientUserId()) {
            invalid("Cannot mark your own story as read");
        }
        fetchExactStory(account, peer, storyId, true);
        TL_stories.TL_stories_readStories request = new TL_stories.TL_stories_readStories();
        request.peer = peer.inputPeer;
        request.max_id = storyId;
        String operationId = "story-read-" + account + "-" + peer.dialogId + "-" + storyId;
        JsonObject readbackArgs = storyReadbackArguments(account, peer, storyId);
        requireVectorResponse(writeRequest(account, request, operationId,
                "telegram.story.list", readbackArgs).response,
                "stories.readStories");
        TL_stories.TL_stories_peerStories readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = fetchPeerStories(account, peer);
            if (readback.stories != null && readback.stories.max_read_id >= storyId) break;
            sleepReadback("Story read-state readback was interrupted");
        }
        if (readback == null || readback.stories == null
                || readback.stories.max_read_id < storyId) {
            throw new McpException("READBACK_FAILED",
                    "Story max_read_id did not reach the requested story", true,
                    readback == null || readback.stories == null ? null
                            : storyPeerStateJson(account, peer, readback.stories));
        }
        JsonObject data = storyPeerStateJson(account, peer, readback.stories);
        data.addProperty("story_id", storyId);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.story.list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject storyReactionSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int storyId = requiredInt(args, "story_id", 1, Integer.MAX_VALUE);
        if (peer.dialogId == UserConfig.getInstance(account).getClientUserId()) {
            invalid("Cannot react to your own story");
        }
        fetchExactStory(account, peer, storyId, true);
        String emoji = optionalString(args, "reaction", "");
        String customId = optionalString(args, "custom_emoji_document_id", "");
        if (!emoji.isEmpty() && !customId.isEmpty()) {
            invalid("Provide reaction or custom_emoji_document_id, not both");
        }
        TLRPC.Reaction expected;
        if (!customId.isEmpty()) {
            long documentId;
            try {
                documentId = Long.parseLong(customId);
            } catch (NumberFormatException error) {
                invalid("custom_emoji_document_id must be a positive 64-bit integer string");
                return null;
            }
            if (documentId <= 0) invalid("custom_emoji_document_id must be positive");
            TLRPC.TL_reactionCustomEmoji value = new TLRPC.TL_reactionCustomEmoji();
            value.document_id = documentId;
            expected = value;
        } else if (!emoji.isEmpty()) {
            TLRPC.TL_reactionEmoji value = new TLRPC.TL_reactionEmoji();
            value.emoticon = emoji;
            expected = value;
        } else {
            expected = new TLRPC.TL_reactionEmpty();
        }
        TL_stories.TL_stories_sendReaction request =
                new TL_stories.TL_stories_sendReaction();
        request.peer = peer.inputPeer;
        request.story_id = storyId;
        request.reaction = expected;
        request.add_to_recent = optionalBoolean(args, "add_to_recent", true)
                && !(expected instanceof TLRPC.TL_reactionEmpty);
        String operationId = "story-reaction-" + account + "-"
                + peer.dialogId + "-" + storyId;
        JsonObject readbackArgs = storyReadbackArguments(account, peer, storyId);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.story.get", readbackArgs);
        processUpdates(account, outcome.response);
        TL_stories.StoryItem readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = fetchExactStory(account, peer, storyId, true);
            if (reactionEquals(expected, readback.sent_reaction)) break;
            sleepReadback("Story reaction readback was interrupted");
        }
        if (readback == null || !reactionEquals(expected, readback.sent_reaction)) {
            throw new McpException("READBACK_FAILED",
                    "Story reaction did not match exact server readback", true,
                    readback == null ? null : storyJson(account, peer, readback));
        }
        JsonObject data = storyJson(account, peer, readback);
        data.add("requested_reaction", reactionJson(expected));
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.story.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject storyHidePeer(JsonObject args, boolean hidden) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.dialogId == UserConfig.getInstance(account).getClientUserId()) {
            invalid("Cannot hide your own stories from your story list");
        }
        TL_stories.TL_stories_togglePeerStoriesHidden request =
                new TL_stories.TL_stories_togglePeerStoriesHidden();
        request.peer = peer.inputPeer;
        request.hidden = hidden;
        String operationId = (hidden ? "story-hide-" : "story-unhide-")
                + account + "-" + peer.dialogId;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.story.list", readbackArgs).response,
                "stories.togglePeerStoriesHidden");
        PeerRef readback = waitForPeerStoriesHidden(account, peer, hidden);
        JsonObject data = peerJson(readback);
        data.addProperty("stories_hidden", hidden);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.story.list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject storyPin(JsonObject args, boolean pinned) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int storyId = requiredInt(args, "story_id", 1, Integer.MAX_VALUE);
        TL_stories.StoryItem before = fetchExactStory(account, peer, storyId, true);
        if (!canManageStory(account, peer, before, false)) {
            throw new McpException("PERMISSION_DENIED",
                    "You cannot pin or archive this story", false,
                    storyJson(account, peer, before));
        }
        TL_stories.togglePinned request = new TL_stories.togglePinned();
        request.peer = peer.inputPeer;
        request.id.add(storyId);
        request.pinned = pinned;
        String operationId = (pinned ? "story-pin-" : "story-unpin-")
                + account + "-" + peer.dialogId + "-" + storyId;
        JsonObject readbackArgs = storyReadbackArguments(account, peer, storyId);
        requireVectorResponse(writeRequest(account, request, operationId,
                "telegram.story.get", readbackArgs).response,
                "stories.togglePinned");
        TL_stories.StoryItem readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = fetchExactStory(account, peer, storyId, true);
            if (readback.pinned == pinned) break;
            sleepReadback("Story pinned-state readback was interrupted");
        }
        if (readback == null || readback.pinned != pinned) {
            throw new McpException("READBACK_FAILED",
                    "Story pinned state did not match exact server readback", true,
                    readback == null ? null : storyJson(account, peer, readback));
        }
        JsonObject data = storyJson(account, peer, readback);
        data.addProperty("archived", !pinned);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.story.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject storyDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int storyId = requiredInt(args, "story_id", 1, Integer.MAX_VALUE);
        TL_stories.StoryItem before = fetchExactStory(account, peer, storyId, true);
        if (!canManageStory(account, peer, before, true)) {
            throw new McpException("PERMISSION_DENIED",
                    "You cannot delete this story", false,
                    storyJson(account, peer, before));
        }
        TL_stories.TL_stories_deleteStories request =
                new TL_stories.TL_stories_deleteStories();
        request.peer = peer.inputPeer;
        request.id.add(storyId);
        String operationId = "story-delete-" + account + "-"
                + peer.dialogId + "-" + storyId;
        JsonObject readbackArgs = storyReadbackArguments(account, peer, storyId);
        requireVectorResponse(writeRequest(account, request, operationId,
                "telegram.story.get", readbackArgs).response,
                "stories.deleteStories");
        TL_stories.StoryItem remaining = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            remaining = fetchExactStory(account, peer, storyId, false);
            if (remaining == null) break;
            sleepReadback("Story deletion readback was interrupted");
        }
        if (remaining != null) {
            throw new McpException("READBACK_FAILED",
                    "Deleted story remains present in exact server readback", true,
                    storyJson(account, peer, remaining));
        }
        JsonObject data = peerJson(peer);
        data.addProperty("story_id", storyId);
        data.addProperty("deleted", true);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.story.get", readbackArgs);
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
        boolean exists = !isEffectivelyEmptyDraft(draft, topicId);
        data.addProperty("exists", exists);
        data.addProperty("text", exists && draft.message != null ? draft.message : "");
        data.addProperty("date", exists ? draft.date : 0);
        data.addProperty("no_webpage", exists && draft.no_webpage);
        data.addProperty("has_media", exists && draft.media != null);
        data.addProperty("has_entities", exists && draft.entities != null && !draft.entities.isEmpty());
        data.addProperty("has_suggested_post", exists && draft.suggested_post != null);
        data.addProperty("has_rich_message", exists && draft.rich_message != null);
        data.addProperty("effect_id", exists ? Long.toString(draft.effect) : "0");
        data.addProperty("invert_media", exists && draft.invert_media);
        data.addProperty("reply_to_message_id",
                exists && draft.reply_to != null ? draft.reply_to.reply_to_msg_id : 0);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject draftSet(JsonObject args, boolean clear) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        long topicId = optionalLong(args, "topic_id", 0, 0, Integer.MAX_VALUE);
        String text = clear ? "" : requiredString(args, "text", 0, 4096);
        TLRPC.DraftMessage existing = uiCall(() -> MediaDataController.getInstance(account)
                .getDraft(peer.dialogId, topicId));
        JsonArray overwritten = richDraftFields(existing, topicId);
        boolean replace = clear || optionalBoolean(args, "replace", false);
        if (!clear && overwritten.size() > 0 && !replace) {
            JsonObject details = peerJson(peer);
            details.add("would_overwrite", overwritten);
            details.addProperty("required_argument", "replace=true");
            throw new McpException("PRECONDITION_FAILED",
                    "The existing draft contains rich state; set replace=true to discard it explicitly",
                    false, details);
        }
        String operationId = (clear ? "draft-clear-" : "draft-set-")
                + account + "-" + peer.dialogId + "-" + topicId;
        uiCall(() -> {
            MediaDataController.getInstance(account).saveDraft(
                    peer.dialogId, topicId, text, new ArrayList<>(), null,
                    null, null, 0, false, clear);
            return null;
        });
        TLRPC.DraftMessage serverDraft = waitForServerDraft(
                account, peer, topicId, text, clear || text.isEmpty());
        JsonObject data = peerJson(peer);
        data.addProperty("topic_id", topicId);
        data.addProperty("text", text);
        data.addProperty("cleared", clear);
        data.addProperty("replace", replace);
        data.add("overwritten_fields", overwritten);
        data.addProperty("server_draft_type", serverDraft == null
                ? "absent" : serverDraft.getClass().getSimpleName());
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        readbackArgs.addProperty("topic_id", topicId);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.draft.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int limit = optionalInt(args, "limit", 100, 1, MAX_LIMIT);
        TLRPC.TL_contacts_contacts response = fetchServerContacts(account);
        cachePeers(account, response.users, new ArrayList<>());
        Map<Long, TLRPC.User> users = new HashMap<>();
        for (TLRPC.User user : response.users) users.put(user.id, user);
        JsonArray contacts = new JsonArray();
        for (TLRPC.TL_contact contact : response.contacts) {
            if (contacts.size() >= limit) break;
            TLRPC.User user = users.get(contact.user_id);
            if (user != null) contacts.add(userJson(user));
        }
        JsonObject data = new JsonObject();
        data.add("contacts", contacts);
        data.addProperty("source", "telegram_server_contacts.getContacts");
        data.addProperty("complete", contacts.size() >= response.contacts.size());
        data.addProperty("total_count", response.contacts.size());
        data.addProperty("saved_count", response.saved_count);
        data.addProperty("sync_state", "fresh");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = requireUserPeer(resolvePeer(
                account, requiredString(args, "user", 1, 256)), "user");
        TLRPC.User user = serverContactUser(account, peer.dialogId);
        if (user == null) {
            JsonObject details = peerJson(peer);
            details.addProperty("contact", false);
            throw new McpException("CONTACT_NOT_FOUND",
                    "The user is not present in Telegram cloud contacts", false, details);
        }
        JsonObject data = userJson(user);
        data.addProperty("peer", canonicalPeer(
                MessagesController.getInstance(account), user.id));
        data.addProperty("contact", true);
        data.addProperty("source", "telegram_server_contacts.getContacts");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactUpsert(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "contact.upsert.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "contact_upsert", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = requireUserPeer(resolvePeer(
                account, requiredString(args, "user", 1, 256)), "user");
        if (peer.dialogId == UserConfig.getInstance(account).getClientUserId()) {
            invalid("The current account cannot be added as its own contact");
        }
        String firstName = requiredString(args, "first_name", 1, 64).trim();
        if (firstName.isEmpty()) invalid("first_name must not be blank");
        String lastName = optionalString(args, "last_name", "").trim();
        if (lastName.length() > 64) invalid("last_name is too long");
        TLRPC.User before = serverContactUser(account, peer.dialogId);
        TLRPC.TL_contacts_addContact request = new TLRPC.TL_contacts_addContact();
        request.id = MessagesController.getInstance(account).getInputUser(peer.user);
        request.first_name = firstName;
        request.last_name = lastName;
        request.phone = peer.user.phone == null ? "" : peer.user.phone;
        String operationId = "contact-upsert-" + account + "-" + peer.dialogId;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("user", canonicalPeer(
                MessagesController.getInstance(account), peer.dialogId));
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.contact.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.User readback = waitForContact(
                account, peer.dialogId, true, firstName, lastName);
        JsonObject data = userJson(readback);
        data.addProperty("peer", canonicalPeer(
                MessagesController.getInstance(account), readback.id));
        data.addProperty("contact", true);
        data.addProperty("created", before == null);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.contact.get", readbackArgs);
        storeIdempotency(account, "contact_upsert", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = requireUserPeer(resolvePeer(
                account, requiredString(args, "user", 1, 256)), "user");
        TLRPC.User before = serverContactUser(account, peer.dialogId);
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("user", canonicalPeer(
                MessagesController.getInstance(account), peer.dialogId));
        if (before == null) {
            JsonObject data = peerJson(peer);
            data.addProperty("deleted", false);
            data.addProperty("already_absent", true);
            addWriteEvidence(data,
                    "contact-delete-absent-" + account + "-" + peer.dialogId,
                    true, true, true, false,
                    "telegram.contact.get", readbackArgs);
            return TelegramMcpServer.successEnvelope(data);
        }
        TLRPC.TL_contacts_deleteContacts request = new TLRPC.TL_contacts_deleteContacts();
        request.id.add(MessagesController.getInstance(account).getInputUser(peer.user));
        String operationId = "contact-delete-" + account + "-" + peer.dialogId;
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.contact.get", readbackArgs);
        processUpdates(account, outcome.response);
        waitForContact(account, peer.dialogId, false, null, null);
        JsonObject data = userJson(before);
        data.addProperty("peer", canonicalPeer(
                MessagesController.getInstance(account), before.id));
        data.addProperty("deleted", true);
        data.addProperty("already_absent", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.contact.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject contactSearch(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String query = requiredString(args, "query", 1, 256).trim();
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        TLRPC.TL_contacts_search request = new TLRPC.TL_contacts_search();
        request.q = query;
        request.limit = limit;
        request.broadcasts = optionalBoolean(args, "include_broadcasts", true);
        request.bots = optionalBoolean(args, "include_bots", true);
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_contacts_found)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_contacts_found response = (TLRPC.TL_contacts_found) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonArray contacts = new JsonArray();
        Set<Long> seen = new HashSet<>();
        for (TLRPC.Peer result : response.my_results) {
            long dialogId = MessageObject.getPeerId(result);
            if (dialogId == 0 || !seen.add(dialogId)) continue;
            JsonObject item = peerJson(localPeer(account, dialogId, "contacts_search"));
            item.addProperty("relationship", "my_result");
            contacts.add(item);
        }
        for (TLRPC.Peer result : response.results) {
            long dialogId = MessageObject.getPeerId(result);
            if (dialogId == 0 || !seen.add(dialogId)) continue;
            JsonObject item = peerJson(localPeer(account, dialogId, "contacts_search"));
            item.addProperty("relationship", "global_result");
            contacts.add(item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("query", query);
        data.add("contacts", contacts);
        data.add("results", contacts.deepCopy());
        data.addProperty("source", "telegram_server_contacts.search");
        data.addProperty("complete", contacts.size() < limit);
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
        String operationId = "contact-block-" + account + "-" + peer.dialogId + "-" + block;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("limit", MAX_LIMIT);
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
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.contact.blocked_list", readbackArgs).response,
                block ? "contacts.block" : "contacts.unblock");
        boolean serverBlocked = serverBlockedContains(account, peer.dialogId);
        if (serverBlocked != block) {
            JsonObject details = peerJson(peer);
            details.addProperty("expected_blocked", block);
            details.addProperty("actual_blocked", serverBlocked);
            throw new McpException("READBACK_FAILED",
                    "Blocked-peer server readback did not match the requested state", true, details);
        }
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
        addWriteEvidence(data, operationId,
                true, true, true, false,
                "telegram.contact.blocked_list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatCreateGroup(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String title = requiredString(args, "title", 1, 128);
        JsonArray members = requiredArray(args, "members", 1, 200);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyPayloadHash("chat.create_group.v2",
                "title", title,
                "members", members);
        JsonObject replay = idempotencyReplay(account, "create_group", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        String operationId = "create-group-" + sha256Hex(account + ":" + key).substring(0, 24);
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
        JsonObject timeoutReadback = new JsonObject();
        timeoutReadback.addProperty("account", account);
        timeoutReadback.addProperty("limit", MAX_LIMIT);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.dialog.list", timeoutReadback);
        processUpdates(account, outcome.response);
        TLRPC.Chat created = requireCreatedChat(outcome.response, title);
        PeerRef createdPeer = localPeer(account, -created.id, "created_group");
        JsonObject readbackArgs = peerReadbackArguments(account, createdPeer);
        JsonObject readback = chatGet(readbackArgs).getAsJsonObject("data");
        if (!title.equals(readback.get("title").getAsString())) {
            throw new McpException("READBACK_FAILED",
                    "Created group title did not match chat.get", true, readback);
        }
        JsonObject data = new JsonObject();
        data.addProperty("title", title);
        data.add("created_chats", chatsFromResponse(outcome.response));
        data.add("chat", readback);
        data.addProperty("idempotent_replay", false);
        if (outcome.response instanceof TLRPC.TL_messages_invitedUsers) {
            TLRPC.TL_messages_invitedUsers value = (TLRPC.TL_messages_invitedUsers) outcome.response;
            data.addProperty("missing_invitees", value.missing_invitees.size());
        }
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        storeIdempotency(account, "create_group", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatCreateChannel(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String title = requiredString(args, "title", 1, 128);
        String about = optionalString(args, "about", "");
        if (about.length() > 255) invalid("about exceeds 255 characters");
        String kind = requiredString(args, "kind", 1, 32);
        if (!"channel".equals(kind) && !"supergroup".equals(kind)
                && !"forum".equals(kind)) {
            invalid("kind must be channel, supergroup, or forum");
        }
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyPayloadHash("chat.create_channel.v2",
                "title", title,
                "about", about,
                "kind", kind);
        JsonObject replay = idempotencyReplay(account, "create_channel", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        String operationId = "create-channel-" + sha256Hex(account + ":" + key).substring(0, 24);
        TLRPC.TL_channels_createChannel request = new TLRPC.TL_channels_createChannel();
        request.title = title;
        request.about = about;
        request.broadcast = "channel".equals(kind);
        request.megagroup = "supergroup".equals(kind) || "forum".equals(kind);
        request.forum = "forum".equals(kind);
        JsonObject timeoutReadback = new JsonObject();
        timeoutReadback.addProperty("account", account);
        timeoutReadback.addProperty("limit", MAX_LIMIT);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.dialog.list", timeoutReadback);
        processUpdates(account, outcome.response);
        TLRPC.Chat created = requireCreatedChat(outcome.response, title);
        PeerRef createdPeer = localPeer(account, -created.id, "created_channel");
        JsonObject readbackArgs = peerReadbackArguments(account, createdPeer);
        JsonObject readback = chatGet(readbackArgs).getAsJsonObject("data");
        if (!title.equals(readback.get("title").getAsString())) {
            throw new McpException("READBACK_FAILED",
                    "Created channel title did not match chat.get", true, readback);
        }
        if ("forum".equals(kind) && !readback.get("forum").getAsBoolean()) {
            throw new McpException("READBACK_FAILED",
                    "Created channel did not retain the requested forum flag", true, readback);
        }
        JsonObject data = new JsonObject();
        data.addProperty("title", title);
        data.addProperty("kind", kind);
        data.add("created_chats", chatsFromResponse(outcome.response));
        data.add("chat", readback);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        storeIdempotency(account, "create_channel", key, payloadHash, data);
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
        data.addProperty("slow_mode_seconds", full.slowmode_seconds);
        data.addProperty("auto_delete_seconds", full.ttl_period);
        data.addProperty("join_requests_pending", full.requests_pending);
        data.addProperty("participants_hidden", full.participants_hidden);
        data.addProperty("anti_spam", full.antispam);
        data.addProperty("history_visible", !full.hidden_prehistory);
        data.addProperty("linked_chat_id", Long.toString(full.linked_chat_id));
        TLRPC.Chat refreshed = MessagesController.getInstance(account).getChat(peer.chat.id);
        TLRPC.Chat effectiveChat = refreshed == null ? peer.chat : refreshed;
        data.addProperty("signatures", effectiveChat.signatures);
        data.addProperty("signature_profiles", effectiveChat.signature_profiles);
        data.add("available_reactions", chatReactionsJson(full.available_reactions));
        data.addProperty("reactions_limit", full.reactions_limit);
        data.addProperty("paid_reactions_enabled", full.paid_reactions_available);
        data.add("default_permissions", bannedRightsJson(
                effectiveChat.default_banned_rights));
        data.addProperty("source", "messages.getFullChat");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject chatPhotoUpload(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "chat.photo_upload.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "chat_photo_upload", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(
                account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        if (!ChatObject.canChangeChatInfo(peer.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current chat rights do not allow changing the avatar",
                    false, peerJson(peer));
        }
        StagedFile staged = requireStagedFile(
                requiredString(args, "file_ref", 1, 128));
        String mime = staged.metadata.get("mime_type").getAsString();
        if (!mime.startsWith("image/")) {
            invalid("chat.photo_upload requires a staged image MIME type");
        }
        long beforePhotoId = chatPhotoId(fetchChatServer(account, peer));
        TLRPC.InputFile uploaded = uploadStagedFile(account, staged);
        TLRPC.TL_inputChatUploadedPhoto photo =
                new TLRPC.TL_inputChatUploadedPhoto();
        photo.file = uploaded;
        photo.flags |= 1;
        TLObject request;
        if (ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_editPhoto value = new TLRPC.TL_channels_editPhoto();
            value.channel = MessagesController.getInputChannel(peer.chat);
            value.photo = photo;
            request = value;
        } else {
            TLRPC.TL_messages_editChatPhoto value =
                    new TLRPC.TL_messages_editChatPhoto();
            value.chat_id = peer.chat.id;
            value.photo = photo;
            request = value;
        }
        String operationId = "chat-photo-upload-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        processUpdates(account, outcome.response);
        TLRPC.Chat readback = waitForChatPhoto(
                account, peer, true, beforePhotoId);
        JsonObject data = chatJson(readback);
        data.addProperty("file_ref",
                staged.metadata.get("file_ref").getAsString());
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        storeIdempotency(account, "chat_photo_upload", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatPhotoClear(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(
                account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        if (!ChatObject.canChangeChatInfo(peer.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current chat rights do not allow changing the avatar",
                    false, peerJson(peer));
        }
        TLRPC.Chat before = fetchChatServer(account, peer);
        if (chatPhotoId(before) == 0) {
            JsonObject data = chatJson(before);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TLRPC.TL_inputChatPhotoEmpty empty = new TLRPC.TL_inputChatPhotoEmpty();
        TLObject request;
        if (ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_editPhoto value = new TLRPC.TL_channels_editPhoto();
            value.channel = MessagesController.getInputChannel(peer.chat);
            value.photo = empty;
            request = value;
        } else {
            TLRPC.TL_messages_editChatPhoto value =
                    new TLRPC.TL_messages_editChatPhoto();
            value.chat_id = peer.chat.id;
            value.photo = empty;
            request = value;
        }
        String operationId = "chat-photo-clear-" + account + "-" + peer.chat.id;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        processUpdates(account, outcome.response);
        JsonObject data = chatJson(waitForChatPhoto(account, peer, false, 0));
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
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

    private JsonObject chatMemberGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        PeerRef member = resolvePeer(account,
                requiredString(args, "member", 1, 256));
        if (chat.chat == null || member.user == null) {
            invalid("peer must be a chat and member must resolve to a user");
        }
        ChatMemberState state = fetchChatMemberState(account, chat, member);
        JsonObject data = peerJson(chat);
        data.add("member", state.data);
        data.addProperty("source", state.source);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatMemberAdd(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        PeerRef member = resolvePeer(account,
                requiredString(args, "member", 1, 256));
        if (chat.chat == null || member.user == null) {
            invalid("peer must be a chat and member must resolve to a user");
        }
        if (!ChatObject.canAddUsers(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not add members to this chat", false, peerJson(chat));
        }
        ChatMemberState before = fetchChatMemberState(account, chat, member);
        if (before.present) {
            JsonObject replay = peerJson(chat);
            replay.add("member", before.data);
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        TLObject request;
        if (ChatObject.isChannel(chat.chat)) {
            TLRPC.TL_channels_inviteToChannel value =
                    new TLRPC.TL_channels_inviteToChannel();
            value.channel = MessagesController.getInputChannel(chat.chat);
            value.users.add(MessagesController.getInstance(account)
                    .getInputUser(member.user));
            request = value;
        } else {
            TLRPC.TL_messages_addChatUser value = new TLRPC.TL_messages_addChatUser();
            value.chat_id = chat.chat.id;
            value.user_id = MessagesController.getInstance(account)
                    .getInputUser(member.user);
            value.fwd_limit = optionalInt(args, "history_limit", 0, 0, 100);
            request = value;
        }
        String operationId = "chat-member-add-" + account + "-" + chat.chat.id
                + "-" + member.user.id;
        JsonObject readbackArgs = memberReadbackArguments(account, chat, member);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.member_get", readbackArgs);
        processUpdates(account, outcome.response);
        ChatMemberState after = waitForChatMemberState(
                account, chat, member, true, null, null);
        JsonObject data = peerJson(chat);
        data.add("member", after.data);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.member_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatMemberRemove(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        PeerRef member = resolvePeer(account,
                requiredString(args, "member", 1, 256));
        if (chat.chat == null || member.user == null) {
            invalid("peer must be a chat and member must resolve to a user");
        }
        if (member.user.id == UserConfig.getInstance(account).getClientUserId()) {
            throw new McpException("SELF_MEMBER_PROTECTED",
                    "Use telegram.chat.leave to remove the current account", false,
                    peerJson(member));
        }
        if (!ChatObject.canBlockUsers(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not remove members from this chat", false,
                    peerJson(chat));
        }
        boolean ban = optionalBoolean(args, "ban", false);
        ChatMemberState before = fetchChatMemberState(account, chat, member);
        if (!before.present && before.banned == ban) {
            JsonObject replay = peerJson(chat);
            replay.add("member", before.data);
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        String operationId = "chat-member-remove-" + account + "-" + chat.chat.id
                + "-" + member.user.id + "-" + ban;
        JsonObject readbackArgs = memberReadbackArguments(account, chat, member);
        if (ChatObject.isChannel(chat.chat)) {
            TLRPC.TL_channels_editBanned remove = new TLRPC.TL_channels_editBanned();
            remove.channel = MessagesController.getInputChannel(chat.chat);
            remove.participant = member.inputPeer;
            remove.banned_rights = new TLRPC.TL_chatBannedRights();
            remove.banned_rights.view_messages = true;
            remove.banned_rights.until_date = 0;
            RequestOutcome removed = writeRequest(account, remove, operationId,
                    "telegram.chat.member_get", readbackArgs);
            processUpdates(account, removed.response);
            waitForChatMemberState(account, chat, member, false, true, null);
            if (!ban) {
                TLRPC.TL_channels_editBanned unban =
                        new TLRPC.TL_channels_editBanned();
                unban.channel = MessagesController.getInputChannel(chat.chat);
                unban.participant = member.inputPeer;
                unban.banned_rights = new TLRPC.TL_chatBannedRights();
                unban.banned_rights.until_date = 0;
                RequestOutcome unbanned = writeRequest(account, unban,
                        operationId + "-unban", "telegram.chat.member_get", readbackArgs);
                processUpdates(account, unbanned.response);
            }
        } else {
            TLRPC.TL_messages_deleteChatUser remove =
                    new TLRPC.TL_messages_deleteChatUser();
            remove.chat_id = chat.chat.id;
            remove.user_id = MessagesController.getInstance(account)
                    .getInputUser(member.user);
            remove.revoke_history = optionalBoolean(args, "revoke_history", false);
            RequestOutcome removed = writeRequest(account, remove, operationId,
                    "telegram.chat.member_get", readbackArgs);
            processUpdates(account, removed.response);
        }
        ChatMemberState after = waitForChatMemberState(
                account, chat, member, false,
                ChatObject.isChannel(chat.chat) ? ban : null, null);
        JsonObject data = peerJson(chat);
        data.add("member", after.data);
        data.addProperty("banned", after.banned);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.member_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatMemberAdminSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        PeerRef member = resolvePeer(account,
                requiredString(args, "member", 1, 256));
        if (chat.chat == null || member.user == null) {
            invalid("peer must be a chat and member must resolve to a user");
        }
        if (!ChatObject.canAddAdmins(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not change administrator roles", false,
                    peerJson(chat));
        }
        boolean admin = optionalBoolean(args, "admin", true);
        JsonObject rightsObject = args.has("rights") && args.get("rights").isJsonObject()
                ? args.getAsJsonObject("rights") : new JsonObject();
        if (admin && rightsObject.size() == 0 && ChatObject.isChannel(chat.chat)) {
            invalid("rights is required when promoting a channel or supergroup administrator");
        }
        String rank = optionalString(args, "rank", "");
        if (rank.length() > 16) invalid("rank exceeds 16 characters");
        TLObject request;
        if (ChatObject.isChannel(chat.chat)) {
            TLRPC.TL_channels_editAdmin value = new TLRPC.TL_channels_editAdmin();
            value.channel = MessagesController.getInputChannel(chat.chat);
            value.user_id = MessagesController.getInstance(account)
                    .getInputUser(member.user);
            value.admin_rights = admin
                    ? adminRightsFromJson(rightsObject) : new TLRPC.TL_chatAdminRights();
            if (!rank.isEmpty()) {
                value.flags |= 1;
                value.rank = rank;
            }
            request = value;
        } else {
            if (rightsObject.size() != 0 || !rank.isEmpty()) {
                invalid("Basic groups support only the admin boolean; migrate to a supergroup for granular rights");
            }
            TLRPC.TL_messages_editChatAdmin value =
                    new TLRPC.TL_messages_editChatAdmin();
            value.chat_id = chat.chat.id;
            value.user_id = MessagesController.getInstance(account)
                    .getInputUser(member.user);
            value.is_admin = admin;
            request = value;
        }
        String operationId = "chat-admin-" + account + "-" + chat.chat.id
                + "-" + member.user.id + "-" + admin;
        JsonObject readbackArgs = memberReadbackArguments(account, chat, member);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.member_get", readbackArgs);
        if (!ChatObject.isChannel(chat.chat)) {
            requireBoolTrue(outcome.response, "messages.editChatAdmin");
        } else {
            processUpdates(account, outcome.response);
        }
        ChatMemberState after = waitForChatMemberState(
                account, chat, member, true, false, admin ? "admin" : "member");
        JsonObject data = peerJson(chat);
        data.add("member", after.data);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.member_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatMemberRestrict(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        PeerRef member = resolvePeer(account,
                requiredString(args, "member", 1, 256));
        if (chat.chat == null || member.user == null || !ChatObject.isChannel(chat.chat)) {
            invalid("member restrictions require a channel or supergroup and a user member");
        }
        if (!ChatObject.canBlockUsers(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not restrict members", false, peerJson(chat));
        }
        if (!args.has("allowed") || !args.get("allowed").isJsonObject()) {
            invalid("allowed must be an object of explicit permission overrides");
        }
        ChatMemberState before = fetchChatMemberState(account, chat, member);
        if (!before.present) {
            throw new McpException("MEMBER_NOT_FOUND",
                    "Restrict requires a current member; use member_remove for a ban",
                    false, before.data);
        }
        TLRPC.TL_chatBannedRights current = before.channelParticipant == null
                ? null : before.channelParticipant.banned_rights;
        int untilDate = optionalInt(args, "until_date", 0, 0, Integer.MAX_VALUE);
        JsonObject requestedAllowed = args.getAsJsonObject("allowed");
        TLRPC.TL_chatBannedRights rights = bannedRightsFromAllowed(
                requestedAllowed, current, untilDate);
        TLRPC.TL_channels_editBanned request = new TLRPC.TL_channels_editBanned();
        request.channel = MessagesController.getInputChannel(chat.chat);
        request.participant = member.inputPeer;
        request.banned_rights = rights;
        String operationId = "chat-restrict-" + account + "-" + chat.chat.id
                + "-" + member.user.id + "-" + UUID.randomUUID();
        JsonObject readbackArgs = memberReadbackArguments(account, chat, member);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.member_get", readbackArgs);
        processUpdates(account, outcome.response);
        ChatMemberState after = waitForChatMemberRights(
                account, chat, member, requestedAllowed, untilDate);
        JsonObject data = peerJson(chat);
        data.add("member", after.data);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.member_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatPermissionsGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null) invalid("peer must be a group or channel");
        TLRPC.Chat refreshed = fetchChatServer(account, chat);
        JsonObject data = peerJson(chat);
        data.add("default_permissions", bannedRightsJson(
                refreshed.default_banned_rights));
        data.addProperty("source", "messages.getChats/channels.getChannels");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatPermissionsSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null) invalid("peer must be a group or channel");
        if (!ChatObject.canBlockUsers(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not change default member permissions", false,
                    peerJson(chat));
        }
        if (!args.has("allowed") || !args.get("allowed").isJsonObject()) {
            invalid("allowed must be an object of explicit permission overrides");
        }
        TLRPC.Chat before = fetchChatServer(account, chat);
        JsonObject requestedAllowed = args.getAsJsonObject("allowed");
        if (requestedAllowedMatches(
                requestedAllowed, before.default_banned_rights)) {
            JsonObject data = peerJson(chat);
            data.add("default_permissions",
                    bannedRightsJson(before.default_banned_rights));
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TLRPC.TL_chatBannedRights rights = bannedRightsFromAllowed(
                requestedAllowed, before.default_banned_rights, 0);
        TLRPC.TL_messages_editChatDefaultBannedRights request =
                new TLRPC.TL_messages_editChatDefaultBannedRights();
        request.peer = chat.inputPeer;
        request.banned_rights = rights;
        String operationId = "chat-default-rights-" + account + "-" + chat.chat.id
                + "-" + UUID.randomUUID();
        JsonObject readbackArgs = peerReadbackArguments(account, chat);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.permissions_get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.Chat after = waitForDefaultPermissions(
                account, chat, requestedAllowed);
        JsonObject data = peerJson(chat);
        data.add("default_permissions", bannedRightsJson(after.default_banned_rights));
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.permissions_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatInviteList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null) invalid("peer must be a group or channel");
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        boolean revoked = optionalBoolean(args, "revoked", false);
        TLRPC.TL_messages_getExportedChatInvites request =
                new TLRPC.TL_messages_getExportedChatInvites();
        request.revoked = revoked;
        request.peer = chat.inputPeer;
        request.admin_id = new TLRPC.TL_inputUserSelf();
        request.offset_date = optionalInt(args, "offset_date", 0, 0, Integer.MAX_VALUE);
        request.offset_link = optionalString(args, "offset_link", "");
        if (request.offset_date != 0 || !request.offset_link.isEmpty()) request.flags |= 4;
        request.limit = limit;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_exportedChatInvites)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_exportedChatInvites response =
                (TLRPC.TL_messages_exportedChatInvites) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        JsonArray invites = new JsonArray();
        int nextDate = 0;
        String nextLink = "";
        for (TLRPC.ExportedChatInvite invite : response.invites) {
            if (!(invite instanceof TLRPC.TL_chatInviteExported)) continue;
            TLRPC.TL_chatInviteExported exported =
                    (TLRPC.TL_chatInviteExported) invite;
            invites.add(inviteJson(exported));
            nextDate = exported.date;
            nextLink = exported.link;
        }
        JsonObject data = peerJson(chat);
        data.add("invites", invites);
        data.addProperty("count", response.count);
        data.addProperty("limit", limit);
        data.addProperty("revoked", revoked);
        JsonObject cursor = new JsonObject();
        cursor.addProperty("offset_date", nextDate);
        cursor.addProperty("offset_link", nextLink);
        data.add("next_cursor", cursor);
        data.addProperty("source", "messages.getExportedChatInvites");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatInviteCreate(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "chat.invite_create.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "invite_create", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null) invalid("peer must be a group or channel");
        if (!ChatObject.canAddUsers(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not create invite links", false, peerJson(chat));
        }
        int expireDate = optionalInt(args, "expire_date", 0, 0, Integer.MAX_VALUE);
        if (expireDate != 0
                && expireDate <= ConnectionsManager.getInstance(account).getCurrentTime()) {
            invalid("expire_date must be a future Unix timestamp or 0");
        }
        int usageLimit = optionalInt(args, "usage_limit", 0, 0, 100000);
        boolean requestNeeded = optionalBoolean(args, "request_needed", false);
        String title = optionalString(args, "title", "");
        if (title.length() > 32) invalid("title exceeds 32 characters");
        TLRPC.TL_messages_exportChatInvite request =
                new TLRPC.TL_messages_exportChatInvite();
        request.peer = chat.inputPeer;
        if (expireDate != 0) {
            request.flags |= 1;
            request.expire_date = expireDate;
        }
        if (usageLimit != 0) {
            request.flags |= 2;
            request.usage_limit = usageLimit;
        }
        request.request_needed = requestNeeded;
        if (!title.isEmpty()) {
            request.flags |= 16;
            request.title = title;
        }
        String operationId = "chat-invite-create-"
                + sha256Hex(account + ":" + chat.chat.id + ":" + key).substring(0, 24);
        JsonObject timeoutReadback = peerReadbackArguments(account, chat);
        timeoutReadback.addProperty("revoked", false);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.invite_list", timeoutReadback);
        if (!(outcome.response instanceof TLRPC.TL_chatInviteExported)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_chatInviteExported created =
                (TLRPC.TL_chatInviteExported) outcome.response;
        TLRPC.TL_chatInviteExported readback = fetchExportedInvite(
                account, chat, created.link);
        JsonObject data = peerJson(chat);
        data.add("invite", inviteJson(readback));
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = peerReadbackArguments(account, chat);
        readbackArgs.addProperty("link", created.link);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.chat.invite_list", readbackArgs);
        storeIdempotency(account, "invite_create", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatInviteRevoke(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null) invalid("peer must be a group or channel");
        String link = requiredString(args, "link", 12, 512);
        TLRPC.TL_chatInviteExported before = fetchExportedInvite(account, chat, link);
        if (before.revoked) {
            JsonObject replay = peerJson(chat);
            replay.add("invite", inviteJson(before));
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        TLRPC.TL_messages_editExportedChatInvite request =
                new TLRPC.TL_messages_editExportedChatInvite();
        request.revoked = true;
        request.peer = chat.inputPeer;
        request.link = link;
        String operationId = "chat-invite-revoke-" + account + "-" + chat.chat.id
                + "-" + sha256Hex(link).substring(0, 16);
        JsonObject readbackArgs = peerReadbackArguments(account, chat);
        readbackArgs.addProperty("link", link);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.invite_list", readbackArgs);
        if (!(outcome.response instanceof TLRPC.messages_ExportedChatInvite)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_chatInviteExported after = waitForInviteRevoked(
                account, chat, link);
        JsonObject data = peerJson(chat);
        data.add("invite", inviteJson(after));
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.chat.invite_list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatJoinRequestList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null) invalid("peer must be a group or channel");
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        String query = optionalString(args, "query", "");
        if (query.length() > 256) invalid("query exceeds 256 characters");
        TLRPC.TL_messages_getChatInviteImporters request =
                new TLRPC.TL_messages_getChatInviteImporters();
        request.requested = true;
        request.peer = chat.inputPeer;
        request.q = query;
        if (!query.isEmpty()) request.flags |= 4;
        request.offset_date = optionalInt(args, "offset_date", 0, 0, Integer.MAX_VALUE);
        request.offset_user = new TLRPC.TL_inputUserEmpty();
        if (args.has("offset_user")) {
            PeerRef offset = resolvePeer(account,
                    requiredString(args, "offset_user", 1, 256));
            if (offset.user == null) invalid("offset_user must resolve to a user");
            request.offset_user = MessagesController.getInstance(account)
                    .getInputUser(offset.user);
        }
        request.limit = limit;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_chatInviteImporters)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_chatInviteImporters response =
                (TLRPC.TL_messages_chatInviteImporters) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        JsonArray requests = new JsonArray();
        int nextDate = 0;
        String nextUser = "";
        for (TLRPC.TL_chatInviteImporter importer : response.importers) {
            JsonObject item = new JsonObject();
            item.add("user", peerJson(localPeer(account, importer.user_id,
                    "join_request")));
            item.addProperty("date", importer.date);
            item.addProperty("about", importer.about == null ? "" : importer.about);
            item.addProperty("via_chatlist", importer.via_chatlist);
            requests.add(item);
            nextDate = importer.date;
            nextUser = "user:" + importer.user_id;
        }
        JsonObject data = peerJson(chat);
        data.add("requests", requests);
        data.addProperty("count", response.count);
        data.addProperty("limit", limit);
        JsonObject cursor = new JsonObject();
        cursor.addProperty("offset_date", nextDate);
        cursor.addProperty("offset_user", nextUser);
        data.add("next_cursor", cursor);
        data.addProperty("source", "messages.getChatInviteImporters");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatJoinRequestDecide(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        PeerRef user = resolvePeer(account, requiredString(args, "user", 1, 256));
        if (chat.chat == null || user.user == null) {
            invalid("peer must be a chat and user must resolve to a user");
        }
        boolean approve = requiredBoolean(args, "approve");
        if (!joinRequestExists(account, chat, user.user.id)) {
            JsonObject replay = peerJson(chat);
            replay.add("user", peerJson(user));
            replay.addProperty("pending", false);
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        TLRPC.TL_messages_hideChatJoinRequest request =
                new TLRPC.TL_messages_hideChatJoinRequest();
        request.approved = approve;
        request.peer = chat.inputPeer;
        request.user_id = MessagesController.getInstance(account)
                .getInputUser(user.user);
        String operationId = "chat-join-request-" + account + "-" + chat.chat.id
                + "-" + user.user.id + "-" + approve;
        JsonObject readbackArgs = peerReadbackArguments(account, chat);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.join_request_list", readbackArgs);
        processUpdates(account, outcome.response);
        waitForJoinRequestAbsent(account, chat, user.user.id);
        JsonObject data = peerJson(chat);
        data.add("user", peerJson(user));
        data.addProperty("approved", approve);
        data.addProperty("pending", false);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.join_request_list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatAdminLog(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null || !ChatObject.isChannel(chat.chat)) {
            invalid("admin_log requires a channel or supergroup");
        }
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        TLRPC.TL_channels_getAdminLog request = new TLRPC.TL_channels_getAdminLog();
        request.channel = MessagesController.getInputChannel(chat.chat);
        request.q = optionalString(args, "query", "");
        request.max_id = optionalLong(args, "max_id", 0, 0, Long.MAX_VALUE);
        request.min_id = optionalLong(args, "min_id", 0, 0, Long.MAX_VALUE);
        request.limit = limit;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_channels_adminLogResults)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_channels_adminLogResults response =
                (TLRPC.TL_channels_adminLogResults) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonArray events = new JsonArray();
        long nextMaxId = 0;
        for (TLRPC.TL_channelAdminLogEvent event : response.events) {
            JsonObject item = new JsonObject();
            item.addProperty("event_id", Long.toString(event.id));
            item.addProperty("date", event.date);
            item.addProperty("actor", "user:" + event.user_id);
            item.addProperty("action_type", event.action == null
                    ? "unknown" : event.action.getClass().getSimpleName());
            events.add(item);
            nextMaxId = event.id;
        }
        JsonObject data = peerJson(chat);
        data.add("events", events);
        data.addProperty("limit", limit);
        data.addProperty("next_max_id", Long.toString(nextMaxId));
        data.addProperty("source", "channels.getAdminLog");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatUsernameSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null || !ChatObject.isChannel(chat.chat)) {
            invalid("username_set requires a channel or supergroup");
        }
        if (!ChatObject.canChangeChatInfo(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not change this chat username", false,
                    peerJson(chat));
        }
        String username = requiredString(args, "username", 0, 32);
        if (!username.isEmpty()
                && !username.matches("[A-Za-z][A-Za-z0-9_]{3,31}")) {
            invalid("username must begin with a letter and contain 4 to 32 letters, digits, or underscores");
        }
        JsonObject readbackArgs = peerReadbackArguments(account, chat);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        if (username.equals(before.get("username").getAsString())) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        TLRPC.TL_channels_updateUsername request =
                new TLRPC.TL_channels_updateUsername();
        request.channel = MessagesController.getInputChannel(chat.chat);
        request.username = username;
        String operationId = "chat-username-" + account + "-" + chat.chat.id;
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs).response,
                "channels.updateUsername");
        JsonObject after = waitForChatField(account, chat, "username", username);
        JsonObject data = peerJson(chat);
        data.addProperty("username", username);
        data.add("server_chat", after);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatSlowModeSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null || !ChatObject.isChannel(chat.chat)
                || !chat.chat.megagroup) {
            invalid("slow_mode_set requires a supergroup");
        }
        if (!ChatObject.canBlockUsers(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not change slow mode", false, peerJson(chat));
        }
        int seconds = requiredInt(args, "seconds", 0, 3600);
        if (seconds != 0 && seconds != 10 && seconds != 30 && seconds != 60
                && seconds != 300 && seconds != 900 && seconds != 3600) {
            invalid("seconds must be 0, 10, 30, 60, 300, 900, or 3600");
        }
        String operationId = "chat-slow-mode-" + account + "-" + chat.chat.id;
        JsonObject readbackArgs = peerReadbackArguments(account, chat);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        if (before.get("slow_mode_seconds").getAsInt() == seconds) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        TLRPC.TL_channels_toggleSlowMode request =
                new TLRPC.TL_channels_toggleSlowMode();
        request.channel = MessagesController.getInputChannel(chat.chat);
        request.seconds = seconds;
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        processUpdates(account, outcome.response);
        JsonObject after = waitForChatIntField(
                account, chat, "slow_mode_seconds", seconds);
        JsonObject data = peerJson(chat);
        data.addProperty("slow_mode_seconds", seconds);
        data.add("server_chat", after);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatAutoDeleteSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef chat = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (chat.chat == null) invalid("peer must be a group or channel");
        if (!ChatObject.canChangeChatInfo(chat.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not change chat auto-delete", false,
                    peerJson(chat));
        }
        int seconds = requiredInt(args, "seconds", 0, 31_536_000);
        String operationId = "chat-auto-delete-" + account + "-" + chat.chat.id;
        JsonObject readbackArgs = peerReadbackArguments(account, chat);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        if (before.get("auto_delete_seconds").getAsInt() == seconds) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        TLRPC.TL_messages_setHistoryTTL request =
                new TLRPC.TL_messages_setHistoryTTL();
        request.peer = chat.inputPeer;
        request.period = seconds;
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        processUpdates(account, outcome.response);
        JsonObject after = waitForChatIntField(
                account, chat, "auto_delete_seconds", seconds);
        JsonObject data = peerJson(chat);
        data.addProperty("auto_delete_seconds", seconds);
        data.add("server_chat", after);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatReactionsGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        JsonObject full = chatGet(peerReadbackArguments(account, peer))
                .getAsJsonObject("data");
        JsonObject data = peerJson(peer);
        data.add("available_reactions", full.get("available_reactions").deepCopy());
        data.addProperty("reactions_limit", full.get("reactions_limit").getAsInt());
        data.addProperty("paid_reactions_enabled",
                full.get("paid_reactions_enabled").getAsBoolean());
        data.addProperty("source", "telegram_server_messages.getFullChat");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatReactionsSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        if (!ChatObject.canChangeChatInfo(peer.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account cannot change this chat's reaction policy",
                    false, peerJson(peer));
        }
        String mode = requiredString(args, "mode", 3, 8);
        TLRPC.ChatReactions available;
        JsonArray requestedReactions = args.has("reactions")
                ? requiredArray(args, "reactions", 0, 100) : new JsonArray();
        boolean allowCustom = optionalBoolean(args, "allow_custom", false);
        if ("none".equals(mode)) {
            if (requestedReactions.size() != 0) invalid("none mode cannot include reactions");
            if (allowCustom) invalid("allow_custom is valid only for all mode");
            available = new TLRPC.TL_chatReactionsNone();
        } else if ("all".equals(mode)) {
            if (requestedReactions.size() != 0) invalid("all mode cannot include reactions");
            TLRPC.TL_chatReactionsAll all = new TLRPC.TL_chatReactionsAll();
            all.allow_custom = allowCustom;
            available = all;
        } else if ("some".equals(mode)) {
            if (requestedReactions.size() == 0) invalid("some mode requires reactions");
            if (allowCustom) invalid("allow_custom is valid only for all mode");
            TLRPC.TL_chatReactionsSome some = new TLRPC.TL_chatReactionsSome();
            HashSet<String> unique = new HashSet<>();
            for (JsonElement value : requestedReactions) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    invalid("reactions must contain emoji or custom:<document_id> strings");
                }
                String token = value.getAsString();
                if (token.isEmpty() || !unique.add(token)) {
                    invalid("reactions must contain unique non-empty values");
                }
                if (token.startsWith("custom:")) {
                    long id;
                    try {
                        id = Long.parseLong(token.substring("custom:".length()));
                    } catch (NumberFormatException error) {
                        invalid("custom reaction must be custom:<positive_document_id>");
                        return null;
                    }
                    if (id <= 0) invalid("custom reaction document ID must be positive");
                    TLRPC.TL_reactionCustomEmoji reaction =
                            new TLRPC.TL_reactionCustomEmoji();
                    reaction.document_id = id;
                    some.reactions.add(reaction);
                } else {
                    TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
                    reaction.emoticon = token;
                    some.reactions.add(reaction);
                }
            }
            available = some;
        } else {
            invalid("mode must be none, all, or some");
            return null;
        }
        JsonObject expected = chatReactionsJson(available);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        int desiredLimit = args.has("limit")
                ? requiredInt(args, "limit", 0, 100) : 0;
        boolean desiredPaid = args.has("paid_enabled")
                && requiredBoolean(args, "paid_enabled");
        boolean limitMatches = !args.has("limit")
                || before.get("reactions_limit").getAsInt() == desiredLimit;
        boolean paidMatches = !args.has("paid_enabled")
                || before.get("paid_reactions_enabled").getAsBoolean() == desiredPaid;
        if (chatReactionsSemanticsEqual(expected,
                before.getAsJsonObject("available_reactions"))
                && limitMatches && paidMatches) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        TLRPC.TL_messages_setChatAvailableReactions request =
                new TLRPC.TL_messages_setChatAvailableReactions();
        request.peer = peer.inputPeer;
        request.available_reactions = available;
        if (args.has("limit")) {
            request.flags |= 1;
            request.reactions_limit = desiredLimit;
        }
        if (args.has("paid_enabled")) {
            request.flags |= 2;
            request.paid_enabled = desiredPaid;
        }
        String operationId = "chat-reactions-" + account + "-" + peer.chat.id;
        RequestOutcome outcome;
        try {
            outcome = writeRequest(account, request, operationId,
                    "telegram.chat.reactions_get", readbackArgs);
        } catch (McpException error) {
            if (!telegramErrorIs(error, "CHAT_NOT_MODIFIED")) {
                throw error;
            }
            // Telegram may normalize a new chat's implicit/default reaction
            // policy differently from messages.getFullChat, while still
            // confirming that the requested explicit policy is a no-op.
            // CHAT_NOT_MODIFIED is a definitive server-side idempotency signal.
            JsonObject unchanged = chatGet(readbackArgs).getAsJsonObject("data");
            unchanged.addProperty("idempotent_replay", true);
            unchanged.addProperty("telegram_noop_confirmed", true);
            return TelegramMcpServer.successEnvelope(unchanged);
        }
        processUpdates(account, outcome.response);
        JsonObject readback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            readback = chatGet(readbackArgs).getAsJsonObject("data");
            boolean reactionsMatch = chatReactionsSemanticsEqual(expected,
                    readback.getAsJsonObject("available_reactions"));
            boolean actualLimitMatches = !args.has("limit")
                    || readback.get("reactions_limit").getAsInt() == desiredLimit;
            boolean actualPaidMatches = !args.has("paid_enabled")
                    || readback.get("paid_reactions_enabled").getAsBoolean() == desiredPaid;
            if (reactionsMatch && actualLimitMatches && actualPaidMatches) break;
            sleepReadback("Chat reaction-policy readback was interrupted");
        }
        if (readback == null
                || !chatReactionsSemanticsEqual(expected,
                readback.getAsJsonObject("available_reactions"))
                || args.has("limit")
                && readback.get("reactions_limit").getAsInt() != desiredLimit
                || args.has("paid_enabled")
                && readback.get("paid_reactions_enabled").getAsBoolean() != desiredPaid) {
            throw new McpException("READBACK_FAILED",
                    "Chat reaction policy did not match server full-chat readback",
                    true, readback);
        }
        readback.addProperty("idempotent_replay", false);
        addWriteEvidence(readback, operationId, true, true, true, false,
                "telegram.chat.reactions_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(readback);
    }

    private JsonObject chatSignaturesSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null || !ChatObject.isChannel(peer.chat)
                || !peer.chat.broadcast) {
            invalid("peer must be a broadcast channel");
        }
        if (!ChatObject.canChangeChatInfo(peer.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account cannot change channel signatures",
                    false, peerJson(peer));
        }
        boolean enabled = requiredBoolean(args, "enabled");
        boolean profiles = optionalBoolean(args, "profiles", false);
        if (profiles && !enabled) invalid("profiles requires enabled=true");
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        if (before.get("signatures").getAsBoolean() == enabled
                && before.get("signature_profiles").getAsBoolean() == profiles) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        TLRPC.TL_channels_toggleSignatures request =
                new TLRPC.TL_channels_toggleSignatures();
        request.channel = MessagesController.getInputChannel(peer.chat);
        request.signatures_enabled = enabled;
        request.profiles_enabled = profiles;
        String operationId = "chat-signatures-" + account + "-" + peer.chat.id;
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        processUpdates(account, outcome.response);
        JsonObject readback = waitForChatBooleanFields(account, peer,
                "signatures", enabled, "signature_profiles", profiles);
        readback.addProperty("idempotent_replay", false);
        addWriteEvidence(readback, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(readback);
    }

    private JsonObject chatLinkedSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef broadcast = resolvePeer(account,
                requiredString(args, "peer", 1, 256));
        if (broadcast.chat == null || !ChatObject.isChannel(broadcast.chat)
                || !broadcast.chat.broadcast) {
            invalid("peer must be a broadcast channel");
        }
        if (!ChatObject.canChangeChatInfo(broadcast.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account cannot change this channel's discussion group",
                    false, peerJson(broadcast));
        }
        String groupToken = optionalString(args, "group_peer", "");
        if (groupToken.length() > 256) invalid("group_peer exceeds 256 characters");
        PeerRef group = null;
        long expectedLinkedId = 0;
        if (!groupToken.isEmpty()) {
            group = resolvePeer(account, groupToken);
            if (group.chat == null || !ChatObject.isChannel(group.chat)
                    || !group.chat.megagroup) {
                invalid("group_peer must be a supergroup, or empty to unlink");
            }
            expectedLinkedId = group.chat.id;
        }
        JsonObject readbackArgs = peerReadbackArguments(account, broadcast);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        if (before.get("linked_chat_id").getAsLong() == expectedLinkedId) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        TLRPC.TL_channels_setDiscussionGroup request =
                new TLRPC.TL_channels_setDiscussionGroup();
        request.broadcast = MessagesController.getInputChannel(broadcast.chat);
        request.group = group == null
                ? new TLRPC.TL_inputChannelEmpty()
                : MessagesController.getInputChannel(group.chat);
        String operationId = "chat-linked-" + account + "-" + broadcast.chat.id;
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        requireBoolTrue(outcome.response, "channels.setDiscussionGroup");
        JsonObject readback = waitForChatField(account, broadcast,
                "linked_chat_id", Long.toString(expectedLinkedId));
        readback.addProperty("idempotent_replay", false);
        if (group != null) readback.add("discussion_group", peerJson(group));
        addWriteEvidence(readback, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(readback);
    }

    private JsonObject chatBooleanSetting(JsonObject args, String setting)
            throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null || !ChatObject.isChannel(peer.chat)) {
            invalid("peer must be a supergroup or channel");
        }
        boolean allowed = "anti_spam".equals(setting)
                || "participants_hidden".equals(setting)
                ? ChatObject.canBlockUsers(peer.chat)
                : ChatObject.canChangeChatInfo(peer.chat);
        if (!allowed) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account cannot change " + setting + " for this chat",
                    false, peerJson(peer));
        }
        boolean expected = "history_visible".equals(setting)
                ? requiredBoolean(args, "visible")
                : requiredBoolean(args, "enabled");
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        if (before.get(setting).getAsBoolean() == expected) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        TLObject request;
        if ("anti_spam".equals(setting)) {
            if (!peer.chat.megagroup) invalid("anti-spam is available only for supergroups");
            TLRPC.TL_channels_toggleAntiSpam value =
                    new TLRPC.TL_channels_toggleAntiSpam();
            value.channel = MessagesController.getInputChannel(peer.chat);
            value.enabled = expected;
            request = value;
        } else if ("participants_hidden".equals(setting)) {
            if (!peer.chat.megagroup) {
                invalid("hidden participants is available only for supergroups");
            }
            TLRPC.TL_channels_toggleParticipantsHidden value =
                    new TLRPC.TL_channels_toggleParticipantsHidden();
            value.channel = MessagesController.getInputChannel(peer.chat);
            value.enabled = expected;
            request = value;
        } else if ("history_visible".equals(setting)) {
            if (!peer.chat.megagroup) {
                invalid("history visibility is available only for supergroups");
            }
            TLRPC.TL_channels_togglePreHistoryHidden value =
                    new TLRPC.TL_channels_togglePreHistoryHidden();
            value.channel = MessagesController.getInputChannel(peer.chat);
            value.enabled = !expected;
            request = value;
        } else {
            invalid("Unsupported chat boolean setting");
            return null;
        }
        String operationId = "chat-" + setting.replace('_', '-') + "-"
                + account + "-" + peer.chat.id;
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        processUpdates(account, outcome.response);
        JsonObject readback = waitForChatBooleanField(account, peer, setting, expected);
        readback.addProperty("idempotent_replay", false);
        addWriteEvidence(readback, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(readback);
    }

    private JsonObject chatBoostStatus(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null || !ChatObject.isChannel(peer.chat)) {
            invalid("peer must be a supergroup or channel");
        }
        TL_stories.TL_premium_getBoostsStatus request =
                new TL_stories.TL_premium_getBoostsStatus();
        request.peer = peer.inputPeer;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TL_stories.TL_premium_boostsStatus)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_stories.TL_premium_boostsStatus status =
                (TL_stories.TL_premium_boostsStatus) outcome.response;
        JsonObject data = peerJson(peer);
        data.addProperty("level", status.level);
        data.addProperty("current_level_boosts", status.current_level_boosts);
        data.addProperty("boosts", status.boosts);
        data.addProperty("gift_boosts", status.gift_boosts);
        data.addProperty("next_level_boosts", status.next_level_boosts);
        data.addProperty("boost_url", status.boost_url == null ? "" : status.boost_url);
        data.addProperty("my_boost", status.my_boost);
        JsonArray slots = new JsonArray();
        for (Integer slot : status.my_boost_slots) slots.add(slot);
        data.add("my_boost_slots", slots);
        if (status.premium_audience != null) {
            JsonObject audience = new JsonObject();
            audience.addProperty("part", status.premium_audience.part);
            audience.addProperty("total", status.premium_audience.total);
            data.add("premium_audience", audience);
        }
        data.addProperty("source", "premium.getBoostsStatus");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatUpdateTitle(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        String title = requiredString(args, "title", 1, 128);
        if (peer.chat == null) invalid("peer must be a group or channel");
        if (!ChatObject.canChangeChatInfo(peer.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account cannot change this chat's title", false, peerJson(peer));
        }
        String operationId = "chat-title-" + account + "-" + peer.dialogId;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        if (title.equals(before.get("title").getAsString())) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
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
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        processUpdates(account, outcome.response);
        JsonObject readback = waitForChatField(account, peer, "title", title);
        JsonObject data = peerJson(peer);
        data.addProperty("title", title);
        data.add("server_chat", readback);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatUpdateAbout(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        if (!ChatObject.canChangeChatInfo(peer.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account cannot change this chat's about text", false, peerJson(peer));
        }
        String about = requiredString(args, "about", 0, 255);
        String operationId = "chat-about-" + account + "-" + peer.dialogId;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        JsonObject before = chatGet(readbackArgs).getAsJsonObject("data");
        if (about.equals(before.get("about").getAsString())) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        TLRPC.TL_messages_editChatAbout request = new TLRPC.TL_messages_editChatAbout();
        request.peer = peer.inputPeer;
        request.about = about;
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs).response, "messages.editChatAbout");
        JsonObject readback = waitForChatField(account, peer, "about", about);
        JsonObject data = peerJson(peer);
        data.addProperty("about", about);
        data.add("server_chat", readback);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId,
                true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatLeave(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        if (peer.chat.left || peer.chat.kicked) {
            JsonObject replay = peerJson(peer);
            replay.addProperty("left", true);
            replay.addProperty("idempotent_replay", true);
            addWriteEvidence(replay, "chat-leave-" + account + "-" + peer.dialogId,
                    true, true, true, false,
                    "telegram.chat.get", peerReadbackArguments(account, peer));
            return TelegramMcpServer.successEnvelope(replay);
        }
        String operationId = "chat-leave-" + account + "-" + peer.dialogId;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
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
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.Chat readback = waitForMembershipState(account, peer, true);
        JsonObject data = peerJson(peer);
        data.addProperty("left", readback.left || readback.kicked);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject chatDeleteOwned(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        if (peer.chat == null) invalid("peer must be a group or channel");
        if (!peer.chat.creator) {
            throw new McpException("PERMISSION_DENIED",
                    "delete_owned is restricted to chats created by the current account",
                    false, peerJson(peer));
        }
        if (peer.chat.deactivated || peer.chat.left || peer.chat.kicked) {
            throw new McpException("PRECONDITION_FAILED",
                    "Chat membership state does not prove that the chat was deleted; "
                            + "refresh the peer before retrying",
                    false, peerJson(peer));
        }
        JsonObject before = peerJson(peer).deepCopy();
        String operationId = "chat-delete-owned-" + account + "-" + peer.chat.id;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        TLObject request;
        boolean channel = ChatObject.isChannel(peer.chat);
        if (channel) {
            TLRPC.TL_channels_deleteChannel value =
                    new TLRPC.TL_channels_deleteChannel();
            value.channel = MessagesController.getInputChannel(peer.chat);
            request = value;
        } else {
            TLRPC.TL_messages_deleteChat value = new TLRPC.TL_messages_deleteChat();
            value.chat_id = peer.chat.id;
            request = value;
        }
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        if (channel) {
            if (!(outcome.response instanceof TLRPC.Updates)) {
                throw unexpectedResponse(outcome.response);
            }
            processUpdates(account, outcome.response);
        } else {
            requireBoolTrue(outcome.response, "messages.deleteChat");
        }

        boolean absent = false;
        String readbackCode = "";
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                chatGet(readbackArgs);
            } catch (McpException error) {
                if (!error.retryable && ("CHAT_NOT_FOUND".equals(error.code)
                        || "PEER_NOT_FOUND".equals(error.code)
                        || telegramErrorIs(error, "CHANNEL_PRIVATE")
                        || telegramErrorIs(error, "CHANNEL_INVALID")
                        || telegramErrorIs(error, "CHAT_ID_INVALID")
                        || telegramErrorIs(error, "PEER_ID_INVALID"))) {
                    absent = true;
                    readbackCode = error.code;
                    break;
                }
            }
            sleepReadback("Owned-chat deletion readback was interrupted");
        }
        if (!absent) {
            throw new McpException("READBACK_FAILED",
                    "Deleted owned chat remained readable from Telegram",
                    true, before);
        }
        JsonObject data = new JsonObject();
        data.add("deleted_chat", before);
        data.addProperty("deleted", true);
        data.addProperty("readback_error_code", readbackCode);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.chat.get", readbackArgs);
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
        if (!peer.chat.left && !peer.chat.kicked) {
            JsonObject replay = peerJson(peer);
            replay.addProperty("joined", true);
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        String operationId = "chat-join-" + account + "-" + peer.dialogId;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        TLRPC.TL_channels_joinChannel request = new TLRPC.TL_channels_joinChannel();
        request.channel = MessagesController.getInputChannel(peer.chat);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.chat.get", readbackArgs);
        if (outcome.response instanceof TLRPC.TL_chatInviteJoinResultWebView) {
            throw new McpException("HUMAN_INTERACTION_REQUIRED",
                    "Telegram requires a web confirmation flow for this destination", false, peerJson(peer));
        }
        processUpdates(account, outcome.response);
        TLRPC.Chat readback = waitForMembershipState(account, peer, false);
        JsonObject data = peerJson(peer);
        data.addProperty("joined", !readback.left && !readback.kicked);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.chat.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject topicList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireForumPeer(peer);
        String query = optionalString(args, "query", "");
        if (query.length() > 256) invalid("query exceeds 256 characters");
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        TL_forum.TL_messages_getForumTopics request =
                new TL_forum.TL_messages_getForumTopics();
        request.peer = peer.inputPeer;
        request.q = query.isEmpty() ? null : query;
        request.offset_date = optionalInt(args, "offset_date", 0, 0, Integer.MAX_VALUE);
        request.offset_id = optionalInt(args, "offset_id", 0, 0, Integer.MAX_VALUE);
        request.offset_topic = optionalInt(args, "offset_topic", 0, 0, Integer.MAX_VALUE);
        request.limit = limit;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_forumTopics)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_forumTopics response =
                (TLRPC.TL_messages_forumTopics) outcome.response;
        cachePeers(account, response.users, response.chats);
        JsonArray topics = new JsonArray();
        int nextDate = 0;
        int nextId = 0;
        int nextTopic = 0;
        for (TLRPC.TL_forumTopic topic : response.topics) {
            if (topic instanceof TLRPC.TL_forumTopicDeleted) continue;
            topics.add(forumTopicJson(topic));
            nextDate = topic.date;
            nextId = topic.top_message;
            nextTopic = topic.id;
        }
        JsonObject data = peerJson(peer);
        data.add("topics", topics);
        data.addProperty("count", response.count);
        data.addProperty("limit", limit);
        data.addProperty("complete", topics.size() >= response.count);
        data.addProperty("source", "messages.getForumTopics");
        JsonObject cursor = new JsonObject();
        cursor.addProperty("offset_date", nextDate);
        cursor.addProperty("offset_id", nextId);
        cursor.addProperty("offset_topic", nextTopic);
        data.add("next_cursor", cursor);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject topicGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int topicId = requiredInt(args, "topic_id", 1, Integer.MAX_VALUE);
        TLRPC.TL_forumTopic topic = fetchForumTopic(account, peer, topicId);
        JsonObject data = peerJson(peer);
        data.add("topic", forumTopicJson(topic));
        data.addProperty("source", "messages.getForumTopicsByID");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject topicCreate(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "topic.create.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "topic_create", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireForumPeer(peer);
        if (!ChatObject.canCreateTopic(peer.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not create topics in this forum", false, peerJson(peer));
        }
        String title = requiredString(args, "title", 1, 128);
        int iconColor = optionalInt(args, "icon_color", 0, 0, 0xFFFFFF);
        long iconEmojiId = optionalLong(args, "icon_emoji_id", 0, 0, Long.MAX_VALUE);
        Set<Integer> before = new HashSet<>();
        for (TLRPC.TL_forumTopic topic : fetchForumTopics(account, peer, title, MAX_LIMIT)) {
            before.add(topic.id);
        }
        String operationId = "topic-create-"
                + sha256Hex(account + ":" + peer.dialogId + ":" + key).substring(0, 24);
        TL_forum.TL_messages_createForumTopic request =
                new TL_forum.TL_messages_createForumTopic();
        request.peer = peer.inputPeer;
        request.title = title;
        request.random_id = deterministicLong(account + ":topic:" + key);
        if (iconColor != 0) {
            request.flags |= 1;
            request.icon_color = iconColor;
        }
        if (iconEmojiId != 0) {
            request.flags |= 8;
            request.icon_emoji_id = iconEmojiId;
        }
        JsonObject timeoutReadback = peerReadbackArguments(account, peer);
        timeoutReadback.addProperty("query", title);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.topic.list", timeoutReadback);
        processUpdates(account, outcome.response);
        TLRPC.TL_forumTopic created = waitForNewForumTopic(
                account, peer, title, before);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.addProperty("topic_id", created.id);
        JsonObject data = peerJson(peer);
        data.add("topic", forumTopicJson(created));
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.topic.get", readbackArgs);
        storeIdempotency(account, "topic_create", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject topicUpdate(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int topicId = requiredInt(args, "topic_id", 1, Integer.MAX_VALUE);
        TLRPC.TL_forumTopic before = fetchForumTopic(account, peer, topicId);
        if (!ChatObject.canManageTopic(account, peer.chat, before)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not edit this topic", false, forumTopicJson(before));
        }
        boolean hasTitle = args.has("title");
        boolean hasIcon = args.has("icon_emoji_id");
        boolean hasClosed = args.has("closed");
        boolean hasHidden = args.has("hidden");
        if (!hasTitle && !hasIcon && !hasClosed && !hasHidden) {
            invalid("At least one of title, icon_emoji_id, closed, or hidden is required");
        }
        String requestedTitle = hasTitle
                ? requiredString(args, "title", 1, 128) : null;
        Long requestedIcon = hasIcon
                ? optionalLong(args, "icon_emoji_id", 0, 0, Long.MAX_VALUE) : null;
        Boolean requestedClosed = hasClosed
                ? optionalBoolean(args, "closed", false) : null;
        Boolean requestedHidden = hasHidden
                ? optionalBoolean(args, "hidden", false) : null;
        if ((requestedTitle == null || requestedTitle.equals(before.title))
                && (requestedIcon == null || requestedIcon == before.icon_emoji_id)
                && (requestedClosed == null || requestedClosed == before.closed)
                && (requestedHidden == null || requestedHidden == before.hidden)) {
            JsonObject replay = peerJson(peer);
            replay.add("topic", forumTopicJson(before));
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        TL_forum.TL_messages_editForumTopic request =
                new TL_forum.TL_messages_editForumTopic();
        request.peer = peer.inputPeer;
        request.topic_id = topicId;
        if (hasTitle) request.title = requestedTitle;
        if (hasIcon) {
            request.flags |= 2;
            request.icon_emoji_id = requestedIcon;
        }
        if (hasClosed) {
            request.flags |= 4;
            request.closed = requestedClosed;
        }
        if (hasHidden) {
            request.flags |= 8;
            request.hidden = requestedHidden;
        }
        String operationId = "topic-update-" + account + "-" + (-peer.dialogId)
                + "-" + topicId + "-" + UUID.randomUUID();
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.addProperty("topic_id", topicId);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.topic.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.TL_forumTopic after = waitForForumTopicState(account, peer, topicId,
                hasTitle ? request.title : null,
                hasIcon ? request.icon_emoji_id : null,
                hasClosed ? request.closed : null,
                hasHidden ? request.hidden : null,
                null);
        JsonObject data = peerJson(peer);
        data.add("topic", forumTopicJson(after));
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.topic.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject topicPin(JsonObject args, boolean pinned) throws McpException {
        int account = requireActiveAccount(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int topicId = requiredInt(args, "topic_id", 1, Integer.MAX_VALUE);
        TLRPC.TL_forumTopic before = fetchForumTopic(account, peer, topicId);
        if (!ChatObject.canManageTopics(peer.chat)) {
            throw new McpException("PERMISSION_DENIED",
                    "Managing pinned topics requires forum-management rights", false,
                    forumTopicJson(before));
        }
        if (before.pinned == pinned) {
            JsonObject replay = peerJson(peer);
            replay.add("topic", forumTopicJson(before));
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        TL_forum.TL_messages_updatePinnedForumTopic request =
                new TL_forum.TL_messages_updatePinnedForumTopic();
        request.peer = peer.inputPeer;
        request.topic_id = topicId;
        request.pinned = pinned;
        String operationId = "topic-pin-" + account + "-" + (-peer.dialogId)
                + "-" + topicId + "-" + pinned;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.addProperty("topic_id", topicId);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.topic.get", readbackArgs);
        processUpdates(account, outcome.response);
        TLRPC.TL_forumTopic after = waitForForumTopicState(account, peer, topicId,
                null, null, null, null, pinned);
        JsonObject data = peerJson(peer);
        data.add("topic", forumTopicJson(after));
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.topic.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject topicDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        int topicId = requiredInt(args, "topic_id", 2, Integer.MAX_VALUE);
        TLRPC.TL_forumTopic before;
        try {
            before = fetchForumTopic(account, peer, topicId);
        } catch (McpException error) {
            if (!"TOPIC_NOT_FOUND".equals(error.code)) throw error;
            JsonObject replay = peerJson(peer);
            replay.addProperty("topic_id", topicId);
            replay.addProperty("deleted", true);
            replay.addProperty("delete_rounds", 0);
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        if (!ChatObject.canDeleteTopic(account, peer.chat, before)) {
            throw new McpException("PERMISSION_DENIED",
                    "Current account may not delete this topic", false, forumTopicJson(before));
        }
        String operationId = "topic-delete-" + account + "-" + (-peer.dialogId)
                + "-" + topicId;
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.addProperty("topic_id", topicId);
        int rounds = 0;
        int offset;
        do {
            TL_forum.TL_messages_deleteTopicHistory request =
                    new TL_forum.TL_messages_deleteTopicHistory();
            request.peer = peer.inputPeer;
            request.top_msg_id = topicId;
            RequestOutcome outcome = writeRequest(account, request, operationId,
                    "telegram.topic.get", readbackArgs);
            if (!(outcome.response instanceof TLRPC.TL_messages_affectedHistory)) {
                throw unexpectedResponse(outcome.response);
            }
            TLRPC.TL_messages_affectedHistory affected =
                    (TLRPC.TL_messages_affectedHistory) outcome.response;
            offset = affected.offset;
            rounds++;
        } while (offset > 0 && rounds < 20);
        if (offset > 0) {
            throw new McpException("READBACK_INCOMPLETE",
                    "Topic deletion exceeded the server pagination safety limit", true,
                    readbackArgs);
        }
        waitForForumTopicAbsent(account, peer, topicId);
        uiCall(() -> {
            MessagesController.getInstance(account).getTopicsController()
                    .reloadTopics(-peer.dialogId, false);
            return null;
        });
        JsonObject data = peerJson(peer);
        data.addProperty("topic_id", topicId);
        data.addProperty("deleted", true);
        data.addProperty("delete_rounds", rounds);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.topic.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject folderList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        ArrayList<TLRPC.DialogFilter> filters = fetchDialogFilters(account);
        JsonArray items = new JsonArray();
        for (int order = 0; order < filters.size(); order++) {
            JsonObject item = dialogFilterJson(account, filters.get(order));
            item.addProperty("order", order);
            items.add(item);
        }
        JsonObject data = new JsonObject();
        data.add("folders", items);
        data.addProperty("count", filters.size());
        data.addProperty("source", "messages.getDialogFilters");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject folderGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int folderId = requiredInt(args, "folder_id", 0, 255);
        for (TLRPC.DialogFilter filter : fetchDialogFilters(account)) {
            if (filter.id == folderId) {
                return TelegramMcpServer.successEnvelope(
                        dialogFilterJson(account, filter));
            }
        }
        JsonObject details = new JsonObject();
        details.addProperty("folder_id", folderId);
        throw new McpException("FOLDER_NOT_FOUND",
                "Telegram did not return the requested dialog folder", false, details);
    }

    private JsonObject folderUpsert(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "folder.upsert.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "folder_upsert", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        ArrayList<TLRPC.DialogFilter> before = fetchDialogFilters(account);
        int folderId = args.has("folder_id")
                ? requiredInt(args, "folder_id", 2, 255)
                : nextDialogFilterId(before);
        TLRPC.DialogFilter existing = null;
        for (TLRPC.DialogFilter filter : before) {
            if (filter.id == folderId) existing = filter;
        }
        if (existing != null && !optionalBoolean(args, "replace", false)) {
            JsonObject details = dialogFilterJson(account, existing);
            details.addProperty("required_argument", "replace=true");
            throw new McpException("PRECONDITION_FAILED",
                    "Updating a folder replaces its full filter; pass replace=true explicitly",
                    false, details);
        }
        // Telegram's own folder editor enforces FilterCreateActivity.MAX_NAME_LENGTH
        // (12 UTF-16 code units).  Letting a longer title reach MTProto produces the
        // misleading server error MESSAGE_TOO_LONG instead of an actionable MCP
        // validation failure.
        String title = requiredString(args, "title", 1, 12);
        TLRPC.TL_dialogFilter filter = new TLRPC.TL_dialogFilter();
        filter.id = folderId;
        filter.title = new TLRPC.TL_textWithEntities();
        filter.title.text = title;
        filter.contacts = optionalBoolean(args, "contacts", false);
        filter.non_contacts = optionalBoolean(args, "non_contacts", false);
        filter.groups = optionalBoolean(args, "groups", false);
        filter.broadcasts = optionalBoolean(args, "broadcasts", false);
        filter.bots = optionalBoolean(args, "bots", false);
        filter.exclude_muted = optionalBoolean(args, "exclude_muted", false);
        filter.exclude_read = optionalBoolean(args, "exclude_read", false);
        filter.exclude_archived = optionalBoolean(args, "exclude_archived", false);
        String emoticon = optionalString(args, "emoticon", "");
        if (!emoticon.isEmpty()) {
            filter.flags |= 1 << 25;
            filter.emoticon = emoticon;
        }
        if (args.has("color")) {
            filter.flags |= 1 << 27;
            filter.color = requiredInt(args, "color", 0, 7);
        }
        addInputPeers(account, args, "include_peers", filter.include_peers);
        addInputPeers(account, args, "exclude_peers", filter.exclude_peers);
        addInputPeers(account, args, "pinned_peers", filter.pinned_peers);
        Set<Long> included = new HashSet<>();
        for (TLRPC.InputPeer peer : filter.include_peers) {
            included.add(DialogObject.getPeerDialogId(peer));
        }
        for (TLRPC.InputPeer pinned : filter.pinned_peers) {
            if (included.add(DialogObject.getPeerDialogId(pinned))) {
                filter.include_peers.add(pinned);
            }
        }
        if (!filter.contacts && !filter.non_contacts && !filter.groups
                && !filter.broadcasts && !filter.bots
                && filter.include_peers.isEmpty()) {
            invalid("Folder must include at least one category or explicit peer");
        }
        TLRPC.TL_messages_updateDialogFilter request =
                new TLRPC.TL_messages_updateDialogFilter();
        request.flags = 1;
        request.id = folderId;
        request.filter = filter;
        String operationId = "folder-upsert-" + account + "-" + folderId;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("folder_id", folderId);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.folder.get", readbackArgs).response,
                "messages.updateDialogFilter");
        TLRPC.DialogFilter after = waitForDialogFilter(account, folderId, true);
        JsonObject data = dialogFilterJson(account, after);
        data.addProperty("created", existing == null);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.folder.get", readbackArgs);
        storeIdempotency(account, "folder_upsert", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject folderDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        int folderId = requiredInt(args, "folder_id", 2, 255);
        TLRPC.DialogFilter before = null;
        for (TLRPC.DialogFilter filter : fetchDialogFilters(account)) {
            if (filter.id == folderId) {
                before = filter;
                break;
            }
        }
        if (before == null) {
            JsonObject replay = new JsonObject();
            replay.addProperty("folder_id", folderId);
            replay.addProperty("deleted", true);
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        TLRPC.TL_messages_updateDialogFilter request =
                new TLRPC.TL_messages_updateDialogFilter();
        request.flags = 0;
        request.id = folderId;
        String operationId = "folder-delete-" + account + "-" + folderId;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("folder_id", folderId);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.folder.get", readbackArgs).response,
                "messages.updateDialogFilter(delete)");
        waitForDialogFilter(account, folderId, false);
        JsonObject data = dialogFilterJson(account, before);
        data.addProperty("deleted", true);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.folder.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject folderReorder(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        ArrayList<Integer> order = requiredIntArray(
                args, "folder_ids", 1, 254, 2, 255);
        if (new HashSet<>(order).size() != order.size()) {
            invalid("folder_ids must be unique");
        }
        ArrayList<TLRPC.DialogFilter> filters = fetchDialogFilters(account);
        Set<Integer> expected = new HashSet<>();
        ArrayList<Integer> current = new ArrayList<>();
        for (TLRPC.DialogFilter filter : filters) {
            if (!(filter instanceof TLRPC.TL_dialogFilterDefault)) {
                expected.add(filter.id);
                current.add(filter.id);
            }
        }
        if (!expected.equals(new HashSet<>(order))) {
            JsonObject details = new JsonObject();
            details.add("expected_folder_ids", intArray(new ArrayList<>(expected)));
            details.add("provided_folder_ids", intArray(order));
            throw new McpException("PRECONDITION_FAILED",
                    "folder_ids must contain every custom folder exactly once", false,
                    details);
        }
        if (current.equals(order)) {
            JsonObject replay = new JsonObject();
            replay.add("folder_ids", intArray(order));
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        TLRPC.TL_messages_updateDialogFiltersOrder request =
                new TLRPC.TL_messages_updateDialogFiltersOrder();
        request.order.addAll(order);
        String operationId = "folder-reorder-" + account + "-" + UUID.randomUUID();
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.folder.list", readbackArgs).response,
                "messages.updateDialogFiltersOrder");
        waitForDialogFilterOrder(account, order);
        JsonObject data = new JsonObject();
        data.add("folder_ids", intArray(order));
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, false, false,
                "telegram.folder.list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject proxyList(JsonObject args) throws McpException {
        return TelegramMcpServer.successEnvelope(proxyStateData());
    }

    private JsonObject proxyUpsert(JsonObject args) throws McpException {
        String address = requiredString(args, "address", 1, 255).trim();
        int port = requiredInt(args, "port", 1, 65535);
        String type = requiredString(args, "type", 1, 16);
        String username = args.has("username")
                ? requiredString(args, "username", 0, 255) : "";
        String password = args.has("password")
                ? requiredString(args, "password", 0, 255) : "";
        String secret = args.has("secret")
                ? requiredString(args, "secret", 0, 512) : "";
        if ("socks5".equals(type)) {
            if (!secret.isEmpty()) invalid("SOCKS5 proxy must not include secret");
        } else if ("mtproto".equals(type)) {
            if (secret.isEmpty()) invalid("MTProto proxy requires secret");
            if (!username.isEmpty() || !password.isEmpty()) {
                invalid("MTProto proxy must not include username or password");
            }
        } else {
            invalid("type must be socks5 or mtproto");
        }
        String existingId = args.has("proxy_id")
                ? requiredString(args, "proxy_id", 3, 80) : "";
        AtomicReference<SharedConfig.ProxyInfo> selected = new AtomicReference<>();
        AtomicReference<Boolean> created = new AtomicReference<>(false);
        AtomicReference<Boolean> changed = new AtomicReference<>(false);
        uiCall(() -> {
            SharedConfig.loadProxyList();
            SharedConfig.ProxyInfo proxy;
            if (existingId.isEmpty()) {
                SharedConfig.ProxyInfo candidate = new SharedConfig.ProxyInfo(
                        address, port, username, password, secret);
                proxy = null;
                for (SharedConfig.ProxyInfo item : SharedConfig.proxyList) {
                    if (address.equals(item.address) && port == item.port
                            && username.equals(item.username)
                            && password.equals(item.password)
                            && secret.equals(item.secret)) {
                        proxy = item;
                        break;
                    }
                }
                if (proxy == null) {
                    proxy = candidate;
                    SharedConfig.proxyList.add(0, proxy);
                    if (!SharedConfig.saveProxyList()) {
                        SharedConfig.proxyList.remove(proxy);
                        throw new McpException("PERSISTENCE_FAILED",
                                "Android rejected the proxy-list preference commit",
                                true, null);
                    }
                    created.set(true);
                    changed.set(true);
                }
            } else {
                proxy = resolveProxy(existingId);
                boolean current = SharedConfig.currentProxy == proxy;
                String oldAddress = proxy.address;
                int oldPort = proxy.port;
                String oldUsername = proxy.username;
                String oldPassword = proxy.password;
                String oldSecret = proxy.secret;
                boolean valuesChanged = !address.equals(oldAddress) || port != oldPort
                        || !username.equals(oldUsername) || !password.equals(oldPassword)
                        || !secret.equals(oldSecret);
                if (!valuesChanged) {
                    selected.set(proxy);
                    return null;
                }
                proxy.address = address;
                proxy.port = port;
                proxy.username = username;
                proxy.password = password;
                proxy.secret = secret;
                boolean persisted;
                if (current) {
                    SharedPreferences preferences =
                            MessagesController.getGlobalMainSettings();
                    boolean enabled = preferences.getBoolean("proxy_enabled", false);
                    SharedPreferences.Editor editor = preferences.edit()
                            .putString("proxy_ip", address)
                            .putString("proxy_pass", password)
                            .putString("proxy_user", username)
                            .putInt("proxy_port", port)
                            .putString("proxy_secret", secret);
                    if (!secret.isEmpty()) {
                        editor.putBoolean("proxy_enabled_calls", false);
                    }
                    persisted = SharedConfig.saveProxyList(editor);
                    if (persisted) {
                        ConnectionsManager.setProxySettings(
                                enabled, address, port, username, password, secret);
                    }
                } else {
                    persisted = SharedConfig.saveProxyList();
                }
                if (!persisted) {
                    proxy.address = oldAddress;
                    proxy.port = oldPort;
                    proxy.username = oldUsername;
                    proxy.password = oldPassword;
                    proxy.secret = oldSecret;
                    throw new McpException("PERSISTENCE_FAILED",
                            "Android rejected the atomic proxy preference commit; "
                                    + "the in-memory proxy was restored",
                            true, null);
                }
                changed.set(true);
            }
            selected.set(proxy);
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.proxySettingsChanged);
            return null;
        });
        JsonObject state = proxyStateData();
        state.add("proxy", proxyJson(selected.get()));
        state.addProperty("created", created.get());
        state.addProperty("changed", changed.get());
        JsonObject readbackArgs = new JsonObject();
        addWriteEvidence(state,
                "proxy-upsert-" + proxyReference(selected.get()).substring(2, 26),
                true, changed.get(), changed.get(), changed.get(),
                "telegram.proxy.list", readbackArgs);
        return TelegramMcpServer.successEnvelope(state);
    }

    private JsonObject proxySelect(JsonObject args) throws McpException {
        String proxyId = requiredString(args, "proxy_id", 3, 80);
        boolean enabled = requiredBoolean(args, "enabled");
        boolean forCalls = optionalBoolean(args, "for_calls", false);
        AtomicReference<SharedConfig.ProxyInfo> selected = new AtomicReference<>();
        uiCall(() -> {
            SharedConfig.loadProxyList();
            SharedConfig.ProxyInfo proxy = resolveProxy(proxyId);
            if (forCalls && !proxy.secret.isEmpty()) {
                throw new McpException("PRECONDITION_FAILED",
                        "Telegram does not support MTProto proxies for calls",
                        false, proxyJson(proxy));
            }
            SharedPreferences.Editor editor =
                    MessagesController.getGlobalMainSettings().edit()
                            .putString("proxy_ip", proxy.address)
                            .putString("proxy_pass", proxy.password)
                            .putString("proxy_user", proxy.username)
                            .putInt("proxy_port", proxy.port)
                            .putString("proxy_secret", proxy.secret)
                            .putBoolean("proxy_enabled", enabled)
                            .putBoolean("proxy_enabled_calls", enabled && forCalls);
            if (!editor.commit()) {
                throw new McpException("PERSISTENCE_FAILED",
                        "Android rejected the proxy preference commit", true, null);
            }
            SharedConfig.currentProxy = proxy;
            ConnectionsManager.setProxySettings(enabled, proxy.address, proxy.port,
                    proxy.username, proxy.password, proxy.secret);
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.proxySettingsChanged);
            selected.set(proxy);
            return null;
        });
        JsonObject state = proxyStateData();
        if (state.get("enabled").getAsBoolean() != enabled
                || state.get("for_calls").getAsBoolean() != (enabled && forCalls)
                || !proxyId.equals(state.get("current_proxy_id").getAsString())) {
            throw new McpException("READBACK_FAILED",
                    "Proxy preferences did not match the requested exact state",
                    true, state);
        }
        state.add("proxy", proxyJson(selected.get()));
        JsonObject readbackArgs = new JsonObject();
        addWriteEvidence(state,
                "proxy-select-" + proxyId.substring(2, Math.min(26, proxyId.length())),
                true, true, true, true, "telegram.proxy.list", readbackArgs);
        return TelegramMcpServer.successEnvelope(state);
    }

    private JsonObject proxyDelete(JsonObject args) throws McpException {
        requireConfirm(args);
        String proxyId = requiredString(args, "proxy_id", 3, 80);
        AtomicReference<JsonObject> deletedProxy = new AtomicReference<>();
        uiCall(() -> {
            SharedConfig.loadProxyList();
            SharedConfig.ProxyInfo proxy = resolveProxy(proxyId);
            deletedProxy.set(proxyJson(proxy));
            if (!SharedConfig.deleteProxy(proxy)) {
                throw new McpException("OUTCOME_UNKNOWN",
                        "The proxy was removed in memory but persistence could not be confirmed",
                        false, proxyJson(proxy));
            }
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.proxySettingsChanged);
            return null;
        });
        JsonObject state = proxyStateData();
        for (JsonElement item : state.getAsJsonArray("proxies")) {
            if (proxyId.equals(item.getAsJsonObject().get("proxy_id").getAsString())) {
                throw new McpException("READBACK_FAILED",
                        "Deleted proxy remains in SharedConfig readback", true, state);
            }
        }
        state.add("deleted_proxy", deletedProxy.get());
        state.addProperty("deleted", true);
        JsonObject readbackArgs = new JsonObject();
        addWriteEvidence(state,
                "proxy-delete-" + proxyId.substring(2, Math.min(26, proxyId.length())),
                true, true, true, false, "telegram.proxy.list", readbackArgs);
        return TelegramMcpServer.successEnvelope(state);
    }

    private JsonObject proxyStateData() throws McpException {
        return uiCall(() -> {
            SharedConfig.loadProxyList();
            SharedPreferences preferences =
                    MessagesController.getGlobalMainSettings();
            boolean configuredEnabled =
                    preferences.getBoolean("proxy_enabled", false);
            boolean enabled = configuredEnabled && SharedConfig.currentProxy != null;
            boolean configuredForCalls =
                    preferences.getBoolean("proxy_enabled_calls", false);
            JsonArray proxies = new JsonArray();
            for (SharedConfig.ProxyInfo proxy : SharedConfig.proxyList) {
                proxies.add(proxyJson(proxy));
            }
            JsonObject data = new JsonObject();
            data.add("proxies", proxies);
            data.addProperty("count", proxies.size());
            data.addProperty("enabled", enabled);
            data.addProperty("configured_enabled", configuredEnabled);
            data.addProperty("for_calls", enabled && configuredForCalls
                    && SharedConfig.currentProxy != null
                    && SharedConfig.currentProxy.secret.isEmpty());
            data.addProperty("current_proxy_id", SharedConfig.currentProxy == null
                    ? "" : proxyReference(SharedConfig.currentProxy));
            data.addProperty("scope", "global_device");
            data.addProperty("credentials_redacted", true);
            data.addProperty("source", "SharedConfig_and_globalMainSettings");
            return data;
        });
    }

    private JsonObject proxyJson(SharedConfig.ProxyInfo proxy) {
        JsonObject data = new JsonObject();
        data.addProperty("proxy_id", proxyReference(proxy));
        data.addProperty("type", proxy.secret.isEmpty() ? "socks5" : "mtproto");
        data.addProperty("address", proxy.address);
        data.addProperty("port", proxy.port);
        data.addProperty("username", proxy.username);
        data.addProperty("has_password", !proxy.password.isEmpty());
        data.addProperty("has_secret", !proxy.secret.isEmpty());
        data.addProperty("selected", SharedConfig.currentProxy == proxy);
        data.addProperty("checking", proxy.checking);
        data.addProperty("available", proxy.available);
        data.addProperty("ping_ms", proxy.ping);
        return data;
    }

    private SharedConfig.ProxyInfo resolveProxy(String proxyId)
            throws McpException {
        for (SharedConfig.ProxyInfo proxy : SharedConfig.proxyList) {
            if (proxyId.equals(proxyReference(proxy))) return proxy;
        }
        JsonObject details = new JsonObject();
        details.addProperty("proxy_id", proxyId);
        throw new McpException("PROXY_NOT_FOUND",
                "Proxy reference is stale or does not belong to this MCP server",
                false, details);
    }

    private String proxyReference(SharedConfig.ProxyInfo proxy) {
        String value = "telegram-proxy\u0000" + proxy.address + "\u0000" + proxy.port
                + "\u0000" + proxy.username + "\u0000" + proxy.password
                + "\u0000" + proxy.secret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(referenceSecret.getBytes(StandardCharsets.US_ASCII),
                    "HmacSHA256"));
            return "p_" + hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable error) {
            return "p_" + sha256Hex(referenceSecret + ":" + value);
        }
    }

    private JsonObject storageStats(JsonObject args) throws McpException {
        JsonObject data = runStorageScan();
        data.addProperty("source", "FileLoader_app_private_storage_scan");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject storageCacheClear(JsonObject args) throws McpException {
        requireConfirm(args);
        int account = args.has("account")
                ? requiredInt(args, "account", 0, UserConfig.MAX_ACCOUNT_COUNT - 1)
                : UserConfig.selectedAccount;
        JsonArray requested = requiredArray(
                args, "categories", 1, STORAGE_CATEGORIES.size());
        Set<String> categories = new HashSet<>();
        for (JsonElement value : requested) {
            if (!value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) {
                invalid("categories must contain strings");
            }
            String category = value.getAsString();
            if (!STORAGE_CATEGORIES.contains(category)) {
                invalid("Unknown storage category: " + category);
            }
            if (!categories.add(category)) {
                invalid("categories must not contain duplicates");
            }
        }
        String operationId = "storage-clear-" + UUID.randomUUID();
        JsonObject before = runStorageScan();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CacheDeleteStats> deletion = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        uiCall(() -> {
            FileLoader.getInstance(account).cancelLoadAllFiles();
            FileLoader.getInstance(account).getFileLoaderQueue().postRunnable(
                    () -> Utilities.globalQueue.postRunnable(() -> {
                        try {
                            deletion.set(clearStorageCategories(categories));
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            latch.countDown();
                        }
                    }));
            return null;
        });
        try {
            if (!latch.await(MEDIA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                JsonObject readbackArgs = new JsonObject();
                throw new McpException("OUTCOME_UNKNOWN",
                        "Cache cleanup did not finish before timeout; inspect storage.stats before retrying",
                        false, unknownOutcomeDetails(operationId,
                        "telegram.storage.stats", readbackArgs));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            JsonObject readbackArgs = new JsonObject();
            throw new McpException("OUTCOME_UNKNOWN",
                    "Cache cleanup wait was interrupted; inspect storage.stats before retrying",
                    false, unknownOutcomeDetails(operationId,
                    "telegram.storage.stats", readbackArgs));
        }
        if (failure.get() != null) {
            Throwable error = failure.get();
            if (error instanceof McpException) throw (McpException) error;
            throw new McpException("FILE_IO_ERROR",
                    error.getMessage() == null
                            ? "Cache cleanup failed" : error.getMessage(),
                    true, null);
        }
        CacheDeleteStats deleted = deletion.get();
        boolean allTelegramCategories = categories.contains("photos")
                && categories.contains("videos")
                && categories.contains("documents")
                && categories.contains("music")
                && categories.contains("voice")
                && categories.contains("stories")
                && categories.contains("stickers")
                && categories.contains("other")
                && categories.contains("temp")
                && categories.contains("logs");
        uiCall(() -> {
            if (allTelegramCategories) {
                FileLoader.getInstance(account).clearFilePaths();
            }
            FileLoader.getInstance(account).checkCurrentDownloadsFiles();
            ImageLoader.getInstance().clearMemory();
            MediaDataController.getInstance(account).checkAllMedia(true);
            return null;
        });
        JsonObject after = runStorageScan();
        JsonObject data = new JsonObject();
        JsonArray categoryArray = new JsonArray();
        ArrayList<String> sorted = new ArrayList<>(categories);
        Collections.sort(sorted);
        for (String category : sorted) categoryArray.add(category);
        data.add("categories", categoryArray);
        data.addProperty("files_deleted", deleted.filesDeleted);
        data.addProperty("bytes_deleted", Long.toString(deleted.bytesDeleted));
        data.addProperty("delete_failures", deleted.failures);
        data.addProperty("staged_references_cleared",
                deleted.stagedReferencesCleared);
        data.addProperty("upload_sessions_cleared",
                deleted.uploadSessionsCleared);
        data.add("before", before);
        data.add("after", after);
        data.addProperty("scope", "app_cache_and_configured_telegram_media");
        JsonObject readbackArgs = new JsonObject();
        addWriteEvidence(data, operationId, true,
                deleted.failures == 0, true, false,
                "telegram.storage.stats", readbackArgs);
        if (deleted.failures != 0) {
            throw new McpException("PARTIAL_DELETE",
                    "One or more cache files could not be deleted",
                    true, data);
        }
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject networkUsage(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        JsonObject data = networkUsageData(account);
        data.addProperty("source", "StatsController_local_persistent_counters");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject networkUsageReset(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        String network = requiredString(args, "network", 1, 16);
        int networkType = statsNetworkType(network);
        JsonObject before = networkUsageData(account)
                .getAsJsonObject("networks").getAsJsonObject(network).deepCopy();
        uiCall(() -> {
            StatsController.getInstance(account).resetStats(networkType);
            return null;
        });
        JsonObject after = networkUsageData(account)
                .getAsJsonObject("networks").getAsJsonObject(network).deepCopy();
        JsonObject total = after.getAsJsonObject("categories")
                .getAsJsonObject("total");
        if (!"0".equals(total.get("sent_bytes").getAsString())
                || !"0".equals(total.get("received_bytes").getAsString())) {
            throw new McpException("READBACK_FAILED",
                    "Network counters did not reset to zero", true, after);
        }
        JsonObject data = new JsonObject();
        data.addProperty("account", account);
        data.addProperty("network", network);
        data.add("before", before);
        data.add("after", after);
        JsonObject readbackArgs = accountArguments(account);
        addWriteEvidence(data,
                "network-usage-reset-" + account + "-" + network,
                true, true, true, false,
                "telegram.network.usage", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private static JsonObject networkUsageData(int account) {
        StatsController controller = StatsController.getInstance(account);
        JsonObject networks = new JsonObject();
        String[] networkNames = {"mobile", "wifi", "roaming"};
        int[] networkTypes = {
                StatsController.TYPE_MOBILE,
                StatsController.TYPE_WIFI,
                StatsController.TYPE_ROAMING
        };
        String[] categoryNames = {
                "calls", "messages", "videos", "audio", "photos",
                "files", "total", "music"
        };
        int[] dataTypes = {
                StatsController.TYPE_CALLS,
                StatsController.TYPE_MESSAGES,
                StatsController.TYPE_VIDEOS,
                StatsController.TYPE_AUDIOS,
                StatsController.TYPE_PHOTOS,
                StatsController.TYPE_FILES,
                StatsController.TYPE_TOTAL,
                StatsController.TYPE_MUSIC
        };
        for (int networkIndex = 0; networkIndex < networkNames.length;
                networkIndex++) {
            int networkType = networkTypes[networkIndex];
            JsonObject item = new JsonObject();
            item.addProperty("reset_at_ms",
                    Long.toString(controller.getResetStatsDate(networkType)));
            item.addProperty("call_time_seconds",
                    controller.getCallsTotalTime(networkType));
            JsonObject categories = new JsonObject();
            for (int typeIndex = 0; typeIndex < dataTypes.length; typeIndex++) {
                int dataType = dataTypes[typeIndex];
                JsonObject category = new JsonObject();
                category.addProperty("sent_bytes", Long.toString(
                        controller.getSentBytesCount(networkType, dataType)));
                category.addProperty("received_bytes", Long.toString(
                        controller.getReceivedBytesCount(networkType, dataType)));
                category.addProperty("sent_items",
                        controller.getSentItemsCount(networkType, dataType));
                category.addProperty("received_items",
                        controller.getRecivedItemsCount(networkType, dataType));
                categories.add(categoryNames[typeIndex], category);
            }
            item.add("categories", categories);
            networks.add(networkNames[networkIndex], item);
        }
        JsonObject data = new JsonObject();
        data.addProperty("account", account);
        data.add("networks", networks);
        data.addProperty("scope", "account_on_this_device");
        return data;
    }

    private static int statsNetworkType(String network) throws McpException {
        if ("mobile".equals(network)) return StatsController.TYPE_MOBILE;
        if ("wifi".equals(network)) return StatsController.TYPE_WIFI;
        if ("roaming".equals(network)) return StatsController.TYPE_ROAMING;
        invalid("network must be mobile, wifi, or roaming");
        return 0;
    }

    private JsonObject autoDownloadGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        JsonObject data = autoDownloadData(account);
        data.addProperty("source", "DownloadController_and_mainSettings");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject autoDownloadSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String network = requiredString(args, "network", 1, 16);
        String preset = requiredString(args, "preset", 1, 16);
        int index;
        if ("off".equals(preset)) index = -1;
        else if ("low".equals(preset)) index = 0;
        else if ("medium".equals(preset)) index = 1;
        else if ("high".equals(preset)) index = 2;
        else {
            invalid("preset must be off, low, medium, or high");
            return null;
        }
        int networkType = statsNetworkType(network);
        uiCall(() -> {
            DownloadController controller = DownloadController.getInstance(account);
            DownloadController.Preset control;
            String presetKey;
            String indexKey;
            if (networkType == StatsController.TYPE_MOBILE) {
                control = controller.mobilePreset;
                presetKey = "mobilePreset";
                indexKey = "currentMobilePreset";
                if (index >= 0) controller.currentMobilePreset = index;
            } else if (networkType == StatsController.TYPE_WIFI) {
                control = controller.wifiPreset;
                presetKey = "wifiPreset";
                indexKey = "currentWifiPreset";
                if (index >= 0) controller.currentWifiPreset = index;
            } else {
                control = controller.roamingPreset;
                presetKey = "roamingPreset";
                indexKey = "currentRoamingPreset";
                if (index >= 0) controller.currentRoamingPreset = index;
            }
            control.enabled = index >= 0;
            int persistedIndex = index >= 0 ? index
                    : networkType == StatsController.TYPE_MOBILE
                    ? controller.currentMobilePreset
                    : networkType == StatsController.TYPE_WIFI
                    ? controller.currentWifiPreset : controller.currentRoamingPreset;
            if (!MessagesController.getMainSettings(account).edit()
                    .putString(presetKey, control.toString())
                    .putInt(indexKey, persistedIndex).commit()) {
                throw new McpException("PERSISTENCE_FAILED",
                        "Android rejected auto-download preference commit", true, null);
            }
            controller.checkAutodownloadSettings();
            controller.savePresetToServer(networkType);
            return null;
        });
        JsonObject state = autoDownloadData(account);
        JsonObject selected = state.getAsJsonObject("networks")
                .getAsJsonObject(network);
        String actual = selected.get("preset").getAsString();
        if (!preset.equals(actual)) {
            throw new McpException("READBACK_FAILED",
                    "Auto-download preset did not match local controller readback",
                    true, selected);
        }
        state.addProperty("network", network);
        state.addProperty("requested_preset", preset);
        JsonObject readbackArgs = accountArguments(account);
        addWriteEvidence(state,
                "auto-download-set-" + account + "-" + network + "-" + preset,
                true, true, true, true,
                "telegram.settings.auto_download_get", readbackArgs);
        return TelegramMcpServer.successEnvelope(state);
    }

    private static JsonObject autoDownloadData(int account) {
        DownloadController controller = DownloadController.getInstance(account);
        JsonObject networks = new JsonObject();
        networks.add("mobile", autoDownloadNetworkJson(
                controller.currentMobilePreset,
                controller.mobilePreset.enabled,
                controller.getCurrentMobilePreset()));
        networks.add("wifi", autoDownloadNetworkJson(
                controller.currentWifiPreset,
                controller.wifiPreset.enabled,
                controller.getCurrentWiFiPreset()));
        networks.add("roaming", autoDownloadNetworkJson(
                controller.currentRoamingPreset,
                controller.roamingPreset.enabled,
                controller.getCurrentRoamingPreset()));
        JsonObject data = new JsonObject();
        data.addProperty("account", account);
        data.add("networks", networks);
        data.addProperty("scope", "account_on_this_device_and_cloud_preset_sync");
        return data;
    }

    private static JsonObject autoDownloadNetworkJson(
            int selectedIndex,
            boolean enabled,
            DownloadController.Preset effective) {
        JsonObject data = new JsonObject();
        String preset = !enabled ? "off"
                : selectedIndex == 0 ? "low"
                : selectedIndex == 1 ? "medium"
                : selectedIndex == 2 ? "high" : "custom";
        data.addProperty("preset", preset);
        data.addProperty("enabled", enabled);
        data.addProperty("selected_index", selectedIndex);
        data.addProperty("preload_video", effective.preloadVideo);
        data.addProperty("preload_music", effective.preloadMusic);
        data.addProperty("preload_stories", effective.preloadStories);
        data.addProperty("less_call_data", effective.lessCallData);
        data.addProperty("max_video_bitrate", effective.maxVideoBitrate);
        JsonObject maxBytes = new JsonObject();
        maxBytes.addProperty("photo", Long.toString(effective.sizes[
                DownloadController.PRESET_SIZE_NUM_PHOTO]));
        maxBytes.addProperty("video", Long.toString(effective.sizes[
                DownloadController.PRESET_SIZE_NUM_VIDEO]));
        maxBytes.addProperty("document", Long.toString(effective.sizes[
                DownloadController.PRESET_SIZE_NUM_DOCUMENT]));
        maxBytes.addProperty("audio", Long.toString(effective.sizes[
                DownloadController.PRESET_SIZE_NUM_AUDIO]));
        data.add("max_bytes", maxBytes);
        JsonArray contexts = new JsonArray();
        String[] names = {"contacts", "private", "groups", "channels"};
        for (int index = 0; index < effective.mask.length; index++) {
            JsonObject context = new JsonObject();
            int mask = effective.mask[index];
            context.addProperty("context", names[index]);
            context.addProperty("photos",
                    (mask & DownloadController.AUTODOWNLOAD_TYPE_PHOTO) != 0);
            context.addProperty("audio",
                    (mask & DownloadController.AUTODOWNLOAD_TYPE_AUDIO) != 0);
            context.addProperty("videos",
                    (mask & DownloadController.AUTODOWNLOAD_TYPE_VIDEO) != 0);
            context.addProperty("documents",
                    (mask & DownloadController.AUTODOWNLOAD_TYPE_DOCUMENT) != 0);
            contexts.add(context);
        }
        data.add("contexts", contexts);
        return data;
    }

    private JsonObject runStorageScan() throws McpException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonObject> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                result.set(storageSnapshot());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(MEDIA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new McpException("TIMEOUT",
                        "Storage scan did not finish before timeout", true, null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("INTERRUPTED",
                    "Storage scan was interrupted", true, null);
        }
        if (failure.get() != null) {
            throw new McpException("FILE_IO_ERROR",
                    failure.get().getMessage() == null
                            ? "Storage scan failed" : failure.get().getMessage(),
                    true, null);
        }
        return result.get();
    }

    private JsonObject storageSnapshot() throws McpException {
        JsonObject categories = new JsonObject();
        categories.add("photos", storageCategoryJson(storageCategorySize("photos")));
        categories.add("videos", storageCategoryJson(storageCategorySize("videos")));
        categories.add("documents", storageCategoryJson(storageCategorySize("documents")));
        categories.add("music", storageCategoryJson(storageCategorySize("music")));
        categories.add("voice", storageCategoryJson(storageCategorySize("voice")));
        categories.add("stories", storageCategoryJson(storageCategorySize("stories")));
        categories.add("stickers", storageCategoryJson(storageCategorySize("stickers")));
        categories.add("other", storageCategoryJson(storageCategorySize("other")));
        categories.add("temp", storageCategoryJson(storageCategorySize("temp")));
        categories.add("logs", storageCategoryJson(storageCategorySize("logs")));
        categories.add("mcp_staging",
                storageCategoryJson(storageCategorySize("mcp_staging")));
        File dataDirectory = ApplicationLoader.applicationContext.getFilesDir();
        JsonObject data = new JsonObject();
        data.add("categories", categories);
        data.addProperty("device_total_bytes",
                Long.toString(dataDirectory.getTotalSpace()));
        data.addProperty("device_free_bytes",
                Long.toString(dataDirectory.getFreeSpace()));
        data.addProperty("device_usable_bytes",
                Long.toString(dataDirectory.getUsableSpace()));
        data.addProperty("category_sizes_may_overlap", true);
        data.addProperty("scope", "global_device_cache");
        data.addProperty("paths_redacted", true);
        return data;
    }

    private static JsonObject storageCategoryJson(StorageSize size) {
        JsonObject data = new JsonObject();
        data.addProperty("bytes", Long.toString(size.bytes));
        data.addProperty("files", size.files);
        data.addProperty("scan_failures", size.failures);
        return data;
    }

    private StorageSize storageCategorySize(String category) throws McpException {
        StorageSize size = new StorageSize();
        for (StorageTarget target : storageTargets(category)) {
            scanStorageTarget(target, size);
        }
        return size;
    }

    private ArrayList<StorageTarget> storageTargets(String category)
            throws McpException {
        ArrayList<StorageTarget> targets = new ArrayList<>();
        if ("photos".equals(category)) {
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_IMAGE), 0));
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_IMAGE_PUBLIC), 0));
        } else if ("videos".equals(category)) {
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_VIDEO), 0));
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_VIDEO_PUBLIC), 0));
        } else if ("documents".equals(category)) {
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_DOCUMENT), 1));
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_FILES), 1));
        } else if ("music".equals(category)) {
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_DOCUMENT), 2));
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_FILES), 2));
        } else if ("voice".equals(category)) {
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_AUDIO), 0));
        } else if ("stories".equals(category)) {
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_STORIES), 0));
        } else if ("stickers".equals(category)) {
            File cache = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_CACHE);
            targets.add(new StorageTarget(cache == null
                    ? null : new File(cache, "acache"), 0));
            targets.add(new StorageTarget(cache, 3));
        } else if ("other".equals(category)) {
            targets.add(new StorageTarget(new File(
                    ApplicationLoader.applicationContext.getFilesDir(),
                    "rasterized/wallpaper"), 0));
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_CACHE), 5));
        } else if ("temp".equals(category)) {
            targets.add(new StorageTarget(FileLoader.checkDirectory(
                    FileLoader.MEDIA_DIR_CACHE), 4));
        } else if ("logs".equals(category)) {
            targets.add(new StorageTarget(AndroidUtilities.getLogsDir(), 0));
        } else if ("mcp_staging".equals(category)) {
            targets.add(new StorageTarget(stagingDirectory(), 0));
        } else {
            invalid("Unknown storage category: " + category);
        }
        return targets;
    }

    private static void scanStorageTarget(StorageTarget target, StorageSize size) {
        if (target.root == null || !target.root.exists()) return;
        ArrayList<File> stack = new ArrayList<>();
        stack.add(target.root);
        while (!stack.isEmpty()) {
            File current = stack.remove(stack.size() - 1);
            if (current.isDirectory()) {
                if (!current.equals(target.root)
                        && "drafts".equals(current.getName())) continue;
                File[] children = current.listFiles();
                if (children == null) {
                    size.failures++;
                    continue;
                }
                Collections.addAll(stack, children);
            } else if (current.isFile()
                    && storageModeMatches(current.getName(), target.mode)) {
                size.files++;
                size.bytes += current.length();
            }
        }
    }

    private CacheDeleteStats clearStorageCategories(Set<String> categories)
            throws McpException {
        CacheDeleteStats result = new CacheDeleteStats();
        Set<String> visited = new HashSet<>();
        for (String category : categories) {
            for (StorageTarget target : storageTargets(category)) {
                clearStorageTarget(target, result, visited);
            }
        }
        if (categories.contains("mcp_staging")) {
            SharedPreferences staged = stagedFilePreferences();
            result.stagedReferencesCleared = staged.getAll().size();
            if (!staged.edit().clear().commit()) result.failures++;
            SharedPreferences uploads = uploadSessionPreferences();
            result.uploadSessionsCleared = uploads.getAll().size();
            if (!uploads.edit().clear().commit()) result.failures++;
            if (!staged.getAll().isEmpty() || !uploads.getAll().isEmpty()) {
                result.failures++;
            }
        }
        return result;
    }

    private static void clearStorageTarget(
            StorageTarget target,
            CacheDeleteStats result,
            Set<String> visited) {
        if (target.root == null || !target.root.exists()) return;
        String canonicalRoot;
        try {
            canonicalRoot = target.root.getCanonicalPath();
        } catch (IOException error) {
            result.failures++;
            return;
        }
        String canonicalPrefix = canonicalRoot.endsWith(File.separator)
                ? canonicalRoot : canonicalRoot + File.separator;
        ArrayList<File> stack = new ArrayList<>();
        stack.add(target.root);
        while (!stack.isEmpty()) {
            File current = stack.remove(stack.size() - 1);
            String canonicalCurrent;
            try {
                canonicalCurrent = current.getCanonicalPath();
            } catch (IOException error) {
                result.failures++;
                continue;
            }
            if (!canonicalRoot.equals(canonicalCurrent)
                    && !canonicalCurrent.startsWith(canonicalPrefix)) {
                result.failures++;
                continue;
            }
            if (current.isDirectory()) {
                if (!current.equals(target.root)
                        && "drafts".equals(current.getName())) continue;
                File[] children = current.listFiles();
                if (children == null) {
                    result.failures++;
                    continue;
                }
                Collections.addAll(stack, children);
            } else if (current.isFile()
                    && storageModeMatches(current.getName(), target.mode)) {
                if (!visited.add(canonicalCurrent)) continue;
                long bytes = current.length();
                if (current.delete()) {
                    result.filesDeleted++;
                    result.bytesDeleted += bytes;
                } else if (current.exists()) {
                    result.failures++;
                }
            }
        }
    }

    private static boolean storageModeMatches(String fileName, int mode) {
        if (mode == 0) return true;
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean music = lower.endsWith(".mp3") || lower.endsWith(".m4a");
        boolean emoji = lower.endsWith(".tgs") || lower.endsWith(".webm");
        boolean temporary = lower.endsWith(".tmp")
                || lower.endsWith(".temp") || lower.endsWith(".preload");
        if (mode == 1) return !music;
        if (mode == 2) return music;
        if (mode == 3) return emoji;
        if (mode == 4) return temporary;
        if (mode == 5) return !emoji && !temporary;
        return false;
    }

    private JsonObject settingsGet(JsonObject args) throws McpException {
        Set<String> keys = requestedSettingKeys(args);
        JsonObject values = uiCall(() -> {
            JsonObject result = new JsonObject();
            for (String key : keys) result.addProperty(key, getSetting(key));
            return result;
        });
        JsonObject data = new JsonObject();
        data.add("values", values);
        data.addProperty("scope", "global_device");
        data.addProperty("persistence", "SharedConfig/preferences");
        data.addProperty("restart_required", false);
        JsonArray allowed = new JsonArray();
        ArrayList<String> sorted = new ArrayList<>(SETTINGS);
        Collections.sort(sorted);
        for (String key : sorted) allowed.add(key);
        data.add("allowed_keys", allowed);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject settingsSet(JsonObject args) throws McpException {
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
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            boolean expected = entry.getValue().getAsBoolean();
            boolean actual = applied.get(entry.getKey()).getAsBoolean();
            if (actual != expected) {
                JsonObject details = new JsonObject();
                details.addProperty("key", entry.getKey());
                details.addProperty("expected", expected);
                details.addProperty("actual", actual);
                throw new McpException("READBACK_FAILED",
                        "A local setting did not match the requested value after its APP toggle",
                        true, details);
            }
        }
        JsonObject data = new JsonObject();
        data.add("values", applied);
        data.addProperty("scope", "global_device");
        data.addProperty("persistence", "SharedConfig/preferences");
        data.addProperty("restart_required", false);
        JsonObject readbackArgs = new JsonObject();
        JsonArray keys = new JsonArray();
        for (String key : values.keySet()) keys.add(key);
        readbackArgs.add("keys", keys);
        addWriteEvidence(data,
                "settings-" + sha256Hex(values.toString()).substring(0, 24),
                true, true, true, false,
                "telegram.settings.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject profileUpdate(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String operationId = "profile-update-" + UUID.randomUUID().toString();
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
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
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.profile.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.User)) throw unexpectedResponse(outcome.response);
        TLRPC.User user = (TLRPC.User) outcome.response;
        uiCall(() -> {
            MessagesController.getInstance(account).putUser(user, false);
            UserConfig.getInstance(account).setCurrentUser(user);
            UserConfig.getInstance(account).saveConfig(true);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
            return null;
        });
        JsonObject serverProfile = waitForProfileFields(account, args);
        addWriteEvidence(serverProfile, operationId, true, true, true, false,
                "telegram.profile.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(serverProfile);
    }

    private JsonObject profileGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        return TelegramMcpServer.successEnvelope(profileGetServer(account));
    }

    private JsonObject profileUsernameSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String username = requiredString(args, "username", 0, 32);
        if (!username.isEmpty() && !username.matches("[A-Za-z][A-Za-z0-9_]{3,31}")) {
            invalid("username must be empty or a valid Telegram username");
        }
        String operationId = "profile-username-" + UUID.randomUUID();
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        TL_account.updateUsername request = new TL_account.updateUsername();
        request.username = username;
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.profile.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.User)) throw unexpectedResponse(outcome.response);
        TLRPC.User returned = (TLRPC.User) outcome.response;
        uiCall(() -> {
            MessagesController.getInstance(account).putUser(returned, false);
            UserConfig.getInstance(account).setCurrentUser(returned);
            UserConfig.getInstance(account).saveConfig(true);
            return null;
        });
        JsonObject profile = waitForProfileValue(account, "username", username);
        addWriteEvidence(profile, operationId, true, true, true, false,
                "telegram.profile.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(profile);
    }

    private JsonObject profileBirthdaySet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean clear = optionalBoolean(args, "clear", false);
        TL_account.updateBirthday request = new TL_account.updateBirthday();
        if (!clear) {
            TL_account.TL_birthday birthday = new TL_account.TL_birthday();
            birthday.day = requiredInt(args, "day", 1, 31);
            birthday.month = requiredInt(args, "month", 1, 12);
            if (args.has("year")) {
                birthday.year = requiredInt(args, "year", 1900, 2100);
                birthday.flags |= 1;
            }
            validateDate(birthday.year == 0 ? 2000 : birthday.year,
                    birthday.month, birthday.day);
            request.birthday = birthday;
            request.flags |= 1;
        }
        String operationId = "profile-birthday-" + UUID.randomUUID();
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.profile.get", readbackArgs).response, "account.updateBirthday");
        JsonObject profile = waitForBirthday(account, request.birthday, clear);
        addWriteEvidence(profile, operationId, true, true, true, false,
                "telegram.profile.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(profile);
    }

    private JsonObject profileEmojiStatusSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean clear = optionalBoolean(args, "clear", false);
        TL_account.updateEmojiStatus request = new TL_account.updateEmojiStatus();
        long documentId = 0;
        int until = 0;
        if (clear) {
            request.emoji_status = new TLRPC.TL_emojiStatusEmpty();
        } else {
            documentId = requiredPositiveLongString(args, "document_id");
            TLRPC.TL_emojiStatus status = new TLRPC.TL_emojiStatus();
            status.document_id = documentId;
            String untilValue = optionalString(args, "until", "");
            if (!untilValue.isEmpty()) {
                until = parseFutureInstant(account, untilValue, "until");
                status.flags |= 1;
                status.until = until;
            }
            request.emoji_status = status;
        }
        String operationId = "profile-emoji-status-" + UUID.randomUUID();
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.profile.get", readbackArgs).response,
                "account.updateEmojiStatus");
        JsonObject profile = waitForEmojiStatus(account, documentId, until, clear);
        addWriteEvidence(profile, operationId, true, true, true, false,
                "telegram.profile.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(profile);
    }

    private JsonObject profilePhotoList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int offset = optionalInt(args, "offset", 0, 0, 100_000);
        int limit = optionalInt(args, "limit", 50, 1, MAX_LIMIT);
        long maxId = args.has("max_id")
                ? requiredPositiveLongString(args, "max_id") : 0;
        TLRPC.photos_Photos response = fetchProfilePhotos(account, offset, maxId, limit);
        JsonArray photos = new JsonArray();
        for (TLRPC.Photo photo : response.photos) photos.add(profilePhotoJson(photo));
        JsonObject data = new JsonObject();
        data.add("photos", photos);
        data.addProperty("count", response.count == 0 ? response.photos.size() : response.count);
        data.addProperty("next_offset", offset + response.photos.size());
        data.addProperty("has_more", response.photos.size() == limit
                && (response.count == 0 || offset + response.photos.size() < response.count));
        data.addProperty("source", "photos.getUserPhotos");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject profilePhotoUpload(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String fileRef = requiredString(args, "file_ref", 66, 66);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyPayloadHash("profile.photo_upload.v2",
                "file_ref", fileRef);
        JsonObject replay = idempotencyReplay(
                account, "profile_photo_upload", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        StagedFile staged = requireStagedFile(fileRef);
        String mimeType = staged.metadata.get("mime_type").getAsString();
        if (!mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            invalid("profile photo file_ref must contain an image MIME type");
        }
        TLRPC.InputFile uploaded = uploadStagedFile(account, staged);
        TLRPC.TL_photos_uploadProfilePhoto request =
                new TLRPC.TL_photos_uploadProfilePhoto();
        request.file = uploaded;
        request.flags |= 1;
        String operationId = "profile-photo-upload-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.profile.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.TL_photos_photo)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_photos_photo response = (TLRPC.TL_photos_photo) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        if (response.photo == null || response.photo instanceof TLRPC.TL_photoEmpty) {
            throw new McpException("EMPTY_RESPONSE",
                    "Telegram accepted the upload but returned no profile photo", true, null);
        }
        JsonObject profile = waitForCurrentProfilePhoto(account, response.photo.id, true);
        profile.add("photo", profilePhotoJson(response.photo));
        profile.add("source_file", stagedFileJson(staged.metadata));
        profile.addProperty("idempotent_replay", false);
        addWriteEvidence(profile, operationId, true, true, true, false,
                "telegram.profile.get", readbackArgs);
        storeIdempotency(account, "profile_photo_upload", key, payloadHash, profile);
        return TelegramMcpServer.successEnvelope(profile);
    }

    private JsonObject profilePhotoSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        long photoId = requiredPositiveLongString(args, "photo_id");
        JsonObject current = profileGetServer(account);
        if (current.get("profile_photo_id").getAsLong() == photoId) {
            current.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(current);
        }
        TLRPC.Photo photo = findProfilePhoto(account, photoId, true);
        TLRPC.TL_photos_updateProfilePhoto request =
                new TLRPC.TL_photos_updateProfilePhoto();
        request.id = inputPhoto(photo);
        String operationId = "profile-photo-set-" + account + "-" + photoId;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.profile.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.TL_photos_photo)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_photos_photo response = (TLRPC.TL_photos_photo) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        JsonObject profile = waitForCurrentProfilePhoto(account, photoId, true);
        profile.addProperty("idempotent_replay", false);
        addWriteEvidence(profile, operationId, true, true, true, false,
                "telegram.profile.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(profile);
    }

    private JsonObject profilePhotoClear(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        JsonObject current = profileGetServer(account);
        if (!current.get("profile_photo_present").getAsBoolean()) {
            current.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(current);
        }
        TLRPC.TL_photos_updateProfilePhoto request =
                new TLRPC.TL_photos_updateProfilePhoto();
        request.id = new TLRPC.TL_inputPhotoEmpty();
        String operationId = "profile-photo-clear-" + account;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.profile.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.TL_photos_photo)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_photos_photo response = (TLRPC.TL_photos_photo) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        JsonObject profile = waitForCurrentProfilePhoto(account, 0, false);
        profile.addProperty("idempotent_replay", false);
        addWriteEvidence(profile, operationId, true, true, true, false,
                "telegram.profile.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(profile);
    }

    private JsonObject profilePhotoDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        long photoId = requiredPositiveLongString(args, "photo_id");
        TLRPC.Photo photo = findProfilePhoto(account, photoId, false);
        if (photo == null) {
            JsonObject data = new JsonObject();
            data.addProperty("photo_id", Long.toString(photoId));
            data.addProperty("deleted", true);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TLRPC.TL_photos_deletePhotos request = new TLRPC.TL_photos_deletePhotos();
        request.id.add(inputPhoto(photo));
        String operationId = "profile-photo-delete-" + account + "-" + photoId;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("offset", 0);
        readbackArgs.addProperty("limit", MAX_LIMIT);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.profile.photo_list", readbackArgs);
        requireVectorResponse(outcome.response, "photos.deletePhotos");
        for (int attempt = 0; attempt < 12; attempt++) {
            if (findProfilePhoto(account, photoId, false) == null) {
                JsonObject data = new JsonObject();
                data.addProperty("photo_id", Long.toString(photoId));
                data.addProperty("deleted", true);
                data.addProperty("idempotent_replay", false);
                addWriteEvidence(data, operationId, true, true, true, false,
                        "telegram.profile.photo_list", readbackArgs);
                return TelegramMcpServer.successEnvelope(data);
            }
            sleepReadback("Profile-photo delete readback was interrupted");
        }
        JsonObject details = new JsonObject();
        details.addProperty("photo_id", Long.toString(photoId));
        throw new McpException("READBACK_FAILED",
                "Deleted profile photo remains in photos.getUserPhotos", true, details);
    }

    private JsonObject quickReplyList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        return TelegramMcpServer.successEnvelope(
                quickReplyCollectionJson(account, fetchQuickReplies(account)));
    }

    private JsonObject quickReplyGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int shortcutId = optionalInt(args, "shortcut_id", 0, 0, Integer.MAX_VALUE);
        String shortcut = optionalString(args, "shortcut", "");
        if (shortcutId == 0 && shortcut.isEmpty()) {
            return TelegramMcpServer.successEnvelope(
                    quickReplyCollectionJson(account, fetchQuickReplies(account)));
        }
        if (shortcutId != 0 && !shortcut.isEmpty()) {
            invalid("Pass exactly one of shortcut_id or shortcut");
        }
        TLRPC.TL_messages_quickReplies replies = fetchQuickReplies(account);
        TLRPC.TL_quickReply reply = shortcutId != 0
                ? findQuickReply(replies, shortcutId)
                : findQuickReply(replies, shortcut);
        if (reply == null) {
            throw new McpException("NOT_FOUND", "Quick-reply shortcut was not found",
                    false, null);
        }
        return TelegramMcpServer.successEnvelope(
                quickReplyWithMessagesJson(account, replies, reply));
    }

    private synchronized JsonObject quickReplyCreateText(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "quick_reply.create_text.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "quick_reply_create_text", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        String shortcut = quickReplyShortcutName(
                requiredString(args, "shortcut", 1, 32));
        TLRPC.TL_messages_quickReplies before = fetchQuickReplies(account);
        if (findQuickReply(before, shortcut) != null) {
            throw new McpException("ALREADY_EXISTS",
                    "A quick-reply shortcut with this exact name already exists",
                    false, quickReplyCollectionJson(account, before));
        }
        return quickReplyTextWrite(account, args, shortcut, 0, 0,
                "quick_reply_create_text", key, payloadHash);
    }

    private synchronized JsonObject quickReplyMessageAddText(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "quick_reply.add_text.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "quick_reply_add_text", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        int shortcutId = optionalInt(
                args, "shortcut_id", 0, 1, Integer.MAX_VALUE);
        TLRPC.TL_messages_quickReplies before = fetchQuickReplies(account);
        TLRPC.TL_quickReply reply = findQuickReply(before, shortcutId);
        if (reply == null) {
            throw new McpException("NOT_FOUND", "Quick-reply shortcut was not found",
                    false, null);
        }
        return quickReplyTextWrite(account, args, null, shortcutId, reply.count,
                "quick_reply_add_text", key, payloadHash);
    }

    private JsonObject quickReplyTextWrite(
            int account,
            JsonObject args,
            String newShortcut,
            int shortcutId,
            int previousCount,
            String idempotencyNamespace,
            String key,
            String payloadHash) throws McpException {
        FormattedText formatted = parseFormattedText(
                account, requiredString(args, "text", 1, 4096),
                optionalString(args, "parse_mode", "plain"));
        boolean linkPreview = optionalBoolean(args, "link_preview", true);
        PeerRef self = resolvePeer(account, "saved");
        String operationId = idempotencyNamespace + "-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        SendResult sent = sendTextViaHelper(
                account, self, formatted.text, formatted.entities, linkPreview,
                null, null, false, 0, key, operationId, newShortcut, shortcutId);

        TLRPC.TL_messages_quickReplies finalReplies = null;
        TLRPC.TL_quickReply finalReply = null;
        TLRPC.Message finalMessage = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            finalReplies = fetchQuickReplies(account);
            finalReply = shortcutId == 0
                    ? findQuickReply(finalReplies, newShortcut)
                    : findQuickReply(finalReplies, shortcutId);
            if (finalReply != null && finalReply.count >= previousCount + 1) {
                TLRPC.messages_Messages messages = fetchQuickReplyMessages(
                        account, finalReply.shortcut_id);
                finalMessage = findMessage(messages.messages, sent.messageId);
                if (finalMessage != null
                        && formatted.text.equals(finalMessage.message)
                        && messageEntitiesJson(formatted.entities).equals(
                        messageEntitiesJson(finalMessage.entities))) {
                    break;
                }
                finalMessage = null;
            }
            sleepReadback("Quick-reply text creation readback was interrupted");
        }
        if (finalReplies == null || finalReply == null || finalMessage == null) {
            JsonObject details = new JsonObject();
            details.addProperty("operation_id", operationId);
            details.addProperty("shortcut", newShortcut == null ? "" : newShortcut);
            details.addProperty("shortcut_id", shortcutId);
            details.addProperty("message_id", sent.messageId);
            throw new McpException("READBACK_FAILED",
                    "Quick-reply text was not present in an independent server readback",
                    true, details);
        }

        JsonObject data = quickReplyJson(account, finalReplies, finalReply);
        data.add("created_message", messageJson(account, finalMessage));
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = accountArguments(account);
        readbackArgs.addProperty("shortcut_id", finalReply.shortcut_id);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.quick_reply.get", readbackArgs);
        storeIdempotency(account, idempotencyNamespace, key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject quickReplyMessageEditText(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        int shortcutId = optionalInt(
                args, "shortcut_id", 0, 1, Integer.MAX_VALUE);
        int messageId = optionalInt(
                args, "message_id", 0, 1, Integer.MAX_VALUE);
        TLRPC.TL_quickReply reply = requireQuickReply(account, shortcutId);
        TLRPC.messages_Messages messages = fetchQuickReplyMessages(account, shortcutId);
        TLRPC.Message existing = findMessage(messages.messages, messageId);
        if (existing == null) {
            throw new McpException("NOT_FOUND",
                    "Message is not present in this quick-reply shortcut", false, null);
        }
        FormattedText formatted = parseFormattedText(
                account, requiredString(args, "text", 1, 4096),
                optionalString(args, "parse_mode", "plain"));
        boolean linkPreview = optionalBoolean(args, "link_preview", true);
        if (formatted.text.equals(existing.message)
                && messageEntitiesJson(formatted.entities).equals(
                messageEntitiesJson(existing.entities))) {
            JsonObject data = messageJson(account, existing);
            data.addProperty("shortcut_id", shortcutId);
            data.addProperty("shortcut", reply.shortcut);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }

        PeerRef self = resolvePeer(account, "saved");
        TLRPC.TL_messages_editMessage request = new TLRPC.TL_messages_editMessage();
        request.peer = self.inputPeer;
        request.id = messageId;
        request.message = formatted.text;
        request.entities = formatted.entities;
        request.no_webpage = !linkPreview;
        request.quick_reply_shortcut_id = shortcutId;
        request.flags |= (1 << 11) | (1 << 3) | (1 << 17);
        String operationId = "quick-reply-message-edit-" + account + "-"
                + shortcutId + "-" + messageId;
        JsonObject readbackArgs = accountArguments(account);
        readbackArgs.addProperty("shortcut_id", shortcutId);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.quick_reply.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        processUpdates(account, outcome.response);
        TLRPC.Message readback = waitForQuickReplyMessage(
                account, shortcutId, messageId, formatted, true);
        JsonObject data = messageJson(account, readback);
        data.addProperty("shortcut_id", shortcutId);
        data.addProperty("shortcut", reply.shortcut);
        data.addProperty("link_preview", linkPreview);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.quick_reply.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject quickReplyMessageDelete(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        int shortcutId = optionalInt(
                args, "shortcut_id", 0, 1, Integer.MAX_VALUE);
        TLRPC.TL_quickReply reply = requireQuickReply(account, shortcutId);
        ArrayList<Integer> requested = requiredIntArray(
                args, "message_ids", 1, 100, 1, Integer.MAX_VALUE);
        TLRPC.messages_Messages before = fetchQuickReplyMessages(account, shortcutId);
        Set<Integer> existingIds = new HashSet<>();
        for (TLRPC.Message message : before.messages) existingIds.add(message.id);
        ArrayList<Integer> present = new ArrayList<>();
        for (int id : requested) if (existingIds.contains(id)) present.add(id);
        if (present.isEmpty()) {
            JsonObject data = new JsonObject();
            data.addProperty("shortcut_id", shortcutId);
            data.add("deleted_message_ids", intArray(requested));
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        if (isSpecialQuickReply(reply.shortcut)
                && present.size() == before.messages.size()) {
            throw new McpException("PRECONDITION_FAILED",
                    "The final hello/away message must be managed through the Business setting",
                    false, null);
        }
        TLRPC.TL_messages_deleteQuickReplyMessages request =
                new TLRPC.TL_messages_deleteQuickReplyMessages();
        request.shortcut_id = shortcutId;
        request.id = present;
        String operationId = "quick-reply-message-delete-" + account + "-"
                + shortcutId + "-" + UUID.randomUUID();
        JsonObject readbackArgs = accountArguments(account);
        readbackArgs.addProperty("shortcut_id", shortcutId);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.quick_reply.get", readbackArgs);
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        processUpdates(account, outcome.response);
        waitForQuickReplyMessagesAbsent(account, shortcutId, requested);
        TLRPC.TL_messages_quickReplies after = fetchQuickReplies(account);
        JsonObject data = new JsonObject();
        data.addProperty("shortcut_id", shortcutId);
        data.add("deleted_message_ids", intArray(requested));
        data.addProperty("shortcut_deleted", findQuickReply(after, shortcutId) == null);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                findQuickReply(after, shortcutId) == null
                        ? "telegram.quick_reply.list" : "telegram.quick_reply.get",
                findQuickReply(after, shortcutId) == null
                        ? accountArguments(account) : readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject quickReplyRename(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        int shortcutId = optionalInt(
                args, "shortcut_id", 0, 1, Integer.MAX_VALUE);
        String shortcut = quickReplyShortcutName(
                requiredString(args, "shortcut", 1, 32));
        TLRPC.TL_messages_quickReplies before = fetchQuickReplies(account);
        TLRPC.TL_quickReply current = findQuickReply(before, shortcutId);
        if (current == null) {
            throw new McpException("NOT_FOUND", "Quick-reply shortcut was not found",
                    false, null);
        }
        if (isSpecialQuickReply(current.shortcut) || isSpecialQuickReply(shortcut)) {
            invalid("hello and away are reserved Business shortcuts and cannot be renamed");
        }
        if (shortcut.equals(current.shortcut)) {
            JsonObject data = quickReplyJson(account, before, current);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TLRPC.TL_quickReply duplicate = findQuickReply(before, shortcut);
        if (duplicate != null && duplicate.shortcut_id != shortcutId) {
            throw new McpException("ALREADY_EXISTS",
                    "A quick-reply shortcut with this exact name already exists",
                    false, quickReplyJson(account, before, duplicate));
        }
        TLRPC.TL_messages_editQuickReplyShortcut request =
                new TLRPC.TL_messages_editQuickReplyShortcut();
        request.shortcut_id = shortcutId;
        request.shortcut = shortcut;
        String operationId = "quick-reply-rename-" + account + "-" + shortcutId;
        JsonObject readbackArgs = accountArguments(account);
        readbackArgs.addProperty("shortcut_id", shortcutId);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.quick_reply.get", readbackArgs).response,
                "messages.editQuickReplyShortcut");
        TLRPC.TL_messages_quickReplies after = waitForQuickReplyName(
                account, shortcutId, shortcut);
        JsonObject data = quickReplyJson(
                account, after, findQuickReply(after, shortcutId));
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.quick_reply.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject quickReplyReorder(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        if (!optionalBoolean(args, "replace", false)) {
            throw new McpException("PRECONDITION_FAILED",
                    "Quick-reply order is a full replacement; pass replace=true",
                    false, null);
        }
        ArrayList<Integer> order = requiredIntArray(
                args, "shortcut_ids", 1, 100, 1, Integer.MAX_VALUE);
        if (new HashSet<>(order).size() != order.size()) {
            invalid("shortcut_ids must not contain duplicates");
        }
        TLRPC.TL_messages_quickReplies before = fetchQuickReplies(account);
        ArrayList<Integer> current = quickReplyOrder(before);
        if (!new HashSet<>(current).equals(new HashSet<>(order))
                || current.size() != order.size()) {
            JsonObject details = quickReplyCollectionJson(account, before);
            details.add("required_shortcut_ids", intArray(current));
            throw new McpException("PRECONDITION_FAILED",
                    "shortcut_ids must contain every current shortcut exactly once",
                    false, details);
        }
        if (current.equals(order)) {
            JsonObject data = quickReplyCollectionJson(account, before);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TLRPC.TL_messages_reorderQuickReplies request =
                new TLRPC.TL_messages_reorderQuickReplies();
        request.order = order;
        String operationId = "quick-reply-reorder-" + account;
        JsonObject readbackArgs = accountArguments(account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.quick_reply.list", readbackArgs).response,
                "messages.reorderQuickReplies");
        TLRPC.TL_messages_quickReplies after = waitForQuickReplyOrder(account, order);
        JsonObject data = quickReplyCollectionJson(account, after);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.quick_reply.list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject quickReplySend(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "quick_reply.send.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "quick_reply_send", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        int shortcutId = optionalInt(
                args, "shortcut_id", 0, 1, Integer.MAX_VALUE);
        TLRPC.TL_quickReply reply = requireQuickReply(account, shortcutId);
        PeerRef peer = resolvePeer(account, requiredString(args, "peer", 1, 256));
        requireCanSend(peer, "text");
        requireNoPaidMessageConfirmation(account, peer);
        TLRPC.messages_Messages sourceResponse = fetchQuickReplyMessages(
                account, shortcutId);
        ArrayList<Integer> requestedIds = args.has("message_ids")
                ? requiredIntArray(args, "message_ids", 1, 100, 1, Integer.MAX_VALUE)
                : new ArrayList<>();
        ArrayList<TLRPC.Message> source = new ArrayList<>();
        if (requestedIds.isEmpty()) {
            source.addAll(sourceResponse.messages);
            for (TLRPC.Message message : source) requestedIds.add(message.id);
        } else {
            for (int id : requestedIds) {
                TLRPC.Message message = findMessage(sourceResponse.messages, id);
                if (message == null) {
                    throw new McpException("NOT_FOUND",
                            "A requested message is not present in the quick reply",
                            false, null);
                }
                source.add(message);
            }
        }
        if (source.isEmpty()) {
            throw new McpException("PRECONDITION_FAILED",
                    "Quick-reply shortcut has no server messages", false, null);
        }
        TLRPC.TL_messages_sendQuickReplyMessages request =
                new TLRPC.TL_messages_sendQuickReplyMessages();
        request.peer = peer.inputPeer;
        request.shortcut_id = shortcutId;
        request.id = requestedIds;
        for (int ignored : requestedIds) request.random_id.add(Utilities.random.nextLong());
        String operationId = "quick-reply-send-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        JsonObject timeoutReadback = peerReadbackArguments(account, peer);
        timeoutReadback.addProperty("limit", Math.min(MAX_LIMIT, source.size() + 10));
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.message.history", timeoutReadback);
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        processUpdates(account, outcome.response);
        ArrayList<Integer> createdIds = uniqueMessageIds(outcome.response);
        if (createdIds.size() != source.size()) {
            JsonObject details = new JsonObject();
            details.addProperty("operation_id", operationId);
            details.add("created_message_ids", intArray(createdIds));
            details.addProperty("expected_count", source.size());
            throw new McpException("MISSING_CREATED_OBJECT",
                    "Telegram did not return one stable message ID per quick-reply message",
                    false, details);
        }
        ArrayList<TLRPC.Message> readback = fetchExactMessages(
                account, peer, createdIds, false, true);
        if (!messageContentFingerprints(source).equals(
                messageContentFingerprints(readback))) {
            JsonObject details = new JsonObject();
            details.addProperty("operation_id", operationId);
            details.add("created_message_ids", intArray(createdIds));
            throw new McpException("READBACK_FAILED",
                    "Sent quick-reply content did not match exact server messages",
                    true, details);
        }
        JsonObject data = peerJson(peer);
        data.addProperty("shortcut_id", shortcutId);
        data.addProperty("shortcut", reply.shortcut);
        data.add("source_message_ids", intArray(requestedIds));
        data.add("message_ids", intArray(createdIds));
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        readbackArgs.add("message_ids", intArray(createdIds));
        readbackArgs.addProperty("scheduled", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
        storeIdempotency(account, "quick_reply_send", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject quickReplyDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        int shortcutId = optionalInt(
                args, "shortcut_id", 0, 1, Integer.MAX_VALUE);
        TLRPC.TL_messages_quickReplies before = fetchQuickReplies(account);
        TLRPC.TL_quickReply reply = findQuickReply(before, shortcutId);
        if (reply == null) {
            JsonObject data = new JsonObject();
            data.addProperty("shortcut_id", shortcutId);
            data.addProperty("deleted", true);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        if (isSpecialQuickReply(reply.shortcut)) {
            throw new McpException("PRECONDITION_FAILED",
                    "hello and away are managed by the corresponding Business setting",
                    false, quickReplyJson(account, before, reply));
        }
        TLRPC.TL_messages_deleteQuickReplyShortcut request =
                new TLRPC.TL_messages_deleteQuickReplyShortcut();
        request.shortcut_id = shortcutId;
        String operationId = "quick-reply-delete-" + account + "-" + shortcutId;
        JsonObject readbackArgs = accountArguments(account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.quick_reply.list", readbackArgs);
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        processUpdates(account, outcome.response);
        waitForQuickReplyAbsent(account, shortcutId);
        JsonObject data = new JsonObject();
        data.addProperty("shortcut_id", shortcutId);
        data.addProperty("shortcut", reply.shortcut);
        data.addProperty("deleted", true);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.quick_reply.list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        return TelegramMcpServer.successEnvelope(businessGetServer(account));
    }

    private JsonObject businessIntroSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean clear = optionalBoolean(args, "clear", false);
        TL_account.updateBusinessIntro request = new TL_account.updateBusinessIntro();
        JsonObject expected = new JsonObject();
        if (!clear) {
            TL_account.TL_inputBusinessIntro intro =
                    new TL_account.TL_inputBusinessIntro();
            intro.title = requiredString(args, "title", 0, 32);
            intro.description = requiredString(args, "description", 0, 70);
            if (intro.title.isEmpty() && intro.description.isEmpty()) {
                invalid("title and description cannot both be empty unless clear=true");
            }
            request.flags |= 1;
            request.intro = intro;
            expected.addProperty("title", intro.title);
            expected.addProperty("description", intro.description);
            expected.addProperty("sticker_document_id", "0");
        }
        String operationId = "business-intro-" + account + "-" + UUID.randomUUID();
        JsonObject readbackArgs = accountArguments(account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.business.get", readbackArgs).response,
                "account.updateBusinessIntro");
        JsonObject data = waitForBusinessSection(account, "intro", expected);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessLocationSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean clear = optionalBoolean(args, "clear", false);
        TL_account.updateBusinessLocation request =
                new TL_account.updateBusinessLocation();
        JsonObject expected = new JsonObject();
        if (!clear) {
            String address = requiredString(args, "address", 1, 96);
            request.address = address;
            request.flags |= 1;
            expected.addProperty("address", address);
            boolean hasLatitude = args.has("latitude");
            boolean hasLongitude = args.has("longitude");
            if (hasLatitude != hasLongitude) {
                invalid("latitude and longitude must be provided together");
            }
            if (hasLatitude) {
                TLRPC.TL_inputGeoPoint point = new TLRPC.TL_inputGeoPoint();
                point.lat = requiredDouble(args, "latitude", -90.0, 90.0);
                point._long = requiredDouble(args, "longitude", -180.0, 180.0);
                request.geo_point = point;
                request.flags |= 2;
                expected.addProperty("latitude", point.lat);
                expected.addProperty("longitude", point._long);
            }
        }
        String operationId = "business-location-" + account + "-" + UUID.randomUUID();
        JsonObject readbackArgs = accountArguments(account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.business.get", readbackArgs).response,
                "account.updateBusinessLocation");
        JsonObject data = waitForBusinessSection(account, "location", expected);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessHoursSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean clear = optionalBoolean(args, "clear", false);
        TL_account.updateBusinessWorkHours request =
                new TL_account.updateBusinessWorkHours();
        JsonObject expected = new JsonObject();
        if (!clear) {
            TL_account.TL_businessWorkHours hours =
                    new TL_account.TL_businessWorkHours();
            hours.timezone_id = requiredString(args, "timezone_id", 1, 64);
            JsonArray intervals = requiredArray(args, "weekly_open", 1, 64);
            int previousEnd = -1;
            JsonArray expectedIntervals = new JsonArray();
            for (JsonElement item : intervals) {
                if (!item.isJsonObject()) invalid("weekly_open items must be objects");
                JsonObject value = item.getAsJsonObject();
                ensureOnlyKeys(value, "start_minute", "end_minute");
                int start = requiredInt(value, "start_minute", 0, 10_079);
                int end = requiredInt(value, "end_minute", 1, 10_080);
                if (end <= start) invalid("weekly_open end_minute must exceed start_minute");
                if (start < previousEnd) invalid("weekly_open intervals must be sorted and non-overlapping");
                previousEnd = end;
                TL_account.TL_businessWeeklyOpen interval =
                        new TL_account.TL_businessWeeklyOpen();
                interval.start_minute = start;
                interval.end_minute = end;
                hours.weekly_open.add(interval);
                JsonObject expectedInterval = new JsonObject();
                expectedInterval.addProperty("start_minute", start);
                expectedInterval.addProperty("end_minute", end);
                expectedIntervals.add(expectedInterval);
            }
            request.flags |= 1;
            request.business_work_hours = hours;
            expected.addProperty("timezone_id", hours.timezone_id);
            expected.add("weekly_open", expectedIntervals);
        }
        String operationId = "business-hours-" + account + "-" + UUID.randomUUID();
        JsonObject readbackArgs = accountArguments(account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.business.get", readbackArgs).response,
                "account.updateBusinessWorkHours");
        JsonObject data = waitForBusinessSection(account, "work_hours", expected);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessGreetingSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean enabled = requiredBoolean(args, "enabled");
        TL_account.updateBusinessGreetingMessage request =
                new TL_account.updateBusinessGreetingMessage();
        JsonObject expected = new JsonObject();
        if (enabled) {
            int shortcutId = optionalInt(
                    args, "shortcut_id", 0, 1, Integer.MAX_VALUE);
            TLRPC.TL_quickReply shortcut = requireQuickReply(account, shortcutId);
            if (!"hello".equalsIgnoreCase(shortcut.shortcut)) {
                invalid("shortcut_id must refer to the reserved hello quick reply");
            }
            int noActivityDays = optionalInt(
                    args, "no_activity_days", 7, 1, 365);
            if (noActivityDays != 7 && noActivityDays != 14
                    && noActivityDays != 21 && noActivityDays != 28) {
                invalid("no_activity_days must be 7, 14, 21, or 28");
            }
            TL_account.TL_inputBusinessRecipients recipients =
                    inputBusinessRecipients(account, args, "recipients");
            TL_account.TL_inputBusinessGreetingMessage message =
                    new TL_account.TL_inputBusinessGreetingMessage();
            message.shortcut_id = shortcutId;
            message.recipients = recipients;
            message.no_activity_days = noActivityDays;
            request.flags |= 1;
            request.message = message;
            expected.addProperty("shortcut_id", shortcutId);
            expected.addProperty("no_activity_days", noActivityDays);
            expected.add("recipients", inputBusinessRecipientsJson(recipients));
        }
        JsonObject before = businessGetServer(account);
        JsonObject beforeSection = before.getAsJsonObject("greeting_message");
        if (businessSectionMatches(beforeSection, expected)) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        String operationId = "business-greeting-" + account + "-" + UUID.randomUUID();
        JsonObject readbackArgs = accountArguments(account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.business.get", readbackArgs).response,
                "account.updateBusinessGreetingMessage");
        JsonObject data = waitForBusinessSection(
                account, "greeting_message", expected);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessAwaySet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        boolean enabled = requiredBoolean(args, "enabled");
        TL_account.updateBusinessAwayMessage request =
                new TL_account.updateBusinessAwayMessage();
        JsonObject expected = new JsonObject();
        if (enabled) {
            int shortcutId = optionalInt(
                    args, "shortcut_id", 0, 1, Integer.MAX_VALUE);
            TLRPC.TL_quickReply shortcut = requireQuickReply(account, shortcutId);
            if (!"away".equalsIgnoreCase(shortcut.shortcut)) {
                invalid("shortcut_id must refer to the reserved away quick reply");
            }
            TL_account.TL_inputBusinessAwayMessage message =
                    new TL_account.TL_inputBusinessAwayMessage();
            message.shortcut_id = shortcutId;
            message.offline_only = optionalBoolean(args, "offline_only", false);
            message.recipients = inputBusinessRecipients(account, args, "recipients");
            String schedule = optionalString(args, "schedule", "always");
            if ("always".equals(schedule)) {
                message.schedule =
                        new TL_account.TL_businessAwayMessageScheduleAlways();
            } else if ("outside_work_hours".equals(schedule)) {
                JsonObject workHours = businessGetServer(account)
                        .getAsJsonObject("work_hours");
                if (workHours == null || workHours.size() == 0) {
                    throw new McpException("PRECONDITION_FAILED",
                            "outside_work_hours requires configured Business work hours",
                            false, null);
                }
                message.schedule = new
                        TL_account.TL_businessAwayMessageScheduleOutsideWorkHours();
            } else if ("custom".equals(schedule)) {
                int start = optionalInt(
                        args, "start_date", 0, 1, Integer.MAX_VALUE);
                int end = optionalInt(
                        args, "end_date", 0, 1, Integer.MAX_VALUE);
                if (end <= start) invalid("end_date must be greater than start_date");
                TL_account.TL_businessAwayMessageScheduleCustom custom =
                        new TL_account.TL_businessAwayMessageScheduleCustom();
                custom.start_date = start;
                custom.end_date = end;
                message.schedule = custom;
                expected.addProperty("start_date", start);
                expected.addProperty("end_date", end);
            } else {
                invalid("schedule must be always, outside_work_hours, or custom");
            }
            request.flags |= 1;
            request.message = message;
            expected.addProperty("shortcut_id", shortcutId);
            expected.addProperty("offline_only", message.offline_only);
            expected.addProperty("schedule_type", schedule);
            expected.add("recipients",
                    inputBusinessRecipientsJson(message.recipients));
        }
        JsonObject before = businessGetServer(account);
        JsonObject beforeSection = before.getAsJsonObject("away_message");
        if (businessSectionMatches(beforeSection, expected)) {
            before.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(before);
        }
        String operationId = "business-away-" + account + "-" + UUID.randomUUID();
        JsonObject readbackArgs = accountArguments(account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.business.get", readbackArgs).response,
                "account.updateBusinessAwayMessage");
        JsonObject data = waitForBusinessSection(account, "away_message", expected);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessBotList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        return TelegramMcpServer.successEnvelope(
                connectedBotsJson(account, fetchConnectedBots(account)));
    }

    private JsonObject businessBotSet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        if (!requiredBoolean(args, "replace")) {
            invalid("replace must be true for the complete Business bot policy");
        }
        PeerRef bot = requireUserPeer(resolvePeer(
                account, requiredString(args, "bot", 1, 256)), "bot");
        if (!bot.user.bot || !bot.user.bot_business) {
            throw new McpException("PRECONDITION_FAILED",
                    "bot must be a Telegram bot that supports Business connections",
                    false, peerJson(bot));
        }
        if (!args.has("rights") || !args.get("rights").isJsonObject()) {
            invalid("rights must be a complete object");
        }
        TL_account.TL_businessBotRights rights = businessBotRightsFromJson(
                args.getAsJsonObject("rights"));
        TL_account.TL_inputBusinessBotRecipients recipients =
                inputBusinessBotRecipients(account, args, "recipients");
        TL_account.connectedBots before = fetchConnectedBots(account);
        for (TL_account.TL_connectedBot connected : before.connected_bots) {
            if (connected.bot_id != bot.dialogId) {
                JsonObject details = connectedBotsJson(account, before);
                details.addProperty("required_action",
                        "Delete the currently connected bot before replacing it");
                throw new McpException("PRECONDITION_FAILED",
                        "Telegram currently has a different connected Business bot",
                        false, details);
            }
        }
        JsonObject expected = new JsonObject();
        expected.addProperty("bot_id", Long.toString(bot.dialogId));
        expected.add("rights", businessBotRightsJson(rights));
        expected.add("recipients", inputBusinessBotRecipientsJson(recipients));
        TL_account.TL_connectedBot existing = findConnectedBot(before, bot.dialogId);
        if (existing != null && jsonContains(
                connectedBotJson(account, existing), expected)) {
            JsonObject data = connectedBotJson(account, existing);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TL_account.updateConnectedBot request = new TL_account.updateConnectedBot();
        request.deleted = false;
        request.rights = rights;
        request.bot = MessagesController.getInstance(account).getInputUser(bot.user);
        request.recipients = recipients;
        String operationId = "business-bot-set-" + account + "-" + bot.dialogId;
        JsonObject readbackArgs = accountArguments(account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.business.bot_list", readbackArgs);
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        processUpdates(account, outcome.response);
        TL_account.TL_connectedBot readback = waitForConnectedBot(
                account, bot.dialogId, expected, true);
        JsonObject data = connectedBotJson(account, readback);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.bot_list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessBotDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        PeerRef bot = requireUserPeer(resolvePeer(
                account, requiredString(args, "bot", 1, 256)), "bot");
        TL_account.connectedBots before = fetchConnectedBots(account);
        TL_account.TL_connectedBot existing = findConnectedBot(before, bot.dialogId);
        if (existing == null) {
            JsonObject data = peerJson(bot);
            data.addProperty("deleted", true);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TL_account.updateConnectedBot request = new TL_account.updateConnectedBot();
        request.deleted = true;
        request.bot = MessagesController.getInstance(account).getInputUser(bot.user);
        request.recipients = new TL_account.TL_inputBusinessBotRecipients();
        String operationId = "business-bot-delete-" + account + "-" + bot.dialogId;
        JsonObject readbackArgs = accountArguments(account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.business.bot_list", readbackArgs);
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        processUpdates(account, outcome.response);
        waitForConnectedBot(account, bot.dialogId, null, false);
        JsonObject data = peerJson(bot);
        data.addProperty("deleted", true);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.bot_list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessLinkList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        TL_account.businessChatLinks response = fetchBusinessLinks(account);
        JsonObject data = new JsonObject();
        JsonArray links = new JsonArray();
        for (TL_account.TL_businessChatLink link : response.links) {
            links.add(businessLinkJson(link));
        }
        data.add("links", links);
        data.addProperty("count", response.links.size());
        data.addProperty("source", "account.getBusinessChatLinks");
        return TelegramMcpServer.successEnvelope(data);
    }

    private synchronized JsonObject businessLinkCreate(JsonObject args)
            throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "idempotency_key", 8, 128);
        String payloadHash = idempotencyArgumentsHash(
                "business.link_create.request.v3", args);
        JsonObject replay = idempotencyReplay(
                account, "business_link_create", key, payloadHash);
        if (replay != null) {
            replay.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(replay);
        }
        TL_account.TL_inputBusinessChatLink input = businessLinkInput(account, args);
        TL_account.createBusinessChatLink request =
                new TL_account.createBusinessChatLink();
        request.link = input;
        String operationId = "business-link-create-"
                + sha256Hex(account + ":" + key).substring(0, 24);
        JsonObject timeoutReadback = accountArguments(account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.business.link_list", timeoutReadback);
        if (!(outcome.response instanceof TL_account.TL_businessChatLink)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_account.TL_businessChatLink created =
                (TL_account.TL_businessChatLink) outcome.response;
        String slug = businessLinkSlug(created.link);
        TL_account.TL_businessChatLink readback = waitForBusinessLink(
                account, slug, input, true);
        JsonObject data = businessLinkJson(readback);
        data.addProperty("slug", slug);
        data.addProperty("idempotent_replay", false);
        JsonObject readbackArgs = accountArguments(account);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.link_list", readbackArgs);
        storeIdempotency(account, "business_link_create", key, payloadHash, data);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessLinkEdit(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String slug = requiredString(args, "slug", 1, 128);
        findBusinessLink(account, slug, true);
        TL_account.TL_inputBusinessChatLink input = businessLinkInput(account, args);
        TL_account.editBusinessChatLink request =
                new TL_account.editBusinessChatLink();
        request.slug = slug;
        request.link = input;
        String operationId = "business-link-edit-" + account + "-" + slug;
        JsonObject readbackArgs = accountArguments(account);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.business.link_list", readbackArgs);
        if (!(outcome.response instanceof TL_account.TL_businessChatLink)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_account.TL_businessChatLink readback = waitForBusinessLink(
                account, slug, input, true);
        JsonObject data = businessLinkJson(readback);
        data.addProperty("slug", slug);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.link_list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject businessLinkDelete(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        String slug = requiredString(args, "slug", 1, 128);
        if (findBusinessLink(account, slug, false) == null) {
            JsonObject data = new JsonObject();
            data.addProperty("slug", slug);
            data.addProperty("deleted", true);
            data.addProperty("idempotent_replay", true);
            return TelegramMcpServer.successEnvelope(data);
        }
        TL_account.deleteBusinessChatLink request =
                new TL_account.deleteBusinessChatLink();
        request.slug = slug;
        String operationId = "business-link-delete-" + account + "-" + slug;
        JsonObject readbackArgs = accountArguments(account);
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.business.link_list", readbackArgs).response,
                "account.deleteBusinessChatLink");
        waitForBusinessLink(account, slug, null, false);
        JsonObject data = new JsonObject();
        data.addProperty("slug", slug);
        data.addProperty("deleted", true);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.business.link_list", readbackArgs);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject privacyGet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "key", 1, 64);
        TL_account.privacyRules rules = fetchPrivacyRules(account, key);
        JsonObject data = privacyRulesJson(account, key, rules.rules);
        data.addProperty("source", "telegram_server_account.getPrivacy");
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject privacySet(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        String key = requiredString(args, "key", 1, 64);
        if (!optionalBoolean(args, "replace", false)) {
            JsonObject details = privacyRulesJson(
                    account, key, fetchPrivacyRules(account, key).rules);
            details.addProperty("required_argument", "replace=true");
            throw new McpException("PRECONDITION_FAILED",
                    "Privacy rules are a full replacement; pass replace=true explicitly",
                    false, details);
        }
        String base = requiredString(args, "base", 1, 16);
        TL_account.setPrivacy request = new TL_account.setPrivacy();
        request.key = inputPrivacyKey(key);

        Set<Long> allowIds = new HashSet<>();
        Set<Long> disallowIds = new HashSet<>();
        addPrivacyPeers(account, args, "allow_peers", true, request.rules, allowIds);
        addPrivacyPeers(account, args, "disallow_peers", false, request.rules, disallowIds);
        Set<Long> overlap = new HashSet<>(allowIds);
        overlap.retainAll(disallowIds);
        if (!overlap.isEmpty()) invalid("A peer cannot be both allowed and disallowed");

        if ("everybody".equals(base)) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueAllowAll());
        } else if ("contacts".equals(base)) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
        } else if ("nobody".equals(base)) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
        } else {
            invalid("base must be everybody, contacts, or nobody");
        }
        if (optionalBoolean(args, "allow_close_friends", false)) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueAllowCloseFriends());
        }
        if (optionalBoolean(args, "allow_premium", false)) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueAllowPremium());
        }
        if (optionalBoolean(args, "disallow_contacts", false)) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueDisallowContacts());
        }
        String bots = optionalString(args, "bots", "inherit");
        if ("allow".equals(bots)) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueAllowBots());
        } else if ("disallow".equals(bots)) {
            request.rules.add(new TLRPC.TL_inputPrivacyValueDisallowBots());
        } else if (!"inherit".equals(bots)) {
            invalid("bots must be inherit, allow, or disallow");
        }

        JsonObject expected = requestedPrivacyJson(account, key, args, allowIds, disallowIds);
        String operationId = "privacy-set-" + account + "-" + key + "-" + UUID.randomUUID();
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);
        readbackArgs.addProperty("key", key);
        RequestOutcome outcome = writeRequest(account, request, operationId,
                "telegram.privacy.get", readbackArgs);
        if (!(outcome.response instanceof TL_account.privacyRules)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_account.privacyRules acknowledged = (TL_account.privacyRules) outcome.response;
        cachePeers(account, acknowledged.users, acknowledged.chats);

        JsonObject readback = null;
        TL_account.privacyRules serverRules = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            serverRules = fetchPrivacyRules(account, key);
            readback = privacyRulesJson(account, key, serverRules.rules);
            if (privacySemanticsEqual(expected, readback)) break;
            readback = null;
            sleepReadback("Privacy-rule readback was interrupted");
        }
        if (readback == null || serverRules == null) {
            JsonObject details = new JsonObject();
            details.add("expected", expected);
            throw new McpException("READBACK_FAILED",
                    "Privacy rules did not match an independent account.getPrivacy readback",
                    true, details);
        }
        int ruleType = privacyRuleType(key);
        if (ruleType >= 0) {
            TL_account.privacyRules finalServerRules = serverRules;
            uiCall(() -> {
                ContactsController.getInstance(account)
                        .setPrivacyRules(finalServerRules.rules, ruleType);
                return null;
            });
        }
        addWriteEvidence(readback, operationId, true, true,
                ruleType >= 0, false, "telegram.privacy.get", readbackArgs);
        return TelegramMcpServer.successEnvelope(readback);
    }

    private JsonObject sessionList(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        RequestOutcome outcome = request(account, new TL_account.getAuthorizations());
        if (!(outcome.response instanceof TL_account.authorizations)) throw unexpectedResponse(outcome.response);
        TL_account.authorizations response = (TL_account.authorizations) outcome.response;
        JsonArray sessions = new JsonArray();
        for (TLRPC.TL_authorization authorization : response.authorizations) {
            String ref = sessionReference(account, authorization.hash);
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
        data.addProperty("reference_stability", "stable_hmac_until_authorization_changes");
        data.add("sessions", sessions);
        return TelegramMcpServer.successEnvelope(data);
    }

    private JsonObject sessionTerminate(JsonObject args) throws McpException {
        int account = requireActiveAccount(args);
        requireConfirm(args);
        String reference = requiredString(args, "session_id", 8, 128);
        SharedPreferences terminated = ApplicationLoader.applicationContext
                .getSharedPreferences(SESSION_TERMINATION_PREFS, Context.MODE_PRIVATE);
        String replayKey = account + ":" + reference;
        JsonObject readbackArgs = new JsonObject();
        readbackArgs.addProperty("account", account);

        RequestOutcome beforeOutcome = request(account, new TL_account.getAuthorizations());
        if (!(beforeOutcome.response instanceof TL_account.authorizations)) {
            throw unexpectedResponse(beforeOutcome.response);
        }
        TL_account.authorizations before = (TL_account.authorizations) beforeOutcome.response;
        TLRPC.TL_authorization target = null;
        for (TLRPC.TL_authorization authorization : before.authorizations) {
            if (reference.equals(sessionReference(account, authorization.hash))) {
                target = authorization;
                break;
            }
        }
        if (target == null) {
            if (terminated.getBoolean(replayKey, false)) {
                JsonObject replay = new JsonObject();
                replay.addProperty("session_id", reference);
                replay.addProperty("terminated", true);
                replay.addProperty("idempotent_replay", true);
                addWriteEvidence(replay, "session-terminate-" + reference,
                        true, true, true, false,
                        "telegram.session.list", readbackArgs);
                return TelegramMcpServer.successEnvelope(replay);
            }
            throw new McpException("STALE_REFERENCE",
                    "session_id is not present in the current authorization set", false, null);
        }
        if (target.current) {
            throw new McpException("CURRENT_SESSION_PROTECTED",
                    "The current Android session cannot be terminated through this tool", false, null);
        }
        TL_account.resetAuthorization request = new TL_account.resetAuthorization();
        request.hash = target.hash;
        String operationId = "session-terminate-" + reference;
        requireBoolTrue(writeRequest(account, request, operationId,
                "telegram.session.list", readbackArgs).response, "resetAuthorization");

        RequestOutcome afterOutcome = request(account, new TL_account.getAuthorizations());
        if (!(afterOutcome.response instanceof TL_account.authorizations)) {
            throw unexpectedResponse(afterOutcome.response);
        }
        boolean stillPresent = false;
        for (TLRPC.TL_authorization authorization :
                ((TL_account.authorizations) afterOutcome.response).authorizations) {
            if (reference.equals(sessionReference(account, authorization.hash))) {
                stillPresent = true;
                break;
            }
        }
        if (stillPresent) {
            throw new McpException("READBACK_FAILED",
                    "Telegram acknowledged termination but the authorization is still present", true, null);
        }
        if (!terminated.edit().putBoolean(replayKey, true).commit()) {
            JsonObject details = new JsonObject();
            details.addProperty("session_id", reference);
            details.addProperty("terminated", true);
            details.addProperty("server_readback_verified", true);
            details.addProperty("replay_marker_persisted", false);
            details.add("readback", readbackArgs.deepCopy());
            throw new McpException("OUTCOME_UNKNOWN",
                    "The session is terminated, but its durable replay marker could not be persisted",
                    true, details);
        }
        JsonObject data = new JsonObject();
        data.addProperty("session_id", reference);
        data.addProperty("terminated", true);
        data.addProperty("idempotent_replay", false);
        addWriteEvidence(data, operationId,
                true, true, true, false,
                "telegram.session.list", readbackArgs);
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

    private ArrayList<TLRPC.Message> fetchExactMessages(
            int account,
            PeerRef peer,
            ArrayList<Integer> ids,
            boolean scheduled,
            boolean requireAll) throws McpException {
        TLObject exactRequest;
        if (scheduled) {
            TLRPC.TL_messages_getScheduledMessages request =
                    new TLRPC.TL_messages_getScheduledMessages();
            request.peer = peer.inputPeer;
            request.id.addAll(ids);
            exactRequest = request;
        } else if (peer.chat != null && ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_getMessages request = new TLRPC.TL_channels_getMessages();
            request.channel = MessagesController.getInputChannel(peer.chat);
            request.id.addAll(ids);
            exactRequest = request;
        } else {
            TLRPC.TL_messages_getMessages request = new TLRPC.TL_messages_getMessages();
            request.id.addAll(ids);
            exactRequest = request;
        }
        RequestOutcome outcome = request(account, exactRequest);
        if (!(outcome.response instanceof TLRPC.messages_Messages)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Messages response = (TLRPC.messages_Messages) outcome.response;
        cachePeers(account, response.users, response.chats);
        Map<Integer, TLRPC.Message> byId = new HashMap<>();
        for (TLRPC.Message message : response.messages) {
            if (message == null || message instanceof TLRPC.TL_messageEmpty
                    || isDeletedMessageTombstone(message)) {
                continue;
            }
            long actualDialogId = MessageObject.getDialogId(message);
            if (actualDialogId != peer.dialogId) {
                JsonObject details = new JsonObject();
                details.addProperty("requested_peer",
                        canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
                details.addProperty("actual_dialog_id", Long.toString(actualDialogId));
                details.addProperty("message_id", message.id);
                throw new McpException("MESSAGE_PEER_MISMATCH",
                        "A requested message belongs to a different dialog", false, details);
            }
            if (scheduled != message.from_scheduled && scheduled) {
                JsonObject details = messageJson(account, message);
                throw new McpException("MESSAGE_MODE_MISMATCH",
                        "A requested message is not a scheduled message", false, details);
            }
            byId.put(message.id, message);
        }
        ArrayList<TLRPC.Message> ordered = new ArrayList<>();
        JsonArray missing = new JsonArray();
        for (Integer id : ids) {
            TLRPC.Message message = byId.get(id);
            if (message == null) {
                missing.add(id);
            } else {
                ordered.add(message);
            }
        }
        if (requireAll && missing.size() != 0) {
            JsonObject details = peerJson(peer);
            details.add("missing_message_ids", missing);
            details.addProperty("scheduled", scheduled);
            throw new McpException("MESSAGE_NOT_FOUND",
                    "One or more message IDs were not found in the requested peer and mode", false, details);
        }
        return ordered;
    }

    private static boolean isDeletedMessageTombstone(TLRPC.Message message) {
        return message instanceof TLRPC.TL_messageService
                && ((TLRPC.TL_messageService) message).action
                instanceof TLRPC.TL_messageActionHistoryClear;
    }

    private static ArrayList<MessageObject> messageObjects(
            int account, ArrayList<TLRPC.Message> messages) {
        ArrayList<MessageObject> result = new ArrayList<>();
        for (TLRPC.Message message : messages) {
            result.add(new MessageObject(account, message, false, true));
        }
        return result;
    }

    private TLRPC.Message waitForExactMessage(
            int account,
            PeerRef peer,
            int messageId,
            boolean scheduled,
            String expectedText,
            boolean requireTextMatch) throws McpException {
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        McpException lastError = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                ArrayList<TLRPC.Message> messages = fetchExactMessages(
                        account, peer, ids, scheduled, false);
                if (!messages.isEmpty()
                        && (!requireTextMatch || expectedText.equals(messages.get(0).message))) {
                    return messages.get(0);
                }
            } catch (McpException error) {
                lastError = error;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new McpException("INTERRUPTED",
                        "Message readback was interrupted", true, null);
            }
        }
        JsonObject details = new JsonObject();
        details.addProperty("message_id", messageId);
        details.addProperty("scheduled", scheduled);
        if (lastError != null) details.addProperty("last_error", lastError.code);
        throw new McpException("READBACK_FAILED",
                "The exact message did not reach the expected server state", true, details);
    }

    private void waitForMessagesAbsent(
            int account,
            PeerRef peer,
            ArrayList<Integer> ids,
            boolean scheduled,
            String operationId) throws McpException {
        for (int attempt = 0; attempt < 12; attempt++) {
            ArrayList<TLRPC.Message> remaining = fetchExactMessages(
                    account, peer, ids, scheduled, false);
            if (remaining.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                JsonObject readbackArgs = new JsonObject();
                readbackArgs.addProperty("account", account);
                readbackArgs.addProperty("peer",
                        canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
                readbackArgs.add("message_ids", intArray(ids));
                readbackArgs.addProperty("scheduled", scheduled);
                throw new McpException("OUTCOME_UNKNOWN",
                        "Delete readback was interrupted; read before retrying", false,
                        unknownOutcomeDetails(operationId, "telegram.message.get", readbackArgs));
            }
        }
        JsonObject details = new JsonObject();
        details.addProperty("operation_id", operationId);
        details.add("remaining_message_ids", intArray(ids));
        details.addProperty("scheduled", scheduled);
        throw new McpException("READBACK_FAILED",
                "One or more deleted messages remain present in exact server readback", true, details);
    }

    private SendResult sendTextViaHelper(
            int account,
            PeerRef peer,
            String text,
            ArrayList<TLRPC.MessageEntity> entities,
            boolean linkPreview,
            MessageObject reply,
            boolean silent,
            int scheduleDate,
            String idempotencyKey,
            String operationId) throws McpException {
        return sendTextViaHelper(account, peer, text, entities, linkPreview,
                reply, null, silent, scheduleDate, idempotencyKey, operationId,
                null, 0);
    }

    private SendResult sendTextViaHelper(
            int account,
            PeerRef peer,
            String text,
            ArrayList<TLRPC.MessageEntity> entities,
            boolean linkPreview,
            MessageObject reply,
            MessageObject replyToTop,
            boolean silent,
            int scheduleDate,
            String idempotencyKey,
            String operationId) throws McpException {
        return sendTextViaHelper(account, peer, text, entities, linkPreview,
                reply, replyToTop, silent, scheduleDate, idempotencyKey,
                operationId, null, 0);
    }

    private SendResult sendTextViaHelper(
            int account,
            PeerRef peer,
            String text,
            ArrayList<TLRPC.MessageEntity> entities,
            boolean linkPreview,
            MessageObject reply,
            MessageObject replyToTop,
            boolean silent,
            int scheduleDate,
            String idempotencyKey,
            String operationId,
            String quickReplyShortcut,
            int quickReplyShortcutId) throws McpException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger localId = new AtomicInteger();
        AtomicReference<TLRPC.Message> sentMessage = new AtomicReference<>();
        AtomicReference<String> sendError = new AtomicReference<>();
        boolean scheduled = scheduleDate != 0;
        NotificationCenter.NotificationCenterDelegate observer =
                (eventId, eventAccount, eventArgs) -> {
                    if (eventAccount != account) return;
                    try {
                        if (eventId == NotificationCenter.didReceiveNewMessages
                                && eventArgs.length >= 3
                                && ((Long) eventArgs[0]) == peer.dialogId
                                && ((Boolean) eventArgs[2]) == scheduled) {
                            @SuppressWarnings("unchecked")
                            ArrayList<MessageObject> messages =
                                    (ArrayList<MessageObject>) eventArgs[1];
                            for (MessageObject message : messages) {
                                if (message != null && message.messageOwner != null
                                        && text.equals(message.messageOwner.message)
                                        && message.messageOwner.out
                                        && messageHasMcpOperation(
                                        message.messageOwner, operationId)
                                        && message.getId() < 0) {
                                    localId.compareAndSet(0, message.getId());
                                }
                            }
                        } else if (eventId == NotificationCenter.messageReceivedByServer
                                && eventArgs.length >= 7
                                && ((Long) eventArgs[3]) == peer.dialogId
                                && ((Boolean) eventArgs[6]) == scheduled) {
                            int oldId = (Integer) eventArgs[0];
                            Object rawMessage = eventArgs[2];
                            if (rawMessage instanceof TLRPC.Message) {
                                TLRPC.Message message = (TLRPC.Message) rawMessage;
                                int expectedLocalId = localId.get();
                                if (text.equals(message.message)
                                        && expectedLocalId != 0
                                        && expectedLocalId == oldId) {
                                    sentMessage.compareAndSet(null, message);
                                    latch.countDown();
                                }
                            }
                        } else if (eventId == NotificationCenter.messageSendError
                                && eventArgs.length >= 1
                                && localId.get() != 0
                                && ((Integer) eventArgs[0]) == localId.get()) {
                            sendError.compareAndSet(null, "Telegram marked the local message as failed");
                            latch.countDown();
                        }
                    } catch (Throwable ignore) {
                        // Another Telegram event may use a legacy argument shape; ignore it safely.
                    }
                };

        try {
            markIdempotentEffectStarted();
            uiCall(() -> {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.addObserver(observer, NotificationCenter.didReceiveNewMessages);
                center.addObserver(observer, NotificationCenter.messageReceivedByServer);
                center.addObserver(observer, NotificationCenter.messageSendError);
                HashMap<String, String> localParams = mcpSendParams(
                        idempotencyKey, operationId);
                SendMessagesHelper.SendMessageParams params =
                        SendMessagesHelper.SendMessageParams.of(
                                text, peer.dialogId, reply, replyToTop, null,
                                linkPreview, entities, null, localParams,
                                !silent, scheduleDate, 0, null, false);
                params.quick_reply_shortcut = quickReplyShortcut;
                params.quick_reply_shortcut_id = quickReplyShortcutId;
                SendMessagesHelper.getInstance(account).sendMessage(params);
                return null;
            });
            if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                String readbackTool = quickReplyShortcut == null
                        && quickReplyShortcutId == 0
                        ? "telegram.message.search" : "telegram.quick_reply.list";
                JsonObject readbackArgs = quickReplyShortcut == null
                        && quickReplyShortcutId == 0
                        ? searchReadbackArguments(account, peer, text)
                        : accountArguments(account);
                JsonObject details = unknownOutcomeDetails(
                        operationId, readbackTool, readbackArgs);
                throw new McpException("OUTCOME_UNKNOWN",
                        "The SendMessagesHelper operation did not reach a terminal event before timeout; read before retrying",
                        false, details);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            String readbackTool = quickReplyShortcut == null
                    && quickReplyShortcutId == 0
                    ? "telegram.message.search" : "telegram.quick_reply.list";
            JsonObject readbackArgs = quickReplyShortcut == null
                    && quickReplyShortcutId == 0
                    ? searchReadbackArguments(account, peer, text)
                    : accountArguments(account);
            JsonObject details = unknownOutcomeDetails(
                    operationId, readbackTool, readbackArgs);
            throw new McpException("OUTCOME_UNKNOWN",
                    "The send wait was interrupted; read before retrying", false, details);
        } finally {
            removeSendObserversBestEffort(account, observer);
        }
        if (sendError.get() != null) {
            JsonObject details = new JsonObject();
            details.addProperty("operation_id", operationId);
            details.addProperty("local_message_id", localId.get());
            throw new McpException("SEND_FAILED", sendError.get(), false, details);
        }
        TLRPC.Message message = sentMessage.get();
        if (message == null || message.id <= 0) {
            throw new McpException("SEND_FAILED",
                    "SendMessagesHelper completed without a stable server message ID", false, null);
        }
        return new SendResult(message.id, message);
    }

    private SendResult sendStructuredViaHelper(
            int account,
            PeerRef peer,
            boolean scheduled,
            String operationId,
            SentMessageMatcher matcher,
            Callable<Void> action) throws McpException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger localId = new AtomicInteger();
        AtomicReference<TLRPC.Message> sentMessage = new AtomicReference<>();
        AtomicReference<String> sendError = new AtomicReference<>();
        NotificationCenter.NotificationCenterDelegate observer =
                (eventId, eventAccount, eventArgs) -> {
                    if (eventAccount != account) return;
                    try {
                        if (eventId == NotificationCenter.didReceiveNewMessages
                                && eventArgs.length >= 3
                                && ((Long) eventArgs[0]) == peer.dialogId
                                && ((Boolean) eventArgs[2]) == scheduled) {
                            @SuppressWarnings("unchecked")
                            ArrayList<MessageObject> messages =
                                    (ArrayList<MessageObject>) eventArgs[1];
                            for (MessageObject object : messages) {
                                if (object != null && object.messageOwner != null
                                        && object.messageOwner.out && object.getId() < 0
                                        && messageHasMcpOperation(
                                        object.messageOwner, operationId)
                                        && matcher.matches(object.messageOwner)) {
                                    localId.compareAndSet(0, object.getId());
                                }
                            }
                        } else if (eventId == NotificationCenter.messageReceivedByServer
                                && eventArgs.length >= 7
                                && ((Long) eventArgs[3]) == peer.dialogId
                                && ((Boolean) eventArgs[6]) == scheduled) {
                            int oldId = (Integer) eventArgs[0];
                            Object raw = eventArgs[2];
                            int expectedLocalId = localId.get();
                            if (raw instanceof TLRPC.Message
                                    && expectedLocalId != 0
                                    && oldId == expectedLocalId
                                    && matcher.matches((TLRPC.Message) raw)) {
                                sentMessage.compareAndSet(null, (TLRPC.Message) raw);
                                latch.countDown();
                            }
                        } else if (eventId == NotificationCenter.messageSendError
                                && eventArgs.length >= 1 && localId.get() != 0
                                && ((Integer) eventArgs[0]) == localId.get()) {
                            sendError.compareAndSet(null,
                                    "Telegram marked the structured message as failed");
                            latch.countDown();
                        }
                    } catch (Throwable ignore) {
                        // Ignore unrelated notification variants.
                    }
                };
        try {
            markIdempotentEffectStarted();
            uiCall(() -> {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.addObserver(observer, NotificationCenter.didReceiveNewMessages);
                center.addObserver(observer, NotificationCenter.messageReceivedByServer);
                center.addObserver(observer, NotificationCenter.messageSendError);
                action.call();
                return null;
            });
            if (!latch.await(MEDIA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new McpException("OUTCOME_UNKNOWN",
                        "Structured message did not reach a terminal send event; read before retrying",
                        false, unknownOutcomeDetails(operationId,
                        "telegram.message.history", peerReadbackArguments(account, peer)));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("OUTCOME_UNKNOWN",
                    "Structured-message wait was interrupted; read before retrying", false,
                    unknownOutcomeDetails(operationId,
                            "telegram.message.history", peerReadbackArguments(account, peer)));
        } finally {
            removeSendObserversBestEffort(account, observer);
        }
        if (sendError.get() != null) {
            JsonObject details = new JsonObject();
            details.addProperty("operation_id", operationId);
            details.addProperty("local_message_id", localId.get());
            throw new McpException("SEND_FAILED", sendError.get(), false, details);
        }
        TLRPC.Message message = sentMessage.get();
        if (message == null || message.id <= 0) {
            throw new McpException("SEND_FAILED",
                    "Structured send completed without a stable server message ID",
                    false, null);
        }
        return new SendResult(message.id, message);
    }

    private ArrayList<Integer> sendMediaViaHelper(
            int account,
            PeerRef peer,
            ArrayList<StagedFile> stagedFiles,
            String kind,
            String caption,
            ArrayList<TLRPC.MessageEntity> captionEntities,
            MessageObject reply,
            MessageObject replyToTop,
            boolean silent,
            int scheduleDate,
            boolean spoiler,
            String operationId) throws McpException {
        CountDownLatch latch = new CountDownLatch(stagedFiles.size());
        Set<String> expectedPaths = Collections.synchronizedSet(new HashSet<>());
        Set<Integer> localIds = Collections.synchronizedSet(new HashSet<>());
        Set<Integer> serverIds = Collections.synchronizedSet(new HashSet<>());
        AtomicReference<String> sendError = new AtomicReference<>();
        ArrayList<File> operationFiles = new ArrayList<>();
        try {
            for (int index = 0; index < stagedFiles.size(); index++) {
                File operationFile = operationScopedSendCopy(
                        stagedFiles.get(index).file, operationId, index);
                operationFiles.add(operationFile);
                expectedPaths.add(operationFile.getAbsolutePath());
            }
        } catch (McpException error) {
            for (File operationFile : operationFiles) operationFile.delete();
            throw error;
        }
        boolean scheduled = scheduleDate != 0;
        NotificationCenter.NotificationCenterDelegate observer =
                (eventId, eventAccount, eventArgs) -> {
                    if (eventAccount != account) return;
                    try {
                        if (eventId == NotificationCenter.didReceiveNewMessages
                                && eventArgs.length >= 3
                                && ((Long) eventArgs[0]) == peer.dialogId
                                && ((Boolean) eventArgs[2]) == scheduled) {
                            @SuppressWarnings("unchecked")
                            ArrayList<MessageObject> messages =
                                    (ArrayList<MessageObject>) eventArgs[1];
                            for (MessageObject message : messages) {
                                if (message == null || message.messageOwner == null
                                        || message.getId() >= 0 || !message.messageOwner.out) {
                                    continue;
                                }
                                String path = message.messageOwner.attachPath;
                                if (path != null && expectedPaths.contains(path)) {
                                    localIds.add(message.getId());
                                }
                            }
                        } else if (eventId == NotificationCenter.messageReceivedByServer
                                && eventArgs.length >= 7
                                && ((Long) eventArgs[3]) == peer.dialogId
                                && ((Boolean) eventArgs[6]) == scheduled) {
                            int oldId = (Integer) eventArgs[0];
                            int newId = (Integer) eventArgs[1];
                            Object rawMessage = eventArgs[2];
                            boolean media = rawMessage instanceof TLRPC.Message
                                    && ((TLRPC.Message) rawMessage).media != null
                                    && !(((TLRPC.Message) rawMessage).media
                                    instanceof TLRPC.TL_messageMediaEmpty);
                            if (newId > 0 && media && localIds.contains(oldId)
                                    && serverIds.add(newId)) {
                                latch.countDown();
                            }
                        } else if (eventId == NotificationCenter.messageSendError
                                && eventArgs.length >= 1
                                && localIds.contains((Integer) eventArgs[0])) {
                            sendError.compareAndSet(null,
                                    "Telegram marked a staged media message as failed");
                            while (latch.getCount() > 0) latch.countDown();
                        }
                    } catch (Throwable ignore) {
                        // Ignore unrelated notification variants.
                    }
                };
        try {
            markIdempotentEffectStarted();
            uiCall(() -> {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.addObserver(observer, NotificationCenter.didReceiveNewMessages);
                center.addObserver(observer, NotificationCenter.messageReceivedByServer);
                center.addObserver(observer, NotificationCenter.messageSendError);
                ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
                for (int index = 0; index < stagedFiles.size(); index++) {
                    StagedFile staged = stagedFiles.get(index);
                    SendMessagesHelper.SendingMediaInfo info =
                            new SendMessagesHelper.SendingMediaInfo();
                    info.path = operationFiles.get(index).getAbsolutePath();
                    String mime = staged.metadata.get("mime_type").getAsString();
                    info.isVideo = "video".equals(kind)
                            || ("auto".equals(kind) && mime.startsWith("video/"));
                    info.forceImage = "photo".equals(kind)
                            || ("auto".equals(kind) && mime.startsWith("image/"));
                    info.caption = index == 0 ? caption : "";
                    info.entities = index == 0 ? captionEntities : null;
                    info.hasMediaSpoilers = spoiler;
                    media.add(info);
                }
                SendMessagesHelper.prepareSendingMedia(
                        AccountInstance.getInstance(account), media, peer.dialogId,
                        reply, replyToTop, null, null, "document".equals(kind),
                        stagedFiles.size() > 1, null, !silent, scheduleDate, 0,
                        0, false, null, null, 0, 0, false, 0, 0, null);
                return null;
            });
            if (!latch.await(MEDIA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                JsonObject readbackArgs = peerReadbackArguments(account, peer);
                readbackArgs.addProperty("limit", Math.min(MAX_LIMIT, stagedFiles.size() * 2));
                throw new McpException("OUTCOME_UNKNOWN",
                        "Media sending did not reach terminal server events before timeout; read before retrying",
                        false, unknownOutcomeDetails(operationId,
                        "telegram.message.history", readbackArgs));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("OUTCOME_UNKNOWN",
                    "Media send wait was interrupted; read before retrying", false,
                    unknownOutcomeDetails(operationId,
                    "telegram.message.history", peerReadbackArguments(account, peer)));
        } finally {
            removeSendObserversBestEffort(account, observer);
            for (File operationFile : operationFiles) {
                try {
                    Utilities.globalQueue.postRunnable(
                            operationFile::delete, 24 * 60 * 60_000L);
                } catch (Throwable cleanupError) {
                    FileLog.e(cleanupError);
                }
            }
        }
        if (sendError.get() != null) {
            JsonObject details = new JsonObject();
            details.addProperty("operation_id", operationId);
            throw new McpException("SEND_FAILED", sendError.get(), false, details);
        }
        if (serverIds.size() != stagedFiles.size()) {
            JsonObject details = new JsonObject();
            details.addProperty("expected", stagedFiles.size());
            details.addProperty("received", serverIds.size());
            throw new McpException("OUTCOME_UNKNOWN",
                    "Media helper completed without all stable server message IDs", false,
                    details);
        }
        ArrayList<Integer> result = new ArrayList<>(serverIds);
        Collections.sort(result);
        for (File operationFile : operationFiles) operationFile.delete();
        return result;
    }

    private ArrayList<Integer> forwardViaHelper(
            int account,
            PeerRef destination,
            ArrayList<MessageObject> sources,
            MessageObject replyToTop,
            boolean silent,
            String operationId) throws McpException {
        CountDownLatch latch = new CountDownLatch(sources.size());
        Set<Integer> localIds = Collections.synchronizedSet(new HashSet<>());
        Set<Integer> serverIds = Collections.synchronizedSet(new HashSet<>());
        AtomicReference<String> sendError = new AtomicReference<>();
        NotificationCenter.NotificationCenterDelegate observer =
                (eventId, eventAccount, eventArgs) -> {
                    if (eventAccount != account) return;
                    try {
                        if (eventId == NotificationCenter.didReceiveNewMessages
                                && eventArgs.length >= 3
                                && ((Long) eventArgs[0]) == destination.dialogId
                                && !((Boolean) eventArgs[2])) {
                            @SuppressWarnings("unchecked")
                            ArrayList<MessageObject> messages =
                                    (ArrayList<MessageObject>) eventArgs[1];
                            for (MessageObject message : messages) {
                                if (message != null && message.messageOwner != null
                                        && message.getId() < 0
                                        && message.messageOwner.out
                                        && messageHasMcpOperation(
                                        message.messageOwner, operationId)
                                        && message.messageOwner.fwd_from != null) {
                                    localIds.add(message.getId());
                                }
                            }
                        } else if (eventId == NotificationCenter.messageReceivedByServer
                                && eventArgs.length >= 7
                                && ((Long) eventArgs[3]) == destination.dialogId
                                && !((Boolean) eventArgs[6])) {
                            int oldId = (Integer) eventArgs[0];
                            int newId = (Integer) eventArgs[1];
                            Object rawMessage = eventArgs[2];
                            boolean matchesLocal = localIds.contains(oldId);
                            boolean forwarded = rawMessage instanceof TLRPC.Message
                                    && ((TLRPC.Message) rawMessage).fwd_from != null;
                            if (newId > 0 && matchesLocal && forwarded && serverIds.add(newId)) {
                                latch.countDown();
                            }
                        } else if (eventId == NotificationCenter.messageSendError
                                && eventArgs.length >= 1
                                && localIds.contains((Integer) eventArgs[0])) {
                            sendError.compareAndSet(null,
                                    "Telegram marked a forwarded local message as failed");
                            while (latch.getCount() > 0) latch.countDown();
                        }
                    } catch (Throwable ignore) {
                        // Ignore unrelated legacy-shaped notification events.
                    }
                };
        try {
            markIdempotentEffectStarted();
            uiCall(() -> {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.addObserver(observer, NotificationCenter.didReceiveNewMessages);
                center.addObserver(observer, NotificationCenter.messageReceivedByServer);
                center.addObserver(observer, NotificationCenter.messageSendError);
                int result = SendMessagesHelper.getInstance(account)
                        .sendMessageWithLocalParams(
                        sources, destination.dialogId, false, false, !silent, 0,
                        0, replyToTop, 0, 0, 0, null,
                        mcpOperationParams(operationId));
                if (result != 0) {
                    sendError.set(
                            "SendMessagesHelper rejected forwarding with code " + result);
                    while (latch.getCount() > 0) latch.countDown();
                }
                return null;
            });
            if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                JsonObject readbackArgs = new JsonObject();
                readbackArgs.addProperty("account", account);
                readbackArgs.addProperty("peer",
                        canonicalPeer(MessagesController.getInstance(account), destination.dialogId));
                readbackArgs.addProperty("limit", Math.min(MAX_LIMIT, sources.size() * 2));
                throw new McpException("OUTCOME_UNKNOWN",
                        "Forwarding did not reach terminal events before timeout; read before retrying",
                        false, unknownOutcomeDetails(operationId,
                        "telegram.message.history", readbackArgs));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("OUTCOME_UNKNOWN",
                    "Forwarding wait was interrupted; read before retrying", false,
                    unknownOutcomeDetails(operationId, "telegram.message.history", new JsonObject()));
        } finally {
            removeSendObserversBestEffort(account, observer);
        }
        if (sendError.get() != null) {
            JsonObject details = new JsonObject();
            details.addProperty("operation_id", operationId);
            throw new McpException("FORWARD_FAILED", sendError.get(), false, details);
        }
        if (serverIds.size() != sources.size()) {
            JsonObject details = new JsonObject();
            details.addProperty("expected_count", sources.size());
            details.addProperty("confirmed_count", serverIds.size());
            throw new McpException("OUTCOME_UNKNOWN",
                    "Forwarding produced an incomplete set of confirmed message IDs",
                    false, details);
        }
        ArrayList<Integer> result = new ArrayList<>(serverIds);
        Collections.sort(result);
        return result;
    }

    private TLRPC.Message waitForReactionState(
            int account, PeerRef peer, int messageId, String reaction) throws McpException {
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        for (int attempt = 0; attempt < 12; attempt++) {
            ArrayList<TLRPC.Message> messages = fetchExactMessages(
                    account, peer, ids, false, true);
            TLRPC.Message message = messages.get(0);
            boolean chosen = false;
            if (message.reactions != null) {
                for (TLRPC.ReactionCount value : message.reactions.results) {
                    if (value.chosen && value.reaction instanceof TLRPC.TL_reactionEmoji
                            && reaction.equals(((TLRPC.TL_reactionEmoji) value.reaction).emoticon)) {
                        chosen = true;
                        break;
                    }
                }
            }
            if ((!reaction.isEmpty() && chosen) || (reaction.isEmpty() && !hasChosenReaction(message))) {
                return message;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new McpException("INTERRUPTED",
                        "Reaction readback was interrupted", true, null);
            }
        }
        throw new McpException("READBACK_FAILED",
                "Message reaction state did not match the server readback", true, null);
    }

    private TLRPC.Message waitForPinnedState(
            int account, PeerRef peer, int messageId, boolean pinned) throws McpException {
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        for (int attempt = 0; attempt < 12; attempt++) {
            TLRPC.Message message = fetchExactMessages(
                    account, peer, ids, false, true).get(0);
            if (message.pinned == pinned) {
                return message;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new McpException("INTERRUPTED",
                        "Pinned-message readback was interrupted", true, null);
            }
        }
        throw new McpException("READBACK_FAILED",
                "Pinned-message state did not match the exact server readback", true, null);
    }

    private static boolean hasChosenReaction(TLRPC.Message message) {
        if (message.reactions == null) return false;
        for (TLRPC.ReactionCount value : message.reactions.results) {
            if (value.chosen) return true;
        }
        return false;
    }

    private static JsonArray richDraftFields(
            TLRPC.DraftMessage draft, long topicId) {
        JsonArray fields = new JsonArray();
        if (draft == null || draft instanceof TLRPC.TL_draftMessageEmpty) return fields;
        if (draft.entities != null && !draft.entities.isEmpty()) fields.add("entities");
        if (draft.reply_to != null
                && !isTopicRoutingReply(draft.reply_to, topicId)) {
            fields.add("reply_to");
        }
        if (draft.media != null) fields.add("media");
        if (draft.effect != 0) fields.add("effect");
        if (draft.suggested_post != null) fields.add("suggested_post");
        if (draft.rich_message != null) fields.add("rich_message");
        if (draft.invert_media) fields.add("invert_media");
        if (draft.no_webpage) fields.add("no_webpage");
        return fields;
    }

    private static boolean isEffectivelyEmptyDraft(
            TLRPC.DraftMessage draft, long topicId) {
        if (draft == null || draft instanceof TLRPC.TL_draftMessageEmpty) {
            return true;
        }
        boolean hasContent = !TextUtils.isEmpty(draft.message)
                || draft.entities != null && !draft.entities.isEmpty()
                || draft.media != null
                || draft.rich_message != null
                || draft.suggested_post != null
                || draft.effect != 0
                || draft.invert_media
                || draft.no_webpage;
        if (hasContent) return false;
        return draft.reply_to == null
                || isTopicRoutingReply(draft.reply_to, topicId);
    }

    private static boolean isTopicRoutingReply(
            TLRPC.InputReplyTo reply, long topicId) {
        if (topicId <= 1
                || !(reply instanceof TLRPC.TL_inputReplyToMessage)) {
            return false;
        }
        TLRPC.TL_inputReplyToMessage message =
                (TLRPC.TL_inputReplyToMessage) reply;
        return message.reply_to_msg_id == (int) topicId
                && (message.flags & 1) != 0
                && message.top_msg_id == (int) topicId;
    }

    private TLRPC.DraftMessage waitForServerDraft(
            int account,
            PeerRef peer,
            long topicId,
            String expectedText,
            boolean expectEmpty) throws McpException {
        TLRPC.DraftMessage last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchServerDraft(account, peer.dialogId, topicId);
            boolean empty = isEffectivelyEmptyDraft(last, topicId);
            if ((expectEmpty && empty)
                    || (!expectEmpty && !empty && expectedText.equals(last.message))) {
                return last;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new McpException("INTERRUPTED",
                        "Draft server readback was interrupted", true, null);
            }
        }
        JsonObject details = new JsonObject();
        details.addProperty("expected_text", expectedText);
        details.addProperty("actual_text", last == null || last.message == null ? "" : last.message);
        throw new McpException("READBACK_FAILED",
                "Draft did not match messages.getAllDrafts server readback", true, details);
    }

    private TLRPC.DraftMessage fetchServerDraft(
            int account, long dialogId, long topicId) throws McpException {
        RequestOutcome outcome = request(account, new TLRPC.TL_messages_getAllDrafts());
        if (!(outcome.response instanceof TLRPC.Updates)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.Updates updates = (TLRPC.Updates) outcome.response;
        cachePeers(account, updates.users, updates.chats);
        for (TLRPC.Update base : updates.updates) {
            if (!(base instanceof TL_update.TL_updateDraftMessage)) continue;
            TL_update.TL_updateDraftMessage update = (TL_update.TL_updateDraftMessage) base;
            long updateDialogId = MessageObject.getPeerId(update.peer);
            long updateTopicId = update.saved_peer_id != null
                    ? MessageObject.getPeerId(update.saved_peer_id) : update.top_msg_id;
            if (updateDialogId == dialogId && updateTopicId == topicId) {
                return update.draft;
            }
        }
        return null;
    }

    private TLRPC.photos_Photos fetchProfilePhotos(
            int account, int offset, long maxId, int limit) throws McpException {
        TLRPC.TL_photos_getUserPhotos request = new TLRPC.TL_photos_getUserPhotos();
        request.user_id = new TLRPC.TL_inputUserSelf();
        request.offset = offset;
        request.max_id = maxId;
        request.limit = limit;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.photos_Photos)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.photos_Photos response = (TLRPC.photos_Photos) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        return response;
    }

    private TLRPC.Photo findProfilePhoto(int account, long photoId, boolean required)
            throws McpException {
        int offset = 0;
        for (int page = 0; page < 100; page++) {
            TLRPC.photos_Photos response = fetchProfilePhotos(account, offset, 0, MAX_LIMIT);
            for (TLRPC.Photo photo : response.photos) {
                if (photo != null && photo.id == photoId
                        && !(photo instanceof TLRPC.TL_photoEmpty)) {
                    return photo;
                }
            }
            if (response.photos.size() < MAX_LIMIT
                    || response.count > 0 && offset + response.photos.size() >= response.count) {
                break;
            }
            offset += response.photos.size();
        }
        if (required) {
            JsonObject details = new JsonObject();
            details.addProperty("photo_id", Long.toString(photoId));
            throw new McpException("PHOTO_NOT_FOUND",
                    "Profile photo ID was not returned by photos.getUserPhotos",
                    false, details);
        }
        return null;
    }

    private static TLRPC.InputPhoto inputPhoto(TLRPC.Photo photo) throws McpException {
        if (photo == null || photo instanceof TLRPC.TL_photoEmpty) {
            invalid("A non-empty profile photo is required");
        }
        TLRPC.TL_inputPhoto result = new TLRPC.TL_inputPhoto();
        result.id = photo.id;
        result.access_hash = photo.access_hash;
        result.file_reference = photo.file_reference == null
                ? new byte[0] : photo.file_reference;
        return result;
    }

    private JsonObject waitForCurrentProfilePhoto(
            int account, long expectedId, boolean expectedPresent) throws McpException {
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = profileGetServer(account);
            if (last.get("profile_photo_present").getAsBoolean() == expectedPresent
                    && last.get("profile_photo_id").getAsLong() == expectedId) {
                return last;
            }
            sleepReadback("Profile-photo readback was interrupted");
        }
        JsonObject details = last == null ? new JsonObject() : last;
        details.addProperty("expected_profile_photo_id", Long.toString(expectedId));
        details.addProperty("expected_profile_photo_present", expectedPresent);
        throw new McpException("READBACK_FAILED",
                "Profile photo did not match users.getFullUser", true, details);
    }

    private TLRPC.UserFull fetchSelfFullUser(int account) throws McpException {
        TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
        request.id = new TLRPC.TL_inputUserSelf();
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_users_userFull)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_users_userFull response =
                (TLRPC.TL_users_userFull) outcome.response;
        cachePeers(account, response.users, response.chats);
        return response.full_user;
    }

    private TLRPC.TL_messages_quickReplies fetchQuickReplies(int account)
            throws McpException {
        TLRPC.TL_messages_getQuickReplies request =
                new TLRPC.TL_messages_getQuickReplies();
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_quickReplies)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_quickReplies response =
                (TLRPC.TL_messages_quickReplies) outcome.response;
        cachePeers(account, response.users, response.chats);
        return response;
    }

    private TLRPC.messages_Messages fetchQuickReplyMessages(
            int account, int shortcutId) throws McpException {
        TLRPC.TL_messages_getQuickReplyMessages request =
                new TLRPC.TL_messages_getQuickReplyMessages();
        request.shortcut_id = shortcutId;
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Messages)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Messages response = (TLRPC.messages_Messages) outcome.response;
        cachePeers(account, response.users, response.chats);
        return response;
    }

    private TLRPC.TL_quickReply requireQuickReply(int account, int shortcutId)
            throws McpException {
        TLRPC.TL_messages_quickReplies replies = fetchQuickReplies(account);
        TLRPC.TL_quickReply reply = findQuickReply(replies, shortcutId);
        if (reply == null) {
            throw new McpException("NOT_FOUND", "Quick-reply shortcut was not found",
                    false, null);
        }
        return reply;
    }

    private static TLRPC.TL_quickReply findQuickReply(
            TLRPC.TL_messages_quickReplies response, int shortcutId) {
        if (response == null) return null;
        for (TLRPC.TL_quickReply reply : response.quick_replies) {
            if (reply != null && reply.shortcut_id == shortcutId) return reply;
        }
        return null;
    }

    private static TLRPC.TL_quickReply findQuickReply(
            TLRPC.TL_messages_quickReplies response, String shortcut) {
        if (response == null) return null;
        for (TLRPC.TL_quickReply reply : response.quick_replies) {
            if (reply != null && TextUtils.equals(reply.shortcut, shortcut)) return reply;
        }
        return null;
    }

    private static TLRPC.Message findMessage(
            ArrayList<TLRPC.Message> messages, int messageId) {
        if (messages == null) return null;
        for (TLRPC.Message message : messages) {
            if (message != null && message.id == messageId) return message;
        }
        return null;
    }

    private static JsonObject quickReplyCollectionJson(
            int account, TLRPC.TL_messages_quickReplies response) {
        JsonObject data = new JsonObject();
        JsonArray replies = new JsonArray();
        for (TLRPC.TL_quickReply reply : response.quick_replies) {
            replies.add(quickReplyJson(account, response, reply));
        }
        data.addProperty("account", account);
        data.add("quick_replies", replies);
        data.add("shortcut_ids", intArray(quickReplyOrder(response)));
        data.addProperty("count", response.quick_replies.size());
        data.addProperty("source", "telegram_server_messages.getQuickReplies");
        return data;
    }

    private static JsonObject quickReplyJson(
            int account,
            TLRPC.TL_messages_quickReplies response,
            TLRPC.TL_quickReply reply) {
        JsonObject data = new JsonObject();
        data.addProperty("shortcut_id", reply.shortcut_id);
        data.addProperty("shortcut", reply.shortcut == null ? "" : reply.shortcut);
        data.addProperty("special", isSpecialQuickReply(reply.shortcut));
        data.addProperty("top_message_id", reply.top_message);
        data.addProperty("message_count", reply.count);
        TLRPC.Message top = findMessage(response.messages, reply.top_message);
        if (top != null) data.add("top_message", messageJson(account, top));
        return data;
    }

    private JsonObject quickReplyWithMessagesJson(
            int account,
            TLRPC.TL_messages_quickReplies response,
            TLRPC.TL_quickReply reply) throws McpException {
        JsonObject data = quickReplyJson(account, response, reply);
        TLRPC.messages_Messages messageResponse = fetchQuickReplyMessages(
                account, reply.shortcut_id);
        JsonArray messages = new JsonArray();
        for (TLRPC.Message message : messageResponse.messages) {
            messages.add(messageJson(account, message));
        }
        data.add("messages", messages);
        data.addProperty("message_count", messageResponse.messages.size());
        data.addProperty("source", "telegram_server_messages.getQuickReplyMessages");
        return data;
    }

    private static ArrayList<Integer> quickReplyOrder(
            TLRPC.TL_messages_quickReplies response) {
        ArrayList<Integer> order = new ArrayList<>();
        for (TLRPC.TL_quickReply reply : response.quick_replies) {
            order.add(reply.shortcut_id);
        }
        return order;
    }

    private static String quickReplyShortcutName(String value) throws McpException {
        if (value.length() > 32
                || !value.matches("[\\d_\\p{L}\\u200c\\u00b7\\u0d80-\\u0dff]+")) {
            invalid("shortcut must be 1..32 letters, digits, underscores, or Telegram-supported join characters");
        }
        return value;
    }

    private static boolean isSpecialQuickReply(String shortcut) {
        return "hello".equalsIgnoreCase(shortcut)
                || "away".equalsIgnoreCase(shortcut);
    }

    private TLRPC.TL_messages_quickReplies waitForQuickReplyName(
            int account, int shortcutId, String shortcut) throws McpException {
        TLRPC.TL_messages_quickReplies last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchQuickReplies(account);
            TLRPC.TL_quickReply reply = findQuickReply(last, shortcutId);
            if (reply != null && TextUtils.equals(reply.shortcut, shortcut)) return last;
            sleepReadback("Quick-reply rename readback was interrupted");
        }
        JsonObject details = last == null
                ? new JsonObject() : quickReplyCollectionJson(account, last);
        details.addProperty("expected_shortcut_id", shortcutId);
        details.addProperty("expected_shortcut", shortcut);
        throw new McpException("READBACK_FAILED",
                "Quick-reply name did not match messages.getQuickReplies",
                true, details);
    }

    private TLRPC.TL_messages_quickReplies waitForQuickReplyOrder(
            int account, ArrayList<Integer> expected) throws McpException {
        TLRPC.TL_messages_quickReplies last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchQuickReplies(account);
            if (expected.equals(quickReplyOrder(last))) return last;
            sleepReadback("Quick-reply reorder readback was interrupted");
        }
        JsonObject details = last == null
                ? new JsonObject() : quickReplyCollectionJson(account, last);
        details.add("expected_shortcut_ids", intArray(expected));
        throw new McpException("READBACK_FAILED",
                "Quick-reply order did not match messages.getQuickReplies",
                true, details);
    }

    private TLRPC.Message waitForQuickReplyMessage(
            int account,
            int shortcutId,
            int messageId,
            FormattedText expected,
            boolean expectedPresent) throws McpException {
        TLRPC.Message last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            TLRPC.messages_Messages messages = fetchQuickReplyMessages(
                    account, shortcutId);
            last = findMessage(messages.messages, messageId);
            if (!expectedPresent && last == null) return null;
            if (expectedPresent && last != null
                    && expected.text.equals(last.message)
                    && messageEntitiesJson(expected.entities).equals(
                    messageEntitiesJson(last.entities))) {
                return last;
            }
            sleepReadback("Quick-reply message readback was interrupted");
        }
        JsonObject details = new JsonObject();
        details.addProperty("shortcut_id", shortcutId);
        details.addProperty("message_id", messageId);
        details.addProperty("expected_present", expectedPresent);
        if (last != null) details.add("last_message", messageJson(account, last));
        throw new McpException("READBACK_FAILED",
                "Quick-reply message did not reach the requested server state",
                true, details);
    }

    private void waitForQuickReplyMessagesAbsent(
            int account, int shortcutId, ArrayList<Integer> messageIds)
            throws McpException {
        Set<Integer> expectedAbsent = new HashSet<>(messageIds);
        JsonArray remaining = new JsonArray();
        for (int attempt = 0; attempt < 12; attempt++) {
            TLRPC.TL_messages_quickReplies replies = fetchQuickReplies(account);
            if (findQuickReply(replies, shortcutId) == null) return;
            TLRPC.messages_Messages messages = fetchQuickReplyMessages(
                    account, shortcutId);
            remaining = new JsonArray();
            for (TLRPC.Message message : messages.messages) {
                if (expectedAbsent.contains(message.id)) remaining.add(message.id);
            }
            if (remaining.size() == 0) return;
            sleepReadback("Quick-reply message delete readback was interrupted");
        }
        JsonObject details = new JsonObject();
        details.addProperty("shortcut_id", shortcutId);
        details.add("remaining_message_ids", remaining);
        throw new McpException("READBACK_FAILED",
                "Deleted quick-reply messages remain on the server", true, details);
    }

    private void waitForQuickReplyAbsent(int account, int shortcutId)
            throws McpException {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (findQuickReply(fetchQuickReplies(account), shortcutId) == null) return;
            sleepReadback("Quick-reply delete readback was interrupted");
        }
        JsonObject details = new JsonObject();
        details.addProperty("shortcut_id", shortcutId);
        throw new McpException("READBACK_FAILED",
                "Deleted quick-reply shortcut remains on the server", true, details);
    }

    private ArrayList<Integer> uniqueMessageIds(TLObject response) {
        JsonArray raw = extractMessageIds(response);
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>();
        for (JsonElement value : raw) {
            int id = value.getAsInt();
            if (id > 0) unique.add(id);
        }
        return new ArrayList<>(unique);
    }

    private static ArrayList<String> messageContentFingerprints(
            ArrayList<TLRPC.Message> messages) {
        ArrayList<String> result = new ArrayList<>();
        for (TLRPC.Message message : messages) {
            result.add(messageContentFingerprint(message));
        }
        Collections.sort(result);
        return result;
    }

    private static String messageContentFingerprint(TLRPC.Message message) {
        StringBuilder value = new StringBuilder();
        value.append(message == null || message.message == null ? "" : message.message)
                .append('\n')
                .append(message == null ? "[]" : messageEntitiesJson(message.entities))
                .append('\n');
        if (message == null || message.media == null) return sha256Hex(value.toString());
        TLRPC.MessageMedia media = message.media;
        value.append(media.getClass().getSimpleName()).append('\n');
        if (media.document != null) value.append("document:").append(media.document.id);
        if (media.photo != null) value.append("photo:").append(media.photo.id);
        if (media.geo != null) value.append("geo:").append(media.geo.lat)
                .append(':').append(media.geo._long);
        value.append("\ncontact:").append(media.phone_number == null ? "" : media.phone_number)
                .append(':').append(media.first_name == null ? "" : media.first_name)
                .append(':').append(media.last_name == null ? "" : media.last_name)
                .append(':').append(media.user_id)
                .append("\nvenue:").append(media.title == null ? "" : media.title)
                .append(':').append(media.address == null ? "" : media.address);
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) media).poll;
            if (poll != null) {
                value.append("\npoll:")
                        .append(poll.question == null ? "" : poll.question.text)
                        .append(':').append(poll.multiple_choice)
                        .append(':').append(poll.quiz)
                        .append(':').append(poll.public_voters);
                for (TLRPC.PollAnswer answer : poll.answers) {
                    value.append(':').append(answer.text == null ? "" : answer.text.text);
                }
            }
        }
        return sha256Hex(value.toString());
    }

    private JsonObject businessGetServer(int account) throws McpException {
        TLRPC.UserFull full = fetchSelfFullUser(account);
        JsonObject data = new JsonObject();
        data.addProperty("account", account);
        TLRPC.User self = full.user == null
                ? UserConfig.getInstance(account).getCurrentUser() : full.user;
        data.addProperty("premium", self != null && self.premium);

        JsonObject intro = new JsonObject();
        if (full.business_intro != null) {
            intro.addProperty("title", full.business_intro.title == null
                    ? "" : full.business_intro.title);
            intro.addProperty("description", full.business_intro.description == null
                    ? "" : full.business_intro.description);
            intro.addProperty("sticker_document_id", full.business_intro.sticker == null
                    ? "0" : Long.toString(full.business_intro.sticker.id));
        }
        data.add("intro", intro);

        JsonObject location = new JsonObject();
        if (full.business_location != null) {
            location.addProperty("address", full.business_location.address == null
                    ? "" : full.business_location.address);
            if (full.business_location.geo_point != null
                    && !(full.business_location.geo_point
                    instanceof TLRPC.TL_geoPointEmpty)) {
                location.addProperty("latitude", full.business_location.geo_point.lat);
                location.addProperty("longitude", full.business_location.geo_point._long);
            }
        }
        data.add("location", location);

        JsonObject workHours = new JsonObject();
        if (full.business_work_hours != null) {
            workHours.addProperty("timezone_id",
                    full.business_work_hours.timezone_id == null
                            ? "" : full.business_work_hours.timezone_id);
            workHours.addProperty("open_now", full.business_work_hours.open_now);
            JsonArray intervals = new JsonArray();
            for (TL_account.TL_businessWeeklyOpen interval
                    : full.business_work_hours.weekly_open) {
                JsonObject item = new JsonObject();
                item.addProperty("start_minute", interval.start_minute);
                item.addProperty("end_minute", interval.end_minute);
                intervals.add(item);
            }
            workHours.add("weekly_open", intervals);
        }
        data.add("work_hours", workHours);

        JsonObject greeting = new JsonObject();
        if (full.business_greeting_message != null) {
            greeting.addProperty("shortcut_id",
                    full.business_greeting_message.shortcut_id);
            greeting.addProperty("no_activity_days",
                    full.business_greeting_message.no_activity_days);
            greeting.add("recipients", businessRecipientsJson(
                    full.business_greeting_message.recipients));
        }
        data.add("greeting_message", greeting);

        JsonObject away = new JsonObject();
        if (full.business_away_message != null) {
            away.addProperty("shortcut_id", full.business_away_message.shortcut_id);
            away.addProperty("offline_only", full.business_away_message.offline_only);
            String scheduleType = "";
            if (full.business_away_message.schedule
                    instanceof TL_account.TL_businessAwayMessageScheduleAlways) {
                scheduleType = "always";
            } else if (full.business_away_message.schedule
                    instanceof TL_account.TL_businessAwayMessageScheduleOutsideWorkHours) {
                scheduleType = "outside_work_hours";
            } else if (full.business_away_message.schedule
                    instanceof TL_account.TL_businessAwayMessageScheduleCustom) {
                scheduleType = "custom";
            }
            away.addProperty("schedule_type", scheduleType);
            if (full.business_away_message.schedule
                    instanceof TL_account.TL_businessAwayMessageScheduleCustom) {
                TL_account.TL_businessAwayMessageScheduleCustom schedule =
                        (TL_account.TL_businessAwayMessageScheduleCustom)
                                full.business_away_message.schedule;
                away.addProperty("start_date", schedule.start_date);
                away.addProperty("end_date", schedule.end_date);
            }
            away.add("recipients", businessRecipientsJson(
                    full.business_away_message.recipients));
        }
        data.add("away_message", away);
        data.addProperty("source", "users.getFullUser.business_fields");
        return data;
    }

    private static JsonObject businessRecipientsJson(
            TL_account.TL_businessRecipients recipients) {
        JsonObject result = new JsonObject();
        if (recipients == null) return result;
        result.addProperty("existing_chats", recipients.existing_chats);
        result.addProperty("new_chats", recipients.new_chats);
        result.addProperty("contacts", recipients.contacts);
        result.addProperty("non_contacts", recipients.non_contacts);
        result.addProperty("exclude_selected", recipients.exclude_selected);
        JsonArray users = new JsonArray();
        for (Long id : recipients.users) users.add(Long.toString(id));
        result.add("user_ids", users);
        return result;
    }

    private TL_account.TL_inputBusinessRecipients inputBusinessRecipients(
            int account, JsonObject args, String key) throws McpException {
        if (!args.has(key) || !args.get(key).isJsonObject()) {
            invalid(key + " must be an object when enabling the Business setting");
        }
        JsonObject value = args.getAsJsonObject(key);
        ensureOnlyKeys(value, "existing_chats", "new_chats", "contacts",
                "non_contacts", "exclude_selected", "users");
        TL_account.TL_inputBusinessRecipients result =
                new TL_account.TL_inputBusinessRecipients();
        result.existing_chats = optionalBoolean(value, "existing_chats", false);
        result.new_chats = optionalBoolean(value, "new_chats", false);
        result.contacts = optionalBoolean(value, "contacts", false);
        result.non_contacts = optionalBoolean(value, "non_contacts", false);
        result.exclude_selected = optionalBoolean(
                value, "exclude_selected", false);
        Set<Long> userIds = new HashSet<>();
        if (value.has("users")) {
            JsonArray users = requiredArray(value, "users", 0, 100);
            for (JsonElement userValue : users) {
                if (!userValue.isJsonPrimitive()
                        || !userValue.getAsJsonPrimitive().isString()) {
                    invalid("recipients.users must contain peer strings");
                }
                PeerRef peer = requireUserPeer(resolvePeer(
                        account, userValue.getAsString()), "recipients.users");
                if (peer.dialogId == UserConfig.getInstance(account).getClientUserId()) {
                    invalid("The current account cannot be a Business recipient");
                }
                if (!userIds.add(peer.dialogId)) {
                    invalid("recipients.users must not contain duplicates");
                }
                TLRPC.InputUser input = uiCall(() ->
                        MessagesController.getInstance(account).getInputUser(peer.user));
                if (input == null || input instanceof TLRPC.TL_inputUserEmpty) {
                    throw new McpException("PEER_UNAVAILABLE",
                            "Telegram could not construct a Business recipient input user",
                            true, peerJson(peer));
                }
                result.users.add(input);
            }
        }
        if (!result.users.isEmpty()) result.flags |= 16;
        if (!result.existing_chats && !result.new_chats
                && !result.contacts && !result.non_contacts
                && result.users.isEmpty()) {
            invalid("recipients must select at least one chat category or user");
        }
        return result;
    }

    private static JsonObject inputBusinessRecipientsJson(
            TL_account.TL_inputBusinessRecipients recipients) {
        JsonObject result = new JsonObject();
        result.addProperty("existing_chats", recipients.existing_chats);
        result.addProperty("new_chats", recipients.new_chats);
        result.addProperty("contacts", recipients.contacts);
        result.addProperty("non_contacts", recipients.non_contacts);
        result.addProperty("exclude_selected", recipients.exclude_selected);
        JsonArray users = new JsonArray();
        for (TLRPC.InputUser user : recipients.users) {
            if (user != null && user.user_id > 0) {
                users.add(Long.toString(user.user_id));
            }
        }
        result.add("user_ids", users);
        return result;
    }

    private TL_account.connectedBots fetchConnectedBots(int account)
            throws McpException {
        RequestOutcome outcome = request(account, new TL_account.getConnectedBots());
        if (!(outcome.response instanceof TL_account.connectedBots)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_account.connectedBots response = (TL_account.connectedBots) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        return response;
    }

    private static TL_account.TL_connectedBot findConnectedBot(
            TL_account.connectedBots response, long botId) {
        if (response == null) return null;
        for (TL_account.TL_connectedBot bot : response.connected_bots) {
            if (bot != null && bot.bot_id == botId) return bot;
        }
        return null;
    }

    private static JsonObject connectedBotsJson(
            int account, TL_account.connectedBots response) {
        JsonObject data = new JsonObject();
        JsonArray bots = new JsonArray();
        for (TL_account.TL_connectedBot bot : response.connected_bots) {
            bots.add(connectedBotJson(account, bot));
        }
        data.add("connected_bots", bots);
        data.addProperty("count", bots.size());
        data.addProperty("source", "telegram_server_account.getConnectedBots");
        return data;
    }

    private static JsonObject connectedBotJson(
            int account, TL_account.TL_connectedBot bot) {
        JsonObject data = new JsonObject();
        data.addProperty("bot_id", Long.toString(bot.bot_id));
        TLRPC.User user = MessagesController.getInstance(account).getUser(bot.bot_id);
        if (user != null) data.add("bot", userJson(user));
        data.add("rights", businessBotRightsJson(bot.rights));
        data.add("recipients", businessBotRecipientsJson(bot.recipients));
        data.addProperty("device", bot.device == null ? "" : bot.device);
        data.addProperty("date", bot.date);
        data.addProperty("location", bot.location == null ? "" : bot.location);
        return data;
    }

    private static TL_account.TL_businessBotRights businessBotRightsFromJson(
            JsonObject value) throws McpException {
        String[] keys = new String[]{
                "reply", "read_messages", "delete_sent_messages",
                "delete_received_messages", "edit_name", "edit_bio",
                "edit_profile_photo", "edit_username", "view_gifts",
                "sell_gifts", "change_gift_settings",
                "transfer_and_upgrade_gifts", "transfer_stars", "manage_stories"};
        ensureOnlyKeys(value, keys);
        if (value.size() != keys.length) {
            invalid("rights must explicitly contain all 14 Business bot rights");
        }
        TL_account.TL_businessBotRights rights =
                new TL_account.TL_businessBotRights();
        rights.reply = requiredBoolean(value, "reply");
        rights.read_messages = requiredBoolean(value, "read_messages");
        rights.delete_sent_messages = requiredBoolean(value, "delete_sent_messages");
        rights.delete_received_messages = requiredBoolean(
                value, "delete_received_messages");
        rights.edit_name = requiredBoolean(value, "edit_name");
        rights.edit_bio = requiredBoolean(value, "edit_bio");
        rights.edit_profile_photo = requiredBoolean(value, "edit_profile_photo");
        rights.edit_username = requiredBoolean(value, "edit_username");
        rights.view_gifts = requiredBoolean(value, "view_gifts");
        rights.sell_gifts = requiredBoolean(value, "sell_gifts");
        rights.change_gift_settings = requiredBoolean(value, "change_gift_settings");
        rights.transfer_and_upgrade_gifts = requiredBoolean(
                value, "transfer_and_upgrade_gifts");
        rights.transfer_stars = requiredBoolean(value, "transfer_stars");
        rights.manage_stories = requiredBoolean(value, "manage_stories");
        return rights;
    }

    private static JsonObject businessBotRightsJson(
            TL_account.TL_businessBotRights rights) {
        JsonObject data = new JsonObject();
        data.addProperty("reply", rights != null && rights.reply);
        data.addProperty("read_messages", rights != null && rights.read_messages);
        data.addProperty("delete_sent_messages",
                rights != null && rights.delete_sent_messages);
        data.addProperty("delete_received_messages",
                rights != null && rights.delete_received_messages);
        data.addProperty("edit_name", rights != null && rights.edit_name);
        data.addProperty("edit_bio", rights != null && rights.edit_bio);
        data.addProperty("edit_profile_photo",
                rights != null && rights.edit_profile_photo);
        data.addProperty("edit_username", rights != null && rights.edit_username);
        data.addProperty("view_gifts", rights != null && rights.view_gifts);
        data.addProperty("sell_gifts", rights != null && rights.sell_gifts);
        data.addProperty("change_gift_settings",
                rights != null && rights.change_gift_settings);
        data.addProperty("transfer_and_upgrade_gifts",
                rights != null && rights.transfer_and_upgrade_gifts);
        data.addProperty("transfer_stars", rights != null && rights.transfer_stars);
        data.addProperty("manage_stories", rights != null && rights.manage_stories);
        return data;
    }

    private TL_account.TL_inputBusinessBotRecipients inputBusinessBotRecipients(
            int account, JsonObject args, String key) throws McpException {
        if (!args.has(key) || !args.get(key).isJsonObject()) {
            invalid(key + " must be a complete object");
        }
        JsonObject value = args.getAsJsonObject(key);
        ensureOnlyKeys(value, "existing_chats", "new_chats", "contacts",
                "non_contacts", "exclude_selected", "users", "exclude_users");
        TL_account.TL_inputBusinessBotRecipients result =
                new TL_account.TL_inputBusinessBotRecipients();
        result.existing_chats = optionalBoolean(value, "existing_chats", false);
        result.new_chats = optionalBoolean(value, "new_chats", false);
        result.contacts = optionalBoolean(value, "contacts", false);
        result.non_contacts = optionalBoolean(value, "non_contacts", false);
        result.exclude_selected = optionalBoolean(
                value, "exclude_selected", false);
        Set<Long> seen = new HashSet<>();
        addBusinessBotRecipientUsers(
                account, value, "users", result.users, seen);
        addBusinessBotRecipientUsers(
                account, value, "exclude_users", result.exclude_users, seen);
        if (!result.users.isEmpty()) result.flags |= 16;
        if (!result.exclude_users.isEmpty()) result.flags |= 64;
        if (!result.existing_chats && !result.new_chats
                && !result.contacts && !result.non_contacts
                && result.users.isEmpty()) {
            invalid("recipients must select at least one chat category or included user");
        }
        return result;
    }

    private void addBusinessBotRecipientUsers(
            int account,
            JsonObject value,
            String key,
            ArrayList<TLRPC.InputUser> output,
            Set<Long> seen) throws McpException {
        if (!value.has(key)) return;
        for (JsonElement item : requiredArray(value, key, 0, 100)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                invalid("recipients." + key + " must contain peer strings");
            }
            PeerRef peer = requireUserPeer(
                    resolvePeer(account, item.getAsString()), "recipients." + key);
            if (!seen.add(peer.dialogId)) {
                invalid("A recipient user cannot appear more than once");
            }
            TLRPC.InputUser input = uiCall(() ->
                    MessagesController.getInstance(account).getInputUser(peer.user));
            if (input == null || input instanceof TLRPC.TL_inputUserEmpty) {
                throw new McpException("PEER_UNAVAILABLE",
                        "Telegram could not construct a Business bot recipient",
                        true, peerJson(peer));
            }
            output.add(input);
        }
    }

    private static JsonObject inputBusinessBotRecipientsJson(
            TL_account.TL_inputBusinessBotRecipients recipients) {
        JsonObject data = new JsonObject();
        data.addProperty("existing_chats", recipients.existing_chats);
        data.addProperty("new_chats", recipients.new_chats);
        data.addProperty("contacts", recipients.contacts);
        data.addProperty("non_contacts", recipients.non_contacts);
        data.addProperty("exclude_selected", recipients.exclude_selected);
        JsonArray users = new JsonArray();
        for (TLRPC.InputUser user : recipients.users) {
            if (user != null && user.user_id > 0) users.add(Long.toString(user.user_id));
        }
        JsonArray excluded = new JsonArray();
        for (TLRPC.InputUser user : recipients.exclude_users) {
            if (user != null && user.user_id > 0) excluded.add(Long.toString(user.user_id));
        }
        data.add("user_ids", users);
        data.add("exclude_user_ids", excluded);
        return data;
    }

    private static JsonObject businessBotRecipientsJson(
            TL_account.TL_businessBotRecipients recipients) {
        JsonObject data = new JsonObject();
        if (recipients == null) return data;
        data.addProperty("existing_chats", recipients.existing_chats);
        data.addProperty("new_chats", recipients.new_chats);
        data.addProperty("contacts", recipients.contacts);
        data.addProperty("non_contacts", recipients.non_contacts);
        data.addProperty("exclude_selected", recipients.exclude_selected);
        JsonArray users = new JsonArray();
        for (Long id : recipients.users) users.add(Long.toString(id));
        JsonArray excluded = new JsonArray();
        for (Long id : recipients.exclude_users) excluded.add(Long.toString(id));
        data.add("user_ids", users);
        data.add("exclude_user_ids", excluded);
        return data;
    }

    private TL_account.TL_connectedBot waitForConnectedBot(
            int account, long botId, JsonObject expected, boolean expectedPresent)
            throws McpException {
        TL_account.connectedBots last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchConnectedBots(account);
            TL_account.TL_connectedBot bot = findConnectedBot(last, botId);
            if (!expectedPresent && bot == null) return null;
            if (expectedPresent && bot != null
                    && jsonContains(connectedBotJson(account, bot), expected)) {
                return bot;
            }
            sleepReadback("Business-bot readback was interrupted");
        }
        JsonObject details = last == null
                ? new JsonObject() : connectedBotsJson(account, last);
        details.addProperty("expected_bot_id", Long.toString(botId));
        details.addProperty("expected_present", expectedPresent);
        throw new McpException("READBACK_FAILED",
                "Business bot did not match account.getConnectedBots",
                true, details);
    }

    private static boolean businessSectionMatches(
            JsonObject actual, JsonObject expected) {
        return expected.size() == 0
                ? actual == null || actual.size() == 0
                : actual != null && jsonContains(actual, expected);
    }

    private JsonObject waitForBusinessSection(
            int account, String section, JsonObject expected) throws McpException {
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = businessGetServer(account);
            JsonObject actual = last.getAsJsonObject(section);
            boolean matches = expected.size() == 0
                    ? actual.size() == 0 : jsonContains(actual, expected);
            if (matches) return last;
            sleepReadback("Business-setting readback was interrupted");
        }
        JsonObject details = last == null ? new JsonObject() : last;
        details.add("expected_" + section, expected);
        throw new McpException("READBACK_FAILED",
                "Business setting did not match users.getFullUser", true, details);
    }

    private static boolean jsonContains(JsonObject actual, JsonObject expected) {
        if (actual == null) return false;
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            if (!actual.has(entry.getKey())) return false;
            JsonElement a = actual.get(entry.getKey());
            JsonElement e = entry.getValue();
            if (e.isJsonObject()) {
                if (!a.isJsonObject() || !jsonContains(
                        a.getAsJsonObject(), e.getAsJsonObject())) return false;
            } else if (!e.equals(a)) {
                return false;
            }
        }
        return true;
    }

    private TL_account.businessChatLinks fetchBusinessLinks(int account)
            throws McpException {
        RequestOutcome outcome = request(account, new TL_account.getBusinessChatLinks());
        if (!(outcome.response instanceof TL_account.businessChatLinks)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_account.businessChatLinks response =
                (TL_account.businessChatLinks) outcome.response;
        cachePeers(account, response.users, response.chats);
        return response;
    }

    private TL_account.TL_inputBusinessChatLink businessLinkInput(
            int account, JsonObject args) throws McpException {
        String raw = requiredString(args, "message", 0, 4096);
        FormattedText message = raw.isEmpty()
                ? new FormattedText("", new ArrayList<>())
                : parseFormattedText(account, raw,
                optionalString(args, "parse_mode", "plain"));
        TL_account.TL_inputBusinessChatLink result =
                new TL_account.TL_inputBusinessChatLink();
        result.message = message.text;
        if (!message.entities.isEmpty()) {
            result.flags |= 1;
            result.entities.addAll(message.entities);
        }
        result.title = optionalString(args, "title", "");
        if (result.title.length() > 32) invalid("title exceeds 32 characters");
        if (!result.title.isEmpty()) result.flags |= 2;
        return result;
    }

    private static JsonObject businessLinkJson(TL_account.TL_businessChatLink link) {
        JsonObject result = new JsonObject();
        result.addProperty("link", link.link == null ? "" : link.link);
        result.addProperty("slug", businessLinkSlug(link.link));
        result.addProperty("message", link.message == null ? "" : link.message);
        result.add("entities", messageEntitiesJson(link.entities));
        result.addProperty("title", link.title == null ? "" : link.title);
        result.addProperty("views", link.views);
        return result;
    }

    private TL_account.TL_businessChatLink findBusinessLink(
            int account, String slug, boolean required) throws McpException {
        for (TL_account.TL_businessChatLink link : fetchBusinessLinks(account).links) {
            if (slug.equals(businessLinkSlug(link.link))) return link;
        }
        if (required) {
            JsonObject details = new JsonObject();
            details.addProperty("slug", slug);
            throw new McpException("BUSINESS_LINK_NOT_FOUND",
                    "Business link slug was not returned by account.getBusinessChatLinks",
                    false, details);
        }
        return null;
    }

    private TL_account.TL_businessChatLink waitForBusinessLink(
            int account,
            String slug,
            TL_account.TL_inputBusinessChatLink expected,
            boolean present) throws McpException {
        TL_account.TL_businessChatLink last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = findBusinessLink(account, slug, false);
            if (!present && last == null) return null;
            if (present && last != null && expected != null
                    && TextUtils.equals(last.message, expected.message)
                    && TextUtils.equals(last.title == null ? "" : last.title,
                    expected.title == null ? "" : expected.title)
                    && messageEntitiesJson(last.entities).equals(
                    messageEntitiesJson(expected.entities))) {
                return last;
            }
            sleepReadback("Business-link readback was interrupted");
        }
        JsonObject details = new JsonObject();
        details.addProperty("slug", slug);
        details.addProperty("expected_present", present);
        if (last != null) details.add("actual", businessLinkJson(last));
        throw new McpException("READBACK_FAILED",
                "Business link did not match account.getBusinessChatLinks",
                true, details);
    }

    private static String businessLinkSlug(String link) {
        if (link == null) return "";
        int query = link.indexOf('?');
        String value = query < 0 ? link : link.substring(0, query);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private JsonObject profileGetServer(int account) throws McpException {
        TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
        request.id = new TLRPC.TL_inputUserSelf();
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_users_userFull)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_users_userFull response = (TLRPC.TL_users_userFull) outcome.response;
        cachePeers(account, response.users, response.chats);
        TLRPC.User user = response.full_user.user;
        if (user == null) {
            user = UserConfig.getInstance(account).getCurrentUser();
        }
        if (user == null) {
            throw new McpException("EMPTY_RESPONSE",
                    "users.getFullUser did not include the current user", true, null);
        }
        JsonObject data = userJson(user);
        TLRPC.UserFull full = response.full_user;
        data.addProperty("about", full.about == null ? "" : full.about);
        data.addProperty("phone_calls_available", full.phone_calls_available);
        data.addProperty("phone_calls_private", full.phone_calls_private);
        data.addProperty("video_calls_available", full.video_calls_available);
        data.addProperty("voice_messages_forbidden", full.voice_messages_forbidden);
        data.addProperty("has_scheduled", full.has_scheduled);
        data.addProperty("common_chats_count", full.common_chats_count);
        data.addProperty("ttl_period", full.ttl_period);
        long profilePhotoId = user.photo == null
                || user.photo instanceof TLRPC.TL_userProfilePhotoEmpty
                ? 0 : user.photo.photo_id;
        data.addProperty("profile_photo_id", Long.toString(profilePhotoId));
        data.addProperty("profile_photo_present", profilePhotoId != 0);
        data.addProperty("personal_photo_present", full.personal_photo != null
                && !(full.personal_photo instanceof TLRPC.TL_photoEmpty));
        JsonObject emojiStatus = new JsonObject();
        if (user.emoji_status instanceof TLRPC.TL_emojiStatus) {
            TLRPC.TL_emojiStatus value = (TLRPC.TL_emojiStatus) user.emoji_status;
            emojiStatus.addProperty("document_id", Long.toString(value.document_id));
            if ((value.flags & 1) != 0) emojiStatus.addProperty("until", value.until);
        } else if (user.emoji_status instanceof TLRPC.TL_emojiStatusCollectible) {
            TLRPC.TL_emojiStatusCollectible value =
                    (TLRPC.TL_emojiStatusCollectible) user.emoji_status;
            emojiStatus.addProperty("collectible", true);
            emojiStatus.addProperty("collectible_id", Long.toString(value.collectible_id));
            emojiStatus.addProperty("document_id", Long.toString(value.document_id));
            if ((value.flags & 1) != 0) emojiStatus.addProperty("until", value.until);
        }
        data.add("emoji_status", emojiStatus);
        JsonObject birthday = new JsonObject();
        if (full.birthday != null) {
            birthday.addProperty("day", full.birthday.day);
            birthday.addProperty("month", full.birthday.month);
            if ((full.birthday.flags & 1) != 0) birthday.addProperty("year", full.birthday.year);
        }
        data.add("birthday", birthday);
        data.addProperty("source", "telegram_server_users.getFullUser");
        return data;
    }

    private JsonObject waitForProfileFields(int account, JsonObject expected) throws McpException {
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = profileGetServer(account);
            boolean matches = true;
            for (String key : new String[]{"first_name", "last_name", "about"}) {
                if (expected.has(key)
                        && !expected.get(key).getAsString().equals(last.get(key).getAsString())) {
                    matches = false;
                }
            }
            if (matches) return last;
            sleepReadback("Profile readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Profile fields did not match users.getFullUser", true, last);
    }

    private JsonObject waitForProfileValue(
            int account, String key, String expected) throws McpException {
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = profileGetServer(account);
            if (last.has(key) && expected.equals(last.get(key).getAsString())) return last;
            sleepReadback("Profile readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Profile value did not match users.getFullUser", true, last);
    }

    private JsonObject waitForBirthday(
            int account, TL_account.TL_birthday expected, boolean clear) throws McpException {
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = profileGetServer(account);
            JsonObject birthday = last.getAsJsonObject("birthday");
            if (clear && birthday.size() == 0) return last;
            if (!clear && birthday.has("day") && birthday.has("month")
                    && birthday.get("day").getAsInt() == expected.day
                    && birthday.get("month").getAsInt() == expected.month
                    && (((expected.flags & 1) == 0 && !birthday.has("year"))
                    || birthday.has("year") && birthday.get("year").getAsInt() == expected.year)) {
                return last;
            }
            sleepReadback("Birthday readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Birthday did not match users.getFullUser", true, last);
    }

    private JsonObject waitForEmojiStatus(
            int account, long documentId, int until, boolean clear) throws McpException {
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = profileGetServer(account);
            JsonObject status = last.getAsJsonObject("emoji_status");
            if (clear && status.size() == 0) return last;
            if (!clear && status.has("document_id")
                    && Long.toString(documentId).equals(
                    status.get("document_id").getAsString())
                    && (until == 0 && !status.has("until")
                    || until != 0 && status.has("until")
                    && status.get("until").getAsInt() == until)) {
                return last;
            }
            sleepReadback("Emoji-status readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Emoji status did not match users.getFullUser", true, last);
    }

    private static void validateDate(int year, int month, int day) throws McpException {
        try {
            LocalDate.of(year, month, day);
        } catch (Throwable error) {
            invalid("day/month/year do not form a valid calendar date");
        }
    }

    private static JsonObject searchReadbackArguments(
            int account, PeerRef peer, String text) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("account", account);
        arguments.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        arguments.addProperty("query", text);
        arguments.addProperty("limit", 20);
        return arguments;
    }

    private static JsonObject unknownOutcomeDetails(
            String operationId, String readbackTool, JsonObject readbackArguments) {
        JsonObject details = new JsonObject();
        details.addProperty("operation_id", operationId);
        details.addProperty("outcome", "unknown");
        details.addProperty("read_before_retry", true);
        JsonObject readback = new JsonObject();
        readback.addProperty("tool", readbackTool);
        readback.add("arguments", readbackArguments);
        details.add("readback", readback);
        return details;
    }

    private RequestOutcome request(int account, TLObject request) throws McpException {
        return requestInternal(account, request, false, null, null, null);
    }

    private RequestOutcome writeRequest(
            int account,
            TLObject request,
            String operationId,
            String readbackTool,
            JsonObject readbackArguments) throws McpException {
        return requestInternal(account, request, true, operationId, readbackTool, readbackArguments);
    }

    private RequestOutcome requestInternal(
            int account,
            TLObject request,
            boolean write,
            String operationId,
            String readbackTool,
            JsonObject readbackArguments) throws McpException {
        if (write) markIdempotentEffectStarted();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TLObject> response = new AtomicReference<>();
        AtomicReference<TLRPC.TL_error> error = new AtomicReference<>();
        AtomicInteger requestId = new AtomicInteger();
        int requestFlags = ConnectionsManager.RequestFlagFailOnServerErrors
                | ConnectionsManager.RequestFlagDoNotWaitFloodWait;
        if (request instanceof TLRPC.TL_channels_deleteChannel
                || request instanceof TLRPC.TL_messages_deleteChat) {
            // Match MessagesController's destructive chat-removal ordering so the
            // request is sequenced after earlier updates for the same session.
            requestFlags |= ConnectionsManager.RequestFlagInvokeAfter;
        }
        int finalRequestFlags = requestFlags;
        uiCall(() -> {
            requestId.set(ConnectionsManager.getInstance(account).sendRequest(request, (result, requestError) -> {
                response.set(result);
                error.set(requestError);
                latch.countDown();
            }, finalRequestFlags));
            return null;
        });
        try {
            if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                if (!write) {
                    ConnectionsManager.getInstance(account).cancelRequest(requestId.get(), true);
                    throw new McpException("TIMEOUT", "Telegram read request timed out", true, null);
                }
                throw new McpException("OUTCOME_UNKNOWN",
                        "Telegram write request timed out; read the target state before retrying",
                        false, unknownOutcomeDetails(operationId, readbackTool, readbackArguments));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (write) {
                throw new McpException("OUTCOME_UNKNOWN",
                        "Telegram write wait was interrupted; read the target state before retrying",
                        false, unknownOutcomeDetails(operationId, readbackTool, readbackArguments));
            }
            throw new McpException("INTERRUPTED", "Telegram read request was interrupted", true, null);
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
            stageCall(() -> {
                MessagesController.getInstance(account).processUpdates(finalUpdates, false);
                return null;
            });
        }
    }

    private String sessionReference(int account, long authorizationHash) {
        String value = "telegram-session:" + account + ":" + authorizationHash;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(referenceSecret.getBytes(StandardCharsets.US_ASCII),
                    "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(66).append("s_");
            for (byte item : digest) {
                builder.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return builder.toString();
        } catch (Throwable error) {
            return "s_" + sha256Hex(referenceSecret + ":" + value);
        }
    }

    private static void requireBoolTrue(TLObject response, String operation) throws McpException {
        if (response instanceof TLRPC.TL_boolTrue) {
            return;
        }
        JsonObject details = new JsonObject();
        details.addProperty("operation", operation);
        details.addProperty("response_type",
                response == null ? "null" : response.getClass().getSimpleName());
        throw new McpException("BUSINESS_REJECTED",
                "Telegram did not confirm " + operation, false, details);
    }

    private static void addWriteEvidence(
            JsonObject data,
            String operationId,
            boolean acknowledged,
            boolean committed,
            boolean locallyApplied,
            boolean persistenceVerified,
            String readbackTool,
            JsonObject readbackArguments) {
        data.addProperty("operation_id", operationId);
        data.addProperty("acknowledged", acknowledged);
        data.addProperty("committed", committed);
        data.addProperty("locally_applied", locallyApplied);
        data.addProperty("readback_verified", committed);
        data.addProperty("persistence_verified", persistenceVerified);
        data.addProperty("outcome", committed ? "confirmed" : "accepted");
        JsonObject readback = new JsonObject();
        readback.addProperty("tool", readbackTool);
        readback.add("arguments", readbackArguments == null ? new JsonObject() : readbackArguments);
        data.add("readback", readback);
    }

    private TL_stories.TL_stories_peerStories fetchPeerStories(
            int account, PeerRef peer) throws McpException {
        TL_stories.TL_stories_getPeerStories request =
                new TL_stories.TL_stories_getPeerStories();
        request.peer = peer.inputPeer;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TL_stories.TL_stories_peerStories)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_stories.TL_stories_peerStories response =
                (TL_stories.TL_stories_peerStories) outcome.response;
        cachePeers(account, response.users, response.chats);
        if (response.stories != null) {
            for (TL_stories.StoryItem story : response.stories.stories) {
                if (story != null) story.dialogId = peer.dialogId;
            }
        }
        return response;
    }

    private TL_stories.StoryItem fetchExactStory(
            int account, PeerRef peer, int storyId, boolean required)
            throws McpException {
        TL_stories.TL_stories_getStoriesByID request =
                new TL_stories.TL_stories_getStoriesByID();
        request.peer = peer.inputPeer;
        request.id.add(storyId);
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TL_stories.TL_stories_stories)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_stories.TL_stories_stories response =
                (TL_stories.TL_stories_stories) outcome.response;
        cachePeers(account, response.users, response.chats);
        for (TL_stories.StoryItem story : response.stories) {
            if (story != null && story.id == storyId
                    && !(story instanceof TL_stories.TL_storyItemDeleted)) {
                story.dialogId = peer.dialogId;
                return story;
            }
        }
        if (required) {
            JsonObject details = peerJson(peer);
            details.addProperty("story_id", storyId);
            throw new McpException("STORY_NOT_FOUND",
                    "Story ID was not returned for the requested peer", false, details);
        }
        return null;
    }

    private static void requireCanPublishStory(int account, PeerRef peer)
            throws McpException {
        if (peer.dialogId == UserConfig.getInstance(account).getClientUserId()) return;
        if (peer.user != null && peer.user.bot && peer.user.bot_can_edit) return;
        if (peer.chat != null && (peer.chat.creator
                || peer.chat.admin_rights != null
                && peer.chat.admin_rights.post_stories)) return;
        throw new McpException("PERMISSION_DENIED",
                "Current account cannot publish stories for this peer",
                false, peerJson(peer));
    }

    private ArrayList<TLRPC.InputPrivacyRule> storyPrivacyRules(
            int account, JsonObject args) throws McpException {
        String privacy = optionalString(args, "privacy", "everyone");
        ArrayList<TLRPC.InputPrivacyRule> rules = new ArrayList<>();
        if ("everyone".equals(privacy)) {
            rules.add(new TLRPC.TL_inputPrivacyValueAllowAll());
        } else if ("contacts".equals(privacy)) {
            rules.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
        } else if ("close_friends".equals(privacy)) {
            rules.add(new TLRPC.TL_inputPrivacyValueAllowCloseFriends());
        } else if ("selected".equals(privacy)) {
            TLRPC.TL_inputPrivacyValueAllowUsers allow =
                    new TLRPC.TL_inputPrivacyValueAllowUsers();
            allow.users.addAll(storyPrivacyUsers(account, args,
                    "privacy_peers", true));
            rules.add(allow);
        } else {
            invalid("privacy must be everyone, contacts, close_friends, or selected");
        }
        ArrayList<TLRPC.InputUser> excluded = storyPrivacyUsers(
                account, args, "except_peers", false);
        if (!excluded.isEmpty()) {
            if ("selected".equals(privacy) || "close_friends".equals(privacy)) {
                invalid("except_peers is valid only with everyone or contacts privacy");
            }
            TLRPC.TL_inputPrivacyValueDisallowUsers disallow =
                    new TLRPC.TL_inputPrivacyValueDisallowUsers();
            disallow.users.addAll(excluded);
            rules.add(0, disallow);
        }
        return rules;
    }

    private ArrayList<TLRPC.InputUser> storyPrivacyUsers(
            int account, JsonObject args, String key, boolean required)
            throws McpException {
        ArrayList<TLRPC.InputUser> result = new ArrayList<>();
        if (!args.has(key)) {
            if (required) invalid(key + " is required for selected privacy");
            return result;
        }
        JsonArray values = requiredArray(args, key, required ? 1 : 0, 100);
        Set<Long> unique = new HashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                invalid(key + " must contain user peer strings");
            }
            PeerRef peer = resolvePeer(account, value.getAsString());
            if (peer.user == null) invalid(key + " accepts only users");
            if (unique.add(peer.user.id)) {
                result.add(MessagesController.getInstance(account).getInputUser(peer.user));
            }
        }
        return result;
    }

    private static String storyPrivacySignature(JsonObject args) {
        return (args.has("privacy") ? args.get("privacy").toString() : "everyone")
                + "\n" + (args.has("privacy_peers")
                ? args.get("privacy_peers").toString() : "[]")
                + "\n" + (args.has("except_peers")
                ? args.get("except_peers").toString() : "[]");
    }

    private static String storyMediaSignature(JsonObject args) {
        return (args.has("media_type") ? args.get("media_type").toString() : "auto")
                + "\n" + (args.has("width") ? args.get("width").toString() : "")
                + "\n" + (args.has("height") ? args.get("height").toString() : "")
                + "\n" + (args.has("duration") ? args.get("duration").toString() : "")
                + "\n" + (args.has("no_sound_video")
                ? args.get("no_sound_video").toString() : "false");
    }

    private TLRPC.InputMedia storyUploadedMedia(
            int account, JsonObject args, StagedFile staged) throws McpException {
        String mimeType = staged.metadata.get("mime_type").getAsString();
        String normalizedMime = mimeType.toLowerCase(Locale.ROOT);
        String mediaType = optionalString(args, "media_type", "auto");
        if ("auto".equals(mediaType)) {
            mediaType = normalizedMime.startsWith("video/") ? "video" : "photo";
        }
        TLRPC.InputFile uploaded = uploadStagedFile(account, staged);
        if ("photo".equals(mediaType)) {
            if (!normalizedMime.startsWith("image/")) {
                invalid("photo story requires an image MIME type");
            }
            TLRPC.TL_inputMediaUploadedPhoto result =
                    new TLRPC.TL_inputMediaUploadedPhoto();
            result.file = uploaded;
            return result;
        }
        if (!"video".equals(mediaType)) {
            invalid("media_type must be auto, photo, or video");
        }
        if (!normalizedMime.startsWith("video/")) {
            invalid("video story requires a video MIME type");
        }
        TLRPC.TL_inputMediaUploadedDocument result =
                new TLRPC.TL_inputMediaUploadedDocument();
        result.file = uploaded;
        result.mime_type = mimeType;
        result.nosound_video = optionalBoolean(args, "no_sound_video", false);
        TLRPC.TL_documentAttributeVideo video =
                new TLRPC.TL_documentAttributeVideo();
        video.duration = requiredDouble(args, "duration", 0.1, 300.0);
        video.w = requiredInt(args, "width", 1, 8192);
        video.h = requiredInt(args, "height", 1, 8192);
        video.supports_streaming = true;
        video.nosound = result.nosound_video;
        result.attributes.add(video);
        TLRPC.TL_documentAttributeFilename filename =
                new TLRPC.TL_documentAttributeFilename();
        filename.file_name = staged.metadata.get("name").getAsString();
        result.attributes.add(filename);
        return result;
    }

    private static TL_stories.StoryItem extractUpdatedStory(
            TLObject response, long dialogId, int storyId) {
        if (!(response instanceof TLRPC.Updates)) return null;
        for (TLRPC.Update update : ((TLRPC.Updates) response).updates) {
            if (!(update instanceof TL_stories.TL_updateStory)) continue;
            TL_stories.TL_updateStory value = (TL_stories.TL_updateStory) update;
            if (value.story != null
                    && MessageObject.getPeerId(value.peer) == dialogId
                    && (storyId == 0 || value.story.id == storyId)) {
                return value.story;
            }
        }
        return null;
    }

    private TL_stories.StoryItem waitForStoryContent(
            int account,
            PeerRef peer,
            int storyId,
            FormattedText expectedCaption,
            JsonObject args,
            boolean verifyMedia) throws McpException {
        TL_stories.StoryItem last = null;
        boolean verifyPrivacy = args.has("privacy") || !args.has("story_id");
        String expectedMedia = verifyMedia ? expectedStoryMediaKind(args) : "";
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchExactStory(account, peer, storyId, true);
            boolean captionMatches = expectedCaption.text.equals(
                    last.caption == null ? "" : last.caption)
                    && messageEntitiesJson(expectedCaption.entities).equals(
                    messageEntitiesJson(last.entities));
            boolean privacyMatches = !verifyPrivacy
                    || storyPrivacyMatches(account, args, last);
            boolean mediaMatches = !verifyMedia
                    || expectedMedia.equals(storyMediaKind(last));
            boolean publishFlagsMatch = args.has("story_id")
                    || last.pinned == optionalBoolean(args, "pinned", false)
                    && last.noforwards == optionalBoolean(args, "no_forwards", false)
                    && last.expire_date - last.date == optionalInt(
                    args, "period", 86_400, 21_600, 172_800);
            if (captionMatches && privacyMatches && mediaMatches && publishFlagsMatch) {
                return last;
            }
            sleepReadback("Story content readback was interrupted");
        }
        JsonObject details = last == null ? peerJson(peer) : storyJson(account, peer, last);
        details.addProperty("expected_media_kind", expectedMedia);
        details.addProperty("expected_privacy",
                optionalString(args, "privacy", "everyone"));
        throw new McpException("READBACK_FAILED",
                "Story did not match exact stories.getStoriesByID readback",
                true, details);
    }

    private boolean storyPrivacyMatches(
            int account, JsonObject args, TL_stories.StoryItem story) throws McpException {
        String privacy = optionalString(args, "privacy", "everyone");
        boolean baseMatches = "everyone".equals(privacy) && story.isPublic
                || "contacts".equals(privacy) && story.contacts
                || "close_friends".equals(privacy) && story.close_friends
                || "selected".equals(privacy) && story.selected_contacts;
        if (!baseMatches) return false;
        Set<Long> expectedAllow = storyPrivacyPeerIds(account, args, "privacy_peers");
        Set<Long> expectedDisallow = storyPrivacyPeerIds(account, args, "except_peers");
        Set<Long> actualAllow = new HashSet<>();
        Set<Long> actualDisallow = new HashSet<>();
        for (TLRPC.PrivacyRule rule : story.privacy) {
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                actualAllow.addAll(((TLRPC.TL_privacyValueAllowUsers) rule).users);
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                actualDisallow.addAll(((TLRPC.TL_privacyValueDisallowUsers) rule).users);
            }
        }
        return ("selected".equals(privacy) ? expectedAllow.equals(actualAllow)
                : actualAllow.isEmpty())
                && expectedDisallow.equals(actualDisallow);
    }

    private Set<Long> storyPrivacyPeerIds(
            int account, JsonObject args, String key) throws McpException {
        Set<Long> result = new HashSet<>();
        if (!args.has(key)) return result;
        for (JsonElement item : requiredArray(args, key, 0, 100)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                invalid(key + " must contain user peer strings");
            }
            PeerRef peer = resolvePeer(account, item.getAsString());
            if (peer.user == null) invalid(key + " accepts only users");
            result.add(peer.user.id);
        }
        return result;
    }

    private String expectedStoryMediaKind(JsonObject args) throws McpException {
        String type = optionalString(args, "media_type", "auto");
        if (!"auto".equals(type)) return type;
        StagedFile staged = requireStagedFile(
                requiredString(args, "file_ref", 66, 66));
        String mime = staged.metadata.get("mime_type").getAsString()
                .toLowerCase(Locale.ROOT);
        return mime.startsWith("video/") ? "video" : "photo";
    }

    private static String storyMediaKind(TL_stories.StoryItem story) {
        if (story == null || story.media == null) return "none";
        if (story.media.photo != null) return "photo";
        if (story.media.document != null
                && story.media.document.mime_type != null
                && story.media.document.mime_type.toLowerCase(Locale.ROOT)
                .startsWith("video/")) return "video";
        if (story.media.document != null) return "document";
        return "unknown";
    }

    private static JsonObject storyReadbackArguments(
            int account, PeerRef peer, int storyId) {
        JsonObject result = peerReadbackArguments(account, peer);
        result.addProperty("story_id", storyId);
        return result;
    }

    private static JsonObject storyPeerStateJson(
            int account, PeerRef peer, TL_stories.PeerStories stories) {
        JsonObject result = peerJson(peer);
        result.addProperty("max_read_id", stories == null ? 0 : stories.max_read_id);
        result.addProperty("story_count", stories == null ? 0 : stories.stories.size());
        return result;
    }

    private static boolean canManageStory(
            int account, PeerRef peer, TL_stories.StoryItem story, boolean delete) {
        if (peer.dialogId == UserConfig.getInstance(account).getClientUserId()) return true;
        if (peer.user != null) return peer.user.bot && peer.user.bot_can_edit;
        TLRPC.Chat chat = peer.chat;
        if (chat == null) return false;
        if (chat.creator) return true;
        if (chat.admin_rights == null) return false;
        if (delete) {
            return chat.admin_rights.delete_stories
                    || story != null && story.out && chat.admin_rights.post_stories;
        }
        return chat.admin_rights.edit_stories || chat.admin_rights.post_stories;
    }

    private PeerRef waitForPeerStoriesHidden(
            int account, PeerRef peer, boolean expected) throws McpException {
        PeerRef last = peer;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = refreshPeerServer(account, peer);
            boolean actual = last.user != null
                    ? last.user.stories_hidden : last.chat.stories_hidden;
            if (actual == expected) return last;
            sleepReadback("Peer story-hidden readback was interrupted");
        }
        JsonObject details = peerJson(last);
        details.addProperty("expected_stories_hidden", expected);
        throw new McpException("READBACK_FAILED",
                "Peer stories_hidden did not match server readback", true, details);
    }

    private PeerRef refreshPeerServer(int account, PeerRef peer) throws McpException {
        if (peer.user != null) {
            TLRPC.TL_users_getFullUser request = new TLRPC.TL_users_getFullUser();
            request.id = MessagesController.getInstance(account).getInputUser(peer.user);
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.TL_users_userFull)) {
                throw unexpectedResponse(outcome.response);
            }
            TLRPC.TL_users_userFull response =
                    (TLRPC.TL_users_userFull) outcome.response;
            cachePeers(account, response.users, response.chats);
            if (response.full_user != null && response.full_user.user != null) {
                ArrayList<TLRPC.User> users = new ArrayList<>();
                users.add(response.full_user.user);
                cachePeers(account, users, new ArrayList<>());
            }
        } else {
            fetchChatServer(account, peer);
        }
        return localPeer(account, peer.dialogId, "server_refreshed_peer");
    }

    private static void requireVectorResponse(TLObject response, String operation)
            throws McpException {
        if (!(response instanceof Vector)) {
            throw new McpException("UNEXPECTED_RESPONSE",
                    operation + " returned "
                            + (response == null ? "null" : response.getClass().getSimpleName()),
                    true, null);
        }
    }

    private static boolean reactionEquals(TLRPC.Reaction expected, TLRPC.Reaction actual) {
        if (expected instanceof TLRPC.TL_reactionEmpty) {
            return actual == null || actual instanceof TLRPC.TL_reactionEmpty;
        }
        if (expected instanceof TLRPC.TL_reactionEmoji
                && actual instanceof TLRPC.TL_reactionEmoji) {
            return TextUtils.equals(((TLRPC.TL_reactionEmoji) expected).emoticon,
                    ((TLRPC.TL_reactionEmoji) actual).emoticon);
        }
        if (expected instanceof TLRPC.TL_reactionCustomEmoji
                && actual instanceof TLRPC.TL_reactionCustomEmoji) {
            return ((TLRPC.TL_reactionCustomEmoji) expected).document_id
                    == ((TLRPC.TL_reactionCustomEmoji) actual).document_id;
        }
        return expected instanceof TLRPC.TL_reactionPaid
                && actual instanceof TLRPC.TL_reactionPaid;
    }

    private static JsonObject reactionJson(TLRPC.Reaction reaction) {
        JsonObject result = new JsonObject();
        if (reaction == null || reaction instanceof TLRPC.TL_reactionEmpty) {
            result.addProperty("type", "none");
            result.addProperty("value", "");
        } else if (reaction instanceof TLRPC.TL_reactionEmoji) {
            result.addProperty("type", "emoji");
            result.addProperty("value",
                    ((TLRPC.TL_reactionEmoji) reaction).emoticon);
        } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            result.addProperty("type", "custom_emoji");
            result.addProperty("value", Long.toString(
                    ((TLRPC.TL_reactionCustomEmoji) reaction).document_id));
        } else if (reaction instanceof TLRPC.TL_reactionPaid) {
            result.addProperty("type", "paid");
            result.addProperty("value", "paid");
        } else {
            result.addProperty("type", "unknown");
            result.addProperty("value", "");
        }
        return result;
    }

    private static JsonObject chatReactionsJson(TLRPC.ChatReactions reactions) {
        JsonObject result = new JsonObject();
        JsonArray values = new JsonArray();
        if (reactions == null) {
            result.addProperty("mode", "default");
            result.addProperty("allow_custom", false);
        } else if (reactions instanceof TLRPC.TL_chatReactionsNone) {
            result.addProperty("mode", "none");
            result.addProperty("allow_custom", false);
        } else if (reactions instanceof TLRPC.TL_chatReactionsAll) {
            result.addProperty("mode", "all");
            result.addProperty("allow_custom",
                    ((TLRPC.TL_chatReactionsAll) reactions).allow_custom);
        } else if (reactions instanceof TLRPC.TL_chatReactionsSome) {
            result.addProperty("mode", "some");
            result.addProperty("allow_custom", false);
            for (TLRPC.Reaction reaction
                    : ((TLRPC.TL_chatReactionsSome) reactions).reactions) {
                if (reaction instanceof TLRPC.TL_reactionEmoji) {
                    values.add(((TLRPC.TL_reactionEmoji) reaction).emoticon);
                } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                    values.add("custom:" + ((TLRPC.TL_reactionCustomEmoji) reaction).document_id);
                } else if (reaction instanceof TLRPC.TL_reactionPaid) {
                    values.add("paid");
                }
            }
        } else {
            result.addProperty("mode", "unknown");
            result.addProperty("allow_custom", false);
        }
        result.add("reactions", values);
        return result;
    }

    private static boolean chatReactionsSemanticsEqual(
            JsonObject expected, JsonObject actual) {
        if (expected == null || actual == null
                || !expected.has("mode") || !actual.has("mode")
                || !expected.get("mode").getAsString()
                .equals(actual.get("mode").getAsString())) {
            return false;
        }
        String mode = expected.get("mode").getAsString();
        if ("all".equals(mode)) {
            return expected.get("allow_custom").getAsBoolean()
                    == actual.get("allow_custom").getAsBoolean();
        }
        if (!"some".equals(mode)) return true;
        Set<String> expectedValues = new HashSet<>();
        Set<String> actualValues = new HashSet<>();
        for (JsonElement value : expected.getAsJsonArray("reactions")) {
            expectedValues.add(value.getAsString());
        }
        for (JsonElement value : actual.getAsJsonArray("reactions")) {
            actualValues.add(value.getAsString());
        }
        return expectedValues.equals(actualValues);
    }

    private static JsonObject storyJson(
            int account, PeerRef peer, TL_stories.StoryItem story) {
        JsonObject result = peerJson(peer);
        result.addProperty("story_id", story.id);
        result.addProperty("date", story.date);
        result.addProperty("expire_date", story.expire_date);
        result.addProperty("caption", story.caption == null ? "" : story.caption);
        result.add("entities", messageEntitiesJson(story.entities));
        result.addProperty("pinned", story.pinned);
        result.addProperty("public", story.isPublic);
        result.addProperty("close_friends", story.close_friends);
        result.addProperty("contacts", story.contacts);
        result.addProperty("selected_contacts", story.selected_contacts);
        result.addProperty("no_forwards", story.noforwards);
        result.addProperty("edited", story.edited);
        result.addProperty("outgoing", story.out);
        result.addProperty("media_type", story.media == null
                ? "none" : story.media.getClass().getSimpleName());
        result.addProperty("media_kind", storyMediaKind(story));
        if (story.media != null && story.media.photo != null) {
            result.addProperty("photo_id", Long.toString(story.media.photo.id));
        }
        if (story.media != null && story.media.document != null) {
            result.addProperty("document_id", Long.toString(story.media.document.id));
            result.addProperty("mime_type", story.media.document.mime_type == null
                    ? "" : story.media.document.mime_type);
            result.addProperty("size", story.media.document.size);
            result.addProperty("file_name",
                    FileLoader.getDocumentFileName(story.media.document));
        }
        if (story.views != null) {
            result.addProperty("views_count", story.views.views_count);
            result.addProperty("forwards_count", story.views.forwards_count);
            result.addProperty("reactions_count", story.views.reactions_count);
            result.addProperty("has_viewers", story.views.has_viewers);
        }
        result.add("sent_reaction", reactionJson(story.sent_reaction));
        result.add("privacy_rules", storyPrivacyJson(story.privacy));
        JsonArray albums = new JsonArray();
        if (story.albums != null) {
            for (Integer album : story.albums) albums.add(album);
        }
        result.add("album_ids", albums);
        result.addProperty("source", "telegram_server_story");
        return result;
    }

    private static JsonArray storyPrivacyJson(ArrayList<TLRPC.PrivacyRule> rules) {
        JsonArray result = new JsonArray();
        if (rules == null) return result;
        for (TLRPC.PrivacyRule rule : rules) {
            JsonObject item = new JsonObject();
            item.addProperty("type", rule.getClass().getSimpleName()
                    .replace("TL_privacyValue", "")
                    .toLowerCase(Locale.ROOT));
            JsonArray users = new JsonArray();
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                for (Long id : ((TLRPC.TL_privacyValueAllowUsers) rule).users) {
                    users.add(Long.toString(id));
                }
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                for (Long id : ((TLRPC.TL_privacyValueDisallowUsers) rule).users) {
                    users.add(Long.toString(id));
                }
            }
            if (users.size() > 0) item.add("user_ids", users);
            result.add(item);
        }
        return result;
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
            if (message == null) continue;
            nextOffset = message.id;
            if (message instanceof TLRPC.TL_messageEmpty
                    || isDeletedMessageTombstone(message)) {
                continue;
            }
            items.add(messageJson(account, message));
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
        item.add("entities", messageEntitiesJson(message.entities));
        item.addProperty("media_type", message.media == null ? "none" : message.media.getClass().getSimpleName());
        item.addProperty("reply_to_message_id",
                message.reply_to == null ? 0 : message.reply_to.reply_to_msg_id);
        item.addProperty("topic_id",
                MessageObject.getTopicId(account, message, true));
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

    private static TLRPC.MessagesFilter messageMediaFilter(String name)
            throws McpException {
        switch (name) {
            case "all": return new TLRPC.TL_inputMessagesFilterEmpty();
            case "photos": return new TLRPC.TL_inputMessagesFilterPhotos();
            case "videos": return new TLRPC.TL_inputMessagesFilterVideo();
            case "photo_video": return new TLRPC.TL_inputMessagesFilterPhotoVideo();
            case "photo_video_documents":
                return new TLRPC.TL_inputMessagesFilterPhotoVideoDocuments();
            case "documents": return new TLRPC.TL_inputMessagesFilterDocument();
            case "music": return new TLRPC.TL_inputMessagesFilterMusic();
            case "voice": return new TLRPC.TL_inputMessagesFilterVoice();
            case "round_voice": return new TLRPC.TL_inputMessagesFilterRoundVoice();
            case "round_video": return new TLRPC.TL_inputMessagesFilterRoundVideo();
            case "gifs": return new TLRPC.TL_inputMessagesFilterGif();
            case "links": return new TLRPC.TL_inputMessagesFilterUrl();
            case "pinned": return new TLRPC.TL_inputMessagesFilterPinned();
            case "mentions": return new TLRPC.TL_inputMessagesFilterMyMentions();
            default:
                invalid("Unknown media filter: " + name);
                return null;
        }
    }

    private static ArrayList<Integer> pollChosenIndices(TLRPC.Message message) {
        ArrayList<Integer> result = new ArrayList<>();
        if (message == null || !(message.media instanceof TLRPC.TL_messageMediaPoll)) {
            return result;
        }
        TLRPC.TL_messageMediaPoll media =
                (TLRPC.TL_messageMediaPoll) message.media;
        if (media.poll == null || media.results == null) return result;
        for (int index = 0; index < media.poll.answers.size(); index++) {
            byte[] option = media.poll.answers.get(index).option;
            for (TLRPC.PollAnswerVoters voters : media.results.results) {
                if (voters.chosen && java.util.Arrays.equals(option, voters.option)) {
                    result.add(index);
                    break;
                }
            }
        }
        return result;
    }

    private static JsonObject pollMediaJson(TLRPC.TL_messageMediaPoll media) {
        JsonObject result = pollJson(media == null ? null : media.poll);
        JsonArray answerResults = new JsonArray();
        if (media != null && media.poll != null && media.results != null) {
            for (int index = 0; index < media.poll.answers.size(); index++) {
                TLRPC.PollAnswer answer = media.poll.answers.get(index);
                JsonObject item = new JsonObject();
                item.addProperty("index", index);
                item.addProperty("text", answer.text == null ? "" : answer.text.text);
                item.addProperty("chosen", false);
                item.addProperty("correct", false);
                item.addProperty("voters", 0);
                for (TLRPC.PollAnswerVoters voters : media.results.results) {
                    if (java.util.Arrays.equals(answer.option, voters.option)) {
                        item.addProperty("chosen", voters.chosen);
                        item.addProperty("correct", voters.correct);
                        item.addProperty("voters", voters.voters);
                        break;
                    }
                }
                answerResults.add(item);
            }
            result.addProperty("total_voters", media.results.total_voters);
            result.addProperty("can_view_stats", media.results.can_view_stats);
            result.addProperty("solution", media.results.solution == null
                    ? "" : media.results.solution);
        }
        result.add("answer_results", answerResults);
        return result;
    }

    private ArrayList<TLRPC.Document> fetchSavedDocuments(
            int account, boolean stickers) throws McpException {
        if (stickers) {
            TLRPC.TL_messages_getFavedStickers request =
                    new TLRPC.TL_messages_getFavedStickers();
            request.hash = 0;
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.TL_messages_favedStickers)) {
                throw unexpectedResponse(outcome.response);
            }
            return new ArrayList<>(
                    ((TLRPC.TL_messages_favedStickers) outcome.response).stickers);
        }
        TLRPC.TL_messages_getSavedGifs request =
                new TLRPC.TL_messages_getSavedGifs();
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_savedGifs)) {
            throw unexpectedResponse(outcome.response);
        }
        return new ArrayList<>(
                ((TLRPC.TL_messages_savedGifs) outcome.response).gifs);
    }

    private static boolean containsDocument(
            ArrayList<TLRPC.Document> documents, long documentId) {
        for (TLRPC.Document document : documents) {
            if (document != null && document.id == documentId) return true;
        }
        return false;
    }

    private static JsonObject documentJson(TLRPC.Document document) {
        JsonObject result = new JsonObject();
        result.addProperty("document_id", Long.toString(document.id));
        result.addProperty("date", document.date);
        result.addProperty("mime_type", document.mime_type == null
                ? "" : document.mime_type);
        result.addProperty("size", document.size);
        result.addProperty("file_name", FileLoader.getDocumentFileName(document));
        result.addProperty("sticker", MessageObject.isStickerDocument(document));
        result.addProperty("animated_sticker",
                MessageObject.isAnimatedStickerDocument(document, true));
        result.addProperty("gif", MessageObject.isGifDocument(document));
        JsonArray stickerEmojis = new JsonArray();
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeSticker) {
                String alt = attribute.alt;
                if (!TextUtils.isEmpty(alt)) stickerEmojis.add(alt);
            }
        }
        result.add("sticker_emojis", stickerEmojis);
        return result;
    }

    private static JsonObject botInlineResultJson(TLRPC.BotInlineResult result) {
        JsonObject data = new JsonObject();
        data.addProperty("result_id", result.id == null ? "" : result.id);
        data.addProperty("type", result.type == null ? "" : result.type);
        data.addProperty("title", result.title == null ? "" : result.title);
        data.addProperty("description",
                result.description == null ? "" : result.description);
        data.addProperty("url", result.url == null ? "" : result.url);
        data.addProperty("send_message_type", result.send_message == null
                ? "" : result.send_message.getClass().getSimpleName());
        if (result.photo != null) data.add("photo", profilePhotoJson(result.photo));
        if (result.document != null) data.add("document", documentJson(result.document));
        return data;
    }

    private FormattedText parseFormattedText(int account, String value, String parseMode)
            throws McpException {
        if ("plain".equals(parseMode)) {
            return new FormattedText(value, new ArrayList<>());
        }
        if (!"telegram_markdown".equals(parseMode)) {
            invalid("parse_mode must be plain or telegram_markdown");
        }
        return uiCall(() -> {
            CharSequence[] text = new CharSequence[]{value};
            ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(account)
                    .getEntities(text, true);
            String normalized = text[0] == null ? "" : text[0].toString();
            if (normalized.isEmpty()) {
                throw new McpException("INVALID_ARGUMENT",
                        "Formatted message becomes empty after parsing", false, null);
            }
            if (normalized.length() > 4096) {
                throw new McpException("INVALID_ARGUMENT",
                        "Formatted message exceeds 4096 characters", false, null);
            }
            return new FormattedText(normalized,
                    entities == null ? new ArrayList<>() : entities);
        });
    }

    private static JsonArray messageEntitiesJson(ArrayList<TLRPC.MessageEntity> entities) {
        JsonArray result = new JsonArray();
        if (entities == null) return result;
        for (TLRPC.MessageEntity entity : entities) {
            if (entity == null) continue;
            JsonObject item = new JsonObject();
            String type = entity.getClass().getSimpleName()
                    .replace("TL_messageEntity", "")
                    .replace("TL_inputMessageEntity", "")
                    .toLowerCase(Locale.ROOT);
            item.addProperty("type", type);
            item.addProperty("offset", entity.offset);
            item.addProperty("length", entity.length);
            if (entity.url != null) item.addProperty("url", entity.url);
            if (entity.language != null) item.addProperty("language", entity.language);
            if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                item.addProperty("document_id", Long.toString(
                        ((TLRPC.TL_messageEntityCustomEmoji) entity).document_id));
            }
            result.add(item);
        }
        return result;
    }

    private TLRPC.TL_textWithEntities textWithEntities(int account, String value)
            throws McpException {
        TLRPC.TL_textWithEntities result = new TLRPC.TL_textWithEntities();
        result.text = value;
        result.entities = new ArrayList<>();
        return result;
    }

    private static boolean pollMessageMatches(
            TLRPC.Message message,
            String question,
            ArrayList<String> answers,
            boolean multipleChoice,
            boolean quiz,
            boolean anonymous) {
        if (message == null || !(message.media instanceof TLRPC.TL_messageMediaPoll)) {
            return false;
        }
        TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) message.media).poll;
        if (poll == null || poll.question == null
                || !question.equals(poll.question.text)
                || poll.answers.size() != answers.size()
                || poll.multiple_choice != multipleChoice
                || poll.quiz != quiz
                || poll.public_voters == anonymous) return false;
        for (int index = 0; index < answers.size(); index++) {
            TLRPC.PollAnswer answer = poll.answers.get(index);
            if (answer == null || answer.text == null
                    || !answers.get(index).equals(answer.text.text)) return false;
        }
        return true;
    }

    private static JsonObject pollJson(TLRPC.Poll poll) {
        JsonObject result = new JsonObject();
        if (poll == null) return result;
        result.addProperty("poll_id", Long.toString(poll.id));
        result.addProperty("question",
                poll.question == null || poll.question.text == null
                        ? "" : poll.question.text);
        result.addProperty("closed", poll.closed);
        result.addProperty("anonymous", !poll.public_voters);
        result.addProperty("multiple_choice", poll.multiple_choice);
        result.addProperty("quiz", poll.quiz);
        result.addProperty("close_period", poll.close_period);
        result.addProperty("close_date", poll.close_date);
        JsonArray answers = new JsonArray();
        for (TLRPC.PollAnswer answer : poll.answers) {
            JsonObject item = new JsonObject();
            item.addProperty("text", answer.text == null || answer.text.text == null
                    ? "" : answer.text.text);
            item.addProperty("option_base64", Base64.encodeToString(
                    answer.option == null ? new byte[0] : answer.option,
                    Base64.NO_WRAP));
            answers.add(item);
        }
        result.add("answers", answers);
        return result;
    }

    private static JsonArray keyboardButtonsJson(TLRPC.ReplyMarkup markup) {
        JsonArray result = new JsonArray();
        if (markup == null || markup.rows == null) return result;
        for (int row = 0; row < markup.rows.size(); row++) {
            TLRPC.TL_keyboardButtonRow value = markup.rows.get(row);
            if (value == null || value.buttons == null) continue;
            for (int column = 0; column < value.buttons.size(); column++) {
                result.add(keyboardButtonJson(value.buttons.get(column), row, column));
            }
        }
        return result;
    }

    private static int countKeyboardButtons(TLRPC.ReplyMarkup markup) {
        int count = 0;
        if (markup == null || markup.rows == null) return 0;
        for (TLRPC.TL_keyboardButtonRow row : markup.rows) {
            if (row != null && row.buttons != null) count += row.buttons.size();
        }
        return count;
    }

    private static TLRPC.KeyboardButton keyboardButtonAt(
            TLRPC.ReplyMarkup markup, int row, int column) throws McpException {
        if (markup == null || markup.rows == null || row >= markup.rows.size()
                || markup.rows.get(row) == null
                || markup.rows.get(row).buttons == null
                || column >= markup.rows.get(row).buttons.size()) {
            JsonObject details = new JsonObject();
            details.addProperty("row", row);
            details.addProperty("column", column);
            details.addProperty("button_count", countKeyboardButtons(markup));
            throw new McpException("BUTTON_NOT_FOUND",
                    "The requested keyboard button does not exist in the exact server message",
                    false, details);
        }
        return markup.rows.get(row).buttons.get(column);
    }

    private static JsonObject keyboardButtonJson(
            TLRPC.KeyboardButton button, int row, int column) {
        JsonObject item = new JsonObject();
        item.addProperty("row", row);
        item.addProperty("column", column);
        item.addProperty("text", button == null || button.text == null ? "" : button.text);
        item.addProperty("type", keyboardButtonType(button));
        item.addProperty("requires_password", button != null && button.requires_password);
        item.addProperty("requires_human_handoff", keyboardButtonRequiresHuman(button));
        if (button != null && button.url != null) item.addProperty("url", button.url);
        if (button != null && button.query != null) item.addProperty("query", button.query);
        if (button instanceof TLRPC.TL_keyboardButtonCopy) {
            item.addProperty("copy_text", ((TLRPC.TL_keyboardButtonCopy) button).copy_text);
        }
        if (button != null && button.user_id != 0) {
            item.addProperty("user_id", Long.toString(button.user_id));
        } else if (button != null && button.inputUser != null
                && button.inputUser.user_id != 0) {
            item.addProperty("user_id", Long.toString(button.inputUser.user_id));
        }
        return item;
    }

    private static String keyboardButtonType(TLRPC.KeyboardButton button) {
        if (button instanceof TLRPC.TL_keyboardButtonCallback) return "callback";
        if (button instanceof TLRPC.TL_keyboardButtonGame) return "game";
        if (button instanceof TLRPC.TL_keyboardButtonUrlAuth) return "url_auth";
        if (button instanceof TLRPC.TL_keyboardButtonSimpleWebView) return "simple_web_view";
        if (button instanceof TLRPC.TL_keyboardButtonWebView) return "web_view";
        if (button instanceof TLRPC.TL_keyboardButtonUrl) return "url";
        if (button instanceof TLRPC.TL_keyboardButtonBuy) return "buy";
        if (button instanceof TLRPC.TL_keyboardButtonRequestPhone) return "request_phone";
        if (button instanceof TLRPC.TL_keyboardButtonRequestGeoLocation) return "request_geo";
        if (button instanceof TLRPC.TL_keyboardButtonRequestPoll) return "request_poll";
        if (button instanceof TLRPC.TL_keyboardButtonRequestPeer) return "request_peer";
        if (button instanceof TLRPC.TL_keyboardButtonSwitchInline) return "switch_inline";
        if (button instanceof TLRPC.TL_keyboardButtonUserProfile) return "user_profile";
        if (button instanceof TLRPC.TL_keyboardButtonCopy) return "copy";
        if (button instanceof TLRPC.TL_keyboardButton) return "text";
        return button == null ? "unknown" : button.getClass().getSimpleName();
    }

    private static boolean keyboardButtonRequiresHuman(TLRPC.KeyboardButton button) {
        return button instanceof TLRPC.TL_keyboardButtonUrlAuth
                || button instanceof TLRPC.TL_keyboardButtonSimpleWebView
                || button instanceof TLRPC.TL_keyboardButtonWebView
                || button instanceof TLRPC.TL_keyboardButtonUrl
                || button instanceof TLRPC.TL_keyboardButtonBuy
                || button instanceof TLRPC.TL_keyboardButtonRequestPhone
                || button instanceof TLRPC.TL_keyboardButtonRequestGeoLocation
                || button instanceof TLRPC.TL_keyboardButtonRequestPoll
                || button instanceof TLRPC.TL_keyboardButtonRequestPeer
                || button instanceof TLRPC.TL_keyboardButtonSwitchInline
                || button instanceof TLRPC.TL_keyboardButtonUserProfile
                || button != null && button.requires_password;
    }

    private static JsonObject botButtonReadbackArguments(
            int account, PeerRef peer, int messageId) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("account", account);
        arguments.addProperty("peer", canonicalPeer(
                MessagesController.getInstance(account), peer.dialogId));
        arguments.addProperty("message_id", messageId);
        return arguments;
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
            } else if (update instanceof TL_update.TL_updateNewScheduledMessage) {
                ids.add(((TL_update.TL_updateNewScheduledMessage) update).message.id);
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

    private TLRPC.Chat requireCreatedChat(TLObject response, String expectedTitle)
            throws McpException {
        TLRPC.Updates updates = null;
        if (response instanceof TLRPC.Updates) {
            updates = (TLRPC.Updates) response;
        } else if (response instanceof TLRPC.TL_messages_invitedUsers) {
            updates = ((TLRPC.TL_messages_invitedUsers) response).updates;
        }
        if (updates != null) {
            for (TLRPC.Chat chat : updates.chats) {
                if (chat != null && expectedTitle.equals(chat.title)) {
                    return chat;
                }
            }
        }
        JsonObject details = new JsonObject();
        details.addProperty("expected_title", expectedTitle);
        details.addProperty("response_type",
                response == null ? "null" : response.getClass().getSimpleName());
        throw new McpException("MISSING_CREATED_OBJECT",
                "Telegram did not return the created chat object", false, details);
    }

    private JsonObject waitForChatField(
            int account, PeerRef peer, String field, String expected) throws McpException {
        JsonObject args = peerReadbackArguments(account, peer);
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = chatGet(args).getAsJsonObject("data");
            if (last.has(field) && expected.equals(last.get(field).getAsString())) {
                return last;
            }
            sleepReadback("Chat-field readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Chat field did not match chat.get", true, last);
    }

    private JsonObject waitForChatIntField(
            int account, PeerRef peer, String field, int expected) throws McpException {
        JsonObject args = peerReadbackArguments(account, peer);
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = chatGet(args).getAsJsonObject("data");
            if (last.has(field) && last.get(field).getAsInt() == expected) return last;
            sleepReadback("Chat integer-field readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Chat integer field did not match chat.get", true, last);
    }

    private JsonObject waitForChatBooleanField(
            int account, PeerRef peer, String field, boolean expected) throws McpException {
        JsonObject args = peerReadbackArguments(account, peer);
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = chatGet(args).getAsJsonObject("data");
            if (last.has(field) && last.get(field).getAsBoolean() == expected) return last;
            sleepReadback("Chat boolean-field readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Chat boolean field did not match chat.get", true, last);
    }

    private JsonObject waitForChatBooleanFields(
            int account,
            PeerRef peer,
            String firstField,
            boolean firstExpected,
            String secondField,
            boolean secondExpected) throws McpException {
        JsonObject args = peerReadbackArguments(account, peer);
        JsonObject last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = chatGet(args).getAsJsonObject("data");
            if (last.has(firstField) && last.has(secondField)
                    && last.get(firstField).getAsBoolean() == firstExpected
                    && last.get(secondField).getAsBoolean() == secondExpected) {
                return last;
            }
            sleepReadback("Chat boolean-fields readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Chat boolean fields did not match chat.get", true, last);
    }

    private ChatMemberState fetchChatMemberState(
            int account, PeerRef chat, PeerRef member) throws McpException {
        if (chat.chat == null || member.user == null) {
            invalid("chat member readback requires a chat and user");
        }
        if (ChatObject.isChannel(chat.chat)) {
            TLRPC.TL_channels_getParticipant request =
                    new TLRPC.TL_channels_getParticipant();
            request.channel = MessagesController.getInputChannel(chat.chat);
            request.participant = member.inputPeer;
            RequestOutcome outcome;
            try {
                outcome = request(account, request);
            } catch (McpException error) {
                if (error.getMessage() != null
                        && (error.getMessage().contains("USER_NOT_PARTICIPANT")
                        || error.getMessage().contains("PARTICIPANT_ID_INVALID"))) {
                    JsonObject data = peerJson(member);
                    data.addProperty("present", false);
                    data.addProperty("banned", false);
                    data.addProperty("role", "absent");
                    return new ChatMemberState(false, false, data,
                            null, null, "channels.getParticipant:not_found");
                }
                throw error;
            }
            if (!(outcome.response instanceof TLRPC.TL_channels_channelParticipant)) {
                throw unexpectedResponse(outcome.response);
            }
            TLRPC.TL_channels_channelParticipant response =
                    (TLRPC.TL_channels_channelParticipant) outcome.response;
            cachePeers(account, response.users, response.chats);
            TLRPC.ChannelParticipant participant = response.participant;
            boolean left = participant instanceof TLRPC.TL_channelParticipantLeft
                    || participant instanceof TLRPC.TL_channelParticipantBanned
                    && ((TLRPC.TL_channelParticipantBanned) participant).left;
            boolean banned = participant instanceof TLRPC.TL_channelParticipantBanned
                    && ((TLRPC.TL_channelParticipantBanned) participant).banned_rights != null;
            JsonObject data = peerJson(member);
            data.addProperty("present", !left);
            data.addProperty("banned", banned);
            data.addProperty("role", left ? "absent" : participantRole(participant));
            data.addProperty("date", participant == null ? 0 : participant.date);
            data.addProperty("rank", participant == null || participant.rank == null
                    ? "" : participant.rank);
            data.add("admin_rights", adminRightsJson(
                    participant == null ? null : participant.admin_rights));
            data.add("permissions", bannedRightsJson(
                    participant == null ? null : participant.banned_rights));
            return new ChatMemberState(!left, banned, data,
                    participant, null, "channels.getParticipant");
        }
        TLRPC.TL_messages_getFullChat request = new TLRPC.TL_messages_getFullChat();
        request.chat_id = chat.chat.id;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_chatFull)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_chatFull response =
                (TLRPC.TL_messages_chatFull) outcome.response;
        cachePeers(account, response.users, response.chats);
        TLRPC.ChatParticipant match = null;
        if (response.full_chat.participants != null) {
            for (TLRPC.ChatParticipant participant :
                    response.full_chat.participants.participants) {
                if (participant.user_id == member.user.id) {
                    match = participant;
                    break;
                }
            }
        }
        JsonObject data = peerJson(member);
        data.addProperty("present", match != null);
        data.addProperty("banned", false);
        data.addProperty("role", match == null ? "absent" : participantRole(match));
        data.addProperty("date", match == null ? 0 : match.date);
        data.addProperty("rank", match == null || match.rank == null ? "" : match.rank);
        return new ChatMemberState(match != null, false, data,
                null, match, "messages.getFullChat");
    }

    private ChatMemberState waitForChatMemberState(
            int account,
            PeerRef chat,
            PeerRef member,
            boolean present,
            Boolean banned,
            String role) throws McpException {
        ChatMemberState last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchChatMemberState(account, chat, member);
            if (last.present == present
                    && (banned == null || last.banned == banned)
                    && (role == null || role.equals(last.data.get("role").getAsString()))) {
                return last;
            }
            sleepReadback("Chat-member state readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Chat member did not reach the requested server state", true,
                last == null ? peerJson(member) : last.data);
    }

    private ChatMemberState waitForChatMemberRights(
            int account,
            PeerRef chat,
            PeerRef member,
            JsonObject requestedAllowed,
            int requestedUntilDate) throws McpException {
        ChatMemberState last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchChatMemberState(account, chat, member);
            TLRPC.TL_chatBannedRights actual = last.channelParticipant == null
                    ? null : last.channelParticipant.banned_rights;
            if (requestedAllowedMatches(requestedAllowed, actual)
                    && normalizeBannedUntilDate(actual == null ? 0 : actual.until_date)
                    == normalizeBannedUntilDate(requestedUntilDate)) {
                return last;
            }
            sleepReadback("Chat-member permissions readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Member permissions did not match channels.getParticipant", true,
                last == null ? peerJson(member) : last.data);
    }

    private TLRPC.Chat waitForDefaultPermissions(
            int account, PeerRef chat, JsonObject requestedAllowed)
            throws McpException {
        TLRPC.Chat last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchChatServer(account, chat);
            if (requestedAllowedMatches(
                    requestedAllowed, last.default_banned_rights)) return last;
            sleepReadback("Default-permissions readback was interrupted");
        }
        JsonObject details = peerJson(chat);
        details.add("requested_allowed", requestedAllowed.deepCopy());
        details.add("actual_permissions", bannedRightsJson(
                last == null ? null : last.default_banned_rights));
        throw new McpException("READBACK_FAILED",
                "Default permissions did not match server chat readback", true, details);
    }

    private static JsonObject memberReadbackArguments(
            int account, PeerRef chat, PeerRef member) {
        JsonObject args = peerReadbackArguments(account, chat);
        args.addProperty("member", "user:" + member.user.id);
        return args;
    }

    private static TLRPC.TL_chatAdminRights adminRightsFromJson(JsonObject values)
            throws McpException {
        Set<String> allowed = new HashSet<>();
        Collections.addAll(allowed, "change_info", "post_messages", "edit_messages",
                "delete_messages", "ban_users", "invite_users", "pin_messages",
                "add_admins", "anonymous", "manage_call", "manage_topics",
                "post_stories", "edit_stories", "delete_stories",
                "manage_direct_messages", "manage_ranks", "manage_linked_peers");
        TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            if (!allowed.contains(entry.getKey()) || !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isBoolean()) {
                invalid("Unknown or non-boolean administrator right: " + entry.getKey());
            }
            boolean value = entry.getValue().getAsBoolean();
            switch (entry.getKey()) {
                case "change_info": rights.change_info = value; break;
                case "post_messages": rights.post_messages = value; break;
                case "edit_messages": rights.edit_messages = value; break;
                case "delete_messages": rights.delete_messages = value; break;
                case "ban_users": rights.ban_users = value; break;
                case "invite_users": rights.invite_users = value; break;
                case "pin_messages": rights.pin_messages = value; break;
                case "add_admins": rights.add_admins = value; break;
                case "anonymous": rights.anonymous = value; break;
                case "manage_call": rights.manage_call = value; break;
                case "manage_topics": rights.manage_topics = value; break;
                case "post_stories": rights.post_stories = value; break;
                case "edit_stories": rights.edit_stories = value; break;
                case "delete_stories": rights.delete_stories = value; break;
                case "manage_direct_messages": rights.manage_direct_messages = value; break;
                case "manage_ranks": rights.manage_ranks = value; break;
                case "manage_linked_peers": rights.manage_linked_peers = value; break;
            }
        }
        return rights;
    }

    private static JsonObject adminRightsJson(TLRPC.TL_chatAdminRights rights) {
        JsonObject data = new JsonObject();
        data.addProperty("change_info", rights != null && rights.change_info);
        data.addProperty("post_messages", rights != null && rights.post_messages);
        data.addProperty("edit_messages", rights != null && rights.edit_messages);
        data.addProperty("delete_messages", rights != null && rights.delete_messages);
        data.addProperty("ban_users", rights != null && rights.ban_users);
        data.addProperty("invite_users", rights != null && rights.invite_users);
        data.addProperty("pin_messages", rights != null && rights.pin_messages);
        data.addProperty("add_admins", rights != null && rights.add_admins);
        data.addProperty("anonymous", rights != null && rights.anonymous);
        data.addProperty("manage_call", rights != null && rights.manage_call);
        data.addProperty("manage_topics", rights != null && rights.manage_topics);
        data.addProperty("post_stories", rights != null && rights.post_stories);
        data.addProperty("edit_stories", rights != null && rights.edit_stories);
        data.addProperty("delete_stories", rights != null && rights.delete_stories);
        data.addProperty("manage_direct_messages",
                rights != null && rights.manage_direct_messages);
        data.addProperty("manage_ranks", rights != null && rights.manage_ranks);
        data.addProperty("manage_linked_peers",
                rights != null && rights.manage_linked_peers);
        return data;
    }

    private static TLRPC.TL_chatBannedRights bannedRightsFromAllowed(
            JsonObject values, TLRPC.TL_chatBannedRights current, int untilDate)
            throws McpException {
        if (values.size() == 0) invalid("allowed must not be empty");
        TLRPC.TL_chatBannedRights rights = current == null
                ? new TLRPC.TL_chatBannedRights()
                : TLRPC.TL_chatBannedRights.clone(current);
        rights.until_date = untilDate;
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isBoolean()) {
                invalid("Permission " + entry.getKey() + " must be boolean");
            }
            boolean banned = !entry.getValue().getAsBoolean();
            switch (entry.getKey()) {
                case "view_messages": rights.view_messages = banned; break;
                case "send_messages": rights.send_messages = banned; break;
                case "send_media": rights.send_media = banned; break;
                case "send_stickers": rights.send_stickers = banned; break;
                case "send_gifs": rights.send_gifs = banned; break;
                case "send_games": rights.send_games = banned; break;
                case "send_inline": rights.send_inline = banned; break;
                case "embed_links": rights.embed_links = banned; break;
                case "send_polls": rights.send_polls = banned; break;
                case "change_info": rights.change_info = banned; break;
                case "invite_users": rights.invite_users = banned; break;
                case "pin_messages": rights.pin_messages = banned; break;
                case "manage_topics": rights.manage_topics = banned; break;
                case "send_photos": rights.send_photos = banned; break;
                case "send_videos": rights.send_videos = banned; break;
                case "send_roundvideos": rights.send_roundvideos = banned; break;
                case "send_audios": rights.send_audios = banned; break;
                case "send_voices": rights.send_voices = banned; break;
                case "send_docs": rights.send_docs = banned; break;
                case "send_plain": rights.send_plain = banned; break;
                case "send_reactions": rights.send_reactions = banned; break;
                case "manage_linked_peers": rights.manage_linked_peers = banned; break;
                default: invalid("Unknown permission: " + entry.getKey());
            }
        }
        return rights;
    }

    private static JsonObject bannedRightsJson(TLRPC.TL_chatBannedRights rights) {
        JsonObject allowed = new JsonObject();
        allowed.addProperty("view_messages", rights == null || !rights.view_messages);
        allowed.addProperty("send_messages", rights == null || !rights.send_messages);
        allowed.addProperty("send_media", rights == null || !rights.send_media);
        allowed.addProperty("send_stickers", rights == null || !rights.send_stickers);
        allowed.addProperty("send_gifs", rights == null || !rights.send_gifs);
        allowed.addProperty("send_games", rights == null || !rights.send_games);
        allowed.addProperty("send_inline", rights == null || !rights.send_inline);
        allowed.addProperty("embed_links", rights == null || !rights.embed_links);
        allowed.addProperty("send_polls", rights == null || !rights.send_polls);
        allowed.addProperty("change_info", rights == null || !rights.change_info);
        allowed.addProperty("invite_users", rights == null || !rights.invite_users);
        allowed.addProperty("pin_messages", rights == null || !rights.pin_messages);
        allowed.addProperty("manage_topics", rights == null || !rights.manage_topics);
        allowed.addProperty("send_photos", rights == null || !rights.send_photos);
        allowed.addProperty("send_videos", rights == null || !rights.send_videos);
        allowed.addProperty("send_roundvideos", rights == null || !rights.send_roundvideos);
        allowed.addProperty("send_audios", rights == null || !rights.send_audios);
        allowed.addProperty("send_voices", rights == null || !rights.send_voices);
        allowed.addProperty("send_docs", rights == null || !rights.send_docs);
        allowed.addProperty("send_plain", rights == null || !rights.send_plain);
        allowed.addProperty("send_reactions", rights == null || !rights.send_reactions);
        allowed.addProperty("manage_linked_peers",
                rights == null || !rights.manage_linked_peers);
        JsonObject data = new JsonObject();
        data.add("allowed", allowed);
        data.addProperty("until_date", normalizeBannedUntilDate(
                rights == null ? 0 : rights.until_date));
        return data;
    }

    private static boolean requestedAllowedMatches(
            JsonObject requested, TLRPC.TL_chatBannedRights actual) {
        JsonObject actualAllowed = bannedRightsJson(actual)
                .getAsJsonObject("allowed");
        for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
            if (!actualAllowed.has(entry.getKey())
                    || actualAllowed.get(entry.getKey()).getAsBoolean()
                    != entry.getValue().getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    private static int normalizeBannedUntilDate(int value) {
        return value == Integer.MAX_VALUE ? 0 : value;
    }

    private TLRPC.TL_chatInviteExported fetchExportedInvite(
            int account, PeerRef chat, String link) throws McpException {
        TLRPC.TL_messages_getExportedChatInvite request =
                new TLRPC.TL_messages_getExportedChatInvite();
        request.peer = chat.inputPeer;
        request.link = link;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_ExportedChatInvite)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_ExportedChatInvite response =
                (TLRPC.messages_ExportedChatInvite) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        if (!(response.invite instanceof TLRPC.TL_chatInviteExported)) {
            throw unexpectedResponse(response.invite);
        }
        return (TLRPC.TL_chatInviteExported) response.invite;
    }

    private TLRPC.TL_chatInviteExported waitForInviteRevoked(
            int account, PeerRef chat, String link) throws McpException {
        TLRPC.TL_chatInviteExported last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchExportedInvite(account, chat, link);
            if (last.revoked) return last;
            sleepReadback("Invite-link revocation readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Invite link remains active in messages.getExportedChatInvite", true,
                last == null ? peerJson(chat) : inviteJson(last));
    }

    private static JsonObject inviteJson(TLRPC.TL_chatInviteExported invite) {
        JsonObject data = new JsonObject();
        data.addProperty("link", invite.link == null ? "" : invite.link);
        data.addProperty("revoked", invite.revoked);
        data.addProperty("permanent", invite.permanent);
        data.addProperty("request_needed", invite.request_needed);
        data.addProperty("admin", "user:" + invite.admin_id);
        data.addProperty("date", invite.date);
        data.addProperty("start_date", invite.start_date);
        data.addProperty("expire_date", invite.expire_date);
        data.addProperty("usage_limit", invite.usage_limit);
        data.addProperty("usage", invite.usage);
        data.addProperty("requested", invite.requested);
        data.addProperty("title", invite.title == null ? "" : invite.title);
        return data;
    }

    private boolean joinRequestExists(int account, PeerRef chat, long userId)
            throws McpException {
        int offsetDate = 0;
        TLRPC.InputUser offsetUser = new TLRPC.TL_inputUserEmpty();
        for (int page = 0; page < 50; page++) {
            TLRPC.TL_messages_getChatInviteImporters request =
                    new TLRPC.TL_messages_getChatInviteImporters();
            request.requested = true;
            request.peer = chat.inputPeer;
            request.q = "";
            request.offset_date = offsetDate;
            request.offset_user = offsetUser;
            request.limit = MAX_LIMIT;
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.TL_messages_chatInviteImporters)) {
                throw unexpectedResponse(outcome.response);
            }
            TLRPC.TL_messages_chatInviteImporters response =
                    (TLRPC.TL_messages_chatInviteImporters) outcome.response;
            cachePeers(account, response.users, new ArrayList<>());
            if (response.importers.isEmpty()) return false;
            for (TLRPC.TL_chatInviteImporter importer : response.importers) {
                if (importer.user_id == userId) return true;
            }
            if (response.importers.size() < MAX_LIMIT) return false;
            TLRPC.TL_chatInviteImporter last = response.importers.get(
                    response.importers.size() - 1);
            offsetDate = last.date;
            TLRPC.User user = MessagesController.getInstance(account).getUser(last.user_id);
            if (user == null) return false;
            offsetUser = MessagesController.getInstance(account).getInputUser(user);
        }
        throw new McpException("READBACK_INCOMPLETE",
                "Join-request pagination exceeded the safety limit", true, peerJson(chat));
    }

    private void waitForJoinRequestAbsent(int account, PeerRef chat, long userId)
            throws McpException {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (!joinRequestExists(account, chat, userId)) return;
            sleepReadback("Join-request decision readback was interrupted");
        }
        JsonObject details = peerJson(chat);
        details.addProperty("user", "user:" + userId);
        throw new McpException("READBACK_FAILED",
                "Join request remains pending after decision", true, details);
    }

    private TLRPC.Chat fetchChatServer(int account, PeerRef peer) throws McpException {
        if (peer.chat == null) invalid("peer must be a group or channel");
        TLObject request;
        if (ChatObject.isChannel(peer.chat)) {
            TLRPC.TL_channels_getChannels value = new TLRPC.TL_channels_getChannels();
            value.id.add(MessagesController.getInputChannel(peer.chat));
            request = value;
        } else {
            TLRPC.TL_messages_getChats value = new TLRPC.TL_messages_getChats();
            value.id.add(peer.chat.id);
            request = value;
        }
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.messages_Chats)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.messages_Chats response = (TLRPC.messages_Chats) outcome.response;
        cachePeers(account, new ArrayList<>(), response.chats);
        for (TLRPC.Chat chat : response.chats) {
            if (chat.id == peer.chat.id) return chat;
        }
        throw new McpException("CHAT_NOT_FOUND",
                "Telegram did not return the requested chat", false, peerJson(peer));
    }

    private TLRPC.Chat waitForChatPhoto(
            int account, PeerRef peer, boolean expectedPresent, long previousPhotoId)
            throws McpException {
        TLRPC.Chat last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchChatServer(account, peer);
            long actual = chatPhotoId(last);
            if (!expectedPresent && actual == 0
                    || expectedPresent && actual != 0 && actual != previousPhotoId) {
                return last;
            }
            sleepReadback("Chat-photo readback was interrupted");
        }
        JsonObject details = last == null ? peerJson(peer) : chatJson(last);
        details.addProperty("expected_photo_present", expectedPresent);
        details.addProperty("previous_photo_id", Long.toString(previousPhotoId));
        throw new McpException("READBACK_FAILED",
                "Chat avatar did not match the independent server chat readback",
                true, details);
    }

    private static long chatPhotoId(TLRPC.Chat chat) {
        return chat == null || chat.photo == null
                || chat.photo instanceof TLRPC.TL_chatPhotoEmpty
                ? 0 : chat.photo.photo_id;
    }

    private TLRPC.Chat waitForMembershipState(
            int account, PeerRef peer, boolean left) throws McpException {
        TLRPC.Chat last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchChatServer(account, peer);
            boolean actualLeft = last.left || last.kicked;
            if (actualLeft == left) return last;
            sleepReadback("Chat membership readback was interrupted");
        }
        JsonObject details = peerJson(peer);
        details.addProperty("expected_left", left);
        details.addProperty("actual_left", last != null && (last.left || last.kicked));
        throw new McpException("READBACK_FAILED",
                "Chat membership did not match server getChats readback", true, details);
    }

    private static JsonObject profilePhotoJson(TLRPC.Photo photo) {
        JsonObject result = new JsonObject();
        boolean empty = photo == null || photo instanceof TLRPC.TL_photoEmpty;
        result.addProperty("photo_id", empty ? "0" : Long.toString(photo.id));
        result.addProperty("empty", empty);
        if (!empty) {
            result.addProperty("date", photo.date);
            result.addProperty("dc_id", photo.dc_id);
            result.addProperty("has_stickers", photo.has_stickers);
            result.addProperty("has_video", !photo.video_sizes.isEmpty());
            result.addProperty("photo_size_count", photo.sizes.size());
            result.addProperty("video_size_count", photo.video_sizes.size());
        }
        return result;
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
        result.addProperty("stories_hidden", user.stories_hidden);
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
        result.addProperty("forum", chat.forum);
        result.addProperty("forum_tabs", chat.forum_tabs);
        result.addProperty("creator", chat.creator);
        result.addProperty("left", chat.left);
        result.addProperty("kicked", chat.kicked);
        result.addProperty("participants_count", chat.participants_count);
        result.addProperty("stories_hidden", chat.stories_hidden);
        long photoId = chatPhotoId(chat);
        result.addProperty("photo_id", Long.toString(photoId));
        result.addProperty("photo_present", photoId != 0);
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

    private static boolean isNotifySettingsMuted(
            int account, TLRPC.PeerNotifySettings settings) {
        return settings != null
                && settings.mute_until > ConnectionsManager.getInstance(account).getCurrentTime();
    }

    private static JsonObject notificationSettingsJson(
            int account,
            PeerRef peer,
            long topicId,
            TLRPC.PeerNotifySettings settings) {
        JsonObject data = peerJson(peer);
        data.addProperty("topic_id", topicId);
        data.addProperty("muted", isNotifySettingsMuted(account, settings));
        data.addProperty("mute_until", settings == null ? 0 : settings.mute_until);
        data.addProperty("silent", settings != null && settings.silent);
        data.addProperty("show_previews", settings == null || settings.show_previews);
        data.addProperty("stories_muted", settings != null && settings.stories_muted);
        data.addProperty("stories_hide_sender",
                settings != null && settings.stories_hide_sender);
        data.addProperty("sound", notificationSoundReference(
                settings == null ? null : settings.android_sound));
        return data;
    }

    private static String notificationSoundReference(TLRPC.NotificationSound sound) {
        if (sound instanceof TLRPC.TL_notificationSoundNone) return "none";
        if (sound instanceof TLRPC.TL_notificationSoundRingtone) {
            return "ringtone:" + ((TLRPC.TL_notificationSoundRingtone) sound).id;
        }
        if (sound instanceof TLRPC.TL_notificationSoundLocal) {
            String data = ((TLRPC.TL_notificationSoundLocal) sound).data;
            if ("default".equalsIgnoreCase(data)) return "default";
            if ("nosound".equalsIgnoreCase(data)) return "none";
            return "local";
        }
        return "default";
    }

    private static TLRPC.NotificationSound inputNotificationSound(String sound)
            throws McpException {
        if ("default".equals(sound)) {
            return new TLRPC.TL_notificationSoundDefault();
        }
        if ("none".equals(sound)) {
            return new TLRPC.TL_notificationSoundNone();
        }
        if (sound.startsWith("ringtone:")) {
            long ringtoneId;
            try {
                ringtoneId = Long.parseLong(sound.substring("ringtone:".length()));
            } catch (NumberFormatException error) {
                ringtoneId = 0;
            }
            if (ringtoneId <= 0) invalid("sound ringtone ID must be positive");
            TLRPC.TL_notificationSoundRingtone ringtone =
                    new TLRPC.TL_notificationSoundRingtone();
            ringtone.id = ringtoneId;
            return ringtone;
        }
        invalid("sound must be default, none, or ringtone:<document-id>");
        return null;
    }

    private static TLRPC.InputNotifyPeer inputGlobalNotifyPeer(String domain)
            throws McpException {
        if ("private".equals(domain) || "stories".equals(domain)) {
            return new TLRPC.TL_inputNotifyUsers();
        }
        if ("groups".equals(domain)) return new TLRPC.TL_inputNotifyChats();
        if ("channels".equals(domain)) {
            return new TLRPC.TL_inputNotifyBroadcasts();
        }
        invalid("domain must be private, groups, channels, or stories");
        return null;
    }

    private static void requireNotificationPeer(PeerRef peer) throws McpException {
        if (peer.user != null && peer.user.self) {
            throw new McpException(
                    "NOT_APPLICABLE",
                    "Saved Messages and the account's own peer do not receive peer notifications",
                    false,
                    peerJson(peer));
        }
    }

    private TLRPC.PeerNotifySettings fetchGlobalNotifySettings(
            int account, String domain) throws McpException {
        TL_account.getNotifySettings request = new TL_account.getNotifySettings();
        request.peer = inputGlobalNotifyPeer(domain);
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.PeerNotifySettings)) {
            throw unexpectedResponse(outcome.response);
        }
        return (TLRPC.PeerNotifySettings) outcome.response;
    }

    private static JsonObject globalNotificationJson(
            int account, String domain, TLRPC.PeerNotifySettings settings) {
        JsonObject data = new JsonObject();
        data.addProperty("account", account);
        data.addProperty("domain", domain);
        if ("stories".equals(domain)) {
            data.addProperty("stories_muted", settings.stories_muted);
            data.addProperty("stories_hide_sender", settings.stories_hide_sender);
            data.addProperty("stories_sound",
                    notificationSoundReference(settings.stories_android_sound));
        } else {
            data.addProperty("muted",
                    isNotifySettingsMuted(account, settings));
            data.addProperty("mute_until", settings.mute_until);
            data.addProperty("show_previews", settings.show_previews);
            data.addProperty("sound",
                    notificationSoundReference(settings.android_sound));
        }
        return data;
    }

    private static boolean globalNotificationMatches(
            JsonObject args,
            String domain,
            TLRPC.PeerNotifySettings settings) throws McpException {
        if ("stories".equals(domain)) {
            if (args.has("stories_muted") && settings.stories_muted
                    != requiredBoolean(args, "stories_muted")) return false;
            if (args.has("stories_hide_sender") && settings.stories_hide_sender
                    != requiredBoolean(args, "stories_hide_sender")) return false;
            if (args.has("stories_sound") && !requiredString(
                    args, "stories_sound", 1, 32).equals(
                    notificationSoundReference(settings.stories_android_sound))) {
                return false;
            }
            return true;
        }
        if (args.has("mute_until") && settings.mute_until
                != requiredInt(args, "mute_until", 0, Integer.MAX_VALUE)) {
            return false;
        }
        if (args.has("show_previews") && settings.show_previews
                != requiredBoolean(args, "show_previews")) return false;
        return !args.has("sound") || requiredString(args, "sound", 1, 32)
                .equals(notificationSoundReference(settings.android_sound));
    }

    private static void applyGlobalNotificationPreferences(
            int account, String domain, JsonObject args) throws McpException {
        SharedPreferences.Editor editor =
                MessagesController.getNotificationsSettings(account).edit();
        if ("stories".equals(domain)) {
            if (args.has("stories_muted")) {
                editor.putBoolean("EnableAllStories",
                        !requiredBoolean(args, "stories_muted"));
            }
            if (args.has("stories_hide_sender")) {
                editor.putBoolean("EnableHideStoriesSenders",
                        requiredBoolean(args, "stories_hide_sender"));
            }
            if (args.has("stories_sound")) {
                applySoundPreference(editor, "Stories",
                        requiredString(args, "stories_sound", 1, 32));
            }
        } else {
            String prefix = "private".equals(domain) ? "All"
                    : "groups".equals(domain) ? "Group" : "Channel";
            if (args.has("mute_until")) {
                editor.putInt("Enable" + prefix + "2",
                        requiredInt(args, "mute_until", 0, Integer.MAX_VALUE));
            }
            if (args.has("show_previews")) {
                editor.putBoolean("EnablePreview" + prefix,
                        requiredBoolean(args, "show_previews"));
            }
            if (args.has("sound")) {
                String soundPrefix = "private".equals(domain)
                        ? "Global" : prefix;
                applySoundPreference(editor, soundPrefix,
                        requiredString(args, "sound", 1, 32));
            }
        }
        if (!editor.commit()) {
            throw new McpException("PERSISTENCE_FAILED",
                    "Android rejected notification preference commit", true, null);
        }
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.notificationsSettingsUpdated);
    }

    private static void applySoundPreference(
            SharedPreferences.Editor editor, String prefix, String sound)
            throws McpException {
        String docKey = prefix + "SoundDocId";
        String pathKey = prefix + "SoundPath";
        if ("default".equals(sound)) {
            editor.remove(docKey).remove(pathKey);
        } else if ("none".equals(sound)) {
            editor.remove(docKey).putString(pathKey, "NoSound");
        } else if (sound.startsWith("ringtone:")) {
            long id;
            try {
                id = Long.parseLong(sound.substring("ringtone:".length()));
            } catch (NumberFormatException error) {
                id = 0;
            }
            if (id <= 0) invalid("sound ringtone ID must be positive");
            editor.putLong(docKey, id).remove(pathKey);
        } else {
            invalid("sound must be default, none, or ringtone:<document-id>");
        }
    }

    private TL_account.TL_reactionsNotifySettings fetchReactionNotifySettings(
            int account) throws McpException {
        RequestOutcome outcome = request(
                account, new TL_account.getReactionsNotifySettings());
        if (!(outcome.response instanceof TL_account.TL_reactionsNotifySettings)) {
            throw unexpectedResponse(outcome.response);
        }
        return (TL_account.TL_reactionsNotifySettings) outcome.response;
    }

    private static String reactionNotifyFromReference(
            TL_account.ReactionNotificationsFrom value) {
        if (value instanceof TL_account.TL_reactionNotificationsFromAll) return "all";
        if (value instanceof TL_account.TL_reactionNotificationsFromContacts) {
            return "contacts";
        }
        return "off";
    }

    private static TL_account.ReactionNotificationsFrom reactionNotifyFrom(
            String value) throws McpException {
        if ("off".equals(value)) return null;
        if ("all".equals(value)) {
            return new TL_account.TL_reactionNotificationsFromAll();
        }
        if ("contacts".equals(value)) {
            return new TL_account.TL_reactionNotificationsFromContacts();
        }
        invalid("reaction notification source must be off, contacts, or all");
        return null;
    }

    private static JsonObject reactionNotificationJson(
            int account, TL_account.TL_reactionsNotifySettings settings) {
        JsonObject data = new JsonObject();
        data.addProperty("account", account);
        data.addProperty("messages",
                reactionNotifyFromReference(settings.messages_notify_from));
        data.addProperty("stories",
                reactionNotifyFromReference(settings.stories_notify_from));
        data.addProperty("poll_votes",
                reactionNotifyFromReference(settings.poll_votes_notify_from));
        data.addProperty("show_previews", settings.show_previews);
        data.addProperty("sound", notificationSoundReference(settings.sound));
        return data;
    }

    private static boolean reactionNotificationMatches(
            JsonObject args,
            TL_account.TL_reactionsNotifySettings settings) throws McpException {
        if (args.has("messages") && !requiredString(args, "messages", 1, 16)
                .equals(reactionNotifyFromReference(settings.messages_notify_from))) {
            return false;
        }
        if (args.has("stories") && !requiredString(args, "stories", 1, 16)
                .equals(reactionNotifyFromReference(settings.stories_notify_from))) {
            return false;
        }
        if (args.has("poll_votes") && !requiredString(args, "poll_votes", 1, 16)
                .equals(reactionNotifyFromReference(settings.poll_votes_notify_from))) {
            return false;
        }
        if (args.has("show_previews") && settings.show_previews
                != requiredBoolean(args, "show_previews")) return false;
        return !args.has("sound") || requiredString(args, "sound", 1, 32)
                .equals(notificationSoundReference(settings.sound));
    }

    private static void applyReactionNotificationPreferences(
            int account, TL_account.TL_reactionsNotifySettings settings)
            throws McpException {
        SharedPreferences.Editor editor =
                MessagesController.getNotificationsSettings(account).edit();
        String messages = reactionNotifyFromReference(settings.messages_notify_from);
        editor.putBoolean("EnableReactionsMessages", !"off".equals(messages));
        editor.putBoolean("EnableReactionsMessagesContacts",
                "contacts".equals(messages));
        String stories = reactionNotifyFromReference(settings.stories_notify_from);
        editor.putBoolean("EnableReactionsStories", !"off".equals(stories));
        editor.putBoolean("EnableReactionsStoriesContacts",
                "contacts".equals(stories));
        editor.putBoolean("EnableReactionsPreview", settings.show_previews);
        AccountInstance.getInstance(account).getNotificationsController()
                .getNotificationsSettingsFacade().applySoundSettings(
                        settings.sound, editor, 0, 0,
                        NotificationsController.TYPE_REACTIONS_MESSAGES, false);
        if (!editor.commit()) {
            throw new McpException("PERSISTENCE_FAILED",
                    "Android rejected reaction notification preference commit",
                    true, null);
        }
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.notificationsSettingsUpdated);
    }

    private static int setFlag(int flags, int mask, boolean enabled) {
        return enabled ? flags | mask : flags & ~mask;
    }

    private static boolean notificationSettingsMatch(
            JsonObject args, TLRPC.PeerNotifySettings settings) throws McpException {
        if (settings == null) return false;
        if (args.has("mute_until")
                && settings.mute_until != requiredInt(
                args, "mute_until", 0, Integer.MAX_VALUE)) return false;
        if (args.has("silent")
                && settings.silent != requiredBoolean(args, "silent")) return false;
        if (args.has("show_previews")
                && settings.show_previews != requiredBoolean(args, "show_previews")) return false;
        if (args.has("stories_muted")
                && settings.stories_muted != requiredBoolean(args, "stories_muted")) return false;
        if (args.has("stories_hide_sender")
                && settings.stories_hide_sender
                != requiredBoolean(args, "stories_hide_sender")) return false;
        if (args.has("sound")) {
            String requested = requiredString(args, "sound", 1, 32);
            if (!requested.equals(notificationSoundReference(settings.android_sound))) return false;
        }
        return true;
    }

    private TLRPC.TL_contacts_contacts fetchServerContacts(int account)
            throws McpException {
        TLRPC.TL_contacts_getContacts request = new TLRPC.TL_contacts_getContacts();
        request.hash = 0;
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_contacts_contacts)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_contacts_contacts response =
                (TLRPC.TL_contacts_contacts) outcome.response;
        cachePeers(account, response.users, new ArrayList<>());
        return response;
    }

    private TLRPC.User serverContactUser(int account, long userId) throws McpException {
        TLRPC.TL_contacts_contacts response = fetchServerContacts(account);
        boolean present = false;
        for (TLRPC.TL_contact contact : response.contacts) {
            if (contact.user_id == userId) {
                present = true;
                break;
            }
        }
        if (!present) return null;
        for (TLRPC.User user : response.users) {
            if (user.id == userId) return user;
        }
        TLRPC.User cached = MessagesController.getInstance(account).getUser(userId);
        if (cached != null) return cached;
        throw new McpException("READBACK_FAILED",
                "Contact membership was returned without the corresponding user", true, null);
    }

    private TLRPC.User waitForContact(
            int account,
            long userId,
            boolean present,
            String firstName,
            String lastName) throws McpException {
        TLRPC.User last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = serverContactUser(account, userId);
            if (!present && last == null) return null;
            if (present && last != null
                    && (firstName == null || firstName.equals(last.first_name))
                    && (lastName == null || lastName.equals(
                    last.last_name == null ? "" : last.last_name))) {
                return last;
            }
            sleepReadback("Cloud-contact readback was interrupted");
        }
        JsonObject details = new JsonObject();
        details.addProperty("user_id", Long.toString(userId));
        details.addProperty("expected_present", present);
        if (firstName != null) details.addProperty("expected_first_name", firstName);
        if (lastName != null) details.addProperty("expected_last_name", lastName);
        if (last != null) details.add("actual", userJson(last));
        throw new McpException("READBACK_FAILED",
                "Cloud contact did not reach the requested server state", true, details);
    }

    private static PeerRef requireUserPeer(PeerRef peer, String argument)
            throws McpException {
        if (peer == null || peer.user == null || peer.dialogId <= 0) {
            throw new McpException("INVALID_ARGUMENT",
                    argument + " must resolve to a Telegram user", false,
                    peer == null ? null : peerJson(peer));
        }
        return peer;
    }

    private static void requireCanSend(PeerRef peer, String kind) throws McpException {
        boolean allowed = true;
        if (peer.user != null) {
            allowed = !UserObject.isDeleted(peer.user)
                    && MessagesController.getInstance(peer.account)
                    .blockePeers.indexOfKey(peer.dialogId) < 0;
        } else if (peer.chat != null) {
            allowed = !ChatObject.isNotInChat(peer.chat) && ChatObject.canSendMessages(peer.chat);
            if (allowed) {
                switch (kind) {
                    case "text": allowed = ChatObject.canSendPlain(peer.chat); break;
                    case "photo": allowed = ChatObject.canSendPhoto(peer.chat); break;
                    case "video": allowed = ChatObject.canSendVideo(peer.chat); break;
                    case "document": allowed = ChatObject.canSendDocument(peer.chat); break;
                    case "poll": allowed = ChatObject.canSendPolls(peer.chat); break;
                    case "sticker": allowed = ChatObject.canSendStickers(peer.chat); break;
                    case "voice": allowed = ChatObject.canSendVoice(peer.chat); break;
                    default: break;
                }
            }
        }
        if (!allowed) {
            JsonObject details = peerJson(peer);
            details.addProperty("message_kind", kind);
            throw new McpException("PERMISSION_DENIED",
                    "Telegram's current peer and permission state does not allow this message kind",
                    false, details);
        }
    }

    private MessageObject resolveReplyMessage(
            int account, PeerRef peer, int replyId) throws McpException {
        if (replyId == 0) return null;
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(replyId);
        return messageObjects(account,
                fetchExactMessages(account, peer, ids, false, true)).get(0);
    }

    private MessageObject resolveTopicTopMessage(
            int account, PeerRef peer, int topicId) throws McpException {
        if (topicId == 0) return null;
        TLRPC.TL_forumTopic topic = fetchForumTopic(account, peer, topicId);
        TLRPC.TL_message start = new TLRPC.TL_message();
        start.message = "";
        start.id = topic.id;
        start.peer_id = MessagesController.getInstance(account).getPeer(peer.dialogId);
        start.action = new TLRPC.TL_messageActionTopicCreate();
        start.action.title = topic.title == null ? "" : topic.title;
        MessageObject result = new MessageObject(account, start, false, false);
        result.isTopicMainMessage = true;
        result.replyToForumTopic = topic;
        return result;
    }

    private static void requireSentTopicMatches(
            int account, TLRPC.Message message, int expectedTopicId)
            throws McpException {
        if (expectedTopicId == 0) return;
        long actualTopicId = MessageObject.getTopicId(account, message, true);
        if (actualTopicId == expectedTopicId) return;
        JsonObject details = messageJson(account, message);
        details.addProperty("expected_topic_id", expectedTopicId);
        details.addProperty("actual_topic_id", actualTopicId);
        throw new McpException("READBACK_FAILED",
                "Sent message was not returned in the requested forum topic",
                true, details);
    }

    private static void requireNoPaidMessageConfirmation(int account, PeerRef peer)
            throws McpException {
        if (MessagesController.getInstance(account)
                .getSendPaidMessagesStars(peer.dialogId) > 0) {
            throw new McpException("HUMAN_INTERACTION_REQUIRED",
                    "This peer requires a paid-message confirmation in Telegram's trusted UI",
                    false, peerJson(peer));
        }
    }

    private static HashMap<String, String> mcpSendParams(
            String idempotencyKey, String operationId) {
        HashMap<String, String> params = mcpOperationParams(operationId);
        params.put("mcp_idempotency_key", idempotencyKey);
        return params;
    }

    private static HashMap<String, String> mcpOperationParams(String operationId) {
        HashMap<String, String> params = new HashMap<>();
        params.put("mcp_operation_id", operationId);
        return params;
    }

    private static boolean messageHasMcpOperation(
            TLRPC.Message message, String operationId) {
        return message != null && message.params != null
                && operationId.equals(message.params.get("mcp_operation_id"));
    }

    private void removeSendObserversBestEffort(
            int account,
            NotificationCenter.NotificationCenterDelegate observer) {
        try {
            uiCall(() -> {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.removeObserver(observer, NotificationCenter.didReceiveNewMessages);
                center.removeObserver(observer, NotificationCenter.messageReceivedByServer);
                center.removeObserver(observer, NotificationCenter.messageSendError);
                return null;
            });
        } catch (McpException cleanupError) {
            FileLog.e(cleanupError);
        }
    }

    private File operationScopedSendCopy(
            File source, String operationId, int index) throws McpException {
        String name = ".mcp-send-"
                + sha256Hex(operationId + ":" + index).substring(0, 32)
                + safeExtension(source.getName());
        File target = new File(stagingDirectory(), name);
        if (target.exists() && !target.delete()) {
            throw new McpException("FILE_IO_ERROR",
                    "A stale operation-scoped media copy could not be replaced",
                    true, null);
        }
        try {
            Os.link(source.getAbsolutePath(), target.getAbsolutePath());
            if (target.isFile() && target.length() == source.length()) {
                return target;
            }
            target.delete();
        } catch (Throwable linkError) {
            // Some devices/filesystems disallow hard links; bounded streaming copy is safe.
            if (target.exists()) target.delete();
        }
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        } catch (IOException error) {
            target.delete();
            throw new McpException("FILE_IO_ERROR",
                    "Could not create an operation-scoped media copy",
                    true, null);
        }
        return target;
    }

    private TLRPC.Message exactSentMessage(
            int account, PeerRef peer, int messageId, boolean scheduled)
            throws McpException {
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        return fetchExactMessages(account, peer, ids, scheduled, true).get(0);
    }

    private static void addSentMessageEvidence(
            JsonObject data,
            String operationId,
            int account,
            PeerRef peer,
            int messageId,
            boolean scheduled) {
        JsonObject readbackArgs = peerReadbackArguments(account, peer);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        readbackArgs.add("message_ids", intArray(ids));
        readbackArgs.addProperty("scheduled", scheduled);
        addWriteEvidence(data, operationId, true, true, true, false,
                "telegram.message.get", readbackArgs);
    }

    private static boolean locationMessageMatches(
            TLRPC.Message message, double latitude, double longitude, String title) {
        if (message == null || message.media == null || message.media.geo == null) return false;
        boolean typeMatches = title == null || title.isEmpty()
                ? message.media instanceof TLRPC.TL_messageMediaGeo
                : message.media instanceof TLRPC.TL_messageMediaVenue
                && title.equals(message.media.title);
        return typeMatches
                && Math.abs(message.media.geo.lat - latitude) < 0.000001
                && Math.abs(message.media.geo._long - longitude) < 0.000001;
    }

    private TL_account.privacyRules fetchPrivacyRules(int account, String key)
            throws McpException {
        TL_account.getPrivacy request = new TL_account.getPrivacy();
        request.key = inputPrivacyKey(key);
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TL_account.privacyRules)) {
            throw unexpectedResponse(outcome.response);
        }
        TL_account.privacyRules rules = (TL_account.privacyRules) outcome.response;
        cachePeers(account, rules.users, rules.chats);
        return rules;
    }

    private static TLRPC.InputPrivacyKey inputPrivacyKey(String key)
            throws McpException {
        switch (key) {
            case "last_seen": return new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
            case "chat_invites": return new TLRPC.TL_inputPrivacyKeyChatInvite();
            case "calls": return new TLRPC.TL_inputPrivacyKeyPhoneCall();
            case "phone_p2p": return new TLRPC.TL_inputPrivacyKeyPhoneP2P();
            case "profile_photo": return new TLRPC.TL_inputPrivacyKeyProfilePhoto();
            case "forwards": return new TLRPC.TL_inputPrivacyKeyForwards();
            case "phone_number": return new TLRPC.TL_inputPrivacyKeyPhoneNumber();
            case "added_by_phone": return new TLRPC.TL_inputPrivacyKeyAddedByPhone();
            case "voice_messages": return new TLRPC.TL_inputPrivacyKeyVoiceMessages();
            case "about": return new TLRPC.TL_inputPrivacyKeyAbout();
            case "birthday": return new TLRPC.TL_inputPrivacyKeyBirthday();
            case "star_gifts_auto_save":
                return new TLRPC.TL_inputPrivacyKeyStarGiftsAutoSave();
            case "no_paid_messages":
                return new TLRPC.TL_inputPrivacyKeyNoPaidMessages();
            case "saved_music": return new TLRPC.TL_inputPrivacyKeySavedMusic();
            default:
                throw new McpException("INVALID_ARGUMENT",
                        "Unsupported privacy key: " + key, false, null);
        }
    }

    private static int privacyRuleType(String key) {
        switch (key) {
            case "last_seen": return ContactsController.PRIVACY_RULES_TYPE_LASTSEEN;
            case "chat_invites": return ContactsController.PRIVACY_RULES_TYPE_INVITE;
            case "calls": return ContactsController.PRIVACY_RULES_TYPE_CALLS;
            case "phone_p2p": return ContactsController.PRIVACY_RULES_TYPE_P2P;
            case "profile_photo": return ContactsController.PRIVACY_RULES_TYPE_PHOTO;
            case "forwards": return ContactsController.PRIVACY_RULES_TYPE_FORWARDS;
            case "phone_number": return ContactsController.PRIVACY_RULES_TYPE_PHONE;
            case "added_by_phone": return ContactsController.PRIVACY_RULES_TYPE_ADDED_BY_PHONE;
            case "voice_messages": return ContactsController.PRIVACY_RULES_TYPE_VOICE_MESSAGES;
            case "about": return ContactsController.PRIVACY_RULES_TYPE_BIO;
            case "birthday": return ContactsController.PRIVACY_RULES_TYPE_BIRTHDAY;
            case "star_gifts_auto_save": return ContactsController.PRIVACY_RULES_TYPE_GIFTS;
            case "no_paid_messages":
                return ContactsController.PRIVACY_RULES_TYPE_NO_PAID_MESSAGES;
            case "saved_music": return ContactsController.PRIVACY_RULES_TYPE_MUSIC;
            default: return -1;
        }
    }

    private void addPrivacyPeers(
            int account,
            JsonObject args,
            String key,
            boolean allow,
            ArrayList<TLRPC.InputPrivacyRule> destination,
            Set<Long> ids) throws McpException {
        if (!args.has(key)) return;
        JsonArray values = requiredArray(args, key, 0, MAX_LIMIT);
        TLRPC.TL_inputPrivacyValueAllowUsers allowUsers =
                new TLRPC.TL_inputPrivacyValueAllowUsers();
        TLRPC.TL_inputPrivacyValueDisallowUsers disallowUsers =
                new TLRPC.TL_inputPrivacyValueDisallowUsers();
        TLRPC.TL_inputPrivacyValueAllowChatParticipants allowChats =
                new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
        TLRPC.TL_inputPrivacyValueDisallowChatParticipants disallowChats =
                new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
        for (JsonElement item : values) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                invalid(key + " must contain peer reference strings");
            }
            PeerRef peer = resolvePeer(account, item.getAsString());
            if (peer.dialogId == UserConfig.getInstance(account).getClientUserId()) {
                invalid("The current account cannot be a privacy exception");
            }
            if (!ids.add(peer.dialogId)) invalid(key + " contains a duplicate peer");
            if (peer.user != null) {
                TLRPC.InputUser input = MessagesController.getInstance(account)
                        .getInputUser(peer.user);
                if (allow) allowUsers.users.add(input);
                else disallowUsers.users.add(input);
            } else if (peer.chat != null) {
                if (allow) allowChats.chats.add(-peer.dialogId);
                else disallowChats.chats.add(-peer.dialogId);
            } else {
                invalid(key + " contains an unsupported peer");
            }
        }
        if (allow) {
            if (!allowUsers.users.isEmpty()) destination.add(allowUsers);
            if (!allowChats.chats.isEmpty()) destination.add(allowChats);
        } else {
            if (!disallowUsers.users.isEmpty()) destination.add(disallowUsers);
            if (!disallowChats.chats.isEmpty()) destination.add(disallowChats);
        }
    }

    private static JsonObject requestedPrivacyJson(
            int account,
            String key,
            JsonObject args,
            Set<Long> allowIds,
            Set<Long> disallowIds) throws McpException {
        JsonObject result = new JsonObject();
        result.addProperty("key", key);
        result.addProperty("base", requiredString(args, "base", 1, 16));
        result.add("allow_peers", privacyPeerReferences(account, allowIds));
        result.add("disallow_peers", privacyPeerReferences(account, disallowIds));
        result.addProperty("allow_close_friends",
                optionalBoolean(args, "allow_close_friends", false));
        result.addProperty("allow_premium",
                optionalBoolean(args, "allow_premium", false));
        result.addProperty("disallow_contacts",
                optionalBoolean(args, "disallow_contacts", false));
        result.addProperty("bots", optionalString(args, "bots", "inherit"));
        return result;
    }

    private static JsonObject privacyRulesJson(
            int account,
            String key,
            ArrayList<TLRPC.PrivacyRule> rules) {
        Set<Long> allowIds = new HashSet<>();
        Set<Long> disallowIds = new HashSet<>();
        boolean allowAll = false;
        boolean allowContacts = false;
        boolean disallowAll = false;
        boolean closeFriends = false;
        boolean premium = false;
        boolean disallowContacts = false;
        String bots = "inherit";
        JsonArray rawTypes = new JsonArray();
        for (TLRPC.PrivacyRule rule : rules) {
            rawTypes.add(rule.getClass().getSimpleName());
            if (rule instanceof TLRPC.TL_privacyValueAllowAll) {
                allowAll = true;
            } else if (rule instanceof TLRPC.TL_privacyValueAllowContacts) {
                allowContacts = true;
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowAll) {
                disallowAll = true;
            } else if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                allowIds.addAll(((TLRPC.TL_privacyValueAllowUsers) rule).users);
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                disallowIds.addAll(((TLRPC.TL_privacyValueDisallowUsers) rule).users);
            } else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                for (Long id : ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats) {
                    allowIds.add(-id);
                }
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                for (Long id : ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats) {
                    disallowIds.add(-id);
                }
            } else if (rule instanceof TLRPC.TL_privacyValueAllowCloseFriends) {
                closeFriends = true;
            } else if (rule instanceof TLRPC.TL_privacyValueAllowPremium) {
                premium = true;
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowContacts) {
                disallowContacts = true;
            } else if (rule instanceof TLRPC.TL_privacyValueAllowBots) {
                bots = "allow";
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowBots) {
                bots = "disallow";
            }
        }
        String base = allowAll ? "everybody"
                : allowContacts ? "contacts"
                : disallowAll ? "nobody" : "unknown";
        JsonObject result = new JsonObject();
        result.addProperty("key", key);
        result.addProperty("base", base);
        result.add("allow_peers", privacyPeerReferences(account, allowIds));
        result.add("disallow_peers", privacyPeerReferences(account, disallowIds));
        result.addProperty("allow_close_friends", closeFriends);
        result.addProperty("allow_premium", premium);
        result.addProperty("disallow_contacts", disallowContacts);
        result.addProperty("bots", bots);
        result.add("server_rule_types", rawTypes);
        return result;
    }

    private static JsonArray privacyPeerReferences(int account, Set<Long> ids) {
        ArrayList<String> references = new ArrayList<>();
        MessagesController controller = MessagesController.getInstance(account);
        for (Long id : ids) references.add(canonicalPeer(controller, id));
        Collections.sort(references);
        JsonArray result = new JsonArray();
        for (String reference : references) result.add(reference);
        return result;
    }

    private static boolean privacySemanticsEqual(JsonObject expected, JsonObject actual) {
        String[] keys = {
                "key", "base", "allow_peers", "disallow_peers",
                "allow_close_friends", "allow_premium", "disallow_contacts", "bots"
        };
        for (String key : keys) {
            if (!expected.has(key) || !actual.has(key)
                    || !expected.get(key).equals(actual.get(key))) return false;
        }
        return true;
    }

    private boolean serverBlockedContains(int account, long dialogId) throws McpException {
        int offset = 0;
        for (int page = 0; page < 100; page++) {
            TLRPC.TL_contacts_getBlocked request = new TLRPC.TL_contacts_getBlocked();
            request.offset = offset;
            request.limit = MAX_LIMIT;
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.contacts_Blocked)) {
                throw unexpectedResponse(outcome.response);
            }
            TLRPC.contacts_Blocked response = (TLRPC.contacts_Blocked) outcome.response;
            cachePeers(account, response.users, response.chats);
            for (TLRPC.TL_peerBlocked item : response.blocked) {
                if (item.peer_id != null && MessageObject.getPeerId(item.peer_id) == dialogId) {
                    return true;
                }
            }
            offset += response.blocked.size();
            int total = response instanceof TLRPC.TL_contacts_blockedSlice
                    ? response.count : response.blocked.size();
            if (response.blocked.isEmpty() || offset >= total) {
                return false;
            }
        }
        throw new McpException("READBACK_INCOMPLETE",
                "Blocked-peer readback exceeded the pagination safety limit", true, null);
    }

    private TLRPC.Dialog fetchPeerDialog(int account, PeerRef peer) throws McpException {
        TLRPC.TL_messages_getPeerDialogs request = new TLRPC.TL_messages_getPeerDialogs();
        TLRPC.TL_inputDialogPeer input = new TLRPC.TL_inputDialogPeer();
        input.peer = peer.inputPeer;
        request.peers.add(input);
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_peerDialogs)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_peerDialogs response =
                (TLRPC.TL_messages_peerDialogs) outcome.response;
        cachePeers(account, response.users, response.chats);
        for (TLRPC.Dialog dialog : response.dialogs) {
            if (dialog.peer != null && MessageObject.getPeerId(dialog.peer) == peer.dialogId) {
                dialog.id = peer.dialogId;
                return dialog;
            }
        }
        throw new McpException("DIALOG_NOT_FOUND",
                "Telegram did not return the requested dialog", false, peerJson(peer));
    }

    private TLRPC.Dialog waitForDialogFolder(
            int account, PeerRef peer, int folderId) throws McpException {
        TLRPC.Dialog last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchPeerDialog(account, peer);
            if (last.folder_id == folderId) return last;
            sleepReadback("Dialog folder readback was interrupted");
        }
        JsonObject details = peerJson(peer);
        details.addProperty("expected_folder_id", folderId);
        details.addProperty("actual_folder_id", last == null ? -1 : last.folder_id);
        throw new McpException("READBACK_FAILED",
                "Dialog folder did not match messages.getPeerDialogs", true, details);
    }

    private TLRPC.Dialog waitForDialogPinned(
            int account, PeerRef peer, boolean pinned) throws McpException {
        TLRPC.Dialog last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchPeerDialog(account, peer);
            if (last.pinned == pinned) return last;
            sleepReadback("Dialog pin readback was interrupted");
        }
        JsonObject details = peerJson(peer);
        details.addProperty("expected_pinned", pinned);
        details.addProperty("actual_pinned", last != null && last.pinned);
        throw new McpException("READBACK_FAILED",
                "Dialog pin state did not match messages.getPeerDialogs", true, details);
    }

    private int waitForReadState(
            int account, PeerRef peer, int maxId, long topicId) throws McpException {
        if (topicId != 0) {
            TLRPC.TL_forumTopic last = null;
            for (int attempt = 0; attempt < 12; attempt++) {
                last = fetchForumTopic(account, peer, (int) topicId);
                if (last.unread_count == 0 || last.read_inbox_max_id >= maxId) {
                    return last.unread_count;
                }
                sleepReadback("Forum-topic read-state readback was interrupted");
            }
            JsonObject details = peerJson(peer);
            details.addProperty("topic_id", topicId);
            details.addProperty("read_inbox_max_id", last == null ? 0 : last.read_inbox_max_id);
            throw new McpException("READBACK_FAILED",
                    "Forum topic read state did not reach the requested message", true, details);
        }
        TLRPC.Dialog last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchPeerDialog(account, peer);
            if (last.unread_count == 0 || last.read_inbox_max_id >= maxId) {
                return last.unread_count;
            }
            sleepReadback("Dialog read-state readback was interrupted");
        }
        JsonObject details = peerJson(peer);
        details.addProperty("read_inbox_max_id", last == null ? 0 : last.read_inbox_max_id);
        throw new McpException("READBACK_FAILED",
                "Dialog read state did not reach the requested message", true, details);
    }

    private TLRPC.Dialog waitForDialogUnreadMark(
            int account, PeerRef peer, boolean unreadMark) throws McpException {
        TLRPC.Dialog last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchPeerDialog(account, peer);
            if (last.unread_mark == unreadMark) return last;
            sleepReadback("Dialog unread-mark readback was interrupted");
        }
        JsonObject details = peerJson(peer);
        details.addProperty("expected_unread_mark", unreadMark);
        details.addProperty("actual_unread_mark", last != null && last.unread_mark);
        throw new McpException("READBACK_FAILED",
                "Dialog unread mark did not match messages.getPeerDialogs", true, details);
    }

    private static void requireForumPeer(PeerRef peer) throws McpException {
        if (peer.chat == null || !ChatObject.isChannel(peer.chat) || !peer.chat.forum) {
            throw new McpException("INVALID_ARGUMENT",
                    "peer must be a forum supergroup", false, peerJson(peer));
        }
    }

    private ArrayList<TLRPC.DialogFilter> fetchDialogFilters(int account)
            throws McpException {
        RequestOutcome outcome = request(account, new TLRPC.TL_messages_getDialogFilters());
        if (!(outcome.response instanceof TLRPC.TL_messages_dialogFilters)) {
            throw unexpectedResponse(outcome.response);
        }
        return new ArrayList<>(((TLRPC.TL_messages_dialogFilters) outcome.response).filters);
    }

    private static int nextDialogFilterId(ArrayList<TLRPC.DialogFilter> filters)
            throws McpException {
        Set<Integer> used = new HashSet<>();
        for (TLRPC.DialogFilter filter : filters) used.add(filter.id);
        for (int id = 2; id <= 255; id++) {
            if (!used.contains(id)) return id;
        }
        throw new McpException("FOLDER_LIMIT_REACHED",
                "No free custom dialog-folder ID remains", false, null);
    }

    private void addInputPeers(
            int account,
            JsonObject args,
            String key,
            ArrayList<TLRPC.InputPeer> destination) throws McpException {
        if (!args.has(key)) return;
        JsonArray values = requiredArray(args, key, 0, MAX_LIMIT);
        Set<Long> seen = new HashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                invalid(key + " must contain peer reference strings");
            }
            PeerRef peer = resolvePeer(account, value.getAsString());
            if (!seen.add(peer.dialogId)) invalid(key + " contains a duplicate peer");
            destination.add(peer.inputPeer);
        }
    }

    private static JsonObject dialogFilterJson(int account, TLRPC.DialogFilter filter) {
        JsonObject data = new JsonObject();
        data.addProperty("folder_id", filter.id);
        data.addProperty("default", filter instanceof TLRPC.TL_dialogFilterDefault);
        data.addProperty("shared_chatlist", filter instanceof TLRPC.TL_dialogFilterChatlist);
        data.addProperty("title", filter.title == null || filter.title.text == null
                ? "" : filter.title.text);
        data.addProperty("emoticon", filter.emoticon == null ? "" : filter.emoticon);
        data.addProperty("color", filter.color);
        data.addProperty("contacts", filter.contacts);
        data.addProperty("non_contacts", filter.non_contacts);
        data.addProperty("groups", filter.groups);
        data.addProperty("broadcasts", filter.broadcasts);
        data.addProperty("bots", filter.bots);
        data.addProperty("exclude_muted", filter.exclude_muted);
        data.addProperty("exclude_read", filter.exclude_read);
        data.addProperty("exclude_archived", filter.exclude_archived);
        data.add("pinned_peers", inputPeersJson(account, filter.pinned_peers));
        data.add("include_peers", inputPeersJson(account, filter.include_peers));
        data.add("exclude_peers", inputPeersJson(account, filter.exclude_peers));
        return data;
    }

    private static JsonArray inputPeersJson(
            int account, ArrayList<TLRPC.InputPeer> peers) {
        JsonArray result = new JsonArray();
        for (TLRPC.InputPeer peer : peers) {
            long dialogId = DialogObject.getPeerDialogId(peer);
            if (peer instanceof TLRPC.TL_inputPeerSelf) {
                result.add("saved");
            } else if (peer instanceof TLRPC.TL_inputPeerUser) {
                result.add("user:" + dialogId);
            } else if (peer instanceof TLRPC.TL_inputPeerChat) {
                result.add("chat:" + (-dialogId));
            } else if (peer instanceof TLRPC.TL_inputPeerChannel) {
                result.add("channel:" + (-dialogId));
            } else if (dialogId != 0) {
                result.add(canonicalPeer(MessagesController.getInstance(account), dialogId));
            }
        }
        return result;
    }

    private TLRPC.DialogFilter waitForDialogFilter(
            int account, int folderId, boolean present) throws McpException {
        TLRPC.DialogFilter last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = null;
            for (TLRPC.DialogFilter filter : fetchDialogFilters(account)) {
                if (filter.id == folderId) {
                    last = filter;
                    break;
                }
            }
            if ((last != null) == present) return last;
            sleepReadback("Dialog-folder readback was interrupted");
        }
        JsonObject details = new JsonObject();
        details.addProperty("folder_id", folderId);
        details.addProperty("expected_present", present);
        if (last != null) details.add("actual", dialogFilterJson(account, last));
        throw new McpException(present ? "FOLDER_NOT_FOUND" : "READBACK_FAILED",
                present ? "Dialog folder was not returned by the server"
                        : "Deleted dialog folder remains on the server",
                !present, details);
    }

    private void waitForDialogFilterOrder(int account, ArrayList<Integer> expected)
            throws McpException {
        ArrayList<Integer> last = new ArrayList<>();
        for (int attempt = 0; attempt < 12; attempt++) {
            last.clear();
            for (TLRPC.DialogFilter filter : fetchDialogFilters(account)) {
                if (!(filter instanceof TLRPC.TL_dialogFilterDefault)) {
                    last.add(filter.id);
                }
            }
            if (last.equals(expected)) return;
            sleepReadback("Dialog-folder order readback was interrupted");
        }
        JsonObject details = new JsonObject();
        details.add("expected", intArray(expected));
        details.add("actual", intArray(last));
        throw new McpException("READBACK_FAILED",
                "Dialog folder order did not match server readback", true, details);
    }

    private ArrayList<TLRPC.TL_forumTopic> fetchForumTopics(
            int account, PeerRef peer, String query, int limit) throws McpException {
        requireForumPeer(peer);
        TL_forum.TL_messages_getForumTopics request =
                new TL_forum.TL_messages_getForumTopics();
        request.peer = peer.inputPeer;
        request.q = query == null || query.isEmpty() ? null : query;
        request.offset_date = 0;
        request.offset_id = 0;
        request.offset_topic = 0;
        request.limit = Math.min(MAX_LIMIT, Math.max(1, limit));
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_forumTopics)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_forumTopics response =
                (TLRPC.TL_messages_forumTopics) outcome.response;
        cachePeers(account, response.users, response.chats);
        ArrayList<TLRPC.TL_forumTopic> result = new ArrayList<>();
        for (TLRPC.TL_forumTopic topic : response.topics) {
            if (!(topic instanceof TLRPC.TL_forumTopicDeleted)) result.add(topic);
        }
        return result;
    }

    private TLRPC.TL_forumTopic waitForNewForumTopic(
            int account, PeerRef peer, String title, Set<Integer> before)
            throws McpException {
        JsonArray last = new JsonArray();
        for (int attempt = 0; attempt < 12; attempt++) {
            ArrayList<TLRPC.TL_forumTopic> topics =
                    fetchForumTopics(account, peer, title, MAX_LIMIT);
            last = new JsonArray();
            for (TLRPC.TL_forumTopic topic : topics) {
                last.add(forumTopicJson(topic));
                if (title.equals(topic.title) && !before.contains(topic.id)) return topic;
            }
            sleepReadback("Forum-topic creation readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Created forum topic was not returned by messages.getForumTopics", true, last);
    }

    private TLRPC.TL_forumTopic waitForForumTopicState(
            int account,
            PeerRef peer,
            int topicId,
            String title,
            Long iconEmojiId,
            Boolean closed,
            Boolean hidden,
            Boolean pinned) throws McpException {
        TLRPC.TL_forumTopic last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchForumTopic(account, peer, topicId);
            if ((title == null || title.equals(last.title))
                    && (iconEmojiId == null || iconEmojiId == last.icon_emoji_id)
                    && (closed == null || closed == last.closed)
                    && (hidden == null || hidden == last.hidden)
                    && (pinned == null || pinned == last.pinned)) {
                return last;
            }
            sleepReadback("Forum-topic state readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Forum topic did not reach the requested state", true,
                last == null ? peerJson(peer) : forumTopicJson(last));
    }

    private void waitForForumTopicAbsent(int account, PeerRef peer, int topicId)
            throws McpException {
        JsonElement last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                TLRPC.TL_forumTopic topic = fetchForumTopic(account, peer, topicId);
                last = forumTopicJson(topic);
            } catch (McpException error) {
                if ("TOPIC_NOT_FOUND".equals(error.code)) return;
                throw error;
            }
            sleepReadback("Forum-topic deletion readback was interrupted");
        }
        throw new McpException("READBACK_FAILED",
                "Deleted forum topic is still returned by the server", true, last);
    }

    private static JsonObject forumTopicJson(TLRPC.TL_forumTopic topic) {
        JsonObject data = new JsonObject();
        data.addProperty("topic_id", topic.id);
        data.addProperty("title", topic.title == null ? "" : topic.title);
        data.addProperty("date", topic.date);
        data.addProperty("icon_color", topic.icon_color);
        data.addProperty("icon_emoji_id", Long.toString(topic.icon_emoji_id));
        data.addProperty("top_message_id", topic.top_message);
        data.addProperty("read_inbox_max_id", topic.read_inbox_max_id);
        data.addProperty("read_outbox_max_id", topic.read_outbox_max_id);
        data.addProperty("unread_count", topic.unread_count);
        data.addProperty("unread_mentions_count", topic.unread_mentions_count);
        data.addProperty("unread_reactions_count", topic.unread_reactions_count);
        data.addProperty("mine", topic.my);
        data.addProperty("closed", topic.closed);
        data.addProperty("hidden", topic.hidden);
        data.addProperty("pinned", topic.pinned);
        data.addProperty("title_missing", topic.title_missing);
        return data;
    }

    private TLRPC.TL_forumTopic fetchForumTopic(
            int account, PeerRef peer, int topicId) throws McpException {
        requireForumPeer(peer);
        TL_forum.TL_messages_getForumTopicsByID request =
                new TL_forum.TL_messages_getForumTopicsByID();
        request.peer = peer.inputPeer;
        request.topics.add(topicId);
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.TL_messages_forumTopics)) {
            throw unexpectedResponse(outcome.response);
        }
        TLRPC.TL_messages_forumTopics response =
                (TLRPC.TL_messages_forumTopics) outcome.response;
        cachePeers(account, response.users, response.chats);
        for (TLRPC.TL_forumTopic topic : response.topics) {
            if (topic.id == topicId && !(topic instanceof TLRPC.TL_forumTopicDeleted)) {
                return topic;
            }
        }
        throw new McpException("TOPIC_NOT_FOUND",
                "Telegram did not return the requested forum topic", false, peerJson(peer));
    }

    private void waitForEmptyHistory(
            int account, PeerRef peer, String operationId) throws McpException {
        for (int attempt = 0; attempt < 12; attempt++) {
            TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
            request.peer = peer.inputPeer;
            request.limit = 1;
            RequestOutcome outcome = request(account, request);
            if (!(outcome.response instanceof TLRPC.messages_Messages)) {
                throw unexpectedResponse(outcome.response);
            }
            if (((TLRPC.messages_Messages) outcome.response).messages.isEmpty()) {
                return;
            }
            sleepReadback("Clear-history readback was interrupted");
        }
        JsonObject details = peerJson(peer);
        details.addProperty("operation_id", operationId);
        throw new McpException("READBACK_FAILED",
                "Conversation history was not empty in messages.getHistory readback", true, details);
    }

    private TLRPC.InputNotifyPeer inputNotifyPeer(PeerRef peer, long topicId) {
        if (topicId != 0 && peer.dialogId != UserConfig.getInstance(peer.account).getClientUserId()) {
            TLRPC.TL_inputNotifyForumTopic topic = new TLRPC.TL_inputNotifyForumTopic();
            topic.peer = peer.inputPeer;
            topic.top_msg_id = (int) topicId;
            return topic;
        }
        if (ChatObject.isCommunity(peer.account, peer.dialogId)) {
            TLRPC.TL_inputNotifyCommunity community = new TLRPC.TL_inputNotifyCommunity();
            community.community = MessagesController.getInstance(peer.account)
                    .getInputChannel(-peer.dialogId);
            return community;
        }
        TLRPC.TL_inputNotifyPeer input = new TLRPC.TL_inputNotifyPeer();
        input.peer = peer.inputPeer;
        return input;
    }

    private TLRPC.PeerNotifySettings fetchPeerNotifySettings(
            int account, PeerRef peer, long topicId) throws McpException {
        TL_account.getNotifySettings request = new TL_account.getNotifySettings();
        request.peer = inputNotifyPeer(peer, topicId);
        RequestOutcome outcome = request(account, request);
        if (!(outcome.response instanceof TLRPC.PeerNotifySettings)) {
            throw unexpectedResponse(outcome.response);
        }
        return (TLRPC.PeerNotifySettings) outcome.response;
    }

    private TLRPC.PeerNotifySettings waitForMuteState(
            int account, PeerRef peer, long topicId, boolean muted) throws McpException {
        TLRPC.PeerNotifySettings last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            last = fetchPeerNotifySettings(account, peer, topicId);
            if (isNotifySettingsMuted(account, last) == muted) return last;
            sleepReadback("Notification-setting readback was interrupted");
        }
        JsonObject details = peerJson(peer);
        details.addProperty("topic_id", topicId);
        details.addProperty("expected_muted", muted);
        details.addProperty("actual_muted", isNotifySettingsMuted(account, last));
        throw new McpException("READBACK_FAILED",
                "Peer notification state did not match account.getNotifySettings", true, details);
    }

    private static JsonObject peerReadbackArguments(int account, PeerRef peer) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("account", account);
        arguments.addProperty("peer",
                canonicalPeer(MessagesController.getInstance(account), peer.dialogId));
        return arguments;
    }

    private static JsonObject accountArguments(int account) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("account", account);
        return arguments;
    }

    private static void sleepReadback(String interruptedMessage) throws McpException {
        try {
            Thread.sleep(250);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("INTERRUPTED", interruptedMessage, true, null);
        }
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

    private int parseScheduleAt(int account, String schedule) throws McpException {
        if (schedule == null || schedule.isEmpty()) return 0;
        long epoch;
        try {
            epoch = Instant.parse(schedule).getEpochSecond();
        } catch (Throwable error) {
            throw new McpException("INVALID_ARGUMENT",
                    "schedule_at must be an ISO-8601 UTC time", false, null);
        }
        if (epoch <= ConnectionsManager.getInstance(account).getCurrentTime()
                || epoch > Integer.MAX_VALUE) {
            throw new McpException("INVALID_ARGUMENT",
                    "schedule_at must be a future representable time", false, null);
        }
        return (int) epoch;
    }

    private int parseFutureInstant(int account, String value, String argument)
            throws McpException {
        long epoch;
        try {
            epoch = Instant.parse(value).getEpochSecond();
        } catch (Throwable error) {
            throw new McpException("INVALID_ARGUMENT",
                    argument + " must be an ISO-8601 UTC time", false, null);
        }
        if (epoch <= ConnectionsManager.getInstance(account).getCurrentTime()
                || epoch > Integer.MAX_VALUE) {
            throw new McpException("INVALID_ARGUMENT",
                    argument + " must be a future representable time", false, null);
        }
        return (int) epoch;
    }

    private SharedPreferences stagedFilePreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                STAGED_FILE_PREFS, Context.MODE_PRIVATE);
    }

    private SharedPreferences uploadSessionPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                UPLOAD_SESSION_PREFS, Context.MODE_PRIVATE);
    }

    private String stagedFileReference(String digest, String name, String mimeType) {
        return "f_" + sha256Hex(referenceSecret + ":file:"
                + digest + ":" + name + ":" + mimeType);
    }

    private static JsonObject uploadRefArguments(String uploadReference) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("upload_ref", uploadReference);
        return arguments;
    }

    private String requiredUploadReference(JsonObject args) throws McpException {
        String reference = requiredString(args, "upload_ref", 66, 66);
        if (!reference.matches("u_[0-9a-f]{64}")) {
            invalid("upload_ref must be returned by telegram.file.upload_begin");
        }
        return reference;
    }

    private File uploadPartFile(String uploadReference) throws McpException {
        if (uploadReference == null || !uploadReference.matches("u_[0-9a-f]{64}")) {
            invalid("upload_ref is invalid");
        }
        return new File(stagingDirectory(), ".upload-"
                + uploadReference.substring(2) + ".part");
    }

    private UploadSession parseUploadSession(String reference, String raw)
            throws McpException {
        try {
            JsonObject metadata = JsonParser.parseString(raw).getAsJsonObject();
            if (!reference.equals(metadata.get("upload_ref").getAsString())
                    || !metadata.get("final_file_ref").getAsString()
                    .matches("f_[0-9a-f]{64}")) {
                throw new McpException("UPLOAD_STATE_CORRUPT",
                        "Upload-session metadata reference is invalid", false, null);
            }
            File part = uploadPartFile(reference);
            if (!part.getName().equals(metadata.get("part_name").getAsString())) {
                throw new McpException("UPLOAD_STATE_CORRUPT",
                        "Upload-session part name is invalid", false, null);
            }
            return new UploadSession(reference, metadata, part);
        } catch (McpException error) {
            throw error;
        } catch (Throwable error) {
            throw new McpException("UPLOAD_STATE_CORRUPT",
                    "Upload-session metadata is corrupt", false, null);
        }
    }

    private UploadSession requireUploadSession(
            String reference, boolean requirePart) throws McpException {
        String raw = uploadSessionPreferences().getString(reference, null);
        if (raw == null) {
            throw new McpException("STALE_REFERENCE",
                    "upload_ref is not present in the private upload catalog",
                    false, null);
        }
        UploadSession session = parseUploadSession(reference, raw);
        if (requirePart && !"active".equals(uploadSessionState(session))) {
            throw new McpException("PRECONDITION_FAILED",
                    "Only an active upload session accepts chunks", false,
                    uploadSessionData(session, uploadFinalExists(session)));
        }
        if (requirePart && (!session.part.exists() || !session.part.isFile())) {
            throw new McpException("UPLOAD_STATE_CORRUPT",
                    "Upload part is missing", false,
                    uploadSessionData(session, false));
        }
        long totalSize = session.metadata.get("total_size").getAsLong();
        if (session.part.exists() && (!session.part.isFile()
                || session.part.length() > totalSize)) {
            throw new McpException("UPLOAD_STATE_CORRUPT",
                    "Upload part has an invalid length", false,
                    uploadSessionData(session, false));
        }
        return session;
    }

    private static void requireUploadIdentity(
            JsonObject metadata,
            String name,
            String mimeType,
            long totalSize,
            String digest,
            String finalReference) throws McpException {
        if (!name.equals(metadata.get("name").getAsString())
                || !mimeType.equals(metadata.get("mime_type").getAsString())
                || totalSize != metadata.get("total_size").getAsLong()
                || !digest.equals(metadata.get("sha256").getAsString())
                || !finalReference.equals(
                metadata.get("final_file_ref").getAsString())) {
            throw new McpException("UPLOAD_CONFLICT",
                    "upload_ref is already bound to different immutable metadata",
                    false, null);
        }
    }

    private JsonObject uploadSessionData(UploadSession session, boolean complete) {
        JsonObject data = uploadSessionData(session.reference, session.part,
                session.metadata.get("name").getAsString(),
                session.metadata.get("mime_type").getAsString(),
                session.metadata.get("total_size").getAsLong(),
                session.metadata.get("sha256").getAsString(),
                session.metadata.get("final_file_ref").getAsString(), complete);
        String storedState = uploadSessionState(session);
        String visibleState = storedState;
        if (complete && !"cancelled".equals(storedState)) {
            visibleState = "complete";
        } else if (!complete && "complete".equals(storedState)) {
            visibleState = "stale_complete";
        }
        data.addProperty("state", visibleState);
        data.addProperty("session_state", storedState);
        data.addProperty("final_present", complete);
        if (session.metadata.has("created_at")) {
            data.addProperty("created_at",
                    session.metadata.get("created_at").getAsLong());
        }
        if (session.metadata.has("completed_at")) {
            data.addProperty("completed_at",
                    session.metadata.get("completed_at").getAsLong());
        }
        return data;
    }

    private static JsonObject uploadSessionData(
            String reference,
            File part,
            String name,
            String mimeType,
            long totalSize,
            String digest,
            String finalReference,
            boolean complete) {
        JsonObject data = new JsonObject();
        long partBytes = part != null && part.exists() ? part.length() : 0;
        long received = complete ? totalSize : partBytes;
        data.addProperty("upload_ref", reference);
        data.addProperty("name", name);
        data.addProperty("mime_type", mimeType);
        data.addProperty("total_size", totalSize);
        data.addProperty("received_bytes", received);
        data.addProperty("remaining_bytes", Math.max(0, totalSize - received));
        data.addProperty("part_bytes", partBytes);
        data.addProperty("cleanup_pending", complete && partBytes > 0);
        data.addProperty("sha256", digest);
        data.addProperty("final_file_ref", finalReference);
        data.addProperty("complete", complete);
        data.addProperty("state", complete ? "complete" : "active");
        data.addProperty("session_state", complete ? "complete" : "active");
        data.addProperty("final_present", complete);
        data.addProperty("max_chunk_bytes", MAX_UPLOAD_CHUNK_BYTES);
        data.addProperty("source", "app_private_chunk_upload");
        return data;
    }

    private static String uploadSessionState(UploadSession session) {
        return session.metadata.has("state")
                ? session.metadata.get("state").getAsString() : "active";
    }

    private boolean persistCompletedUploadTombstone(
            String reference,
            String name,
            String mimeType,
            long totalSize,
            String digest,
            String finalReference) throws McpException {
        SharedPreferences preferences = uploadSessionPreferences();
        String raw = preferences.getString(reference, null);
        JsonObject metadata;
        if (raw != null) {
            UploadSession existing = parseUploadSession(reference, raw);
            requireUploadIdentity(existing.metadata, name, mimeType,
                    totalSize, digest, finalReference);
            metadata = existing.metadata.deepCopy();
        } else {
            metadata = new JsonObject();
            metadata.addProperty("upload_ref", reference);
            metadata.addProperty("name", name);
            metadata.addProperty("mime_type", mimeType);
            metadata.addProperty("total_size", totalSize);
            metadata.addProperty("sha256", digest);
            metadata.addProperty("final_file_ref", finalReference);
            metadata.addProperty("part_name", uploadPartFile(reference).getName());
            metadata.addProperty("created_at", System.currentTimeMillis());
        }
        metadata.addProperty("state", "complete");
        metadata.addProperty("completed_at", System.currentTimeMillis());
        if (!preferences.edit().putString(reference, metadata.toString()).commit()) {
            throw new McpException("PERSISTENCE_FAILED",
                    "Could not persist the completed upload tombstone",
                    true, uploadSessionData(reference, uploadPartFile(reference),
                    name, mimeType, totalSize, digest, finalReference, true));
        }
        File part = uploadPartFile(reference);
        return part.exists() && !part.delete();
    }

    private boolean markUploadTerminal(UploadSession session, String state) {
        JsonObject terminal = session.metadata.deepCopy();
        terminal.addProperty("state", state);
        terminal.addProperty("completed_at", System.currentTimeMillis());
        boolean persisted = uploadSessionPreferences().edit()
                .putString(session.reference, terminal.toString()).commit();
        if (persisted) {
            session.metadata.addProperty("state", state);
            session.metadata.addProperty("completed_at",
                    terminal.get("completed_at").getAsLong());
        }
        return persisted;
    }

    private boolean uploadFinalExists(UploadSession session) throws McpException {
        try {
            requireStagedFile(session.metadata.get("final_file_ref").getAsString());
            return true;
        } catch (McpException error) {
            if ("STALE_REFERENCE".equals(error.code)) return false;
            throw error;
        }
    }

    private static McpException uploadOffsetConflict(long requested, long current) {
        JsonObject details = new JsonObject();
        details.addProperty("requested_offset", requested);
        details.addProperty("current_offset", current);
        return new McpException("UPLOAD_OFFSET_CONFLICT",
                "Upload offset must match the current durable file length",
                false, details);
    }

    private static boolean truncateUploadPart(File part, long length) {
        try (RandomAccessFile file = new RandomAccessFile(part, "rw")) {
            file.setLength(length);
            file.getFD().sync();
            return file.length() == length;
        } catch (Throwable error) {
            FileLog.e(error);
            return false;
        }
    }

    private static String sha256FileRange(File file, long offset, int length)
            throws McpException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (offset < 0 || length < 0 || offset + length > input.length()) {
                throw new IOException("Requested digest range is outside the file");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.seek(offset);
            byte[] buffer = new byte[Math.min(64 * 1024, Math.max(1, length))];
            int remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("Unexpected end of upload part");
                digest.update(buffer, 0, count);
                remaining -= count;
            }
            return hex(digest.digest());
        } catch (Exception error) {
            throw new McpException("FILE_IO_ERROR",
                    "Could not verify the upload chunk from disk", true, null);
        }
    }

    private static String sha256File(File file) throws McpException {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } catch (Exception error) {
            throw new McpException("FILE_IO_ERROR",
                    "Could not compute the staged-file SHA-256", true, null);
        }
    }

    private File stagingDirectory() throws McpException {
        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(),
                "mcp-staging");
        if ((!directory.exists() && !directory.mkdirs())
                || !directory.isDirectory()) {
            throw new McpException("FILE_IO_ERROR",
                    "Could not create the app-private MCP staging directory", true, null);
        }
        return directory;
    }

    private static String safeFileName(String raw) throws McpException {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 255
                || value.contains("/") || value.contains("\\")
                || value.indexOf('\0') >= 0 || value.contains("\r") || value.contains("\n")
                || ".".equals(value) || "..".equals(value)) {
            invalid("name must be a simple file name without path separators");
        }
        return value;
    }

    private static String safeExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return ".bin";
        String extension = name.substring(dot + 1);
        if (!extension.matches("[A-Za-z0-9]{1,10}")) return ".bin";
        return "." + extension.toLowerCase(Locale.ROOT);
    }

    private JsonObject stageBytes(byte[] bytes, String name, String mimeType, String source)
            throws McpException {
        String digest = sha256Hex(bytes);
        String reference = stagedFileReference(digest, name, mimeType);
        String storedName = reference + safeExtension(name);
        File target = new File(stagingDirectory(), storedName);
        boolean createdNow = false;
        if (!target.exists()) {
            File partial = new File(stagingDirectory(), storedName + ".part");
            try (FileOutputStream output = new FileOutputStream(partial, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            } catch (IOException error) {
                partial.delete();
                throw new McpException("FILE_IO_ERROR",
                        "Could not persist staged file", true, null);
            }
            if (!partial.renameTo(target)) {
                partial.delete();
                throw new McpException("FILE_IO_ERROR",
                        "Could not atomically commit staged file", true, null);
            }
            createdNow = true;
        }
        if (target.length() != bytes.length) {
            throw new McpException("FILE_INTEGRITY_ERROR",
                    "Staged file length does not match decoded content", false, null);
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("file_ref", reference);
        metadata.addProperty("name", name);
        metadata.addProperty("mime_type", mimeType);
        metadata.addProperty("size", bytes.length);
        metadata.addProperty("sha256", digest);
        metadata.addProperty("created_at", System.currentTimeMillis());
        metadata.addProperty("source", source);
        metadata.addProperty("stored_name", storedName);
        if (!stagedFilePreferences().edit()
                .putString(reference, metadata.toString()).commit()) {
            boolean rollbackComplete = !createdNow || target.delete();
            JsonObject details = new JsonObject();
            details.addProperty("file_ref", reference);
            details.addProperty("created_now", createdNow);
            details.addProperty("file_preserved", target.exists());
            throw new McpException(
                    rollbackComplete ? "PERSISTENCE_FAILED" : "OUTCOME_UNKNOWN",
                    rollbackComplete
                            ? "Could not persist staged-file metadata"
                            : "Metadata persistence failed and the new staged file "
                                    + "could not be rolled back",
                    rollbackComplete, details);
        }
        return metadata;
    }

    private JsonObject stageExistingFile(
            File source, String name, String mimeType, String provenance) throws McpException {
        name = safeFileName(name);
        long size = source.length();
        if (size <= 0 || size > MAX_STAGED_FILE_BYTES) {
            JsonObject details = new JsonObject();
            details.addProperty("size", size);
            details.addProperty("max_file_bytes", MAX_STAGED_FILE_BYTES);
            throw new McpException("FILE_SIZE_LIMIT",
                    "Attachment cannot be copied into the bounded MCP staging area",
                    false, details);
        }
        String digest = sha256File(source);
        String reference = stagedFileReference(digest, name, mimeType);
        String storedName = reference + safeExtension(name);
        File target = new File(stagingDirectory(), storedName);
        boolean createdNow = false;
        if (target.exists()) {
            if (!target.isFile() || target.length() != size
                    || !digest.equals(sha256File(target))) {
                throw new McpException("FILE_INTEGRITY_ERROR",
                        "Existing staged attachment conflicts with the source digest",
                        false, null);
            }
        } else {
            File partial = new File(stagingDirectory(), storedName + ".copy-"
                    + UUID.randomUUID() + ".part");
            long copied = 0;
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(partial, false)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.flush();
                output.getFD().sync();
            } catch (IOException error) {
                partial.delete();
                throw new McpException("FILE_IO_ERROR",
                        "Could not stream the Telegram attachment into private staging",
                        true, null);
            }
            if (copied != size || partial.length() != size
                    || !digest.equals(sha256File(partial))) {
                partial.delete();
                throw new McpException("FILE_INTEGRITY_ERROR",
                        "Streamed attachment failed exact size or digest verification",
                        false, null);
            }
            if (!partial.renameTo(target)) {
                partial.delete();
                throw new McpException("FILE_IO_ERROR",
                        "Could not atomically commit the streamed attachment",
                        true, null);
            }
            createdNow = true;
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("file_ref", reference);
        metadata.addProperty("name", name);
        metadata.addProperty("mime_type", mimeType);
        metadata.addProperty("size", size);
        metadata.addProperty("sha256", digest);
        metadata.addProperty("created_at", System.currentTimeMillis());
        metadata.addProperty("source", provenance);
        metadata.addProperty("stored_name", storedName);
        if (!stagedFilePreferences().edit()
                .putString(reference, metadata.toString()).commit()) {
            boolean rolledBack = !createdNow || target.delete();
            throw new McpException(
                    rolledBack ? "PERSISTENCE_FAILED" : "OUTCOME_UNKNOWN",
                    "Could not persist streamed staged-file metadata",
                    rolledBack, stagedFileJson(metadata));
        }
        verifyStagedFileDigest(metadata);
        return metadata;
    }

    private File stagedFileFromMetadata(JsonObject metadata) throws McpException {
        if (metadata == null || !metadata.has("stored_name")) {
            throw new McpException("STALE_REFERENCE",
                    "Staged-file metadata is missing", false, null);
        }
        try {
            File directory = stagingDirectory().getCanonicalFile();
            File file = new File(directory,
                    metadata.get("stored_name").getAsString()).getCanonicalFile();
            if (!directory.equals(file.getParentFile())) {
                throw new McpException("FILE_REFERENCE_INVALID",
                        "Staged-file metadata escaped the private allowlist", false, null);
            }
            return file;
        } catch (IOException error) {
            throw new McpException("FILE_IO_ERROR",
                    "Could not resolve staged file", true, null);
        }
    }

    private StagedFile requireStagedFile(String reference) throws McpException {
        if (reference == null || !reference.matches("f_[0-9a-f]{64}")) {
            invalid("file_ref must be a reference returned by telegram.file.put_base64");
        }
        String raw = stagedFilePreferences().getString(reference, null);
        if (raw == null) {
            throw new McpException("STALE_REFERENCE",
                    "file_ref is not present in the private staging catalog", false, null);
        }
        try {
            JsonObject metadata = JsonParser.parseString(raw).getAsJsonObject();
            if (!reference.equals(metadata.get("file_ref").getAsString())) {
                throw new McpException("FILE_INTEGRITY_ERROR",
                        "Staged-file reference does not match metadata", false, null);
            }
            File file = stagedFileFromMetadata(metadata);
            if (!file.exists() || !file.isFile()
                    || file.length() != metadata.get("size").getAsLong()) {
                throw new McpException("STALE_REFERENCE",
                        "Staged file is missing or its size changed", false,
                        stagedFileJson(metadata));
            }
            return new StagedFile(file, metadata);
        } catch (McpException error) {
            throw error;
        } catch (Throwable error) {
            throw new McpException("FILE_INTEGRITY_ERROR",
                    "Staged-file metadata is corrupt", false, null);
        }
    }

    private void verifyStagedFileDigest(JsonObject metadata) throws McpException {
        StagedFile staged = requireStagedFile(metadata.get("file_ref").getAsString());
        String expected = metadata.get("sha256").getAsString();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (FileInputStream input = new FileInputStream(staged.file)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
        } catch (Exception error) {
            throw new McpException("FILE_IO_ERROR",
                    "Could not independently verify the staged file digest",
                    true, null);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) {
            actual.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        if (!expected.equals(actual.toString())) {
            throw new McpException("FILE_INTEGRITY_ERROR",
                    "Independent staged-file digest readback did not match metadata",
                    false, stagedFileJson(metadata));
        }
    }

    private void assertStagedFileAbsent(String reference) throws McpException {
        try {
            requireStagedFile(reference);
        } catch (McpException error) {
            if ("STALE_REFERENCE".equals(error.code)) return;
            throw error;
        }
        throw new McpException("READBACK_FAILED",
                "Deleted staged file remained addressable", true,
                fileRefArguments(reference));
    }

    private TLRPC.InputFile uploadStagedFile(int account, StagedFile staged)
            throws McpException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TLRPC.InputFile> uploaded = new AtomicReference<>();
        FileLoader.getInstance(account).uploadFile(
                staged.file.getAbsolutePath(), value -> {
                    uploaded.set(value);
                    latch.countDown();
                });
        boolean completed;
        try {
            completed = latch.await(MEDIA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpException("INTERRUPTED",
                    "Telegram file upload was interrupted", true,
                    stagedFileJson(staged.metadata));
        }
        if (!completed) {
            throw new McpException("UPLOAD_TIMEOUT",
                    "Telegram did not finish the staged-file upload in time",
                    true, stagedFileJson(staged.metadata));
        }
        if (uploaded.get() == null) {
            throw new McpException("UPLOAD_FAILED",
                    "Telegram rejected the staged-file upload", true,
                    stagedFileJson(staged.metadata));
        }
        return uploaded.get();
    }

    private static JsonObject stagedFileJson(JsonObject metadata) {
        JsonObject result = metadata.deepCopy();
        result.remove("stored_name");
        result.addProperty("scope", "app_private_mcp_staging");
        return result;
    }

    private static JsonObject fileRefArguments(String reference) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("file_ref", reference);
        return arguments;
    }

    private File downloadMessageAttachment(int account, TLRPC.Message message)
            throws McpException {
        MessageObject messageObject = new MessageObject(account, message, false, true);
        TLRPC.Document document = MessageObject.getDocument(message);
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        String expectedName;
        TLRPC.PhotoSize photoSize = null;
        if (document != null) {
            expectedName = FileLoader.getAttachFileName(document);
        } else if (media instanceof TLRPC.TL_messageMediaPhoto
                && media.photo != null) {
            photoSize = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes,
                    AndroidUtilities.getPhotoSize(true), false, null, true);
            if (photoSize == null) {
                throw new McpException("NO_DOWNLOADABLE_ATTACHMENT",
                        "Photo message has no downloadable size", false,
                        messageJson(account, message));
            }
            expectedName = FileLoader.getAttachFileName(photoSize);
        } else {
            throw new McpException("NO_DOWNLOADABLE_ATTACHMENT",
                    "Message has no supported downloadable attachment", false,
                    messageJson(account, message));
        }
        NotificationCenter.NotificationCenterDelegate observer =
                (eventId, eventAccount, eventArgs) -> {
                    if (eventAccount != account || eventArgs.length == 0) return;
                    try {
                        if (!expectedName.equals(String.valueOf(eventArgs[0]))) return;
                        if (eventId == NotificationCenter.fileLoaded) {
                            latch.countDown();
                        } else if (eventId == NotificationCenter.fileLoadFailed) {
                            failure.compareAndSet(null, "Telegram file loader reported failure");
                            latch.countDown();
                        }
                    } catch (Throwable ignore) {
                        // Ignore unrelated loader events.
                    }
                };
        TLRPC.PhotoSize finalPhotoSize = photoSize;
        uiCall(() -> {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(observer, NotificationCenter.fileLoaded);
            center.addObserver(observer, NotificationCenter.fileLoadFailed);
            if (document != null) {
                FileLoader.getInstance(account).loadFile(document, messageObject,
                        FileLoader.PRIORITY_HIGH, 0);
            } else {
                ImageLocation location = ImageLocation.getForObject(
                        finalPhotoSize, media.photo);
                FileLoader.getInstance(account).loadFile(location, messageObject,
                        "jpg", FileLoader.PRIORITY_HIGH, 0);
            }
            return null;
        });
        try {
            if (!latch.await(MEDIA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new McpException("DOWNLOAD_TIMEOUT",
                        "Telegram attachment download did not finish before timeout",
                        true, messageJson(account, message));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("INTERRUPTED",
                    "Attachment download was interrupted", true, null);
        } finally {
            uiCall(() -> {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.removeObserver(observer, NotificationCenter.fileLoaded);
                center.removeObserver(observer, NotificationCenter.fileLoadFailed);
                return null;
            });
        }
        File file = FileLoader.getInstance(account).getPathToMessage(message);
        if (failure.get() != null || !file.exists() || file.length() == 0) {
            throw new McpException("DOWNLOAD_FAILED",
                    failure.get() == null ? "Downloaded attachment file is missing" : failure.get(),
                    true, messageJson(account, message));
        }
        return file;
    }

    private JsonObject idempotencyReplay(
            int account,
            String operation,
            String key,
            String payloadHash) throws McpException {
        if (CURRENT_IDEMPOTENCY.get() != null) {
            throw new McpException("IDEMPOTENCY_STATE_ERROR",
                    "A nested idempotent operation is not allowed", false, null);
        }
        String slot = idempotencySlot(account, operation, key);
        synchronized (IDEMPOTENCY_LOCK) {
            SharedPreferences preferences = idempotencyPreferences();
            String raw = preferences.getString(slot, null);
            if (raw != null) {
                JsonObject stored;
                try {
                    stored = JsonParser.parseString(raw).getAsJsonObject();
                } catch (Throwable error) {
                    throw new McpException("IDEMPOTENCY_STATE_CORRUPT",
                            "Persisted idempotency state is unreadable; do not retry this key",
                            false, null);
                }
                String storedHash = stored.has("payload_hash")
                        ? stored.get("payload_hash").getAsString() : "";
                if (!payloadHash.equals(storedHash)) {
                    throw new McpException("IDEMPOTENCY_CONFLICT",
                            "idempotency_key was already used with different arguments",
                            false, null);
                }
                String state = stored.has("state")
                        ? stored.get("state").getAsString()
                        : stored.has("result") ? "complete" : "unknown";
                if ("complete".equals(state) && stored.has("result")
                        && stored.get("result").isJsonObject()) {
                    return stored.getAsJsonObject("result").deepCopy();
                }
                JsonObject details = stored.has("details")
                        && stored.get("details").isJsonObject()
                        ? stored.getAsJsonObject("details").deepCopy()
                        : idempotencyUnknownDetails(operation);
                details.addProperty("operation", operation);
                details.addProperty("read_before_retry", true);
                boolean inProgress = "pending".equals(state)
                        && payloadHash.equals(ACTIVE_IDEMPOTENCY.get(slot));
                details.addProperty("outcome", inProgress ? "in_progress" : "unknown");
                throw new McpException(
                        inProgress ? "IDEMPOTENCY_IN_PROGRESS" : "OUTCOME_UNKNOWN",
                        inProgress
                                ? "An identical idempotent operation is still running"
                                : "A previous attempt may have taken effect; read the target state before using a new key",
                        inProgress, details);
            }

            String activeHash = ACTIVE_IDEMPOTENCY.putIfAbsent(slot, payloadHash);
            if (activeHash != null) {
                if (!payloadHash.equals(activeHash)) {
                    throw new McpException("IDEMPOTENCY_CONFLICT",
                            "idempotency_key is active with different arguments",
                            false, null);
                }
                JsonObject details = idempotencyUnknownDetails(operation);
                details.addProperty("outcome", "in_progress");
                throw new McpException("IDEMPOTENCY_IN_PROGRESS",
                        "An identical idempotent operation is still running",
                        true, details);
            }

            try {
                ensureIdempotencyCapacity(preferences);
            } catch (McpException error) {
                ACTIVE_IDEMPOTENCY.remove(slot, payloadHash);
                throw error;
            }
            JsonObject pending = new JsonObject();
            pending.addProperty("state", "pending");
            pending.addProperty("payload_hash", payloadHash);
            pending.addProperty("created_at", System.currentTimeMillis());
            pending.add("details", idempotencyUnknownDetails(operation));
            if (!preferences.edit().putString(slot, pending.toString()).commit()) {
                ACTIVE_IDEMPOTENCY.remove(slot, payloadHash);
                throw new McpException("PERSISTENCE_FAILED",
                        "Could not reserve the idempotency key before execution",
                        true, null);
            }
            CURRENT_IDEMPOTENCY.set(new IdempotencyContext(
                    account, operation, key, payloadHash, slot));
            return null;
        }
    }

    private void storeIdempotency(
            int account,
            String operation,
            String key,
            String payloadHash,
            JsonObject result) throws McpException {
        String slot = idempotencySlot(account, operation, key);
        IdempotencyContext context = CURRENT_IDEMPOTENCY.get();
        if (context == null || !slot.equals(context.slot)
                || !payloadHash.equals(context.payloadHash)) {
            throw new McpException("IDEMPOTENCY_STATE_ERROR",
                    "Idempotency completion did not match its durable reservation",
                    false, null);
        }
        synchronized (IDEMPOTENCY_LOCK) {
            JsonObject stored = new JsonObject();
            stored.addProperty("state", "complete");
            stored.addProperty("payload_hash", payloadHash);
            stored.addProperty("created_at", System.currentTimeMillis());
            stored.add("result", result.deepCopy());
            if (!idempotencyPreferences().edit()
                    .putString(slot, stored.toString()).commit()) {
                JsonObject details = idempotencyUnknownDetails(operation);
                markCurrentIdempotencyUnknown(details);
                throw new McpException("OUTCOME_UNKNOWN",
                        "The operation completed but its idempotency result could not be persisted",
                        false, details);
            }
            ACTIVE_IDEMPOTENCY.remove(slot, payloadHash);
            CURRENT_IDEMPOTENCY.remove();
        }
    }

    void ensureIdempotencyCompletedOnSuccess() throws McpException {
        IdempotencyContext context = CURRENT_IDEMPOTENCY.get();
        if (context == null) return;
        JsonObject details = idempotencyUnknownDetails(context.operation);
        details.addProperty("reason", "handler_returned_without_storing_result");
        markCurrentIdempotencyUnknown(details);
        throw new McpException("OUTCOME_UNKNOWN",
                "The handler returned before durable idempotency completion; read before retrying",
                false, details);
    }

    void finishIdempotencyAfterError(McpException error) {
        IdempotencyContext context = CURRENT_IDEMPOTENCY.get();
        if (context == null) return;
        if (context.effectStarted || idempotencyErrorIsUncertain(error)) {
            JsonObject details = error.details != null && error.details.isJsonObject()
                    ? error.details.getAsJsonObject().deepCopy()
                    : idempotencyUnknownDetails(context.operation);
            details.addProperty("cause_code", error.code);
            markCurrentIdempotencyUnknown(details);
        } else {
            releaseCurrentIdempotencyReservation();
        }
    }

    void finishIdempotencyAfterThrowable(Throwable error) {
        IdempotencyContext context = CURRENT_IDEMPOTENCY.get();
        if (context == null) return;
        JsonObject details = idempotencyUnknownDetails(context.operation);
        details.addProperty("cause_code", "INTERNAL_ERROR");
        markCurrentIdempotencyUnknown(details);
    }

    private static boolean idempotencyErrorIsUncertain(McpException error) {
        if (error == null) return true;
        if (error.details != null && error.details.isJsonObject()) {
            JsonObject details = error.details.getAsJsonObject();
            if (details.has("outcome")
                    && "unknown".equals(details.get("outcome").getAsString())) {
                return true;
            }
        }
        return "OUTCOME_UNKNOWN".equals(error.code)
                || "READBACK_FAILED".equals(error.code)
                || "UNEXPECTED_RESPONSE".equals(error.code)
                || "EMPTY_RESPONSE".equals(error.code)
                || "UI_TIMEOUT".equals(error.code)
                || "STAGE_TIMEOUT".equals(error.code)
                || "SEND_FAILED".equals(error.code)
                || "FORWARD_FAILED".equals(error.code);
    }

    private static void markIdempotentEffectStarted() {
        IdempotencyContext context = CURRENT_IDEMPOTENCY.get();
        if (context != null) context.effectStarted = true;
    }

    private static void markCurrentIdempotencyUnknown(JsonObject suppliedDetails) {
        IdempotencyContext context = CURRENT_IDEMPOTENCY.get();
        if (context == null) return;
        synchronized (IDEMPOTENCY_LOCK) {
            JsonObject unknown = new JsonObject();
            unknown.addProperty("state", "unknown");
            unknown.addProperty("payload_hash", context.payloadHash);
            unknown.addProperty("created_at", System.currentTimeMillis());
            JsonObject details = suppliedDetails == null
                    ? idempotencyUnknownDetails(context.operation)
                    : suppliedDetails.deepCopy();
            details.addProperty("operation", context.operation);
            details.addProperty("outcome", "unknown");
            details.addProperty("read_before_retry", true);
            unknown.add("details", details);
            // The initial pending reservation is already durable. If this commit
            // fails, leaving that record still prevents an unsafe retry.
            idempotencyPreferences().edit()
                    .putString(context.slot, unknown.toString()).commit();
            ACTIVE_IDEMPOTENCY.remove(context.slot, context.payloadHash);
            CURRENT_IDEMPOTENCY.remove();
        }
    }

    private static void releaseCurrentIdempotencyReservation() {
        IdempotencyContext context = CURRENT_IDEMPOTENCY.get();
        if (context == null) return;
        synchronized (IDEMPOTENCY_LOCK) {
            SharedPreferences preferences = idempotencyPreferences();
            String raw = preferences.getString(context.slot, null);
            if (raw != null) {
                try {
                    JsonObject stored = JsonParser.parseString(raw).getAsJsonObject();
                    String state = stored.has("state")
                            ? stored.get("state").getAsString() : "complete";
                    String hash = stored.has("payload_hash")
                            ? stored.get("payload_hash").getAsString() : "";
                    if ("pending".equals(state) && context.payloadHash.equals(hash)) {
                        preferences.edit().remove(context.slot).commit();
                    }
                } catch (Throwable ignore) {
                    // Preserve unreadable state so retry remains fail-closed.
                }
            }
            ACTIVE_IDEMPOTENCY.remove(context.slot, context.payloadHash);
            CURRENT_IDEMPOTENCY.remove();
        }
    }

    private static void ensureIdempotencyCapacity(SharedPreferences preferences)
            throws McpException {
        if (preferences.getAll().size() < MAX_IDEMPOTENCY_ENTRIES) return;
        String oldestCompleted = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            try {
                JsonObject value = JsonParser.parseString(
                        String.valueOf(entry.getValue())).getAsJsonObject();
                String state = value.has("state")
                        ? value.get("state").getAsString()
                        : value.has("result") ? "complete" : "unknown";
                if (!"complete".equals(state)) continue;
                long createdAt = value.has("created_at")
                        ? value.get("created_at").getAsLong() : 0;
                if (createdAt < oldestTime) {
                    oldestTime = createdAt;
                    oldestCompleted = entry.getKey();
                }
            } catch (Throwable ignore) {
                // Corrupt state is deliberately retained to stay fail-closed.
            }
        }
        if (oldestCompleted == null
                || !preferences.edit().remove(oldestCompleted).commit()) {
            throw new McpException("IDEMPOTENCY_CAPACITY_EXHAUSTED",
                    "No completed idempotency record can be safely evicted",
                    false, null);
        }
    }

    private static SharedPreferences idempotencyPreferences() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(IDEMPOTENCY_PREFS, Context.MODE_PRIVATE);
    }

    private static String idempotencySlot(
            int account, String operation, String key) {
        return operation + ":" + sha256Hex(account + ":" + key);
    }

    private static JsonObject idempotencyUnknownDetails(String operation) {
        JsonObject details = new JsonObject();
        details.addProperty("operation", operation);
        details.addProperty("outcome", "unknown");
        details.addProperty("read_before_retry", true);
        return details;
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

    private static <T> T stageCall(Callable<T> callable) throws McpException {
        if (Thread.currentThread() == Utilities.stageQueue) {
            try { return callable.call(); }
            catch (McpException error) { throw error; }
            catch (Throwable error) {
                throw new McpException("STAGE_OPERATION_FAILED",
                        error.getMessage(), true, null);
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Utilities.stageQueue.postRunnable(() -> {
            try { result.set(callable.call()); }
            catch (Throwable throwable) { error.set(throwable); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new McpException("STAGE_TIMEOUT",
                        "Telegram stage-queue operation timed out", true, null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("INTERRUPTED", "Telegram operation was interrupted", true, null);
        }
        if (error.get() instanceof McpException) throw (McpException) error.get();
        if (error.get() != null) throw new McpException("STAGE_OPERATION_FAILED",
                error.get().getMessage() == null ? "Stage operation failed" : error.get().getMessage(),
                true, null);
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
        if (error.text != null && "PREMIUM_ACCOUNT_REQUIRED".equals(error.text)) {
            return new McpException("PREMIUM_REQUIRED",
                    "This Telegram operation requires a Premium account", false, details);
        }
        return new McpException("TELEGRAM_ERROR",
                error.text == null ? "Telegram rejected the request" : error.text, retryable, details);
    }

    private static boolean telegramErrorIs(McpException error, String expected) {
        if (error == null || !"TELEGRAM_ERROR".equals(error.code)
                || !(error.details instanceof JsonObject)) {
            return false;
        }
        JsonObject details = (JsonObject) error.details;
        return details.has("telegram_error")
                && expected.equals(details.get("telegram_error").getAsString());
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

    private static long requiredPositiveLongString(JsonObject args, String key)
            throws McpException {
        String value = requiredString(args, key, 1, 20);
        if (!value.matches("[1-9][0-9]{0,18}")) {
            invalid(key + " must be a positive 64-bit integer encoded as a string");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            invalid(key + " exceeds the positive 64-bit integer range");
            return 0;
        }
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

    private static boolean requiredBoolean(JsonObject args, String key) throws McpException {
        if (!args.has(key) || !args.get(key).isJsonPrimitive()
                || !args.get(key).getAsJsonPrimitive().isBoolean()) {
            invalid(key + " must be boolean");
        }
        return args.get(key).getAsBoolean();
    }

    private static int requiredInt(JsonObject args, String key, int min, int max) throws McpException {
        if (!args.has(key)) invalid(key + " must be an integer");
        int value = exactInt(args.get(key), key);
        if (value < min || value > max) invalid(key + " must be " + min + ".." + max);
        return value;
    }

    private static int optionalInt(JsonObject args, String key, int fallback, int min, int max) throws McpException {
        return args.has(key) ? requiredInt(args, key, min, max) : fallback;
    }

    private static long optionalLong(JsonObject args, String key, long fallback, long min, long max) throws McpException {
        if (!args.has(key)) return fallback;
        long value;
        try {
            if (!args.get(key).isJsonPrimitive()
                    || !args.get(key).getAsJsonPrimitive().isNumber()) {
                invalid(key + " must be an integer");
            }
            value = args.get(key).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            invalid(key + " must be an integer");
            return fallback;
        }
        if (value < min || value > max) invalid(key + " is out of range");
        return value;
    }

    private static long requiredLong(
            JsonObject args, String key, long min, long max) throws McpException {
        if (!args.has(key)) invalid(key + " must be an integer");
        return optionalLong(args, key, 0, min, max);
    }

    private static double requiredDouble(
            JsonObject args, String key, double min, double max) throws McpException {
        if (!args.has(key) || !args.get(key).isJsonPrimitive()
                || !args.get(key).getAsJsonPrimitive().isNumber()) {
            invalid(key + " must be a number");
        }
        try {
            double value = args.get(key).getAsDouble();
            if (!Double.isFinite(value) || value < min || value > max) {
                invalid(key + " is out of range");
            }
            return value;
        } catch (Throwable error) {
            invalid(key + " must be a finite number");
            return 0;
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
            int number = exactInt(value, key + " item");
            if (number < min || number > max) invalid(key + " contains an out-of-range value");
            result.add(number);
        }
        return result;
    }

    private static int exactInt(JsonElement value, String label) throws McpException {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            invalid(label + " must be an integer");
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            invalid(label + " must be an exact 32-bit integer");
            return 0;
        }
    }

    private static void ensureOnlyKeys(JsonObject value, String... allowed)
            throws McpException {
        Set<String> keys = new HashSet<>();
        Collections.addAll(keys, allowed);
        for (String key : value.keySet()) {
            if (!keys.contains(key)) invalid("Unexpected object field: " + key);
        }
    }

    private static JsonArray intArray(ArrayList<Integer> values) {
        JsonArray result = new JsonArray();
        for (Integer value : values) result.add(value);
        return result;
    }

    private static ArrayList<Integer> jsonIntList(JsonArray values) {
        ArrayList<Integer> result = new ArrayList<>();
        for (JsonElement value : values) result.add(value.getAsInt());
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

    /**
     * Hashes idempotent business arguments using a typed, named JSON structure.
     * Delimiter-joined strings and List.toString() are intentionally forbidden:
     * both admit collisions between distinct user inputs.
     */
    private static String idempotencyPayloadHash(
            String schema, Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Idempotency payload fields must be name/value pairs");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("_schema", schema);
        Set<String> names = new HashSet<>();
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            Object rawName = nameValuePairs[index];
            if (!(rawName instanceof String) || ((String) rawName).isEmpty()) {
                throw new IllegalArgumentException(
                        "Idempotency payload field names must be non-empty strings");
            }
            String name = (String) rawName;
            if (!names.add(name)) {
                throw new IllegalArgumentException(
                        "Duplicate idempotency payload field: " + name);
            }
            payload.add(name, idempotencyJsonValue(nameValuePairs[index + 1]));
        }
        return sha256Hex(payload.toString());
    }

    /**
     * Canonical request hash used when replay must precede volatile lookups.
     * Transport/account/confirmation fields do not change the business payload.
     */
    private static String idempotencyArgumentsHash(String schema, JsonObject arguments) {
        JsonObject payload = arguments.deepCopy();
        payload.remove("account");
        payload.remove("idempotency_key");
        payload.remove("_confirm");
        return idempotencyPayloadHash(schema,
                "arguments", canonicalJson(payload));
    }

    private static JsonElement canonicalJson(JsonElement value) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonPrimitive()) return value.deepCopy();
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) {
                result.add(canonicalJson(item));
            }
            return result;
        }
        JsonObject result = new JsonObject();
        ArrayList<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry
                : value.getAsJsonObject().entrySet()) {
            names.add(entry.getKey());
        }
        Collections.sort(names);
        for (String name : names) {
            result.add(name, canonicalJson(value.getAsJsonObject().get(name)));
        }
        return result;
    }

    private static JsonElement idempotencyJsonValue(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement) {
            return ((JsonElement) value).deepCopy();
        }
        if (value instanceof String || value instanceof Character) {
            return new JsonPrimitive(value.toString());
        }
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Number) return new JsonPrimitive((Number) value);
        if (value instanceof Iterable<?>) {
            JsonArray result = new JsonArray();
            for (Object item : (Iterable<?>) value) {
                result.add(idempotencyJsonValue(item));
            }
            return result;
        }
        throw new IllegalArgumentException(
                "Unsupported idempotency payload value type: "
                        + value.getClass().getName());
    }

    private static String sha256Hex(String value) {
        byte[] digest = sha256(value);
        return hex(digest);
    }

    private static String sha256Hex(byte[] value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Throwable error) {
            throw new IllegalStateException(error);
        }
    }

    private static String hex(byte[] digest) {
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

    private static final class IdempotencyContext {
        final int account;
        final String operation;
        final String key;
        final String payloadHash;
        final String slot;
        boolean effectStarted;

        IdempotencyContext(
                int account,
                String operation,
                String key,
                String payloadHash,
                String slot) {
            this.account = account;
            this.operation = operation;
            this.key = key;
            this.payloadHash = payloadHash;
            this.slot = slot;
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

    private interface SentMessageMatcher {
        boolean matches(TLRPC.Message message);
    }

    private static final class SendResult {
        final int messageId;
        final TLRPC.Message message;

        SendResult(int messageId, TLRPC.Message message) {
            this.messageId = messageId;
            this.message = message;
        }
    }

    private static final class FormattedText {
        final String text;
        final ArrayList<TLRPC.MessageEntity> entities;

        FormattedText(String text, ArrayList<TLRPC.MessageEntity> entities) {
            this.text = text;
            this.entities = entities;
        }
    }

    private static final class StagedFile {
        final File file;
        final JsonObject metadata;

        StagedFile(File file, JsonObject metadata) {
            this.file = file;
            this.metadata = metadata;
        }
    }

    private static final class UploadSession {
        final String reference;
        final JsonObject metadata;
        final File part;

        UploadSession(String reference, JsonObject metadata, File part) {
            this.reference = reference;
            this.metadata = metadata;
            this.part = part;
        }
    }

    private static final class StorageTarget {
        final File root;
        final int mode;

        StorageTarget(File root, int mode) {
            this.root = root;
            this.mode = mode;
        }
    }

    private static final class StorageSize {
        long bytes;
        int files;
        int failures;
    }

    private static final class CacheDeleteStats {
        long bytesDeleted;
        int filesDeleted;
        int failures;
        int stagedReferencesCleared;
        int uploadSessionsCleared;
    }

    private static final class ChatMemberState {
        final boolean present;
        final boolean banned;
        final JsonObject data;
        final TLRPC.ChannelParticipant channelParticipant;
        final TLRPC.ChatParticipant chatParticipant;
        final String source;

        ChatMemberState(
                boolean present,
                boolean banned,
                JsonObject data,
                TLRPC.ChannelParticipant channelParticipant,
                TLRPC.ChatParticipant chatParticipant,
                String source) {
            this.present = present;
            this.banned = banned;
            this.data = data;
            this.channelParticipant = channelParticipant;
            this.chatParticipant = chatParticipant;
            this.source = source;
        }
    }

}
