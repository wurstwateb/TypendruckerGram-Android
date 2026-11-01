package app.typendruckergram.sync;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Settings of TypendruckerGram Sync: pairing, on/off, media limit, status. */
public class SyncSettingsActivity extends UniversalFragment implements SyncWorker.Listener {

    private static final int BUTTON_CREATE = 1;
    private static final int BUTTON_JOIN = 2;
    private static final int CHECK_ENABLED = 3;
    private static final int BUTTON_SHOW_CODE = 4;
    private static final int BUTTON_MEDIA_LIMIT = 5;
    private static final int BUTTON_SYNC_NOW = 6;
    private static final int BUTTON_DISCONNECT = 7;

    private static final long[] MEDIA_LIMITS_MB = {0, 5, 10, 20, 50};

    private boolean busy;

    @Override
    public boolean onFragmentCreate() {
        SyncWorker.getInstance().setListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        SyncWorker.getInstance().setListener(null);
        super.onFragmentDestroy();
    }

    @Override
    public void onSyncStateChanged() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.TypSyncTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!SyncStore.hasCredentials()) {
            items.add(UItem.asHeader(getString(R.string.TypSyncSetupHeader)));
            items.add(UItem.asButton(BUTTON_JOIN, R.drawable.msg_qrcode, getString(R.string.TypSyncJoin)));
            items.add(UItem.asButton(BUTTON_CREATE, R.drawable.msg_add, getString(R.string.TypSyncCreate)));
            items.add(UItem.asShadow(getString(R.string.TypSyncSetupInfo)));
            return;
        }
        items.add(UItem.asCheck(CHECK_ENABLED, getString(R.string.TypSyncEnabled)).setChecked(SyncStore.isEnabled()));
        items.add(UItem.asShadow(statusText()));

        items.add(UItem.asHeader(getString(R.string.TypSyncDevicesHeader)));
        items.add(UItem.asButton(BUTTON_SHOW_CODE, R.drawable.msg_link, getString(R.string.TypSyncShowCode)));
        items.add(UItem.asButton(BUTTON_MEDIA_LIMIT, R.drawable.msg_media, getString(R.string.TypSyncMediaLimit),
                mediaLimitText(SyncStore.getMediaLimit() / (1024 * 1024))));
        items.add(UItem.asButton(BUTTON_SYNC_NOW, R.drawable.msg_retry, getString(R.string.TypSyncNow)));
        items.add(UItem.asShadow(getString(R.string.TypSyncMediaInfo)));

        items.add(UItem.asButton(BUTTON_DISCONNECT, R.drawable.msg_leave, getString(R.string.TypSyncDisconnect)).red());
        items.add(UItem.asShadow(null));
    }

    private CharSequence statusText() {
        String error = SyncWorker.getInstance().getLastError();
        long last = SyncStore.getLastSyncAt();
        StringBuilder text = new StringBuilder();
        if (error != null) {
            text.append(formatString(R.string.TypSyncStatusError, error));
        } else if (last > 0) {
            text.append(formatString(R.string.TypSyncStatusLast, LocaleController.formatDateAudio(last, true)));
        } else {
            text.append(getString(R.string.TypSyncStatusNever));
        }
        if (SyncStore.isTrimmed()) {
            text.append("\n").append(getString(R.string.TypSyncTrimmed));
        }
        return text;
    }

    private static String mediaLimitText(long mb) {
        if (mb == 0) {
            return getString(R.string.TypSyncMediaOff);
        }
        String text = mb + " MB";
        return mb == SyncStore.DEFAULT_MEDIA_LIMIT / (1024 * 1024) ? text + " · " + getString(R.string.TypSyncRecommended) : text;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (busy) {
            return;
        }
        switch (item.id) {
            case BUTTON_CREATE:
                askText(getString(R.string.TypSyncCreate), getString(R.string.TypSyncInviteHint), this::createOwner);
                break;
            case BUTTON_JOIN:
                askText(getString(R.string.TypSyncJoin), "XXXX-XXXX-XXXX", this::join);
                break;
            case CHECK_ENABLED:
                SyncStore.setEnabled(!SyncStore.isEnabled());
                SyncWorker.getInstance().syncNow();
                listView.adapter.update(true);
                break;
            case BUTTON_SHOW_CODE:
                showPairCode();
                break;
            case BUTTON_MEDIA_LIMIT:
                chooseMediaLimit();
                break;
            case BUTTON_SYNC_NOW:
                SyncWorker.getInstance().syncNow();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.TypSyncStarted)).show();
                break;
            case BUTTON_DISCONNECT:
                confirmDisconnect();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // ---- actions ----

    private interface NetworkCall {
        String run() throws Exception; // returns a message to show, or null
    }

    private void runNetwork(NetworkCall call) {
        busy = true;
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);
        Utilities.globalQueue.postRunnable(() -> {
            String message;
            boolean failed = false;
            try {
                message = call.run();
            } catch (SyncClient.RelayException e) {
                message = describe(e);
                failed = true;
            } catch (Exception e) {
                message = formatString(R.string.TypSyncStatusError, e.getClass().getSimpleName());
                failed = true;
            }
            String finalMessage = message;
            boolean finalFailed = failed;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                progress.dismiss();
                if (listView != null) {
                    listView.adapter.update(true);
                }
                if (finalMessage != null) {
                    if (finalFailed) {
                        BulletinFactory.of(this).createErrorBulletin(finalMessage).show();
                    } else {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, finalMessage).show();
                    }
                }
            });
        });
    }

    private static String describe(SyncClient.RelayException e) {
        switch (e.code) {
            case "invite_required":
            case "bad_invite":
                return getString(R.string.TypSyncBadInvite);
            case "pairing_not_found":
                return getString(R.string.TypSyncBadCode);
            case "rate_limited":
                return getString(R.string.TypSyncRateLimited);
            default:
                return formatString(R.string.TypSyncStatusError, e.code);
        }
    }

    private static String deviceName() {
        String name = Build.MANUFACTURER + " " + Build.MODEL;
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    private void createOwner(String invite) {
        runNetwork(() -> {
            SyncCrypto.KeyPair keys = SyncCrypto.generateKeyPair();
            SyncClient client = new SyncClient(SyncStore.DEFAULT_RELAY_URL, null);
            SyncClient.Credentials c = client.createOwner(keys.publicKey, deviceName(), invite.trim());
            SyncStore.saveCredentials(SyncStore.DEFAULT_RELAY_URL, c.ownerId, c.deviceId, c.token, keys.privateKey);
            SyncWorker.getInstance().syncNow();
            return getString(R.string.TypSyncCreated);
        });
    }

    private void join(String code) {
        String normalized = SyncCrypto.normalizePairCode(code);
        if (normalized == null) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.TypSyncBadCode)).show();
            return;
        }
        runNetwork(() -> {
            SyncClient client = new SyncClient(SyncStore.DEFAULT_RELAY_URL, null);
            SyncClient.Credentials c = client.claimPairing(normalized, deviceName());
            if (c.payload == null) {
                return getString(R.string.TypSyncBadCode);
            }
            JsonObject payload = JsonParser.parseString(new String(c.payload, StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] ownerPrivate = SyncClient.unb64(payload.get("ownerPrivate").getAsString());
            String relay = payload.has("relayUrl") ? payload.get("relayUrl").getAsString() : SyncStore.DEFAULT_RELAY_URL;
            SyncStore.saveCredentials(relay, c.ownerId, c.deviceId, c.token, ownerPrivate);
            SyncWorker.getInstance().syncNow();
            return getString(R.string.TypSyncJoined);
        });
    }

    private void showPairCode() {
        busy = true;
        Utilities.globalQueue.postRunnable(() -> {
            String code = null;
            String error = null;
            try {
                code = SyncClient.fromStore().startPairing(SyncStore.getOwnerPrivate());
            } catch (SyncClient.RelayException e) {
                error = describe(e);
            } catch (Exception e) {
                error = formatString(R.string.TypSyncStatusError, e.getClass().getSimpleName());
            }
            String finalCode = code;
            String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (finalCode == null) {
                    BulletinFactory.of(this).createErrorBulletin(finalError).show();
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                builder.setTitle(getString(R.string.TypSyncShowCode));
                builder.setMessage(AndroidUtilities.replaceTags(formatString(R.string.TypSyncCodeText, finalCode)));
                builder.setPositiveButton(getString(R.string.Copy), (d, w) -> {
                    AndroidUtilities.addToClipboard(finalCode);
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
                });
                builder.setNegativeButton(getString(R.string.Close), null);
                showDialog(builder.create());
            });
        });
    }

    private void chooseMediaLimit() {
        String[] labels = new String[MEDIA_LIMITS_MB.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = mediaLimitText(MEDIA_LIMITS_MB[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.TypSyncMediaLimit));
        builder.setItems(labels, (dialog, which) -> {
            SyncStore.setMediaLimit(MEDIA_LIMITS_MB[which] * 1024 * 1024);
            listView.adapter.update(true);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void confirmDisconnect() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.TypSyncDisconnect));
        builder.setMessage(getString(R.string.TypSyncDisconnectInfo));
        builder.setPositiveButton(getString(R.string.TypSyncDisconnect), (d, w) -> runNetwork(() -> {
            String deviceId = SyncStore.getDeviceId();
            try {
                if (deviceId != null) {
                    SyncClient.fromStore().removeDevice(deviceId);
                }
            } catch (Exception ignored) {
                // Offline or already removed: forget the device locally anyway.
            }
            SyncStore.clearCredentials();
            return getString(R.string.TypSyncDisconnected);
        }));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.redPositive();
    }

    private void askText(String title, String hint, Utilities.Callback<String> onDone) {
        Context context = getParentActivity();
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.lineYFix = true;
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        editText.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        editText.setHintColor(getThemedColor(Theme.key_groupcreate_hintText));
        editText.setHintText(hint);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        editText.setBackground(null);
        editText.setLineColors(getThemedColor(Theme.key_windowBackgroundWhiteInputField),
                getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                getThemedColor(Theme.key_text_RedRegular));
        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        editText.setPadding(0, dp(6), 0, dp(6));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 24f, 0f, 24f, 12f));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(title);
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Done), (dialog, which) -> {
            String text = editText.getText().toString().trim();
            AndroidUtilities.hideKeyboard(editText);
            if (!TextUtils.isEmpty(text)) {
                onDone.run(text);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 60));
        showDialog(dialog);
    }
}
