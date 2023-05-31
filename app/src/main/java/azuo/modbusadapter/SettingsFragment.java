package azuo.modbusadapter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import java.util.Objects;

public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        Objects.<Preference>requireNonNull(findPreference("adapter_start"))
            .setOnPreferenceChangeListener((preference, start) -> {
                Context context = requireContext();
                if (start instanceof Boolean && (Boolean)start) {
                    ContextCompat.startForegroundService(
                        context,
                        new Intent(context, AdapterService.class)
                    );
                    return false;
                }
                else {
                    context.stopService(new Intent(context, AdapterService.class));
                    Objects.<Preference>requireNonNull(findPreference("uart")).setEnabled(true);
                    Objects.<Preference>requireNonNull(findPreference("tcp")).setEnabled(true);
                    return true;
                }
            }
        );

        TwoStatePreference debug = Objects.requireNonNull(findPreference("adapter_debug"));
        if (!debug.isChecked())
            debug.setSummary(R.string.adapter_debug_off);
        else if (App.DEBUG_LOG != null)
            debug.setSummary(getString(R.string.adapter_debug_on, App.DEBUG_LOG));
        else
            debug.setSummary(R.string.adapter_debug_error);
        debug.setOnPreferenceChangeListener((preference, checked) -> {
            final Context context = requireContext();
            new AlertDialog.Builder(requireContext())
                .setMessage(R.string.adapter_debug_restart)
                .setPositiveButton(android.R.string.ok, ((dialog, which) -> {
                    preference.getSharedPreferences().edit().putBoolean(
                        preference.getKey(),
                        checked instanceof Boolean && (Boolean)checked
                    ).commit();
                    context.startActivity(Intent.makeRestartActivityTask(
                        context.getPackageManager()
                               .getLaunchIntentForPackage(context.getPackageName())
                               .getComponent()
                    ));
                    System.exit(0);
                }))
                .setNegativeButton(android.R.string.cancel, ((dialog, which) -> {
                    dialog.dismiss();
                }))
                .show();
            return false;
        });

        Objects.<EditTextPreference>requireNonNull(findPreference("tcp_port"))
            .setOnBindEditTextListener(
                editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER)
            );

        Objects.<Preference>requireNonNull(findPreference("about_version")).setSummary(
            getString(R.string.app_name) + " v" +
            BuildConfig.VERSION_NAME +
            (BuildConfig.DEBUG ? " dev" : "")
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    public void refresh() {
        TwoStatePreference start = Objects.requireNonNull(findPreference("adapter_start"));
        boolean started = AdapterService.SERVICE_STARTED;
        if (started != start.isChecked()) {
            start.setChecked(started);
            Objects.<Preference>requireNonNull(findPreference("uart")).setEnabled(!started);
            Objects.<Preference>requireNonNull(findPreference("tcp")).setEnabled(!started);
        }
    }
}
