package io.wazo.callkeep;


import static io.wazo.callkeep.Constants.AUDIO_ROUTE;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.telecom.CallAudioState;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.facebook.react.bridge.ReadableMap;

public class NotificationHelper {

    public static final int NO_ICON = 0;

    private final Context context;

    public NotificationHelper(Context context) {
        this.context = context;
    }

    private int provideSmallIcon() {
        ReadableMap settings = VoiceConnectionService.getForegroundSettings(context);
        if (settings == null || !settings.hasKey("notificationIcon")) {
            return provideLauncherIcon();
        }
        String iconName = settings.getString("notificationIcon");
        Resources resources = context.getResources();
        int id = resources.getIdentifier(iconName, "mipmap", context.getPackageName());
        if (id != NO_ICON) return id;
        id = resources.getIdentifier(iconName, "drawable", context.getPackageName());
        if (id != NO_ICON) return id;
        return provideLauncherIcon();
    }

    private int provideLauncherIcon() {
        return context.getResources().getIdentifier("ic_launcher", "mipmap", context.getPackageName());
    }

    @Nullable
    public Notification getCallInProgressNotification(
            String channelId,
            Bundle serviceExtras,
            PendingIntentFactory onReturnToAppClicked,
            PendingIntentFactory endCall,
            PendingIntentFactory toggleSpeakers
    ) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setContentIntent(onReturnToAppClicked.asPendingIntent(context, serviceExtras))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);
        builder.setSmallIcon(provideSmallIcon());
        builder.setContentTitle("Call in progress");
        builder.setContentText(getDisplayName(serviceExtras));
        builder.addAction(new NotificationCompat.Action(NO_ICON, "End call", endCall.asPendingIntent(context, serviceExtras)));

        int audioRoute = serviceExtras.getInt(AUDIO_ROUTE, CallAudioState.ROUTE_EARPIECE);
        final String label;
        if (audioRoute == CallAudioState.ROUTE_EARPIECE) {
            label = "Speaker on";
        } else {
            label = "Speaker off";
        }
        builder.addAction(new NotificationCompat.Action(NO_ICON, label, toggleSpeakers.asPendingIntent(context, serviceExtras)));

        final Notification notification = builder.build();
        notification.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        return notification;
    }

    private String getDisplayName(Bundle serviceExtras) {
        // todo: pass through user metadata
        return "User";
    }

    //region CUSTOM EXCEPTIONS

    public static class MissingActivityLauncherException extends RuntimeException {
        public MissingActivityLauncherException(Throwable cause) {
            super(cause);
        }
    }
    //endregion
}
