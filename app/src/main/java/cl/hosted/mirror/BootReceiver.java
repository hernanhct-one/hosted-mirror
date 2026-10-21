package cl.hosted.mirror;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Deja el receptor disponible apenas se enciende el Fire TV. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            MirrorService.start(context);
        }
    }
}
