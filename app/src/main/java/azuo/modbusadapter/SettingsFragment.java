package azuo.modbusadapter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import java.util.Objects;

public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        Objects.<EditTextPreference>requireNonNull(findPreference("tcp_port"))
            .setOnBindEditTextListener(
                editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER)
            );

        Objects.<Preference>requireNonNull(findPreference("adapter_start"))
            .setOnPreferenceChangeListener((preference, start) -> {
                Context context = requireContext();
                if (start instanceof Boolean && (Boolean)start) {
                    context.startForegroundService(new Intent(context, AdapterService.class));
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

        Preference version = Objects.requireNonNull(findPreference("about_version"));
        version.setSummary(
            getString(R.string.app_name) + " v" +
            BuildConfig.VERSION_NAME +
            (BuildConfig.DEBUG ? " dev" : "")
        );
        version.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/azuo/modbus-adapter"));
            startActivity(intent);
            return true;
        });
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
