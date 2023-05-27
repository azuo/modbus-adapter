package azuo.modbusadapter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AdapterService.ACTION_SERVICE_NOTIFY);
        filter.addAction(AdapterService.ACTION_USB_PERMISSION);
        registerReceiver(adapterReceiver, filter);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(adapterReceiver);
        super.onPause();
    }

    private final BroadcastReceiver adapterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AdapterService.ACTION_SERVICE_NOTIFY.equals(intent.getAction())) {
                String error = intent.getStringExtra("error");
                if (error != null) {
                    Snackbar snackbar = Snackbar.make(
                        findViewById(android.R.id.content),
                        intent.getStringExtra("error"),
                        Snackbar.LENGTH_LONG
                    );
                    ((TextView)snackbar.getView().findViewById(
                        com.google.android.material.R.id.snackbar_text
                    )).setMaxLines(10);
                    snackbar.show();
                }
                SettingsFragment settings =
                    (SettingsFragment)getSupportFragmentManager().findFragmentById(R.id.settings);
                if (settings != null)
                    settings.refresh();
            }
            else if (AdapterService.ACTION_USB_PERMISSION.equals(intent.getAction())) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    ContextCompat.startForegroundService(
                        context,
                        new Intent(context, AdapterService.class)
                    );
                else
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.usb_denied,
                        Snackbar.LENGTH_LONG
                    ).show();
            }
        }
    };
}
