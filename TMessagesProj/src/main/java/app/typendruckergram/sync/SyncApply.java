package app.typendruckergram.sync;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.EditedMessage;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes events from other devices into AyuGram's database. Goes to the DAOs
 * directly, not through AyuMessagesController, so applied events are never sent back.
 */
public final class SyncApply {

    private SyncApply() {
    }

    /** Returns true if the event was handled (applied or deliberately skipped). */
    public static boolean apply(SyncEvents.Event event, SyncClient client) {
        int accountId = accountFor(event.account);
        List<String> keys = SyncEvents.dedupKeys(event);
        if (accountId < 0) {
            // Not an account on this phone: remember it so the cursor moves on.
            for (String key : keys) SyncStore.markSeen(key);
            return true;
        }
        long userId = event.account;
        switch (event.type) {
            case SyncEvents.TYPE_EDIT:
                if (!SyncStore.isSeen(keys.get(0))) {
                    applyEdit(accountId, userId, event);
                    SyncStore.markSeen(keys.get(0));
                }
                return true;
            case SyncEvents.TYPE_DELETE:
                List<Integer> ids = new ArrayList<>(new java.util.TreeSet<>(event.msgIds));
                for (int i = 0; i < ids.size(); i++) {
                    String key = keys.get(i);
                    if (SyncStore.isSeen(key)) continue;
                    SyncEvents.Snapshot snapshot = find(event.snapshots, ids.get(i));
                    if (snapshot != null) {
                        applyDelete(accountId, userId, snapshot, client);
                    }
                    SyncStore.markSeen(key);
                }
                return true;
            case SyncEvents.TYPE_MESSAGE:
                // A message caught while every device was offline: keep it as a deleted-or-not
                // copy only if Telegram no longer has it; for now just record it.
                for (String key : keys) SyncStore.markSeen(key);
                return true;
            default:
                return true;
        }
    }

    private static void applyEdit(int accountId, long userId, SyncEvents.Event event) {
        SyncEvents.Snapshot previous = event.previous;
        for (EditedMessage existing : AyuData.getEditedMessageDao().getAllRevisions(userId, previous.dialog, previous.msgId)) {
            if (existing.editDate == previous.editDate && existing.date == previous.date) {
                return; // already saved on this phone
            }
        }
        EditedMessage revision = new EditedMessage();
        SyncAyuBridge.fromSnapshot(userId, previous, revision);
        revision.entityCreateDate = event.newEditDate;
        AyuData.getEditedMessageDao().insert(revision);
        long dialogId = previous.dialog;
        int msgId = previous.msgId;
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(accountId)
                .postNotificationName(AyuConstants.MESSAGE_EDITED_NOTIFICATION, dialogId, msgId));
    }

    private static void applyDelete(int accountId, long userId, SyncEvents.Snapshot snapshot, SyncClient client) {
        List<Integer> existing = AyuData.getDeletedMessageDao()
                .getExistingMessageIds(userId, snapshot.dialog, Collections.singletonList(snapshot.msgId));
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        DeletedMessage message = new DeletedMessage();
        SyncAyuBridge.fromSnapshot(userId, snapshot, message);
        message.entityCreateDate = (int) (System.currentTimeMillis() / 1000);
        SyncMedia.attach(snapshot, message, client);
        AyuData.getDeletedMessageDao().insert(message);
        long dialogId = snapshot.dialog;
        ArrayList<Integer> ids = new ArrayList<>(Collections.singletonList(snapshot.msgId));
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(accountId)
                .postNotificationName(AyuConstants.MESSAGES_DELETED_NOTIFICATION, dialogId, ids));
    }

    private static SyncEvents.Snapshot find(List<SyncEvents.Snapshot> snapshots, int msgId) {
        for (SyncEvents.Snapshot s : snapshots) {
            if (s.msgId == msgId) return s;
        }
        return null;
    }

    private static int accountFor(long telegramUserId) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && config.getClientUserId() == telegramUserId) {
                return a;
            }
        }
        FileLog.d("typsync: event for account " + telegramUserId + " which is not signed in here");
        return -1;
    }
}
