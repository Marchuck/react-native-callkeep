package io.wazo.callkeep;

import static io.wazo.callkeep.Constants.ACTION_CALL_IN_PROGRESS;
import static io.wazo.callkeep.Constants.ACTION_END_CALL;
import static io.wazo.callkeep.Constants.ACTION_TOGGLE_AUDIOROUTE;
import static io.wazo.callkeep.Constants.KEY_AUDIO_ROUTE;
import static io.wazo.callkeep.Constants.KEY_AUDIO_ROUTE_CHANGER;
import static io.wazo.callkeep.Constants.KEY_CALLER_NAME;
import static io.wazo.callkeep.Constants.KEY_CALL_HANDLE;
import static io.wazo.callkeep.Constants.KEY_IS_ONGOING;
import static io.wazo.callkeep.Constants.KEY_UUID;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;

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

    public static Intent callStartedIntent(
            Context context,
            String uuid,
            boolean isOngoing,
            String callNumber,
            String callerName
    ) {
        Intent intent = new Intent(context, KLAZZ);
        intent.setAction(ACTION_CALL_IN_PROGRESS);
        intent.putExtra(KEY_UUID, uuid);
        intent.putExtra(KEY_IS_ONGOING, isOngoing);
        intent.putExtra(KEY_CALL_HANDLE, callNumber);
        intent.putExtra(KEY_CALLER_NAME, callerName);
        return intent;
    }

    public static Intent toggleAudioRouteIntent(Context context, @Nullable Bundle originalExtras, int targetAudioRoute, String changer) {
        Intent intent = new Intent(context, KLAZZ);
        intent.setAction(ACTION_TOGGLE_AUDIOROUTE);
        if (originalExtras != null && !originalExtras.keySet().isEmpty()) {
            intent.putExtras(originalExtras);
        }
        intent.putExtra(KEY_AUDIO_ROUTE, targetAudioRoute);
        intent.putExtra(KEY_AUDIO_ROUTE_CHANGER, changer);
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
