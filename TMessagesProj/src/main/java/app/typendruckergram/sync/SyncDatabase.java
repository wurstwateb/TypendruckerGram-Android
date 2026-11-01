package app.typendruckergram.sync;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {SyncOutboxItem.class, SyncSeenKey.class}, version = 1, exportSchema = false)
public abstract class SyncDatabase extends RoomDatabase {
    public abstract SyncDao dao();
}
