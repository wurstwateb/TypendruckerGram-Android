package app.typendruckergram.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.room.Room;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Local state of TypendruckerGram Sync: the outgoing queue and applied keys in
 * typsync.db (separate from AyuGram's database, so its migrations stay
 * untouched), settings in private preferences with the token and owner key
 * wrapped by the Android Keystore.
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
                    prefs = ApplicationLoader.applicationContext.getSharedPreferences("typsync_state", Context.MODE_PRIVATE);
                }
            }
        }
        return prefs;
    }

    // Token and owner key are wrapped with a non-exportable Android Keystore key.
    private static final String KEYSTORE_ALIAS = "typsync_state";

    private static SecretKey keystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String wrap(byte[] secret) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
            byte[] iv = cipher.getIV();
            byte[] sealed = cipher.doFinal(secret);
            byte[] out = new byte[1 + iv.length + sealed.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(sealed, 0, out, 1 + iv.length, sealed.length);
            return "k:" + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            // Keystore broken on some devices; the app sandbox still protects the file.
            FileLog.e("typsync: keystore unavailable", e);
            return "p:" + Base64.encodeToString(secret, Base64.NO_WRAP);
        }
    }

    private static byte[] unwrap(String value) {
        if (value == null) {
            return null;
        }
        try {
            byte[] data = Base64.decode(value.substring(2), Base64.NO_WRAP);
            if (value.startsWith("p:")) {
                return data;
            }
            int ivLength = data[0];
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(),
                    new GCMParameterSpec(128, data, 1, ivLength));
            return cipher.doFinal(data, 1 + ivLength, data.length - 1 - ivLength);
        } catch (Exception e) {
            FileLog.e("typsync: cannot unwrap stored secret", e);
            return null;
        }
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

    // Unwrapped once per process: the Keystore is slow and the worker asks every tick.
    private static volatile String cachedToken;
    private static volatile byte[] cachedOwnerPrivate;

    public static String getToken() {
        if (cachedToken == null) {
            byte[] token = unwrap(prefs().getString("token", null));
            cachedToken = token == null ? null : new String(token, StandardCharsets.UTF_8);
        }
        return cachedToken;
    }

    public static String getOwnerId() {
        return prefs().getString("ownerId", null);
    }

    public static String getDeviceId() {
        return prefs().getString("deviceId", null);
    }

    public static byte[] getOwnerPrivate() {
        if (cachedOwnerPrivate == null) {
            cachedOwnerPrivate = unwrap(prefs().getString("ownerPrivate", null));
        }
        return cachedOwnerPrivate;
    }

    public static byte[] getOwnerPublic() {
        return bytes("ownerPublic");
    }

    public static void saveCredentials(String relayUrl, String ownerId, String deviceId, String token, byte[] ownerPrivate) {
        prefs().edit()
                .putString("relayUrl", relayUrl)
                .putString("ownerId", ownerId)
                .putString("deviceId", deviceId)
                .putString("token", wrap(token.getBytes(StandardCharsets.UTF_8)))
                .putString("ownerPrivate", wrap(ownerPrivate))
                .putString("ownerPublic", Base64.encodeToString(SyncCrypto.publicFromPrivate(ownerPrivate), Base64.NO_WRAP))
                .putLong("cursor", 0)
                .putBoolean("enabled", true)
                .commit();
        cachedToken = token;
        cachedOwnerPrivate = ownerPrivate;
    }

    public static void clearCredentials() {
        prefs().edit().clear().commit();
        cachedToken = null;
        cachedOwnerPrivate = null;
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
