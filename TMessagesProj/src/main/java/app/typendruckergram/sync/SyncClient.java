package app.typendruckergram.sync;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Blocking client of the relay API (docs/sync/protocol.md, section 6). Call off the UI thread. */
public final class SyncClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType OCTET = MediaType.get("application/octet-stream");

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    /** HTTP error with the relay's {"error": code}. */
    public static final class RelayException extends IOException {
        public final int status;
        public final String code;

        RelayException(int status, String code) {
            super("relay " + status + " " + code);
            this.status = status;
            this.code = code;
        }
    }

    public static final class Credentials {
        public String ownerId;
        public String deviceId;
        public String token;
        public String kind;
        public byte[] payload; // pair/claim only, already decrypted
    }

    public static final class Incoming {
        public long seq;
        public String key;
        public byte[] blob;
        public String from;
    }

    public static final class Page {
        public List<Incoming> events = new ArrayList<>();
        public long cursor;
        public boolean more;
        public boolean trimmed;
    }

    private final String baseUrl;
    private final String token;

    public SyncClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    public static SyncClient fromStore() {
        return new SyncClient(SyncStore.getRelayUrl(), SyncStore.getToken());
    }

    // ---- registration and pairing ----

    public Credentials createOwner(byte[] ownerPublic, String name, String invite) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("publicKey", b64(ownerPublic));
        body.addProperty("name", name);
        body.addProperty("invite", invite == null ? "" : invite);
        return credentials(json("POST", "/v1/owners", body, false));
    }

    /** Offers ownerPrivate to the device that types the code; returns the code. */
    public String startPairing(byte[] ownerPrivate) throws IOException {
        String code = SyncCrypto.newPairCode();
        String normalized = SyncCrypto.normalizePairCode(code);
        JsonObject payload = new JsonObject();
        payload.addProperty("ownerPrivate", b64(ownerPrivate));
        payload.addProperty("relayUrl", baseUrl);
        JsonObject body = new JsonObject();
        body.addProperty("lookupHash", SyncCrypto.pairLookupHash(normalized));
        body.addProperty("kind", "device");
        body.addProperty("payload", b64(SyncCrypto.pairEncrypt(normalized,
                payload.toString().getBytes(StandardCharsets.UTF_8))));
        json("POST", "/v1/pair/start", body, true);
        return code;
    }

    /** Returns null payload if the code does not decrypt it. */
    public Credentials claimPairing(String normalizedCode, String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("lookupHash", SyncCrypto.pairLookupHash(normalizedCode));
        body.addProperty("name", name);
        JsonObject response = json("POST", "/v1/pair/claim", body, false);
        Credentials c = credentials(response);
        c.payload = SyncCrypto.pairDecrypt(normalizedCode, unb64(response.get("payload").getAsString()));
        return c;
    }

    // ---- events and blobs ----

    /** items: relayKey → sealed blob. Returns the number of accepted events. */
    public int postEvents(List<String> keys, List<byte[]> blobs) throws IOException {
        JsonArray events = new JsonArray();
        for (int i = 0; i < keys.size(); i++) {
            JsonObject item = new JsonObject();
            item.addProperty("key", keys.get(i));
            item.addProperty("blob", b64(blobs.get(i)));
            events.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("events", events);
        return json("POST", "/v1/events", body, true).get("accepted").getAsInt();
    }

    public Page getEvents(long after, int limit) throws IOException {
        String path = "/v1/events?limit=" + limit + (after > 0 ? "&after=" + after : "");
        JsonObject o = json("GET", path, null, true);
        Page page = new Page();
        for (JsonElement element : o.getAsJsonArray("events")) {
            JsonObject e = element.getAsJsonObject();
            Incoming in = new Incoming();
            in.seq = e.get("seq").getAsLong();
            in.key = e.get("key").getAsString();
            in.blob = unb64(e.get("blob").getAsString());
            in.from = e.has("from") && !e.get("from").isJsonNull() ? e.get("from").getAsString() : "";
            page.events.add(in);
        }
        page.cursor = o.get("cursor").getAsLong();
        page.more = o.get("more").getAsBoolean();
        page.trimmed = o.has("trimmed") && o.get("trimmed").getAsBoolean();
        return page;
    }

    public void putBlob(String blobId, byte[] sealed) throws IOException {
        Request request = request("/v1/blobs/" + blobId, true).put(RequestBody.create(sealed, OCTET)).build();
        try (Response response = http.newCall(request).execute()) {
            check(response);
        }
    }

    public byte[] getBlob(String blobId) throws IOException {
        Request request = request("/v1/blobs/" + blobId, true).get().build();
        try (Response response = http.newCall(request).execute()) {
            check(response);
            ResponseBody body = response.body();
            return body == null ? new byte[0] : body.bytes();
        }
    }

    public void heartbeat() throws IOException {
        json("POST", "/v1/heartbeat", new JsonObject(), true);
    }

    public JsonObject status() throws IOException {
        return json("GET", "/v1/status", null, true);
    }

    public JsonObject putSettings(JsonObject changes) throws IOException {
        return json("PUT", "/v1/settings", changes, true);
    }

    public void removeDevice(String deviceId) throws IOException {
        json("DELETE", "/v1/devices/" + deviceId, null, true);
    }

    // ---- plumbing ----

    private Request.Builder request(String path, boolean auth) {
        Request.Builder builder = new Request.Builder().url(baseUrl + path);
        if (auth && token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private JsonObject json(String method, String path, JsonObject body, boolean auth) throws IOException {
        RequestBody requestBody = body == null ? null : RequestBody.create(body.toString(), JSON);
        if (requestBody == null && !method.equals("GET") && !method.equals("DELETE")) {
            requestBody = RequestBody.create("{}", JSON);
        }
        Request request = request(path, auth).method(method, requestBody).build();
        try (Response response = http.newCall(request).execute()) {
            check(response);
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            JsonElement parsed = JsonParser.parseString(text.isEmpty() ? "{}" : text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        }
    }

    private static void check(Response response) throws IOException {
        if (response.isSuccessful()) {
            return;
        }
        String code = "http_" + response.code();
        try {
            ResponseBody body = response.body();
            if (body != null) {
                JsonElement parsed = JsonParser.parseString(body.string());
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("error")) {
                    code = parsed.getAsJsonObject().get("error").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
        }
        throw new RelayException(response.code(), code);
    }

    private static Credentials credentials(JsonObject o) {
        Credentials c = new Credentials();
        c.ownerId = o.get("ownerId").getAsString();
        c.deviceId = o.get("deviceId").getAsString();
        c.token = o.get("token").getAsString();
        c.kind = o.has("kind") ? o.get("kind").getAsString() : "device";
        return c;
    }

    static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    static byte[] unb64(String data) {
        return Base64.decode(data, Base64.NO_WRAP);
    }
}
