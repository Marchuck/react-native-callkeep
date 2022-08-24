package io.wazo.callkeep;


import static java.lang.Math.abs;
import static io.wazo.callkeep.Constants.KEY_AUDIO_ROUTE;
import static io.wazo.callkeep.Constants.KEY_CALLER_NAME;
import static io.wazo.callkeep.Constants.KEY_CALL_HANDLE;
import static io.wazo.callkeep.Constants.KEY_CALL_START_DATE;
import static io.wazo.callkeep.Constants.KEY_MUTE;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.telecom.CallAudioState;
import android.text.TextUtils;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.facebook.react.bridge.ReadableMap;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Date;
import java.util.Locale;

public class NotificationHelper {

    public static final String TAG = "NotificationHelper";
    public static final int NO_ICON = 0;

    private final Context context;

    public NotificationHelper(Context context) {
        this.context = context;
    }

    @DrawableRes
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
    public Notification getStyledCallInProgressNotification(
            String channelId,
            Bundle serviceExtras,
            PendingIntentFactory onReturnToAppClicked,
            PendingIntentFactory endCall,
            PendingIntentFactory toggleSpeakers,
            PendingIntentFactory toggleMute
    ) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);
        builder.setSmallIcon(provideSmallIcon());
        PendingIntent endCallPendingIntent = endCall.asPendingIntent(context, serviceExtras);

        int audioRoute = serviceExtras.getInt(KEY_AUDIO_ROUTE, CallAudioState.ROUTE_EARPIECE);

        final boolean isEarpiece = audioRoute == CallAudioState.ROUTE_EARPIECE;
        final PendingIntent toggleSpeakersPendingIntent = toggleSpeakers.asPendingIntent(context, serviceExtras);

        boolean shouldMute = serviceExtras.getBoolean(KEY_MUTE, false);
        final PendingIntent mutePendingIntent = toggleMute.asPendingIntent(context, serviceExtras);

        final PendingIntent returnToAppPendingIntent = onReturnToAppClicked.asPendingIntent(context, serviceExtras);
        RemoteViews collapsedRemoteViews = createCollapsedContentView(
                serviceExtras,
                returnToAppPendingIntent,
                toggleSpeakersPendingIntent,
                isEarpiece,
                mutePendingIntent,
                shouldMute,
                endCallPendingIntent
        );
        builder.setCustomContentView(collapsedRemoteViews);
        builder.setCustomHeadsUpContentView(collapsedRemoteViews);

        final Notification notification = builder.build();
        notification.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        return notification;
    }

    @NonNull
    private RemoteViews createCollapsedContentView(Bundle extraBundle,
                                                   PendingIntent returnToAppPendingIntent,
                                                   PendingIntent toggleSpeakersPendingIntent,
                                                   boolean isEarpiece,
                                                   PendingIntent mutePendingIntent,
                                                   boolean shouldMute,
                                                   PendingIntent endCallPendingIntent
    ) {
        final InCallNotificationBridge appearance = resolveNotificationBridgeIfAny();
        return appearance.createCollapsedView(
                context,
                returnToAppPendingIntent,
                toggleSpeakersPendingIntent,
                endCallPendingIntent,
                mutePendingIntent,
                isEarpiece,
                shouldMute,
                null,
                getFirstLine(extraBundle),
                getSecondLine(extraBundle)
        );
    }

    private String getSecondLine(Bundle extraBundle) {
        Date date = (Date) extraBundle.getSerializable(KEY_CALL_START_DATE);
        if (date == null) return null;
        Date now = new Date();
        int elapsed = (int) abs(now.getTime() - date.getTime());

        int minutes = elapsed / (60 * 1000);
        int seconds = (elapsed / 1000) % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    @NonNull
    private InCallNotificationBridge resolveNotificationBridgeIfAny() {
        final ReadableMap settings = VoiceConnectionService.getForegroundSettings(context);

        ErrorFactory factory = IllegalStateException::new;

        if (settings == null || !settings.hasKey("notificationAppearanceClass"))
            throw factory.create("'notificationAppearanceClass' should be present");
        String className = settings.getString("notificationAppearanceClass");
        if (className == null) throw factory.create("'notificationAppearanceClass' missing");
        try {
            return (InCallNotificationBridge) Class.forName(className).newInstance();
        } catch (Exception e) {
            Log.e(TAG, "createCollapsedContentView", e);
            throw factory.create("'notificationAppearanceClass' missing due to " + e.getMessage());
        }
    }


    private String getFirstLine(Bundle serviceExtras) {
        String callerName = serviceExtras.getString(KEY_CALLER_NAME, null);
        if (TextUtils.isEmpty(callerName)) {
            callerName = serviceExtras.getString(KEY_CALL_HANDLE, null);
        }
        if (TextUtils.isEmpty(callerName)) {
            return "Call in progress";
        }
        final String name;
        try {
            // JS issue with Strings: (e.g. transforms '%20's to spaces)
            name = URLDecoder.decode(callerName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return callerName;
        }
        return name;
    }

    //region CUSTOM EXCEPTIONS

    public static class MissingActivityLauncherException extends RuntimeException {
        public MissingActivityLauncherException(Throwable cause) {
            super(cause);
        }
    }
    //endregion

    interface ErrorFactory {
        IllegalStateException create(String message);
    }
}
