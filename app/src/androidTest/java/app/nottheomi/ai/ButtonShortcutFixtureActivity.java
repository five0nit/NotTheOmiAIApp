package app.nottheomi.ai;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** Installed only in the isolated test APK; no production exported fixtures. */
public class ButtonShortcutFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())) {
            Intent target = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(getPackageName(), Launch.class.getName()))
                .putExtra("synthetic", "selected fixture");
            setResult(RESULT_OK, new Intent().putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)
                .putExtra(Intent.EXTRA_SHORTCUT_NAME, "Synthetic phone shortcut"));
        }
        finish();
    }
    public static class Launch extends Activity {
        @Override public void onCreate(Bundle state) { super.onCreate(state); finish(); }
    }
    public static class Private extends Activity {}
    public static class Restricted extends Activity {}
}
