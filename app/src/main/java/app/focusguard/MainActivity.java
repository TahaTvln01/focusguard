package app.focusguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class MainActivity extends Activity {

    private static final String[] LABELS = {
            "Instagram: Reels sekmesi → ana sayfa",
            "Instagram: ana akış → yalnızca Takip Edilenler",
            "Instagram: açılan reel izlenir, sonrakine kaydırma engellenir",
            "YouTube: Shorts sekmesi → ana sayfa",
            "YouTube: açılan short izlenir, sonrakine kaydırma engellenir",
    };

    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences prefs = getSharedPreferences(GuardService.PREFS, MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setPadding(0, 0, 0, pad);
        box.addView(status);

        Button open = new Button(this);
        open.setText("Erişilebilirlik ayarlarını aç");
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable ignored) {}
        });
        box.addView(open);

        for (int i = 0; i < LABELS.length; i++) {
            final String key = GuardService.KEYS[i];
            Switch s = new Switch(this);
            s.setText(LABELS[i]);
            s.setPadding(0, pad, 0, pad);
            s.setChecked(prefs.getBoolean(key, true));
            s.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean(key, c).apply());
            box.addView(s);
        }

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(box);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = false;
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled = s != null && s.contains(getPackageName() + "/");
        } catch (Throwable ignored) {}
        status.setText(enabled ? "Durum: AKTİF" : "Durum: KAPALI — erişilebilirlik iznini açın");
    }
}
