/*
 * Copyright (c) 2016-2019 The CallKeep Authors (see the AUTHORS file)
 * SPDX-License-Identifier: ISC, MIT
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package io.wazo.callkeep;

import static io.wazo.callkeep.CallStatusHelper.toggleAudioRouteIntent;
import static io.wazo.callkeep.Constants.ACTION_ANSWER_CALL;
import static io.wazo.callkeep.Constants.ACTION_AUDIO_SESSION;
import static io.wazo.callkeep.Constants.ACTION_CHECK_REACHABILITY;
import static io.wazo.callkeep.Constants.ACTION_DID_CHANGE_AUDIO_ROUTE;
import static io.wazo.callkeep.Constants.ACTION_DTMF_TONE;
import static io.wazo.callkeep.Constants.ACTION_END_CALL;
import static io.wazo.callkeep.Constants.ACTION_HOLD_CALL;
import static io.wazo.callkeep.Constants.ACTION_MUTE_CALL;
import static io.wazo.callkeep.Constants.ACTION_ONGOING_CALL;
import static io.wazo.callkeep.Constants.ACTION_ON_CREATE_CONNECTION_FAILED;
import static io.wazo.callkeep.Constants.ACTION_ON_SILENCE_INCOMING_CALL;
import static io.wazo.callkeep.Constants.ACTION_SHOW_INCOMING_CALL_UI;
import static io.wazo.callkeep.Constants.ACTION_UNHOLD_CALL;
import static io.wazo.callkeep.Constants.ACTION_UNMUTE_CALL;
import static io.wazo.callkeep.Constants.ACTION_WAKE_APP;
import static io.wazo.callkeep.Constants.EXTRA_CALLER_NAME;
import static io.wazo.callkeep.Constants.EXTRA_CALL_NUMBER;
import static io.wazo.callkeep.Constants.EXTRA_CALL_UUID;
import static io.wazo.callkeep.Constants.KEY_AUDIO_ROUTE_CHANGER_APP;
import static io.wazo.callkeep.Constants.KEY_CALLER_NAME;
import static io.wazo.callkeep.Constants.KEY_CALL_HANDLE;
import static io.wazo.callkeep.Constants.KEY_UUID;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter;
import com.facebook.react.modules.permissions.PermissionsModule;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// @see https://github.com/kbagchiGWC/voice-quickstart-android/blob/9a2aff7fbe0d0a5ae9457b48e9ad408740dfb968/exampleConnectionService/src/main/java/com/twilio/voice/examples/connectionservice/VoiceConnectionServiceActivity.java
public class RNCallKeepModule extends ReactContextBaseJavaModule {
    public static final int REQUEST_READ_PHONE_STATE = 1337;
    public static final int REQUEST_REGISTER_CALL_PROVIDER = 394859;

    public static RNCallKeepModule instance = null;

    private static final String E_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST";
    private static final String REACT_NATIVE_MODULE_NAME = "RNCallKeep";
    private static String[] permissions = {
            Build.VERSION.SDK_INT < 30 ? Manifest.permission.READ_PHONE_STATE : Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO
    };

    private static final String TAG = "RNCallKeep";
    private static TelecomManager telecomManager;
    private static TelephonyManager telephonyManager;
    private static Promise hasPhoneAccountPromise;
    private ReactApplicationContext reactContext;
    public static PhoneAccountHandle handle;
    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;
    private static WritableMap _settings;
    private WritableNativeArray delayedEvents;
    private boolean hasListeners = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public static RNCallKeepModule getInstance(ReactApplicationContext reactContext, boolean realContext) {
        if (instance == null) {
            Log.d(TAG, "[RNCallKeepModule] getInstance : " + (reactContext == null ? "null" : "ok"));
            instance = new RNCallKeepModule(reactContext);
        }
        if (realContext) {
            instance.setContext(reactContext);
        }
        return instance;
    }

    public static WritableMap getSettings(@Nullable Context context) {
        if (_settings == null) {
            fetchStoredSettings(context);
        }

        return _settings;
    }

    private RNCallKeepModule(ReactApplicationContext reactContext) {
        super(reactContext);
        Log.d(TAG, "[RNCallKeepModule] constructor");

        this.reactContext = reactContext;
        delayedEvents = new WritableNativeArray();
        this.registerReceiver();
    }

    private boolean isSelfManaged() {
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && _settings.hasKey("selfManaged") && _settings.getBoolean("selfManaged");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return REACT_NATIVE_MODULE_NAME;
    }

    public void setContext(ReactApplicationContext reactContext) {
        Log.d(TAG, "[RNCallKeepModule] updating react context");
        this.reactContext = reactContext;
    }

    public ReactApplicationContext getContext() {
        return this.reactContext;
    }

    public void reportNewIncomingCall(String uuid, String number, String callerName, boolean hasVideo, String payload) {
        Log.d(TAG, "[RNCallKeepModule] reportNewIncomingCall, uuid: " + uuid + ", number: " + number + ", callerName: " + callerName);
        // @TODO: handle video

        this.displayIncomingCall(uuid, number, callerName, hasVideo);

        // Send event to JS
        WritableMap args = Arguments.createMap();
        args.putString("handle", number);
        args.putString("callUUID", uuid);
        args.putString("name", callerName);
        if (payload != null) {
            args.putString("payload", payload);
        }
        sendEventToJS("RNCallKeepDidDisplayIncomingCall", args);
    }

    public void startObserving() {
        int count = delayedEvents.size();
        Log.d(TAG, "[RNCallKeepModule] startObserving, event count: " + count);
        if (count > 0) {
            this.reactContext.getJSModule(RCTDeviceEventEmitter.class).emit("RNCallKeepDidLoadWithEvents", delayedEvents);
            delayedEvents = new WritableNativeArray();
        }
    }

    public void initializeTelecomManager() {
        Context context = this.getAppContext();
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][initializeTelecomManager] no react context found.");
            return;
        }
        ComponentName cName = new ComponentName(context, VoiceConnectionService.class);
        String appName = this.getApplicationName(context);

        handle = new PhoneAccountHandle(cName, appName);
        telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    }

    @ReactMethod
    public void setSettings(ReadableMap options) {
        if (options == null) {
            return;
        }
        Log.d(TAG, "[RNCallKeepModule] setSettings: " + options);
        storeSettings(options);

        _settings = getSettings(null);
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    public void setup(ReadableMap options) {
        Log.d(TAG, "[RNCallKeepModule] setup");

        VoiceConnectionService.setAvailable(false);
        VoiceConnectionService.setInitialized(true);
        this.setSettings(options);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isSelfManaged()) {
                Log.d(TAG, "[RNCallKeepModule] API Version supports self managed, and is enabled in setup");
            } else {
                Log.d(TAG, "[RNCallKeepModule] API Version supports self managed, but it is not enabled in setup");
            }
        }

        // If we're running in self managed mode we need fewer permissions.
        if (isSelfManaged()) {
            Log.d(TAG, "[RNCallKeepModule] setup, adding RECORD_AUDIO in permissions in self managed");
            permissions = new String[]{Manifest.permission.RECORD_AUDIO};
        }

        if (isConnectionServiceAvailable()) {
            this.registerPhoneAccount(options);
            this.registerEvents();
            this.startObserving();
            VoiceConnectionService.setAvailable(true);
        }
    }

    @ReactMethod
    public void registerPhoneAccount(ReadableMap options) {
        storeSettings(options);

        if (!isConnectionServiceAvailable()) {
            Log.w(TAG, "[RNCallKeepModule] registerPhoneAccount ignored due to no ConnectionService");
            return;
        }

        Log.d(TAG, "[RNCallKeepModule] registerPhoneAccount");
        Context context = this.getAppContext();
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][registerPhoneAccount] no react context found.");
            return;
        }

        this.registerPhoneAccount(context);
    }

    @ReactMethod
    public void registerEvents() {
        if (!isConnectionServiceAvailable()) {
            Log.w(TAG, "[RNCallKeepModule] registerEvents ignored due to no ConnectionService");
            return;
        }

        Log.d(TAG, "[RNCallKeepModule] registerEvents");

        this.hasListeners = true;
        this.startObserving();
        VoiceConnectionService.setPhoneAccountHandle(handle);
    }

    @ReactMethod
    public void unregisterEvents() {
        Log.d(TAG, "[RNCallKeepModule] unregisterEvents");

        this.hasListeners = false;
    }

    @ReactMethod
    public void displayIncomingCall(String uuid, String number, String callerName, boolean hasVideo) {
        if (!isConnectionServiceAvailable() || !hasPhoneAccount()) {
            Log.w(TAG, "[RNCallKeepModule] displayIncomingCall ignored due to no ConnectionService or no phone account");
            return;
        }

        Log.d(TAG, "[RNCallKeepModule] displayIncomingCall, uuid: " + uuid + ", number: " + number + ", callerName: " + callerName);

        Bundle extras = new Bundle();
        Uri uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);

        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, uri);
        extras.putString(EXTRA_CALLER_NAME, callerName);
        extras.putString(EXTRA_CALL_UUID, uuid);

        telecomManager.addNewIncomingCall(handle, extras);
    }

    @ReactMethod
    public void answerIncomingCall(String uuid) {
        Log.d(TAG, "[RNCallKeepModule] answerIncomingCall, uuid: " + uuid);
        if (!isConnectionServiceAvailable() || !hasPhoneAccount()) {
            Log.w(TAG, "[RNCallKeepModule] answerIncomingCall ignored due to no ConnectionService or no phone account");
            return;
        }

        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] answerIncomingCall ignored because no connection found, uuid: " + uuid);
            return;
        }

        conn.onAnswer();
    }

    @ReactMethod
    public void startCall(String uuid, String number, String callerName, boolean hasVideo) {
        startCall(uuid, number, callerName);
    }

    @ReactMethod
    public void startCall(String uuid, String number, String callerName) {
        Log.d(TAG, "[RNCallKeepModule] startCall called, uuid: " + uuid + ", number: " + number + ", callerName: " + callerName);

        if (!isConnectionServiceAvailable() || !hasPhoneAccount() || !hasPermissions() || number == null) {
            Log.w(TAG, "[RNCallKeepModule] startCall ignored: " + isConnectionServiceAvailable() + ", " + hasPhoneAccount() + ", " + hasPermissions() + ", " + number);
            return;
        }

        Bundle extras = new Bundle();
        Uri uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);

        Bundle callExtras = new Bundle();
        callExtras.putString(EXTRA_CALLER_NAME, callerName);
        callExtras.putString(EXTRA_CALL_UUID, uuid);
        callExtras.putString(EXTRA_CALL_NUMBER, number);

        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
        extras.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras);

        Log.d(TAG, "[RNCallKeepModule] startCall, uuid: " + uuid);

        telecomManager.placeCall(uri, extras);
    }

    @ReactMethod
    public void endCall(String uuid) {
        endCallNative(uuid);
    }

    public static void endCallNative(@Nullable String uuid) {
        Log.d(TAG, "[RNCallKeepModule] endCall called, uuid: " + uuid);
        if (!isConnectionServiceAvailable() || !hasPhoneAccount()) {
            Log.w(TAG, "[RNCallKeepModule] endCall ignored due to no ConnectionService or no phone account");
            return;
        }

        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] endCall ignored because no connection found, uuid: " + uuid);
            return;
        }
        conn.onDisconnect();
        Log.d(TAG, "[RNCallKeepModule] endCall executed, uuid: " + uuid);
    }

    @ReactMethod
    public void endAllCalls() {
        Log.d(TAG, "[RNCallKeepModule] endAllCalls called");
        if (!isConnectionServiceAvailable() || !hasPhoneAccount()) {
            Log.w(TAG, "[RNCallKeepModule] endAllCalls ignored due to no ConnectionService or no phone account");
            return;
        }

        ArrayList<Map.Entry<String, VoiceConnection>> connections =
                new ArrayList<Map.Entry<String, VoiceConnection>>(VoiceConnectionService.currentConnections.entrySet());
        for (Map.Entry<String, VoiceConnection> connectionEntry : connections) {
            Connection connectionToEnd = connectionEntry.getValue();
            connectionToEnd.onDisconnect();
        }

        Log.d(TAG, "[RNCallKeepModule] endAllCalls executed");
    }

    @ReactMethod
    public void checkPhoneAccountPermission(ReadableArray optionalPermissions, Promise promise) {
        Activity currentActivity = this.getCurrentReactActivity();

        if (!isConnectionServiceAvailable()) {
            String error = "ConnectionService not available for this version of Android.";
            Log.w(TAG, "[RNCallKeepModule] checkPhoneAccountPermission error " + error);
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, error);
            return;
        }
        if (currentActivity == null) {
            String error = "Activity doesn't exist";
            Log.w(TAG, "[RNCallKeepModule] checkPhoneAccountPermission error " + error);
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, error);
            return;
        }
        String[] optionalPermsArr = new String[optionalPermissions.size()];
        for (int i = 0; i < optionalPermissions.size(); i++) {
            optionalPermsArr[i] = optionalPermissions.getString(i);
        }

        final String[] allPermissions = Arrays.copyOf(permissions, permissions.length + optionalPermsArr.length);
        System.arraycopy(optionalPermsArr, 0, allPermissions, permissions.length, optionalPermsArr.length);

        hasPhoneAccountPromise = promise;

        if (!this.hasPermissions()) {
            WritableArray allPermissionaw = Arguments.createArray();
            for (String allPermission : allPermissions) {
                allPermissionaw.pushString(allPermission);
            }

            getReactApplicationContext()
                    .getNativeModule(PermissionsModule.class)
                    .requestMultiplePermissions(allPermissionaw, new Promise() {
                        @Override
                        public void resolve(@Nullable Object value) {
                            WritableMap grantedPermission = (WritableMap) value;
                            int[] grantedResult = new int[allPermissions.length];
                            for (int i = 0; i < allPermissions.length; ++i) {
                                String perm = allPermissions[i];
                                grantedResult[i] = grantedPermission.getString(perm).equals("granted")
                                        ? PackageManager.PERMISSION_GRANTED
                                        : PackageManager.PERMISSION_DENIED;
                            }
                            RNCallKeepModule.onRequestPermissionsResult(REQUEST_READ_PHONE_STATE, allPermissions, grantedResult);
                        }

                        @Override
                        public void reject(String code, String message) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(String code, Throwable throwable) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(String code, String message, Throwable throwable) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(Throwable throwable) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(Throwable throwable, WritableMap userInfo) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(String code, @NonNull WritableMap userInfo) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(String code, Throwable throwable, WritableMap userInfo) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(String code, String message, @NonNull WritableMap userInfo) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(String code, String message, Throwable throwable, WritableMap userInfo) {
                            hasPhoneAccountPromise.resolve(false);
                        }

                        @Override
                        public void reject(String message) {
                            hasPhoneAccountPromise.resolve(false);
                        }
                    });
            return;
        }

        promise.resolve(!hasPhoneAccount());
    }

    @ReactMethod
    public void checkDefaultPhoneAccount(Promise promise) {
        if (!isConnectionServiceAvailable() || !hasPhoneAccount()) {
            promise.resolve(true);
            return;
        }

        if (!Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
            promise.resolve(true);
            return;
        }

        boolean hasSim = telephonyManager.getSimState() != TelephonyManager.SIM_STATE_ABSENT;
        boolean hasDefaultAccount = telecomManager.getDefaultOutgoingPhoneAccount("tel") != null;

        promise.resolve(!hasSim || hasDefaultAccount);
    }

    @ReactMethod
    public void getInitialEvents(Promise promise) {
        promise.resolve(delayedEvents);
    }

    @ReactMethod
    public void clearInitialEvents() {
        delayedEvents = new WritableNativeArray();
    }

    @ReactMethod
    public void setOnHold(String uuid, boolean shouldHold) {
        Log.d(TAG, "[RNCallKeepModule] setOnHold, uuid: " + uuid + ", shouldHold: " + (shouldHold ? "true" : "false"));

        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] setOnHold ignored because no connection found, uuid: " + uuid);
            return;
        }

        if (shouldHold) {
            conn.onHold();
        } else {
            conn.onUnhold();
        }
    }

    @ReactMethod
    public void reportEndCallWithUUID(String uuid, int reason) {
        Log.d(TAG, "[RNCallKeepModule] reportEndCallWithUUID, uuid: " + uuid + ", reason: " + reason);
        if (!isConnectionServiceAvailable() || !hasPhoneAccount()) {
            return;
        }
        if (uuid == null) {
            Log.w(TAG, "[RNCallKeepModule] reportEndCallWithUUID ignored because uuid == null");
            return;
        }
        //region dismiss notification + finish call activity
        Context context = getAppContext();
        if (context != null) {
            Intent intent = CallStatusHelper.endCall(getAppContext());
            context.startService(intent);
            finishCallActivityIfPossible(context);
        }
        //endregion
        VoiceConnection conn = (VoiceConnection) VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] reportEndCallWithUUID ignored because no connection found, uuid: " + uuid);
        } else {
            conn.reportDisconnect(reason);
        }
    }

    private void finishCallActivityIfPossible(@NonNull Context context) {
        ReadableMap settings = VoiceConnectionService.getForegroundSettings(context);
        if (settings == null || !settings.hasKey("callingActivityClass")) {
            Log.w(TAG, "[RNCallKeepModule] failed to fetch callingActivityClass");
            return;
        }
        if (!settings.hasKey("shouldCloseScreenOnDisconnect")) return;
        boolean shouldCloseScreenOnDisconnect = settings.getBoolean("shouldCloseScreenOnDisconnect");
        if (!shouldCloseScreenOnDisconnect) return;
        String activityKlazz = settings.getString("callingActivityClass");
        Activity currentActivity = reactContext.getCurrentActivity();
        if (currentActivity == null || activityKlazz == null) {
            return;
        }
        String simpleActivityName = currentActivity.getClass().getSimpleName();
        String[] parts = activityKlazz.split("\\.");
        if (simpleActivityName.endsWith(parts[parts.length - 1])) {
            currentActivity.finish();
        }
    }

    @ReactMethod
    public void rejectCall(String uuid) {
        Log.d(TAG, "[RNCallKeepModule] rejectCall, uuid: " + uuid);
        if (!isConnectionServiceAvailable() || !hasPhoneAccount()) {
            Log.w(TAG, "[RNCallKeepModule] rejectCall ignored due to no ConnectionService or no phone account");
            return;
        }

        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] rejectCall ignored because no connection found, uuid: " + uuid);
            return;
        }

        conn.onReject();
    }

    @ReactMethod
    public void setConnectionState(String uuid, int state) {
        Log.d(TAG, "[RNCallKeepModule] setConnectionState, uuid: " + uuid + ", state :" + state);
        if (!isConnectionServiceAvailable() || !hasPhoneAccount()) {
            Log.w(TAG, "[RNCallKeepModule] String ignored due to no ConnectionService or no phone account");
            return;
        }

        VoiceConnectionService.setState(uuid, state);
    }

    @ReactMethod
    public void setMutedCall(String uuid, boolean shouldMute) {
        Log.d(TAG, "[RNCallKeepModule] setMutedCall, uuid: " + uuid + ", shouldMute: " + (shouldMute ? "true" : "false"));
        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] setMutedCall ignored because no connection found, uuid: " + uuid);
            return;
        }

        CallAudioState newAudioState = null;
        //if the requester wants to mute, do that. otherwise unmute
        if (shouldMute) {
            newAudioState = new CallAudioState(true, conn.getCallAudioState().getRoute(),
                    conn.getCallAudioState().getSupportedRouteMask());
        } else {
            newAudioState = new CallAudioState(false, conn.getCallAudioState().getRoute(),
                    conn.getCallAudioState().getSupportedRouteMask());
        }
        conn.onCallAudioStateChanged(newAudioState);
    }

    /**
     * toggle audio route for speaker via connection service function
     *
     * @param uuid
     * @param routeSpeaker
     */
    @ReactMethod
    public void toggleAudioRouteSpeaker(String uuid, boolean routeSpeaker) {
        Log.d(TAG, "[RNCallKeepModule] toggleAudioRouteSpeaker, uuid: " + uuid + ", routeSpeaker: " + (routeSpeaker ? "true" : "false"));
        VoiceConnection conn = (VoiceConnection) VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] toggleAudioRouteSpeaker ignored because no connection found, uuid: " + uuid);
            return;
        }
        final int state;
        if (routeSpeaker) {
            state = CallAudioState.ROUTE_SPEAKER;
        } else {
            state = CallAudioState.ROUTE_EARPIECE;
        }
        setCallAudioState(uuid, state);

        notifyCallStatusService(uuid, state, conn);
    }

    public static void setCallAudioState(String uuid, int callAudioState) {
        VoiceConnection conn = (VoiceConnection) VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] toggleAudioRouteSpeaker ignored because no connection found, uuid: " + uuid);
            return;
        }
        if (callAudioState == CallAudioState.ROUTE_SPEAKER) {
            conn.setAudioRoute(CallAudioState.ROUTE_SPEAKER);
            Log.w(TAG, "[RNCallKeepModule] toggleAudioRouteSpeaker set to ROUTE_SPEAKER");
        } else {
            conn.setAudioRoute(CallAudioState.ROUTE_EARPIECE);
            Log.w(TAG, "[RNCallKeepModule] toggleAudioRouteSpeaker set to ROUTE_EARPIECE");
        }
    }

    @ReactMethod
    public void setAudioRoute(String uuid, String audioRoute, Promise promise) {
        try {
            VoiceConnection conn = (VoiceConnection) VoiceConnectionService.getConnection(uuid);
            if (conn == null) {
                return;
            }
            if (audioRoute.equals("Bluetooth")) {
                Log.d(TAG, "[RNCallKeepModule] setting audio route: Bluetooth");
                conn.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH);
                promise.resolve(true);
                return;
            }
            if (audioRoute.equals("Headset")) {
                Log.d(TAG, "[RNCallKeepModule] setting audio route: Headset");
                conn.setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET);
                promise.resolve(true);
                return;
            }
            if (audioRoute.equals("Speaker")) {
                Log.d(TAG, "[RNCallKeepModule] setting audio route: Speaker");
                conn.setAudioRoute(CallAudioState.ROUTE_SPEAKER);
                notifyCallStatusService(uuid, CallAudioState.ROUTE_SPEAKER, conn);
                promise.resolve(true);
                return;
            }
            Log.d(TAG, "[RNCallKeepModule] setting audio route: Wired/Earpiece");
            conn.setAudioRoute(CallAudioState.ROUTE_WIRED_OR_EARPIECE);
            notifyCallStatusService(uuid, CallAudioState.ROUTE_EARPIECE, conn);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("SetAudioRoute", e.getMessage());
        }
    }

    private void notifyCallStatusService(String uuid, int state, VoiceConnection conn) {
        Context context = getAppContext();
        if (context == null) return;
        Bundle extras = new Bundle();
        extras.putString(KEY_UUID, uuid);
        extras.putString(KEY_CALL_HANDLE, conn.getCallNumber());
        extras.putString(KEY_CALLER_NAME, conn.getCallerName());
        Intent changeAudioRouteIntent = toggleAudioRouteIntent(
                context,
                extras,
                state,
                KEY_AUDIO_ROUTE_CHANGER_APP
        );
        ContextCompat.startForegroundService(context, changeAudioRouteIntent);
    }

    @ReactMethod
    public void getAudioRoutes(Promise promise) {
        try {
            Context context = this.getAppContext();
            if (context == null) {
                Log.w(TAG, "[RNCallKeepModule][getAudioRoutes] no react context found.");
                promise.reject(new NullPointerException("No react context found to list audio routes"));
                return;
            }
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            WritableArray devices = Arguments.createArray();
            ArrayList<String> typeChecker = new ArrayList<>();
            AudioDeviceInfo[] audioDeviceInfo = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS + AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : audioDeviceInfo) {
                String type = getAudioRouteType(device.getType());
                if (type != null && !typeChecker.contains(type)) {
                    WritableMap deviceInfo = Arguments.createMap();
                    deviceInfo.putString("name", type);
                    deviceInfo.putString("type", type);
                    typeChecker.add(type);
                    devices.pushMap(deviceInfo);
                }
            }
            promise.resolve(devices);
        } catch (Exception e) {
            promise.reject("GetAudioRoutes Error", e.getMessage());
        }
    }

    private String getAudioRouteType(int type) {
        switch (type) {
            case (AudioDeviceInfo.TYPE_BLUETOOTH_A2DP):
            case (AudioDeviceInfo.TYPE_BLUETOOTH_SCO):
                return "Bluetooth";
            case (AudioDeviceInfo.TYPE_WIRED_HEADPHONES):
            case (AudioDeviceInfo.TYPE_WIRED_HEADSET):
                return "Headset";
            case (AudioDeviceInfo.TYPE_BUILTIN_MIC):
                return "Phone";
            case (AudioDeviceInfo.TYPE_BUILTIN_SPEAKER):
                return "Speaker";
            default:
                return null;
        }
    }

    @ReactMethod
    public void sendDTMF(String uuid, String key) {
        Log.d(TAG, "[RNCallKeepModule] sendDTMF, uuid: " + uuid + ", key: " + key);
        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] sendDTMF ignored because no connection found, uuid: " + uuid);
            return;
        }
        char dtmf = key.charAt(0);
        conn.onPlayDtmfTone(dtmf);
    }

    @ReactMethod
    public void updateDisplay(String uuid, String displayName, String uri) {
        Log.d(TAG, "[RNCallKeepModule] updateDisplay, uuid: " + uuid + ", displayName: " + displayName + ", uri: " + uri);
        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] updateDisplay ignored because no connection found, uuid: " + uuid);
            return;
        }

        conn.setAddress(Uri.parse(uri), TelecomManager.PRESENTATION_ALLOWED);
        conn.setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED);
    }

    @ReactMethod
    public void hasPhoneAccount(Promise promise) {
        if (telecomManager == null) {
            this.initializeTelecomManager();
        }

        promise.resolve(hasPhoneAccount());
    }

    @ReactMethod
    public void hasOutgoingCall(Promise promise) {
        promise.resolve(VoiceConnectionService.hasOutgoingCall);
    }

    @ReactMethod
    public void hasPermissions(Promise promise) {
        promise.resolve(this.hasPermissions());
    }

    @ReactMethod
    public void setAvailable(Boolean active) {
        VoiceConnectionService.setAvailable(active);
    }

    @ReactMethod
    public void setForegroundServiceSettings(ReadableMap foregroundServerSettings) {
        if (foregroundServerSettings == null) {
            return;
        }

        // Retrieve settings and set the `foregroundService` value
        WritableMap settings = getSettings(null);
        if (settings != null) {
            settings.putMap("foregroundService", MapUtils.readableToWritableMap(foregroundServerSettings));
        }

        storeSettings(settings);
    }

    @ReactMethod
    public void canMakeMultipleCalls(Boolean allow) {
        VoiceConnectionService.setCanMakeMultipleCalls(allow);
    }

    @ReactMethod
    public void setReachable() {
        VoiceConnectionService.setReachable();
    }

    @ReactMethod
    public void setCurrentCallActive(String uuid) {
        Log.d(TAG, "[RNCallKeepModule] setCurrentCallActive, uuid: " + uuid);
        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[RNCallKeepModule] setCurrentCallActive ignored because no connection found, uuid: " + uuid);
            return;
        }

        conn.setConnectionCapabilities(conn.getConnectionCapabilities() | Connection.CAPABILITY_HOLD);
        conn.setActive();
    }

    @ReactMethod
    public void openPhoneAccounts() {
        Log.d(TAG, "[RNCallKeepModule] openPhoneAccounts");
        if (!isConnectionServiceAvailable()) {
            Log.w(TAG, "[RNCallKeepModule] openPhoneAccounts ignored due to no ConnectionService");
            return;
        }

        if (Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
            Intent intent = new Intent();
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            intent.setComponent(new ComponentName("com.android.server.telecom",
                    "com.android.server.telecom.settings.EnableAccountPreferenceActivity"));

            Context context = this.getAppContext();
            if (context == null) {
                Log.w(TAG, "[RNCallKeepModule][openPhoneAccounts] no react context found.");
                return;
            }

            context.startActivity(intent);
            return;
        }

        openPhoneAccountSettings();
    }

    @ReactMethod
    public void openPhoneAccountSettings() {
        Log.d(TAG, "[RNCallKeepModule] openPhoneAccountSettings");
        if (!isConnectionServiceAvailable()) {
            Log.w(TAG, "[RNCallKeepModule] openPhoneAccountSettings ignored due to no ConnectionService");
            return;
        }

        Intent intent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Context context = this.getAppContext();
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][openPhoneAccountSettings] no react context found.");
            return;
        }
        context.startActivity(intent);
    }

    public static Boolean isConnectionServiceAvailable() {
        // PhoneAccount is available since api level 23
        return Build.VERSION.SDK_INT >= 23;
    }

    @ReactMethod
    public void isConnectionServiceAvailable(Promise promise) {
        promise.resolve(isConnectionServiceAvailable());
    }

    @ReactMethod
    public void checkPhoneAccountEnabled(Promise promise) {
        promise.resolve(hasPhoneAccount());
    }

    @ReactMethod
    public void backToForeground() {
        Context context = getAppContext();
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][backToForeground] no react context found.");
            return;
        }
        String packageName = context.getApplicationContext().getPackageName();
        Intent focusIntent = context.getPackageManager().getLaunchIntentForPackage(packageName).cloneFilter();
        Activity activity = getCurrentReactActivity();
        boolean isOpened = activity != null;
        Log.d(TAG, "[RNCallKeepModule] backToForeground, app isOpened ?" + (isOpened ? "true" : "false"));

        if (isOpened) {
            focusIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(focusIntent);
        } else {
            focusIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK +
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED +
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD +
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

            getReactApplicationContext().startActivity(focusIntent);
        }
    }

    public static void onRequestPermissionsResult(int requestCode, String[] grantedPermissions, int[] grantResults) {
        int permissionsIndex = 0;
        List<String> permsList = Arrays.asList(permissions);
        for (int result : grantResults) {
            if (permsList.contains(grantedPermissions[permissionsIndex]) && result != PackageManager.PERMISSION_GRANTED) {
                hasPhoneAccountPromise.resolve(false);
                return;
            }
            permissionsIndex++;
        }
        hasPhoneAccountPromise.resolve(true);
    }

    public Activity getCurrentReactActivity() {
        return this.reactContext.getCurrentActivity();
    }

    private void registerPhoneAccount(Context appContext) {
        if (!isConnectionServiceAvailable()) {
            Log.w(TAG, "[RNCallKeepModule] registerPhoneAccount ignored due to no ConnectionService");
            return;
        }

        this.initializeTelecomManager();
        Context context = this.getAppContext();
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][registerPhoneAccount] no react context found.");
            return;
        }
        String appName = this.getApplicationName(context);

        PhoneAccount.Builder builder = new PhoneAccount.Builder(handle, appName);
        if (isSelfManaged()) {
            builder.setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED);
        } else {
            builder.setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER);
        }

        if (_settings != null && _settings.hasKey("imageName")) {
            int identifier = appContext.getResources().getIdentifier(_settings.getString("imageName"), "drawable", appContext.getPackageName());
            Icon icon = Icon.createWithResource(appContext, identifier);
            builder.setIcon(icon);
        }

        PhoneAccount account = builder.build();

        telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        telecomManager.registerPhoneAccount(account);
    }

    private void sendEventToJS(String eventName, @Nullable WritableMap params) {
        boolean isBoundToJS = this.reactContext.hasActiveCatalystInstance();
        Log.v(TAG, "[RNCallKeepModule] sendEventToJS, eventName: " + eventName + ", bound: " + isBoundToJS + ", hasListeners: " + hasListeners + " args : " + (params != null ? params.toString() : "null"));

        if (isBoundToJS && hasListeners) {
            this.reactContext.getJSModule(RCTDeviceEventEmitter.class).emit(eventName, params);
        } else {
            WritableMap data = Arguments.createMap();
            if (params == null) {
                params = Arguments.createMap();
            }

            data.putString("name", eventName);
            data.putMap("data", params);
            delayedEvents.pushMap(data);
        }
    }

    private String getApplicationName(Context appContext) {
        ApplicationInfo applicationInfo = appContext.getApplicationInfo();
        int stringId = applicationInfo.labelRes;

        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : appContext.getString(stringId);
    }

    private Boolean hasPermissions() {
        Activity currentActivity = this.getCurrentReactActivity();

        if (currentActivity == null) {
            return false;
        }

        boolean hasPermissions = true;
        for (String permission : permissions) {
            int permissionCheck = ContextCompat.checkSelfPermission(currentActivity, permission);
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                hasPermissions = false;
            }
        }

        return hasPermissions;
    }

    private static boolean hasPhoneAccount() {
        try {
            if (!isConnectionServiceAvailable()) return false;
            TelecomManager manager = telecomManager;
            if (manager == null) return false;
            PhoneAccount phoneAccount = manager.getPhoneAccount(handle);
            if (phoneAccount == null) {
                return false;
            }
            return phoneAccount.isEnabled();
        } catch (SecurityException exception) {
            return false;
        }
    }

    private void registerReceiver() {
        if (!isReceiverRegistered) {
            voiceBroadcastReceiver = new VoiceBroadcastReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_END_CALL);
            intentFilter.addAction(ACTION_ANSWER_CALL);
            intentFilter.addAction(ACTION_MUTE_CALL);
            intentFilter.addAction(ACTION_UNMUTE_CALL);
            intentFilter.addAction(ACTION_DTMF_TONE);
            intentFilter.addAction(ACTION_UNHOLD_CALL);
            intentFilter.addAction(ACTION_HOLD_CALL);
            intentFilter.addAction(ACTION_ONGOING_CALL);
            intentFilter.addAction(ACTION_AUDIO_SESSION);
            intentFilter.addAction(ACTION_CHECK_REACHABILITY);
            intentFilter.addAction(ACTION_SHOW_INCOMING_CALL_UI);
            intentFilter.addAction(ACTION_ON_SILENCE_INCOMING_CALL);
            intentFilter.addAction(ACTION_ON_CREATE_CONNECTION_FAILED);
            intentFilter.addAction(ACTION_DID_CHANGE_AUDIO_ROUTE);

            if (this.reactContext != null) {
                LocalBroadcastManager.getInstance(this.reactContext).registerReceiver(voiceBroadcastReceiver, intentFilter);
                isReceiverRegistered = true;

                VoiceConnectionService.startObserving();
            }
        }
    }

    @Nullable
    private Context getAppContext() {
        return this.reactContext != null ? this.reactContext.getApplicationContext() : null;
    }

    // Store all callkeep settings in JSON
    private void storeSettings(ReadableMap options) {
        Context context = getInstance(null, false).getAppContext();
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][storeSettings] no react context found.");
            return;
        }

        SharedPreferences sharedPref = context.getSharedPreferences("rn-callkeep", Context.MODE_PRIVATE);
        try {
            JSONObject jsonObject = MapUtils.convertMapToJson(options);
            String jsonString = jsonObject.toString();
            sharedPref.edit().putString("settings", jsonString).apply();
        } catch (JSONException e) {
        }
    }

    private static void fetchStoredSettings(@Nullable Context fromContext) {
        if (instance == null && fromContext == null) {
            Log.w(TAG, "[RNCallKeepModule][fetchStoredSettings] no instance nor fromContext.");
            return;
        }
        Context context = fromContext != null ? fromContext : instance.getAppContext();
        _settings = new WritableNativeMap();
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][fetchStoredSettings] no react context found.");
            return;
        }

        SharedPreferences sharedPref = context.getSharedPreferences("rn-callkeep", Context.MODE_PRIVATE);
        try {
            String jsonString = sharedPref.getString("settings", (new JSONObject()).toString());
            if (jsonString != null) {
                JSONObject jsonObject = new JSONObject(jsonString);

                _settings = MapUtils.convertJsonToMap(jsonObject);
            }
        } catch (JSONException e) {
        }
    }

    private class VoiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            WritableMap args = Arguments.createMap();
            HashMap<String, String> attributeMap = (HashMap<String, String>) intent.getSerializableExtra("attributeMap");

            Log.d(TAG, "[RNCallKeepModule][onReceive] :" + intent.getAction());

            String uuid = attributeMap.get(EXTRA_CALL_UUID);
            String callHandle = attributeMap.get(EXTRA_CALL_NUMBER);
            String callerName = attributeMap.get(EXTRA_CALLER_NAME);

            switch (intent.getAction()) {
                case ACTION_END_CALL:
                    args.putString("callUUID", uuid);
                    context.startService(CallStatusHelper.endCall(context));
                    sendEventToJS("RNCallKeepPerformEndCallAction", args);
                    break;
                case ACTION_ANSWER_CALL:
                    args.putString("callUUID", uuid);

                    sendEventToJS("RNCallKeepPerformAnswerCallAction", args);
                    break;
                case ACTION_HOLD_CALL:
                    args.putBoolean("hold", true);
                    args.putString("callUUID", uuid);
                    sendEventToJS("RNCallKeepDidToggleHoldAction", args);
                    break;
                case ACTION_UNHOLD_CALL:
                    args.putBoolean("hold", false);
                    args.putString("callUUID", uuid);
                    sendEventToJS("RNCallKeepDidToggleHoldAction", args);
                    break;
                case ACTION_MUTE_CALL:
                    args.putBoolean("muted", true);
                    args.putString("callUUID", uuid);
                    sendEventToJS("RNCallKeepDidPerformSetMutedCallAction", args);
                    break;
                case ACTION_UNMUTE_CALL:
                    args.putBoolean("muted", false);
                    args.putString("callUUID", uuid);
                    sendEventToJS("RNCallKeepDidPerformSetMutedCallAction", args);
                    break;
                case ACTION_DTMF_TONE:
                    args.putString("digits", attributeMap.get("DTMF"));
                    args.putString("callUUID", uuid);
                    sendEventToJS("RNCallKeepDidPerformDTMFAction", args);
                    break;
                case ACTION_ONGOING_CALL:
                    args.putString("handle", callHandle);
                    args.putString("callUUID", uuid);
                    args.putString("name", callerName);

                    Intent startOngoingCallIntent = CallStatusHelper.callStartedIntent(
                            context,
                            uuid,
                            true,
                            callHandle,
                            callerName
                    );
                    ContextCompat.startForegroundService(context, startOngoingCallIntent);

                    sendEventToJS("RNCallKeepDidReceiveStartCallAction", args);
                    break;
                case ACTION_AUDIO_SESSION:
                    sendEventToJS("RNCallKeepDidActivateAudioSession", null);
                    break;
                case ACTION_CHECK_REACHABILITY:
                    sendEventToJS("RNCallKeepCheckReachability", null);
                    break;
                case ACTION_SHOW_INCOMING_CALL_UI:
                    args.putString("handle", callHandle);
                    args.putString("callUUID", uuid);
                    args.putString("name", callerName);

                    Intent incomingCallUiIntent = CallStatusHelper.callStartedIntent(
                            context,
                            uuid,
                            false,
                            callHandle,
                            callerName
                    );
                    ContextCompat.startForegroundService(context, incomingCallUiIntent);

                    sendEventToJS("RNCallKeepShowIncomingCallUi", args);
                    break;
                case ACTION_WAKE_APP:
                    Intent headlessIntent = new Intent(reactContext, RNCallKeepBackgroundMessagingService.class);
                    headlessIntent.putExtra("callUUID", uuid);
                    headlessIntent.putExtra("name", attributeMap.get(EXTRA_CALLER_NAME));
                    headlessIntent.putExtra("handle", callHandle);
                    Log.d(TAG, "[RNCallKeepModule] wakeUpApplication: " + uuid + ", number : " + attributeMap.get(EXTRA_CALL_NUMBER) + ", displayName:" + attributeMap.get(EXTRA_CALLER_NAME));

                    ComponentName name = reactContext.startService(headlessIntent);
                    if (name != null) {
                        HeadlessJsTaskService.acquireWakeLockNow(reactContext);
                    }
                    break;
                case ACTION_ON_SILENCE_INCOMING_CALL:
                    args.putString("handle", callHandle);
                    args.putString("callUUID", uuid);
                    args.putString("name", callerName);
                    sendEventToJS("RNCallKeepOnSilenceIncomingCall", args);
                    break;
                case ACTION_ON_CREATE_CONNECTION_FAILED:
                    args.putString("handle", callHandle);
                    args.putString("callUUID", uuid);
                    args.putString("name", callerName);
                    context.startService(CallStatusHelper.endCall(context));
                    sendEventToJS("RNCallKeepOnIncomingConnectionFailed", args);
                case ACTION_DID_CHANGE_AUDIO_ROUTE:
                    String audioRoute = attributeMap.get("output");
                    args.putString("handle", callHandle);
                    args.putString("callUUID", uuid);
                    args.putString("output", audioRoute);
                    Log.w(TAG, "didChangeAudioRoute: " + audioRoute);

                    sendEventToJS("RNCallKeepDidChangeAudioRoute", args);
                    break;
            }
        }
    }

    private void showToast(final String message) {
        if (Looper.getMainLooper() != Looper.myLooper()) {
            handler.post(() -> showToast(message));
        } else {
            Toast.makeText(getAppContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
