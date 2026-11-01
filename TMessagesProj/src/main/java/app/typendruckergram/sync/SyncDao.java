package app.typendruckergram.sync;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SyncDao {
    @Insert
    long addOutbox(SyncOutboxItem item);

    @Query("SELECT * FROM outbox ORDER BY id LIMIT :limit")
    List<SyncOutboxItem> getOutbox(int limit);

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    void removeOutbox(List<Long> ids);

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id IN (:ids)")
    void bumpAttempts(List<Long> ids);

    @Query("UPDATE outbox SET mediaPath = NULL WHERE id = :id")
    void markMediaUploaded(long id);

    @Query("SELECT COUNT(*) FROM outbox")
    int outboxSize();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long addSeen(SyncSeenKey key);

    @Query("SELECT COUNT(*) FROM seen WHERE dedupKey = :key")
    int isSeen(String key);
}
