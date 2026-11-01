package app.typendruckergram.sync;

import android.text.TextUtils;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.AyuMessageBase;
import com.radolyn.ayugram.proprietary.AyuMessageUtils;

import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

/** Converts between AyuGram's saved messages and protocol snapshots. */
public final class SyncAyuBridge {

    private SyncAyuBridge() {
    }

    public static SyncEvents.Snapshot toSnapshot(AyuMessageBase m) {
        SyncEvents.Snapshot s = new SyncEvents.Snapshot();
        s.dialog = m.dialogId;
        s.msgId = m.messageId;
        s.fromId = m.fromId;
        s.date = m.date;
        s.editDate = m.editDate;
        s.topic = Math.max(m.topicId, 0);
        s.text = m.text == null ? "" : m.text;
        s.replyTo = m.replyMessageId;
        s.groupedId = m.groupedId;
        for (TLRPC.MessageEntity e : deserializeEntities(m.textEntities)) {
            SyncEvents.Entity out = toProtocol(e);
            if (out != null) {
                s.entities.add(out);
            }
        }
        s.media = mediaOf(m);
        return s;
    }

    /** Fills the protocol part of an Ayu record; media files are attached by SyncMedia. */
    public static void fromSnapshot(long userId, SyncEvents.Snapshot s, AyuMessageBase out) {
        out.userId = userId;
        out.dialogId = s.dialog;
        out.peerId = s.dialog;
        out.messageId = s.msgId;
        out.fromId = s.fromId;
        out.date = s.date;
        out.editDate = s.editDate;
        out.topicId = s.topic;
        out.text = s.text;
        out.replyMessageId = s.replyTo;
        out.groupedId = s.groupedId;
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        for (SyncEvents.Entity e : s.entities) {
            TLRPC.MessageEntity tl = toTl(e);
            if (tl != null) {
                entities.add(tl);
            }
        }
        out.textEntities = AyuMessageUtils.serializeMultiple(entities);
        out.documentType = AyuConstants.DOCUMENT_TYPE_NONE;
        if (s.media != null) {
            out.mimeType = s.media.mime;
        }
    }

    private static ArrayList<TLRPC.MessageEntity> deserializeEntities(byte[] data) {
        return AyuMessageUtils.deserializeMultiple(data,
                (NativeByteBuffer b) -> TLRPC.MessageEntity.TLdeserialize(b, b.readInt32(false), false));
    }

    private static SyncEvents.Media mediaOf(AyuMessageBase m) {
        if (m.documentType == AyuConstants.DOCUMENT_TYPE_NONE || m.documentType == AyuConstants.DOCUMENT_TYPE_WEBPAGE) {
            return null;
        }
        SyncEvents.Media media = new SyncEvents.Media();
        media.mime = m.mimeType == null ? "" : m.mimeType;
        media.kind = kindOf(m);
        if (!TextUtils.isEmpty(m.mediaPath)) {
            File file = new File(m.mediaPath);
            media.name = file.getName();
            media.size = file.length();
        }
        return media;
    }

    private static String kindOf(AyuMessageBase m) {
        if (m.documentType == AyuConstants.DOCUMENT_TYPE_PHOTO) {
            return "photo";
        }
        if (m.documentType == AyuConstants.DOCUMENT_TYPE_STICKER) {
            return "sticker";
        }
        String mime = m.mimeType == null ? "" : m.mimeType;
        if (mime.equals("video/mp4") && m.documentAttributesSerialized != null) {
            for (TLRPC.DocumentAttribute a : AyuMessageUtils.deserializeMultiple(m.documentAttributesSerialized,
                    (NativeByteBuffer b) -> TLRPC.DocumentAttribute.TLdeserialize(b, b.readInt32(false), false))) {
                if (a instanceof TLRPC.TL_documentAttributeAnimated) {
                    return "animation";
                }
                if (a instanceof TLRPC.TL_documentAttributeVideo && a.round_message) {
                    return "round";
                }
            }
        }
        if (mime.startsWith("video/")) return "video";
        if (mime.equals("audio/ogg")) return "voice";
        if (mime.startsWith("audio/")) return "audio";
        if (mime.startsWith("image/")) return "photo";
        return "file";
    }

    private static SyncEvents.Entity entity(String type, TLRPC.MessageEntity e) {
        SyncEvents.Entity out = new SyncEvents.Entity();
        out.type = type;
        out.offset = e.offset;
        out.length = e.length;
        return out;
    }

