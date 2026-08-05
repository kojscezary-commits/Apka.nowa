package com.esp.ble;

import android.app.AlarmManager;
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
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class BleService extends Service {

    private static final String TAG = "BleService";

    // ── Dane ESP ──────────────────────────────────────────────────
    private static final String ESP_ADDRESS   = "E0:72:A1:6F:6F:2E";
    private static final UUID SERVICE_UUID    = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CHAR_UUID       = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); // RX (write)
    private static final UUID CHAR_TX_UUID    = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); // TX (notify)
    private static final UUID CCCD_UUID       = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    // ─────────────────────────────────────────────────────────────

    public static final String ACTION_START_FOREGROUND = "START_FOREGROUND";
    public static final String ACTION_TOGGLE           = "TOGGLE";
    public static final String ACTION_STOP             = "STOP";
    public static final String ACTION_AUTO_OFF         = "AUTO_OFF";   // NOWE

    // Broadcasta do MainActivity – informuje o stanie busy
    public static final String ACTION_BUSY_CHANGED     = "com.esp.ble.BUSY_CHANGED";
    public static final String EXTRA_IS_BUSY           = "is_busy";

    private static final String CHANNEL_ID   = "ble_toggle_channel";
    private static final int    NOTIF_ID     = 1001;

    // ── Auto-wyłączanie ───────────────────────────────────────────
    /** Po tylu ms od włączenia stanu wysokiego wysyłane jest przełączenie na niski. */
    public  static final long   AUTO_OFF_DELAY_MS = 60L * 60L * 1000L;   // 1 godzina
    private static final String PREFS             = "ble_prefs";
    private static final String KEY_AUTO_OFF_AT   = "auto_off_at";
    private static final int    ALARM_REQ_CODE    = 77;
    // ─────────────────────────────────────────────────────────────

    private BluetoothGatt bluetoothGatt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isBusy = false;

    /** Charakterystyka RX zapamiętana do zapisu po włączeniu notyfikacji. */
    private BluetoothGattCharacteristic pendingRxChar;

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
                startForegroundWithNotification("⏳ Łączę ");
                connectAndSend();
                break;

            case ACTION_AUTO_OFF:
                // Alarm po godzinie – wyślij przełączenie
                Log.d(TAG, "Auto-off: minęła godzina, wysyłam przełączenie");
                if (isBusy) {
                    // Zajęte – spróbuj ponownie za 30 s
                    scheduleAutoOffAt(this, System.currentTimeMillis() + 30_000L);
                } else {
                    startForegroundWithNotification("⏳ Auto-wyłączanie...");
                    connectAndSend();
                }
                break;

            case ACTION_STOP:
                cancelAutoOff(this);
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
        Intent broadcast = new Intent(ACTION_BUSY_CHANGED);
        broadcast.putExtra(EXTRA_IS_BUSY, busy);
        sendBroadcast(broadcast);
    }

    private void connectAndSend() {
        setBusy(true);
        pendingRxChar = null;
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

            BluetoothGattCharacteristic rx = service.getCharacteristic(CHAR_UUID);
            if (rx == null) { finishWithError("Nie znaleziono charakterystyki RX"); return; }
            pendingRxChar = rx;

            // Najpierw włącz notyfikacje na TX – dzięki temu poznamy realny stan wyjścia
            BluetoothGattCharacteristic tx = service.getCharacteristic(CHAR_TX_UUID);
            if (tx != null) {
                gatt.setCharacteristicNotification(tx, true);
                BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (gatt.writeDescriptor(cccd)) {
                        return; // zapis '1' nastąpi w onDescriptorWrite
                    }
                }
            }
            // Brak TX / nie udało się – wyślij od razu (bez znajomości stanu)
            Log.w(TAG, "Notyfikacje niedostępne – wysyłam bez odczytu stanu");
            writePendingToggle(gatt);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "CCCD zapisany, status=" + status);
            writePendingToggle(gatt);
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
            // Zostaw ~1,2 s na dotarcie notyfikacji ze stanem, potem rozłącz
            handler.postDelayed(() -> {
                disconnectGatt();
                updateNotification("Naciśnij aby przełączyć LED");
            }, 1200);

            handler.postDelayed(() -> {
                setBusy(false);
                updateNotification("Naciśnij aby przełączyć LED");
            }, 2200);
        }

        /** Odpowiedź ESP: "GPIO0 HIGH" / "GPIO0 LOW" – na jej podstawie ustawiamy alarm. */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw == null) return;
            String value = new String(raw, StandardCharsets.UTF_8).trim();
            Log.d(TAG, "Notify z ESP: " + value);

            if (value.contains("HIGH")) {
                long at = System.currentTimeMillis() + AUTO_OFF_DELAY_MS;
                scheduleAutoOffAt(BleService.this, at);
                handler.post(() -> updateNotification("💡 Włączone – wyłączy się o " + formatTime(at)));
            } else if (value.contains("LOW")) {
                cancelAutoOff(BleService.this);
                handler.post(() -> updateNotification("Naciśnij aby przełączyć LED"));
            }
        }
    };

    private void writePendingToggle(BluetoothGatt gatt) {
        if (pendingRxChar == null) { finishWithError("Brak charakterystyki RX"); return; }
        pendingRxChar.setValue("1");
        pendingRxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        boolean ok = gatt.writeCharacteristic(pendingRxChar);
        Log.d(TAG, "writeCharacteristic: " + ok);
    }

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
            updateNotification("Naciśnij aby przełączyć LED"), 3000);
    }

    // ── Alarm auto-wyłączenia ─────────────────────────────────────

    private static PendingIntent autoOffPendingIntent(Context ctx) {
        Intent i = new Intent(ctx, BleService.class);
        i.setAction(ACTION_AUTO_OFF);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(ctx, ALARM_REQ_CODE, i, flags);
        }
        return PendingIntent.getService(ctx, ALARM_REQ_CODE, i, flags);
    }

    /** Ustawia (lub przesuwa) alarm wyłączenia na podany moment. */
    public static void scheduleAutoOffAt(Context ctx, long triggerAtMillis) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = autoOffPendingIntent(ctx);
        try {
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                exact = am.canScheduleExactAlarms();
            }
            if (exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Bez zgody na dokładne alarmy – może się spóźnić o kilka minut
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Brak uprawnień do alarmu: " + e.getMessage());
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
        prefs(ctx).edit().putLong(KEY_AUTO_OFF_AT, triggerAtMillis).apply();
        Log.d(TAG, "Auto-off zaplanowane na " + formatTime(triggerAtMillis));
    }

    public static void cancelAutoOff(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(autoOffPendingIntent(ctx));
        prefs(ctx).edit().remove(KEY_AUTO_OFF_AT).apply();
        Log.d(TAG, "Auto-off anulowane");
    }

    /** Po restarcie telefonu alarmy giną – przywróć, jeśli termin jeszcze nie minął. */
    public static void restoreAutoOffAfterBoot(Context ctx) {
        long at = prefs(ctx).getLong(KEY_AUTO_OFF_AT, 0L);
        if (at <= 0L) return;
        if (at > System.currentTimeMillis()) {
            scheduleAutoOffAt(ctx, at);
        } else {
            // Termin minął w czasie, gdy telefon był wyłączony – nie przełączamy w ciemno
            prefs(ctx).edit().remove(KEY_AUTO_OFF_AT).apply();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    // ── Powiadomienie ─────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "LED Toggle",
                    NotificationManager.IMPORTANCE_HIGH);
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
                .setSilent(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // Podgląd terminu auto-wyłączenia
        long at = prefs(this).getLong(KEY_AUTO_OFF_AT, 0L);
        if (at > System.currentTimeMillis()) {
            builder.setSubText("Auto-off " + formatTime(at));
        }

        if (!isBusy) {
            builder.addAction(android.R.drawable.ic_media_play, "🔁 Przełącz", piToggle); // index 0
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", piStop);  // index 1
            mediaStyle.setShowActionsInCompactView(0);
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", piStop); // index 0
            mediaStyle.setShowActionsInCompactView();
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
