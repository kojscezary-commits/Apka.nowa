package com.esp.ble;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TextView statusText;
    private Button toggleButton;

    // Odbiera broadcast od BleService o stanie busy
    private final BroadcastReceiver busyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean busy = intent.getBooleanExtra(BleService.EXTRA_IS_BUSY, false);
            toggleButton.setEnabled(!busy);
            toggleButton.setAlpha(busy ? 0.4f : 1.0f);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        toggleButton = findViewById(R.id.toggleButton);
        Button startServiceButton = findViewById(R.id.startServiceButton);

        toggleButton.setOnClickListener(v -> sendBleCommand());

        startServiceButton.setOnClickListener(v -> {
            startBleService();
            Toast.makeText(this, "powiadomienie aktywowane", Toast.LENGTH_SHORT).show();
        });

        requestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rejestruj receiver gdy aktywność jest widoczna
        IntentFilter filter = new IntentFilter(BleService.ACTION_BUSY_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(busyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(busyReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Wyrejestruj receiver gdy aktywność niewidoczna
        unregisterReceiver(busyReceiver);
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> toRequest = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            startBleService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startBleService();
        }
    }

    private void startBleService() {
        Intent serviceIntent = new Intent(this, BleService.class);
        serviceIntent.setAction(BleService.ACTION_START_FOREGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        statusText.setText("Powiadomienie widoczne w pasku");
    }

    private void sendBleCommand() {
        statusText.setText("⏳ Łączę");
        Intent serviceIntent = new Intent(this, BleService.class);
        serviceIntent.setAction(BleService.ACTION_TOGGLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}

