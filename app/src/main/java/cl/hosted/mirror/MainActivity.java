package cl.hosted.mirror;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Pantalla de inicio: cómo conectarse y estado del receptor. */
public final class MainActivity extends Activity implements MirrorState.Listener {
    private View statusDot;
    private TextView statusText;
    private TextView nameValue;
    private TextView step3;
    private TextView footer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusDot = findViewById(R.id.status_dot);
        statusText = findViewById(R.id.status_text);
        nameValue = findViewById(R.id.name_value);
        step3 = findViewById(R.id.step3_text);
        footer = findViewById(R.id.footer);
        View rename = findViewById(R.id.rename);
        rename.setOnClickListener(v -> showRenameDialog());
        rename.requestFocus();
        MirrorService.start(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        MirrorState.get().addListener(this);
    }

    @Override
    protected void onStop() {
        MirrorState.get().removeListener(this);
        super.onStop();
    }

    @Override
    public void onMirrorStateChanged(MirrorState s) {
        nameValue.setText(s.name);
        step3.setText(getString(R.string.step3, s.name));
        int dot;
        String text;
        switch (s.server) {
            case READY:
                if (s.audioOnly && s.connections > 0) {
                    dot = R.drawable.dot_active;
                    text = getString(R.string.status_audio, who(s));
                } else if (s.connections > 0) {
                    dot = R.drawable.dot_active;
                    text = getString(R.string.status_connected, who(s));
                } else {
                    dot = R.drawable.dot_ready;
                    text = getString(R.string.status_ready);
                }
                break;
            case ERROR:
                dot = R.drawable.dot_error;
                text = getString(R.string.status_error, s.errorCode);
                break;
            default:
                dot = R.drawable.dot_idle;
                text = getString(R.string.status_starting);
                break;
        }
        statusDot.setBackgroundResource(dot);
        statusText.setText(text);
        String ip = localIp();
        footer.setText(getString(R.string.footer, ip == null ? getString(R.string.no_network) : ip,
                BuildConfigHelper.versionName(this)));
    }

    private String who(MirrorState s) {
        return s.clientName == null || s.clientName.isEmpty() ? getString(R.string.your_device) : s.clientName;
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setText(Prefs.name(this));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.rename_title)
                .setMessage(R.string.rename_message)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.length() > 40) n = n.substring(0, 40);
                    Prefs.setName(this, n);
                    MirrorService.restart(this);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