    private static SyncEvents.Entity toProtocol(TLRPC.MessageEntity e) {
        if (e instanceof TLRPC.TL_messageEntityBold) return entity("bold", e);
        if (e instanceof TLRPC.TL_messageEntityItalic) return entity("italic", e);
        if (e instanceof TLRPC.TL_messageEntityUnderline) return entity("underline", e);
        if (e instanceof TLRPC.TL_messageEntityStrike) return entity("strike", e);
        if (e instanceof TLRPC.TL_messageEntitySpoiler) return entity("spoiler", e);
        if (e instanceof TLRPC.TL_messageEntityCode) return entity("code", e);
        if (e instanceof TLRPC.TL_messageEntityUrl) return entity("url", e);
        if (e instanceof TLRPC.TL_messageEntityMention) return entity("mention", e);
        if (e instanceof TLRPC.TL_messageEntityHashtag) return entity("hashtag", e);
        if (e instanceof TLRPC.TL_messageEntityCashtag) return entity("cashtag", e);
        if (e instanceof TLRPC.TL_messageEntityBotCommand) return entity("botCommand", e);
        if (e instanceof TLRPC.TL_messageEntityEmail) return entity("email", e);
        if (e instanceof TLRPC.TL_messageEntityPhone) return entity("phone", e);
        if (e instanceof TLRPC.TL_messageEntityBlockquote) return entity("blockquote", e);
        if (e instanceof TLRPC.TL_messageEntityPre) {
            SyncEvents.Entity out = entity("pre", e);
            if (!TextUtils.isEmpty(e.language)) out.language = e.language;
            return out;
        }
        if (e instanceof TLRPC.TL_messageEntityTextUrl) {
            SyncEvents.Entity out = entity("textUrl", e);
            out.url = e.url == null ? "" : e.url;
            return out;
        }
        if (e instanceof TLRPC.TL_messageEntityMentionName) {
            SyncEvents.Entity out = entity("mentionName", e);
            out.userId = ((TLRPC.TL_messageEntityMentionName) e).user_id;
            return out;
        }
        if (e instanceof TLRPC.TL_messageEntityCustomEmoji) {
            SyncEvents.Entity out = entity("customEmoji", e);
            out.documentId = ((TLRPC.TL_messageEntityCustomEmoji) e).document_id;
            return out;
        }
        return null;
    }

    private static TLRPC.MessageEntity toTl(SyncEvents.Entity e) {
        TLRPC.MessageEntity out;
        switch (e.type == null ? "" : e.type) {
            case "bold": out = new TLRPC.TL_messageEntityBold(); break;
            case "italic": out = new TLRPC.TL_messageEntityItalic(); break;
            case "underline": out = new TLRPC.TL_messageEntityUnderline(); break;
            case "strike": out = new TLRPC.TL_messageEntityStrike(); break;
            case "spoiler": out = new TLRPC.TL_messageEntitySpoiler(); break;
            case "code": out = new TLRPC.TL_messageEntityCode(); break;
            case "url": out = new TLRPC.TL_messageEntityUrl(); break;
            case "mention": out = new TLRPC.TL_messageEntityMention(); break;
            case "hashtag": out = new TLRPC.TL_messageEntityHashtag(); break;
            case "cashtag": out = new TLRPC.TL_messageEntityCashtag(); break;
            case "botCommand": out = new TLRPC.TL_messageEntityBotCommand(); break;
            case "email": out = new TLRPC.TL_messageEntityEmail(); break;
            case "phone": out = new TLRPC.TL_messageEntityPhone(); break;
            case "blockquote": out = new TLRPC.TL_messageEntityBlockquote(); break;
            case "pre":
                out = new TLRPC.TL_messageEntityPre();
                out.language = e.language == null ? "" : e.language;
                break;
            case "textUrl":
                out = new TLRPC.TL_messageEntityTextUrl();
                out.url = e.url == null ? "" : e.url;
                break;
            case "mentionName": {
                TLRPC.TL_messageEntityMentionName m = new TLRPC.TL_messageEntityMentionName();
                m.user_id = e.userId == null ? 0 : e.userId;
                out = m;
                break;
            }
            case "customEmoji": {
                TLRPC.TL_messageEntityCustomEmoji m = new TLRPC.TL_messageEntityCustomEmoji();
                m.document_id = e.documentId == null ? 0 : e.documentId;
                out = m;
                break;
            }
            default:
                return null;
        }
        out.offset = e.offset;
        out.length = e.length;
        return out;
    }
}
