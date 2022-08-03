package io.wazo.callkeep;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;

public interface PendingIntentFactory {
    PendingIntent asPendingIntent(Context context, Bundle extras);
}
