package azuo.modbusadapter;

import android.app.Application;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

import androidx.preference.PreferenceManager;

public class App extends Application {
    public static String DEBUG_LOG = null;

    @Override
    public void onCreate() {
        super.onCreate();
        setupDebugLog();
    }

    private void setupDebugLog() {
        if (DEBUG_LOG != null)
            return;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.getBoolean("adapter_debug", false))
            return;
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            Toast.makeText(
                this, "Failed to capture debug log: No externalFilesDir", Toast.LENGTH_LONG
            ).show();
            return;
        }
        File log = new File(dir, "debug.txt");
        //log.delete();
        try {
            String path = log.getAbsolutePath();
            Runtime.getRuntime().exec(new String[] {
                "logcat", "-f", path, "AdapterService:V", "*:S"
            });
            DEBUG_LOG = path;
        }
        catch (IOException e) {
            Toast.makeText(
                this, "Failed to capture debug log: " + e, Toast.LENGTH_LONG
            ).show();
        }
    }
}
