package app.typendruckergram.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Plaintext events of TypendruckerGram Sync, see docs/sync/protocol.md.
 *
 * Encoding is canonical JSON (sorted keys, no spaces, non-ASCII as is), so
 * the bytes match the desktop and server encoders exactly.
 */
public final class SyncEvents {

    public static final int PROTOCOL_VERSION = 1;

    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_EDIT = "edit";
    public static final String TYPE_DELETE = "delete";

    private SyncEvents() {
    }

    public static final class Media {
        public String blobId = "";
        public String mime = "";
        public String kind = "";
        public String name = "";
        public long size;
    }

    public static final class Entity {
        public String type;
        public int offset;
        public int length;
        public String url; // textUrl
        public Long userId; // mentionName
        public Long documentId; // customEmoji
        public String language; // pre
    }

    /** One version of one message. */
    public static final class Snapshot {
        public long dialog;
        public int msgId;
        public long fromId;
        public int date;
        public int editDate;
        public long topic;
        public String text = "";
        public List<Entity> entities = new ArrayList<>();
        public int replyTo;
        public long groupedId;
        public Media media;
    }

    public static final class Event {
        public String type;
        public long account;
        public Snapshot snapshot; // message
        public Snapshot previous; // edit: the version that was replaced
        public int newEditDate; // edit
        public long dialog; // delete: channel/supergroup, 0 otherwise
        public List<Integer> msgIds = new ArrayList<>(); // delete
        public List<Snapshot> snapshots = new ArrayList<>(); // delete: known contents
        public int date; // delete

        public static Event edit(long account, Snapshot previous, int newEditDate) {
            Event e = new Event();
            e.type = TYPE_EDIT;
            e.account = account;
            e.previous = previous;
            e.newEditDate = newEditDate;
            return e;
        }

        public static Event delete(long account, long dialog, List<Integer> msgIds, List<Snapshot> snapshots, int date) {
            Event e = new Event();
            e.type = TYPE_DELETE;
            e.account = account;
            e.dialog = dialog;
            e.msgIds = msgIds;
            e.snapshots = snapshots;
            e.date = date;
            return e;
        }
    }

    // ---- encoding ----

