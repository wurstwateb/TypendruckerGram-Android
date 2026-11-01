package app.typendruckergram.sync;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** An encrypted event waiting to be sent to the relay. */
@Entity(tableName = "outbox")
public class SyncOutboxItem {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long account;
    public String relayKey;
    public byte[] blob;
    public String mediaPath; // plaintext file to upload first, null once uploaded
    public String blobId; // id of mediaPath on the relay
    public long createdAt;
    public int attempts;
}
