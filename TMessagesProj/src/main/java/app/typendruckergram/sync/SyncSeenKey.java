package app.typendruckergram.sync;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Dedup key of an event that was already applied. */
@Entity(tableName = "seen")
public class SyncSeenKey {
    @PrimaryKey
    @NonNull
    public String dedupKey = "";
    public long appliedAt;
}
