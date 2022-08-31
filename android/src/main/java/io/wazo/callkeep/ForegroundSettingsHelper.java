package io.wazo.callkeep;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReadableMap;

public class ForegroundSettingsHelper {

    private ForegroundSettingsHelper() {
    }

    public static boolean getBooleanFlagValue(
            @NonNull Context context,
            @NonNull String key,
            boolean defaultValue
    ) {
        Boolean value = getBooleanFlagValue(context, key);
        return value == null ? defaultValue : value;
    }

    public static int getIntFlagValue(
            @NonNull Context context,
            @NonNull String key,
            int defaultValue
    ) {
        Integer value = getIntFlagValue(context, key);
        return value == null ? defaultValue : value;
    }

    public static String getBooleanFlagValue(
            @NonNull Context context,
            @NonNull String key,
            String defaultValue
    ) {
        String value = getStringFlagValue(context, key);
        return value == null ? defaultValue : value;
    }

    @Nullable
    public static Boolean getBooleanFlagValue(@NonNull Context context, @NonNull String key) {
        ReadableMap map = resolveMap(context, key);
        if (map == null) return null;
        return map.getBoolean(key);
    }

    @Nullable
    public static Integer getIntFlagValue(@NonNull Context context, @NonNull String key) {
        ReadableMap map = resolveMap(context, key);
        if (map == null) return null;
        return map.getInt(key);
    }

    public static String getStringFlagValue(@NonNull Context context, @NonNull String key) {
        ReadableMap map = resolveMap(context, key);
        if (map == null) return null;
        return map.getString(key);
    }

    @Nullable
    private static ReadableMap resolveMap(@NonNull Context context, @NonNull String key) {
        ReadableMap map = VoiceConnectionService.getForegroundSettings(context);
        if (map == null) return null;
        if (!map.hasKey(key)) return null;
        return map;
    }
}
