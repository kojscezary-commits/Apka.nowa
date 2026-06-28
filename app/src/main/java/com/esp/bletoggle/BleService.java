package com.esp.bletoggle3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.util.UUID;
import androidx.media.app.NotificationCompat.MediaStyle;

public class BleService extends Service {

    private static final String TAG = "BleService";

    // ── Dane ESP ──────────────────────────────────────────────────
    private static final String ESP_ADDRESS   = "E0:72:A1:70:EB:4E";
    private static final UUID SERVICE_UUID    = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CHAR_UUID       = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    // ─────────────────────────────────────────────────────────────

    public static final String ACTION_START_FOREGROUND = "START_FOREGROUND";
    public static final String ACTION_TOGGLE           = "TOGGLE";
    public static final String ACTION_STOP             = "STOP";

    // Broadcasta do MainActivity – informuje o stanie busy
    public static final String ACTION_BUSY_CHANGED     = "com.esp.bletoggle3.BUSY_CHANGED";
    public static final String EXTRA_IS_BUSY           = "is_busy";

    private static final String CHANNEL_ID   = "ble_toggle_channel";
    private static final int    NOTIF_ID     = 1001;

    private BluetoothGatt bluetoothGatt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isBusy = false;

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            startForegroundWithNotification("Gotowy");
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) action = "";

        switch (action) {
            case ACTION_START_FOREGROUND:
                startForegroundWithNotification("Gotowy – naciśnij aby przełączyć LED");
                break;
            case ACTION_TOGGLE:
                if (isBusy) {
                    Log.d(TAG, "Już w trakcie – ignoruję kliknięcie");
                    return START_STICKY;
                }
                startForegroundWithNotification("⏳ Łączę z ESP...");
                connectAndSend();
                break;
            case ACTION_STOP:
                disconnectGatt();
                stopForeground(true);
                stopSelf();
                break;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        disconnectGatt();
        super.onDestroy();
    }

    // ── BLE ───────────────────────────────────────────────────────

    private void setBusy(boolean busy) {
        isBusy = busy;
        // Powiadom MainActivity o zmianie stanu
        Intent broadcast = new Intent(ACTION_BUSY_CHANGED);
        broadcast.putExtra(EXTRA_IS_BUSY, busy);
        sendBroadcast(broadcast);
    }

    private void connectAndSend() {
        setBusy(true);
        disconnectGatt();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) { finishWithError("Brak BluetoothManager"); return; }

        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { finishWithError("Bluetooth wyłączony"); return; }

        BluetoothDevice device = adapter.getRemoteDevice(ESP_ADDRESS);
        Log.d(TAG, "Łączę z " + ESP_ADDRESS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }

        // Timeout 15 sekund
        handler.postDelayed(() -> {
            if (isBusy) finishWithError("Timeout – nie można połączyć");
        }, 15_000);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Połączono – odkrywam serwisy");
                updateNotification("🔗 Połączono – wysyłam...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Rozłączono");
                setBusy(false);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError("Błąd odkrywania serwisów");
                return;
            }
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) { finishWithError("Nie znaleziono serwisu NUS"); return; }

            BluetoothGattCharacteristic ch = service.getCharacteristic(CHAR_UUID);
            if (ch == null) { finishWithError("Nie znaleziono charakterystyki TX"); return; }

            ch.setValue("1");
            ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            boolean ok = gatt.writeCharacteristic(ch);
            Log.d(TAG, "writeCharacteristic: " + ok);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ Wysłano '1' do ESP");
                updateNotification("✅ LED przełączony!");
            } else {
                Log.e(TAG, "Błąd zapisu: " + status);
                updateNotification("❌ Błąd wysyłania");
            }
            // Rozłącz po chwili i wróć do stanu gotowości
            handler.postDelayed(() -> {
                disconnectGatt();
                updateNotification("Gotowy – naciśnij aby przełączyć LED");
            }, 800);
            // Przycisk Przełącz wraca dopiero po 2.2s
            handler.postDelayed(() -> {
                setBusy(false);
                updateNotification("Gotowy – naciśnij aby przełączyć LED");
            }, 2200);
        }
    };

    private void disconnectGatt() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    private void finishWithError(String msg) {
        Log.e(TAG, msg);
        disconnectGatt();
        setBusy(false);
        updateNotification("❌ " + msg);
        handler.postDelayed(() ->
            updateNotification("Gotowy – naciśnij aby przełączyć LED"), 3000);
    }

    // ── Powiadomienie ─────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "ESP LED Toggle",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Sterowanie LED przez BLE");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String status) {
    Intent openApp = new Intent(this, MainActivity.class);
    openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent piOpen = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Intent toggleIntent = new Intent(this, BleService.class);
    toggleIntent.setAction(ACTION_TOGGLE);
    PendingIntent piToggle = PendingIntent.getService(this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Intent stopIntent = new Intent(this, BleService.class);
    stopIntent.setAction(ACTION_STOP);
    PendingIntent piStop = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    MediaStyle mediaStyle = new MediaStyle();

    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SWIATLO LED")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(piOpen)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC); // pełna treść na ekranie blokady

    if (!isBusy) {
        builder.addAction(android.R.drawable.ic_media_play, "🔁 Przełącz", piToggle); // index 0
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", piStop);  // index 1
        mediaStyle.setShowActionsInCompactView(0); // Przełącz widoczny bez rozwijania
    } else {
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", piStop); // index 0
        mediaStyle.setShowActionsInCompactView(); // brak przycisku gdy busy
    }

    builder.setStyle(mediaStyle);

    return builder.build();
}

    private void startForegroundWithNotification(String status) {
        startForeground(NOTIF_ID, buildNotification(status));
    }

    private void updateNotification(String status) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(status));
    }
}

