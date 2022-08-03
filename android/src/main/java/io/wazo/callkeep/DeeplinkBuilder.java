package io.wazo.callkeep;

import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public class DeeplinkBuilder {
    private final String scheme;
    private final String path;
    private final Map<String, String> data;

    public DeeplinkBuilder(String scheme, String path, Bundle data) {
        this.scheme = scheme;
        this.path = path;
        this.data = toMap(data);
    }

    private static Map<String, String> toMap(Bundle data) {
        Map<String, String> map = new HashMap<>();
        if (data != null && !data.keySet().isEmpty()) {
            for (String key : data.keySet()) {
                map.put(key, String.valueOf(data.get(key)));
            }
        }
        return map;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder(scheme + "://").append(path).append('?');

        int index = 0;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (index != 0) {
                stringBuilder.append('&');
            }
            stringBuilder
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue());
            ++index;
        }
        return stringBuilder.toString();
    }
}
