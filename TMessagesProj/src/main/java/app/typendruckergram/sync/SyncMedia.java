package app.typendruckergram.sync;

import android.text.TextUtils;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.entities.AyuMessageBase;
import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Media of synced messages. Outgoing files are sealed with the owner key and
 * uploaded before their event; incoming ones are fetched right away, because the
 * relay keeps blobs for 5 days only.
 */
public final class SyncMedia {

    private SyncMedia() {
    }

    public static void upload(SyncClient client, String blobId, String path) throws IOException {
        File file = new File(path);
        if (!file.isFile() || file.length() > SyncStore.MAX_MEDIA_LIMIT) {
            return; // gone or too large since it was queued; the event still carries the metadata
        }
        byte[] data = new byte[(int) file.length()];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.readFully(data);
        }
        client.putBlob(blobId, SyncCrypto.seal(SyncStore.getOwnerPublic(), data));
    }

    /** Downloads the snapshot's blob into AyuGram's attachments folder and points the record at it. */
    public static void attach(SyncEvents.Snapshot snapshot, AyuMessageBase out, SyncClient client) {
        if (snapshot.media == null || TextUtils.isEmpty(snapshot.media.blobId)) {
            return;
        }
        try {
            byte[] plain = SyncCrypto.open(SyncStore.getOwnerPrivate(), client.getBlob(snapshot.media.blobId));
            if (plain == null) {
                FileLog.e("typsync: media blob " + snapshot.media.blobId + " does not decrypt");
                return;
            }
            File dir = AyuMessagesController.attachmentsPath;
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, fileName(snapshot));
            try (FileOutputStream os = new FileOutputStream(file)) {
                os.write(plain);
            }
            out.mediaPath = file.getAbsolutePath();
            out.mimeType = snapshot.media.mime;
            out.documentType = "photo".equals(snapshot.media.kind)
                    ? AyuConstants.DOCUMENT_TYPE_PHOTO
                    : AyuConstants.DOCUMENT_TYPE_FILE;
        } catch (SyncClient.RelayException e) {
            FileLog.d("typsync: media blob " + snapshot.media.blobId + " is gone (" + e.code + ")");
        } catch (Exception e) {
            FileLog.e("typsync: media download", e);
        }
    }

    private static String fileName(SyncEvents.Snapshot s) {
        String name = s.media.name == null ? "" : s.media.name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String prefix = "sync_" + s.dialog + "_" + s.msgId + "_" + s.editDate;
        if (!name.isEmpty()) {
            return prefix + "_" + name;
        }
        String mime = s.media.mime == null ? "" : s.media.mime;
        String ext = mime.equals("image/jpeg") ? ".jpg"
                : mime.equals("image/png") ? ".png"
                : mime.equals("video/mp4") ? ".mp4"
                : mime.equals("audio/ogg") ? ".ogg"
                : mime.equals("image/webp") ? ".webp"
                : "";
        return prefix + ext;
    }
}
