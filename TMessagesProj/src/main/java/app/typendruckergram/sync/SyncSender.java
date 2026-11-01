package app.typendruckergram.sync;

import android.text.TextUtils;

import com.radolyn.ayugram.database.entities.AyuMessageBase;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what AyuGram just saved into encrypted events in the outbox.
 * Called from AyuMessagesController on its background queue.
 */
public final class SyncSender {

    private static final long DELETE_BATCH_DELAY_MS = 200;

    private static final class PendingDelete {
        final int accountId;
        final long account;
        final long dialog;
        final List<Integer> msgIds = new ArrayList<>();
        final List<AyuMessageBase> messages = new ArrayList<>();

        PendingDelete(int accountId, long account, long dialog) {
            this.accountId = accountId;
            this.account = account;
            this.dialog = dialog;
        }
    }

    private static final Map<String, PendingDelete> pendingDeletes = new HashMap<>();

    private SyncSender() {
    }

    /** previous: the version that was replaced, as AyuGram stored it. */
    public static void onEdited(int accountId, AyuMessageBase previous, int newEditDate) {
        if (!SyncStore.isEnabled()) {
            return;
        }
        try {
            long account = UserConfig.getInstance(accountId).getClientUserId();
            List<String> media = new ArrayList<>();
            SyncEvents.Snapshot snapshot = snapshotWithMedia(previous, media);
            enqueue(SyncEvents.Event.edit(account, snapshot, newEditDate), media);
        } catch (Exception e) {
            FileLog.e("typsync: edit", e);
        }
    }

    public static void onDeleted(int accountId, AyuMessageBase message) {
        if (!SyncStore.isEnabled()) {
            return;
        }
        long account = UserConfig.getInstance(accountId).getClientUserId();
        long dialog = isChannel(accountId, message.dialogId) ? message.dialogId : 0;
        String key = account + ":" + dialog;
        synchronized (pendingDeletes) {
            PendingDelete pending = pendingDeletes.get(key);
            if (pending == null) {
                pending = new PendingDelete(accountId, account, dialog);
                pendingDeletes.put(key, pending);
                Utilities.globalQueue.postRunnable(() -> flushDeletes(key), DELETE_BATCH_DELAY_MS);
            }
            pending.msgIds.add(message.messageId);
            pending.messages.add(message);
        }
    }

    private static void flushDeletes(String key) {
        PendingDelete pending;
        synchronized (pendingDeletes) {
            pending = pendingDeletes.remove(key);
        }
        if (pending == null) {
            return;
        }
        try {
            List<String> media = new ArrayList<>();
            List<SyncEvents.Snapshot> snapshots = new ArrayList<>();
            for (AyuMessageBase m : pending.messages) {
                snapshots.add(snapshotWithMedia(m, media));
            }
            int now = (int) (System.currentTimeMillis() / 1000);
            enqueue(SyncEvents.Event.delete(pending.account, pending.dialog, pending.msgIds, snapshots, now), media);
        } catch (Exception e) {
            FileLog.e("typsync: delete", e);
        }
    }

    /** Media within the limit gets a blob id; its file path goes to mediaPaths as "blobId|path". */
    private static SyncEvents.Snapshot snapshotWithMedia(AyuMessageBase m, List<String> mediaPaths) {
        SyncEvents.Snapshot s = SyncAyuBridge.toSnapshot(m);
        if (s.media != null && !TextUtils.isEmpty(m.mediaPath)) {
            File file = new File(m.mediaPath);
            if (file.isFile() && file.length() > 0 && file.length() <= SyncStore.getMediaLimit()) {
                s.media.blobId = SyncCrypto.toHex(SyncCrypto.randomBytes(16));
                mediaPaths.add(s.media.blobId + "|" + file.getAbsolutePath());
            }
        }
        return s;
    }

    private static void enqueue(SyncEvents.Event event, List<String> media) {
        byte[] ownerPublic = SyncStore.getOwnerPublic();
        if (ownerPublic == null) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        SyncDao dao = SyncStore.dao();
        // Media goes first as separate items without an event, so the event never points at a missing blob.
        for (String item : media) {
            int bar = item.indexOf('|');
            SyncOutboxItem blob = new SyncOutboxItem();
            blob.account = event.account;
            blob.blobId = item.substring(0, bar);
            blob.mediaPath = item.substring(bar + 1);
            blob.createdAt = now;
            dao.addOutbox(blob);
        }
        SyncOutboxItem out = new SyncOutboxItem();
        out.account = event.account;
        out.relayKey = SyncEvents.relayKey(event);
        out.blob = SyncCrypto.seal(ownerPublic, SyncEvents.encode(event));
        out.createdAt = now;
        dao.addOutbox(out);
        // Remember own events so they are never applied back if the relay echoes them.
        for (String key : SyncEvents.dedupKeys(event)) {
            SyncStore.markSeen(key);
        }
        SyncWorker.getInstance().wakeUp();
    }

    private static boolean isChannel(int accountId, long dialogId) {
        if (dialogId >= 0) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(-dialogId);
        return chat != null && ChatObject.isChannel(chat);
    }
}
