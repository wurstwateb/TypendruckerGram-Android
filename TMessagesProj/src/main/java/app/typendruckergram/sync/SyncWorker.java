package app.typendruckergram.sync;

import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Background loop of TypendruckerGram Sync while the app is running:
 * outbox every 5 s, pull every 15 s, heartbeat every 60 s, backoff 5 s → 5 min on errors.
 */
public final class SyncWorker {

    private static final long TICK_MS = 5_000;
    private static final long PULL_EVERY_MS = 15_000;
    private static final long HEARTBEAT_EVERY_MS = 60_000;
    private static final long MAX_BACKOFF_MS = 5 * 60_000;
    private static final int BATCH = 100;
    private static final int PAGE = 200;

    private static volatile SyncWorker instance;

    public static SyncWorker getInstance() {
        if (instance == null) {
            synchronized (SyncWorker.class) {
                if (instance == null) {
                    instance = new SyncWorker();
                }
            }
        }
        return instance;
    }

    /** Status line for the settings screen. */
    public interface Listener {
        void onSyncStateChanged();
    }

    private final DispatchQueue queue = new DispatchQueue("typsync");
    private final Runnable tick = this::tick;
    private boolean started;
    private long lastPull;
    private long lastHeartbeat;
    private long backoffMs;
    private long nextAttemptAt;
    private volatile String lastError;
    private volatile Listener listener;

    private SyncWorker() {
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public String getLastError() {
        return lastError;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        queue.postRunnable(tick, 1_000);
    }

    /** Something was queued or settings changed: run soon instead of waiting for the tick. */
    public void wakeUp() {
        start();
        queue.cancelRunnable(tick);
        queue.postRunnable(tick, 300);
    }

    /** Pull now (used by the "sync now" button). */
    public void syncNow() {
        lastPull = 0;
        nextAttemptAt = 0;
        wakeUp();
    }

    private void tick() {
        try {
            if (!SyncStore.isEnabled()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < nextAttemptAt) {
                return;
            }
            SyncClient client = SyncClient.fromStore();
            try {
                boolean sent = flushOutbox(client);
                if (now - lastPull >= PULL_EVERY_MS) {
                    pull(client);
                    lastPull = now;
                    lastHeartbeat = now; // any request counts as presence
                } else if (!sent && now - lastHeartbeat >= HEARTBEAT_EVERY_MS) {
                    client.heartbeat();
                    lastHeartbeat = now;
                }
                backoffMs = 0;
                nextAttemptAt = 0;
                lastError = null;
                SyncStore.setLastSyncAt(now / 1000);
            } catch (IOException e) {
                backoffMs = backoffMs == 0 ? TICK_MS : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                nextAttemptAt = now + backoffMs;
                lastError = e instanceof SyncClient.RelayException ? ((SyncClient.RelayException) e).code : e.getClass().getSimpleName();
                FileLog.e("typsync: " + lastError + ", retry in " + backoffMs / 1000 + " s");
                if (e instanceof SyncClient.RelayException && ((SyncClient.RelayException) e).status == 401) {
                    SyncStore.setEnabled(false); // device was removed on another device
                }
            }
            notifyListener();
        } catch (Exception e) {
            FileLog.e("typsync: tick", e);
        } finally {
            queue.postRunnable(tick, TICK_MS);
        }
    }

    /** Returns true if anything was sent. */
    private boolean flushOutbox(SyncClient client) throws IOException {
        SyncDao dao = SyncStore.dao();
        boolean sent = false;
        while (true) {
            List<SyncOutboxItem> items = dao.getOutbox(BATCH);
            if (items.isEmpty()) {
                return sent;
            }
            List<Long> done = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            List<byte[]> blobs = new ArrayList<>();
            List<Long> eventIds = new ArrayList<>();
            for (SyncOutboxItem item : items) {
                if (item.mediaPath != null) {
                    if (!eventIds.isEmpty()) {
                        break; // keep order: events before this blob go first
                    }
                    try {
                        SyncMedia.upload(client, item.blobId, item.mediaPath);
                    } catch (SyncClient.RelayException e) {
                        if (e.status != 429 && e.status < 500) {
                            // Rejected for good (too large, quota): the event keeps the metadata.
                            FileLog.e("typsync: blob " + item.blobId + " rejected: " + e.code);
                        } else {
                            throw e;
                        }
                    }
                    done.add(item.id);
                } else if (item.blob != null) {
                    keys.add(item.relayKey);
                    blobs.add(item.blob);
                    eventIds.add(item.id);
                } else {
                    done.add(item.id);
                }
            }
            if (!eventIds.isEmpty()) {
                client.postEvents(keys, blobs);
                done.addAll(eventIds);
            }
            dao.removeOutbox(done);
            sent = true;
        }
    }

    private void pull(SyncClient client) throws IOException {
        byte[] ownerPrivate = SyncStore.getOwnerPrivate();
        while (true) {
            SyncClient.Page page = client.getEvents(0, PAGE);
            if (page.trimmed) {
                SyncStore.setTrimmed(true);
            }
            for (SyncClient.Incoming in : page.events) {
                byte[] plain = SyncCrypto.open(ownerPrivate, in.blob);
                SyncEvents.Event event = plain == null ? null : SyncEvents.decode(plain);
                if (event == null) {
                    FileLog.e("typsync: cannot read event seq " + in.seq);
                    continue;
                }
                try {
                    SyncApply.apply(event, client);
                } catch (Exception e) {
                    FileLog.e("typsync: apply seq " + in.seq, e);
                }
            }
            SyncStore.setCursor(page.cursor);
            if (!page.more) {
                return;
            }
        }
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) {
            org.telegram.messenger.AndroidUtilities.runOnUIThread(l::onSyncStateChanged);
        }
    }
}