    public static byte[] encode(Event event) {
        Map<String, Object> object = new TreeMap<>();
        object.put("v", PROTOCOL_VERSION);
        object.put("type", event.type);
        object.put("account", event.account);
        switch (event.type) {
            case TYPE_MESSAGE:
                object.put("snapshot", snapshotMap(event.snapshot));
                break;
            case TYPE_EDIT:
                object.put("previous", snapshotMap(event.previous));
                object.put("newEditDate", event.newEditDate);
                break;
            case TYPE_DELETE:
                List<Object> snapshots = new ArrayList<>();
                for (Snapshot s : event.snapshots) {
                    snapshots.add(snapshotMap(s));
                }
                object.put("dialog", event.dialog);
                object.put("msgIds", new ArrayList<Object>(event.msgIds));
                object.put("snapshots", snapshots);
                object.put("date", event.date);
                break;
            default:
                throw new IllegalArgumentException("unknown event type " + event.type);
        }
        StringBuilder out = new StringBuilder();
        writeValue(out, object);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> snapshotMap(Snapshot s) {
        Map<String, Object> m = new TreeMap<>();
        m.put("dialog", s.dialog);
        m.put("msgId", s.msgId);
        m.put("fromId", s.fromId);
        m.put("date", s.date);
        m.put("editDate", s.editDate);
        m.put("topic", s.topic);
        m.put("text", s.text == null ? "" : s.text);
        List<Object> entities = new ArrayList<>();
        for (Entity e : s.entities) {
            Map<String, Object> em = new TreeMap<>();
            em.put("type", e.type);
            em.put("offset", e.offset);
            em.put("length", e.length);
            if (e.url != null) em.put("url", e.url);
            if (e.userId != null) em.put("userId", e.userId);
            if (e.documentId != null) em.put("documentId", e.documentId);
            if (e.language != null) em.put("language", e.language);
            entities.add(em);
        }
        m.put("entities", entities);
        m.put("replyTo", s.replyTo);
        m.put("groupedId", s.groupedId);
        if (s.media != null) {
            Map<String, Object> mm = new TreeMap<>();
            mm.put("blobId", s.media.blobId);
            mm.put("mime", s.media.mime);
            mm.put("kind", s.media.kind);
            mm.put("name", s.media.name);
            mm.put("size", s.media.size);
            m.put("media", mm);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder out, Object value) {
        if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, e.getKey());
                out.append(':');
                writeValue(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else {
            throw new IllegalArgumentException("unsupported JSON value " + value);
        }
    }

    // Same escaping as Python json.dumps(ensure_ascii=False).
    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // ---- decoding ----

    /** Returns null for anything that is not a valid v1 event. */
    public static Event decode(byte[] json) {
        try {
            JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();
            Event e = new Event();
            e.type = str(o, "type");
            e.account = num(o, "account");
            if (e.account == 0) {
                return null;
            }
            switch (e.type) {
                case TYPE_MESSAGE:
                    e.snapshot = snapshot(o.get("snapshot"));
                    return e.snapshot == null ? null : e;
                case TYPE_EDIT:
                    e.previous = snapshot(o.get("previous"));
                    e.newEditDate = (int) num(o, "newEditDate");
                    return e.previous == null ? null : e;
                case TYPE_DELETE:
                    e.dialog = num(o, "dialog");
                    e.date = (int) num(o, "date");
                    if (o.has("msgIds") && o.get("msgIds").isJsonArray()) {
                        for (JsonElement id : o.getAsJsonArray("msgIds")) {
                            e.msgIds.add(id.getAsInt());
                        }
                    }
                    if (o.has("snapshots") && o.get("snapshots").isJsonArray()) {
                        for (JsonElement item : o.getAsJsonArray("snapshots")) {
                            Snapshot s = snapshot(item);
                            if (s != null) {
                                e.snapshots.add(s);
                            }
                        }
                    }
                    return e.msgIds.isEmpty() ? null : e;
                default:
                    return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Snapshot snapshot(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject o = element.getAsJsonObject();
        if (!o.has("dialog") || !o.has("msgId")) {
            return null;
        }
        Snapshot s = new Snapshot();
        s.dialog = num(o, "dialog");
        s.msgId = (int) num(o, "msgId");
        s.fromId = num(o, "fromId");
        s.date = (int) num(o, "date");
        s.editDate = (int) num(o, "editDate");
        s.topic = num(o, "topic");
        s.text = str(o, "text");
        s.replyTo = (int) num(o, "replyTo");
        s.groupedId = num(o, "groupedId");
        if (o.has("entities") && o.get("entities").isJsonArray()) {
            JsonArray array = o.getAsJsonArray("entities");
            for (JsonElement item : array) {
                if (!item.isJsonObject()) continue;
                JsonObject eo = item.getAsJsonObject();
                Entity e = new Entity();
                e.type = str(eo, "type");
                e.offset = (int) num(eo, "offset");
                e.length = (int) num(eo, "length");
                if (eo.has("url")) e.url = str(eo, "url");
                if (eo.has("userId")) e.userId = num(eo, "userId");
                if (eo.has("documentId")) e.documentId = num(eo, "documentId");
                if (eo.has("language")) e.language = str(eo, "language");
                s.entities.add(e);
            }
        }
        if (o.has("media") && o.get("media").isJsonObject()) {
            JsonObject mo = o.getAsJsonObject("media");
            Media m = new Media();
            m.blobId = str(mo, "blobId");
            m.mime = str(mo, "mime");
            m.kind = str(mo, "kind");
            m.name = str(mo, "name");
            m.size = num(mo, "size");
            s.media = m;
        }
        return s;
    }

    private static long num(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? 0 : v.getAsLong();
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? "" : v.getAsString();
    }

    // ---- dedup ----

    public static String dedupKey(long account, long dialog, long msgId, long editDate, String type) {
        return SyncCrypto.sha256Hex(account + "|" + dialog + "|" + msgId + "|" + editDate + "|" + type);
    }

    /** Per-message keys, used to apply events idempotently. */
    public static List<String> dedupKeys(Event e) {
        List<String> keys = new ArrayList<>();
        switch (e.type) {
            case TYPE_MESSAGE:
                keys.add(dedupKey(e.account, e.snapshot.dialog, e.snapshot.msgId, e.snapshot.editDate, e.type));
                break;
            case TYPE_EDIT:
                keys.add(dedupKey(e.account, e.previous.dialog, e.previous.msgId, e.newEditDate, e.type));
                break;
            case TYPE_DELETE:
                for (int id : new TreeSet<>(e.msgIds)) {
                    keys.add(dedupKey(e.account, e.dialog, id, 0, e.type));
                }
                break;
        }
        return keys;
    }

    /** One key per event, used by the relay to drop duplicates. */
    public static String relayKey(Event e) {
        List<String> keys = dedupKeys(e);
        return keys.size() == 1 ? keys.get(0) : SyncCrypto.sha256Hex(String.join("|", keys));
    }
}
