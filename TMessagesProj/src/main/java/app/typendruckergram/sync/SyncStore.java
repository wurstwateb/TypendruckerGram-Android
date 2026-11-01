package app.typendruckergram.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.room.Room;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Local state of TypendruckerGram Sync: the outgoing queue and applied keys in
 * typsync.db (separate from AyuGram's database, so its migrations stay
 * untouched), keys and settings in encrypted preferences.
 */
public final class SyncStore {

    public static final long DEFAULT_MEDIA_LIMIT = 20L * 1024 * 1024;
    public static final long MAX_MEDIA_LIMIT = 50L * 1024 * 1024;
    public static final String DEFAULT_RELAY_URL = "https://unionofjackbox.tokenized.name/typsync";

    private static volatile SyncDao dao;
    private static volatile SharedPreferences prefs;

    private SyncStore() {
    }

    public static SyncDao dao() {
        if (dao == null) {
            synchronized (SyncStore.class) {
                if (dao == null) {
                    dao = Room.databaseBuilder(ApplicationLoader.applicationContext, SyncDatabase.class, "typsync.db")
                            .fallbackToDestructiveMigration()
                            .build()
                            .dao();
                }
            }
        }
        return dao;
    }

    /** Returns true if the key was not applied before; records it either way. */
    public static boolean markSeen(String dedupKey) {
        SyncSeenKey key = new SyncSeenKey();
        key.dedupKey = dedupKey;
        key.appliedAt = System.currentTimeMillis() / 1000;
        return dao().addSeen(key) != -1;
    }

    public static boolean isSeen(String dedupKey) {
        return dao().isSeen(dedupKey) > 0;
    }

    // ---- state ----

    private static SharedPreferences prefs() {
        if (prefs == null) {
            synchronized (SyncStore.class) {
                if (prefs == null) {
                    Context context = ApplicationLoader.applicationContext;
                    try {
                        MasterKey key = new MasterKey.Builder(context)
                                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                .build();
                        prefs = EncryptedSharedPreferences.create(context, "typsync_state", key,
                                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
                    } catch (Exception e) {
                        // Keystore broken on some devices; the app sandbox still protects the file.
                        FileLog.e("typsync: encrypted prefs unavailable", e);
                        prefs = context.getSharedPreferences("typsync_state_plain", Context.MODE_PRIVATE);
                    }
                }
            }
        }
        return prefs;
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", false) && hasCredentials();
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean("enabled", enabled).apply();
    }

    public static boolean hasCredentials() {
        return getToken() != null && getOwnerPrivate() != null;
    }

    public static String getRelayUrl() {
        return prefs().getString("relayUrl", DEFAULT_RELAY_URL);
    }

    public static String getToken() {
        return prefs().getString("token", null);
    }

    public static String getOwnerId() {
        return prefs().getString("ownerId", null);
    }

    public static String getDeviceId() {
        return prefs().getString("deviceId", null);
    }

    public static byte[] getOwnerPrivate() {
        return bytes("ownerPrivate");
    }

    public static byte[] getOwnerPublic() {
        return bytes("ownerPublic");
    }

    public static void saveCredentials(String relayUrl, String ownerId, String deviceId, String token, byte[] ownerPrivate) {
        prefs().edit()
                .putString("relayUrl", relayUrl)
                .putString("ownerId", ownerId)
                .putString("deviceId", deviceId)
                .putString("token", token)
                .putString("ownerPrivate", Base64.encodeToString(ownerPrivate, Base64.NO_WRAP))
                .putString("ownerPublic", Base64.encodeToString(SyncCrypto.publicFromPrivate(ownerPrivate), Base64.NO_WRAP))
                .putLong("cursor", 0)
                .putBoolean("enabled", true)
                .apply();
    }

    public static void clearCredentials() {
        prefs().edit().clear().apply();
    }

    public static long getCursor() {
        return prefs().getLong("cursor", 0);
    }

    public static void setCursor(long cursor) {
        prefs().edit().putLong("cursor", cursor).apply();
    }

    public static long getMediaLimit() {
        return prefs().getLong("mediaLimit", DEFAULT_MEDIA_LIMIT);
    }

    public static void setMediaLimit(long limit) {
        prefs().edit().putLong("mediaLimit", Math.max(0, Math.min(limit, MAX_MEDIA_LIMIT))).apply();
    }

    public static long getLastSyncAt() {
        return prefs().getLong("lastSyncAt", 0);
    }

    public static void setLastSyncAt(long time) {
        prefs().edit().putLong("lastSyncAt", time).apply();
    }

    public static boolean isTrimmed() {
        return prefs().getBoolean("trimmed", false);
    }

    public static void setTrimmed(boolean trimmed) {
        prefs().edit().putBoolean("trimmed", trimmed).apply();
    }

    private static byte[] bytes(String key) {
        String value = prefs().getString(key, null);
        return value == null ? null : Base64.decode(value, Base64.NO_WRAP);
    }
}
