package io.wazo.callkeep;

import android.app.PendingIntent;
import android.content.Context;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface InCallNotificationBridge {

    @NonNull
    RemoteViews createCollapsedView(
            Context context,
            PendingIntent returnToAppPendingIntent,
            PendingIntent toggleSpeakersPendingIntent,
            PendingIntent endCallPendingIntent,
            PendingIntent mutePendingIntent,
            boolean isEarpiece,
            boolean shouldMute,
            @Nullable String provider,
            @NonNull String firstLine,
            @Nullable String secondLine
    );

}
