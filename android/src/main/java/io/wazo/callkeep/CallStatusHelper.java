package io.wazo.callkeep;

import static io.wazo.callkeep.Constants.ACTION_CALL_IN_PROGRESS;
import static io.wazo.callkeep.Constants.ACTION_END_CALL;
import static io.wazo.callkeep.Constants.ACTION_TOGGLE_AUDIOROUTE;
import static io.wazo.callkeep.Constants.AUDIO_ROUTE;
import static io.wazo.callkeep.Constants.KEY_UUID;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class CallStatusHelper {

    public static final Class<?> KLAZZ = VoiceConnectionService.class;
    public static final String TAG = "CallStatusService";

    //region INTERACTION INTENTS
    public static Intent endCall(Context context, String uuid) {
        Intent intent = new Intent(context, KLAZZ);
        intent.setAction(ACTION_END_CALL);
        intent.putExtra(KEY_UUID, uuid);
        return intent;
    }

    public static Intent callStartedIntent(Context context, String uuid) {
        Intent intent = new Intent(context, KLAZZ);
        intent.setAction(ACTION_CALL_IN_PROGRESS);
        intent.putExtra(KEY_UUID, uuid);
        return intent;
    }

    public static Intent toggleAudioRouteIntent(Context context, String uuid, int targetAudioRoute) {
        Intent intent = new Intent(context, KLAZZ);
        intent.setAction(ACTION_TOGGLE_AUDIOROUTE);
        intent.putExtra(KEY_UUID, uuid);
        intent.putExtra(AUDIO_ROUTE, targetAudioRoute);
        return intent;
    }
    //endregion

    public static PendingIntent getForegroundServiceCompat(
            Context context,
            int req,
            Intent intent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(context, req, intent, pendingIntentFlags());
        } else {
            return PendingIntent.getService(context, req, intent, pendingIntentFlags());
        }
    }

    private static int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
