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

import static io.wazo.callkeep.CallStatusHelper.endCall;
import static io.wazo.callkeep.CallStatusHelper.getForegroundServiceCompat;
import static io.wazo.callkeep.CallStatusHelper.toggleAudioRouteIntent;
import static io.wazo.callkeep.Constants.ACTION_AUDIO_SESSION;
import static io.wazo.callkeep.Constants.ACTION_CALL_IN_PROGRESS;
import static io.wazo.callkeep.Constants.ACTION_CHECK_REACHABILITY;
import static io.wazo.callkeep.Constants.ACTION_END_CALL;
import static io.wazo.callkeep.Constants.ACTION_ONGOING_CALL;
import static io.wazo.callkeep.Constants.ACTION_ON_CREATE_CONNECTION_FAILED;
import static io.wazo.callkeep.Constants.ACTION_TOGGLE_AUDIOROUTE;
import static io.wazo.callkeep.Constants.AUDIO_ROUTE;
import static io.wazo.callkeep.Constants.EXTRA_CALLER_NAME;
import static io.wazo.callkeep.Constants.EXTRA_CALL_NUMBER;
import static io.wazo.callkeep.Constants.EXTRA_CALL_NUMBER_SCHEMA;
import static io.wazo.callkeep.Constants.EXTRA_CALL_UUID;
import static io.wazo.callkeep.Constants.EXTRA_DISABLE_ADD_CALL;
import static io.wazo.callkeep.Constants.FOREGROUND_SERVICE_TYPE_MICROPHONE;
import static io.wazo.callkeep.Constants.KEY_UUID;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// @see https://github.com/kbagchiGWC/voice-quickstart-android/blob/9a2aff7fbe0d0a5ae9457b48e9ad408740dfb968/exampleConnectionService/src/main/java/com/twilio/voice/examples/connectionservice/VoiceConnectionService.java
@TargetApi(Build.VERSION_CODES.M)
public class VoiceConnectionService extends ConnectionService {
    private static Boolean isAvailable = false;
    private static Boolean isInitialized = false;
    private static Boolean isReachable = false;
    private static Boolean canMakeMultipleCalls = true;
    private static String notReachableCallUuid;
    private static ConnectionRequest currentConnectionRequest;
    private static PhoneAccountHandle phoneAccountHandle;
    private static final String TAG = "RNCallKeep";

    // Delay events sent to RNCallKeepModule when there is no listener available
    private static List<Bundle> delayedEvents = new ArrayList<Bundle>();

    public static Map<String, VoiceConnection> currentConnections = new HashMap<>();
    public static Boolean hasOutgoingCall = false;
    public static VoiceConnectionService currentConnectionService = null;

    public static Connection getConnection(String connectionId) {
        if (currentConnections.containsKey(connectionId)) {
            return currentConnections.get(connectionId);
        }
        return null;
    }

    public VoiceConnectionService() {
        super();
        Log.d(TAG, "[VoiceConnectionService] Constructor");
        currentConnectionRequest = null;
        currentConnectionService = this;
    }

    public static void setPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
        VoiceConnectionService.phoneAccountHandle = phoneAccountHandle;
    }

    public static void setAvailable(Boolean value) {
        Log.d(TAG, "[VoiceConnectionService] setAvailable: " + (value ? "true" : "false"));
        if (value) {
            setInitialized(true);
        }

        isAvailable = value;
    }

    public static WritableMap getSettings(@Nullable Context context) {
        WritableMap settings = RNCallKeepModule.getSettings(context);
        return settings;
    }

    public static ReadableMap getForegroundSettings(@Nullable Context context) {
        WritableMap settings = VoiceConnectionService.getSettings(context);
        if (settings == null) {
            return null;
        }

        return settings.getMap("foregroundService");
    }

    public static void setCanMakeMultipleCalls(Boolean value) {
        Log.d(TAG, "[VoiceConnectionService] setCanMakeMultipleCalls: " + (value ? "true" : "false"));

        VoiceConnectionService.canMakeMultipleCalls = value;
    }

    public static void setReachable() {
        Log.d(TAG, "[VoiceConnectionService] setReachable");
        isReachable = true;
        VoiceConnectionService.currentConnectionRequest = null;
    }

    public static void setInitialized(boolean value) {
        Log.d(TAG, "[VoiceConnectionService] setInitialized: " + (value ? "true" : "false"));

        isInitialized = value;
    }

    public static void deinitConnection(String connectionId) {
        Log.d(TAG, "[VoiceConnectionService] deinitConnection:" + connectionId);
        VoiceConnectionService.hasOutgoingCall = false;

        currentConnectionService.stopForegroundService();
        currentConnections.remove(connectionId);
    }

    public static void setState(String uuid, int state) {
        Connection conn = VoiceConnectionService.getConnection(uuid);
        if (conn == null) {
            Log.w(TAG, "[VoiceConnectionService] setState ignored because no connection found, uuid: " + uuid);
            return;
        }

        switch (state) {
            case Connection.STATE_ACTIVE:
                conn.setActive();
                break;
            case Connection.STATE_DIALING:
                conn.setDialing();
                break;
            case Connection.STATE_HOLDING:
                conn.setOnHold();
                break;
            case Connection.STATE_INITIALIZING:
                conn.setInitializing();
                break;
            case Connection.STATE_RINGING:
                conn.setRinging();
                break;
        }
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        final Bundle extra = request.getExtras();
        Uri number = request.getAddress();
        String name = extra.getString(EXTRA_CALLER_NAME);
        String callUUID = extra.getString(EXTRA_CALL_UUID);
        boolean isForeground = VoiceConnectionService.isRunning(this.getApplicationContext());
        WritableMap settings = getSettings(this);
        Integer timeout = settings.hasKey("displayCallReachabilityTimeout") ? settings.getInt("displayCallReachabilityTimeout") : null;

        Log.d(TAG, "[VoiceConnectionService] onCreateIncomingConnection, name:" + name + ", number" + number +
                ", isForeground: " + isForeground + ", isReachable:" + isReachable + ", timeout: " + timeout);

        Connection incomingCallConnection = createConnection(request);
        incomingCallConnection.setRinging();
        incomingCallConnection.setInitialized();

        startForegroundService();

        if (timeout != null) {
            this.checkForAppReachability(callUUID, timeout);
        }

        return incomingCallConnection;
    }

    //endregion

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        VoiceConnectionService.hasOutgoingCall = true;
        String uuid = UUID.randomUUID().toString();

        Log.d(TAG, "[VoiceConnectionService] onCreateOutgoingConnection, uuid:" + uuid);

        if (!isInitialized && !isReachable) {
            notReachableCallUuid = uuid;
            currentConnectionRequest = request;
            this.checkReachability();
        }

        return this.makeOutgoingCall(request, uuid, false);
    }

    private Connection makeOutgoingCall(ConnectionRequest request, String uuid, Boolean forceWakeUp) {
        Bundle extras = request.getExtras();
        Connection outgoingCallConnection = null;
        String number = request.getAddress().getSchemeSpecificPart();
        String extrasNumber = extras.getString(EXTRA_CALL_NUMBER);
        String displayName = extras.getString(EXTRA_CALLER_NAME);
        Boolean isForeground = VoiceConnectionService.isRunning(this.getApplicationContext());

        Log.d(TAG, "[VoiceConnectionService] makeOutgoingCall, uuid:" + uuid + ", number: " + number + ", displayName:" + displayName);

        // Wakeup application if needed
        if (!isForeground || forceWakeUp) {
            Log.d(TAG, "[VoiceConnectionService] onCreateOutgoingConnection: Waking up application");
            this.wakeUpApplication(uuid, number, displayName);
        } else if (!this.canMakeOutgoingCall() && isReachable) {
            Log.d(TAG, "[VoiceConnectionService] onCreateOutgoingConnection: not available");
            return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.LOCAL));
        }

        // TODO: Hold all other calls
        if (extrasNumber == null || !extrasNumber.equals(number)) {
            extras.putString(EXTRA_CALL_UUID, uuid);
            extras.putString(EXTRA_CALLER_NAME, displayName);
            extras.putString(EXTRA_CALL_NUMBER, number);
        }

        if (!canMakeMultipleCalls) {
            Log.d(TAG, "[VoiceConnectionService] onCreateOutgoingConnection: disabling multi calls");
            extras.putBoolean(EXTRA_DISABLE_ADD_CALL, true);
        }

        outgoingCallConnection = createConnection(request);
        outgoingCallConnection.setDialing();
        outgoingCallConnection.setAudioModeIsVoip(true);
        outgoingCallConnection.setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED);

        startForegroundService();

        // ‍️Weirdly on some Samsung phones (A50, S9...) using `setInitialized` will not display the native UI ...
        // when making a call from the native Phone application. The call will still be displayed correctly without it.
        if (!Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
            Log.d(TAG, "[VoiceConnectionService] onCreateOutgoingConnection: initializing connection on Samsung device");
            outgoingCallConnection.setInitialized();
        }

        HashMap<String, String> extrasMap = this.bundleToMap(extras);

        sendCallRequestToActivity(ACTION_ONGOING_CALL, extrasMap, true);
        sendCallRequestToActivity(ACTION_AUDIO_SESSION, extrasMap, true);

        Log.d(TAG, "[VoiceConnectionService] onCreateOutgoingConnection: done");

        return outgoingCallConnection;
    }


    private void startForegroundService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Foreground services not required before SDK 28
            return;
        }
        Log.d(TAG, "[VoiceConnectionService] startForegroundService");
        ReadableMap foregroundSettings = getForegroundSettings(null);

        if (foregroundSettings == null || !foregroundSettings.hasKey("channelId")) {
            Log.w(TAG, "[VoiceConnectionService] Not creating foregroundService because not configured");
            return;
        }

        if (foregroundSettings.hasKey("callingActivityClass")) {
            onCallInProgress(new Bundle());
            return;
        }

        String NOTIFICATION_CHANNEL_ID = foregroundSettings.getString("channelId");

        String channelName = foregroundSettings.getString("channelName");
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        notificationBuilder.setOngoing(true)
                .setContentTitle(foregroundSettings.getString("notificationTitle"))
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (foregroundSettings.hasKey("notificationIcon")) {
            Context context = this.getApplicationContext();
            Resources res = context.getResources();
            String smallIcon = foregroundSettings.getString("notificationIcon");
            notificationBuilder.setSmallIcon(res.getIdentifier(smallIcon, "mipmap", context.getPackageName()));
        }

        Log.d(TAG, "[VoiceConnectionService] Starting foreground service");

        Notification notification = notificationBuilder.build();
        startForeground(FOREGROUND_SERVICE_TYPE_MICROPHONE, notification);
    }


    private void stopForegroundService() {
        Log.d(TAG, "[VoiceConnectionService] stopForegroundService");
        ReadableMap foregroundSettings = getForegroundSettings(null);

        if (foregroundSettings == null || !foregroundSettings.hasKey("channelId")) {
            Log.d(TAG, "[VoiceConnectionService] Discarding stop foreground service, no service configured");
            return;
        }

        stopForeground(true);
    }

    private void wakeUpApplication(String uuid, String number, String displayName) {
        Log.d(TAG, "[VoiceConnectionService] wakeUpApplication, uuid:" + uuid + ", number :" + number + ", displayName:" + displayName);

        // Avoid to call wake up the app again in wakeUpAfterReachabilityTimeout.
        this.currentConnectionRequest = null;

        Intent headlessIntent = new Intent(
                this.getApplicationContext(),
                RNCallKeepBackgroundMessagingService.class
        );
        headlessIntent.putExtra("callUUID", uuid);
        headlessIntent.putExtra("name", displayName);
        headlessIntent.putExtra("handle", number);

        ComponentName name = this.getApplicationContext().startService(headlessIntent);
        if (name != null) {
            Log.d(TAG, "[VoiceConnectionService] wakeUpApplication, acquiring lock for application:" + name);
            HeadlessJsTaskService.acquireWakeLockNow(this.getApplicationContext());
        }
    }

    private void wakeUpAfterReachabilityTimeout(ConnectionRequest request) {
        if (currentConnectionRequest == null) {
            return;
        }
        Bundle extras = request.getExtras();
        String number = request.getAddress().getSchemeSpecificPart();
        String displayName = extras.getString(EXTRA_CALLER_NAME);
        Log.d(TAG, "[VoiceConnectionService] checkReachability timeout, force wakeup, number :" + number + ", displayName: " + displayName);

        wakeUpApplication(notReachableCallUuid, number, displayName);

        VoiceConnectionService.currentConnectionRequest = null;
    }

    private void checkReachability() {
        Log.d(TAG, "[VoiceConnectionService] checkReachability");

        final VoiceConnectionService instance = this;
        sendCallRequestToActivity(ACTION_CHECK_REACHABILITY, null, true);

        new android.os.Handler().postDelayed(
                () -> instance.wakeUpAfterReachabilityTimeout(instance.currentConnectionRequest),
                2000
        );
    }

    private Boolean canMakeOutgoingCall() {
        return isAvailable;
    }

    private Connection createConnection(ConnectionRequest request) {
        Bundle extras = request.getExtras();
        if (request.getAddress() == null) {
            return null;
        }
        HashMap<String, String> extrasMap = this.bundleToMap(extras);

        String callerNumber = request.getAddress().toString();
        Log.d(TAG, "[VoiceConnectionService] createConnection, callerNumber:" + callerNumber);

        if (callerNumber.contains(":")) {
            //CallerNumber contains a schema which we'll separate out
            int schemaIndex = callerNumber.indexOf(":");
            String number = callerNumber.substring(schemaIndex + 1);
            String schema = callerNumber.substring(0, schemaIndex);

            extrasMap.put(EXTRA_CALL_NUMBER, number);
            extrasMap.put(EXTRA_CALL_NUMBER_SCHEMA, schema);
        } else {
            extrasMap.put(EXTRA_CALL_NUMBER, callerNumber);
        }

        VoiceConnection connection = new VoiceConnection(this, extrasMap);
        connection.setConnectionCapabilities(Connection.CAPABILITY_MUTE | Connection.CAPABILITY_SUPPORT_HOLD);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = getApplicationContext();
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            PhoneAccount phoneAccount = telecomManager.getPhoneAccount(request.getAccountHandle());

            //If the phone account is self managed, then this connection must also be self managed.
            if ((phoneAccount.getCapabilities() & PhoneAccount.CAPABILITY_SELF_MANAGED) == PhoneAccount.CAPABILITY_SELF_MANAGED) {
                Log.d(TAG, "[VoiceConnectionService] PhoneAccount is SELF_MANAGED, so connection will be too");
                connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
            } else {
                Log.d(TAG, "[VoiceConnectionService] PhoneAccount is not SELF_MANAGED, so connection won't be either");
            }
        }

        connection.setInitializing();
        connection.setExtras(extras);
        currentConnections.put(extras.getString(EXTRA_CALL_UUID), connection);

        // Get other connections for conferencing
        Map<String, VoiceConnection> otherConnections = new HashMap<>();
        for (Map.Entry<String, VoiceConnection> entry : currentConnections.entrySet()) {
            if (!(extras.getString(EXTRA_CALL_UUID).equals(entry.getKey()))) {
                otherConnections.put(entry.getKey(), entry.getValue());
            }
        }
        List<Connection> conferenceConnections = new ArrayList<>(otherConnections.values());
        connection.setConferenceableConnections(conferenceConnections);

        return connection;
    }

    @Override
    public void onConference(Connection connection1, Connection connection2) {
        Log.d(TAG, "[VoiceConnectionService] onConference");
        super.onConference(connection1, connection2);
        VoiceConnection voiceConnection1 = (VoiceConnection) connection1;
        VoiceConnection voiceConnection2 = (VoiceConnection) connection2;

        VoiceConference voiceConference = new VoiceConference(phoneAccountHandle);
        voiceConference.addConnection(voiceConnection1);
        voiceConference.addConnection(voiceConnection2);

        connection1.onUnhold();
        connection2.onUnhold();

        this.addConference(voiceConference);
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request);
        Log.w(TAG, "[VoiceConnectionService] onCreateIncomingConnectionFailed: " + request);

        Bundle extras = request.getExtras();
        HashMap<String, String> extrasMap = this.bundleToMap(extras);

        String callerNumber = request.getAddress().toString();
        if (callerNumber.contains(":")) {
            //CallerNumber contains a schema which we'll separate out
            int schemaIndex = callerNumber.indexOf(":");
            String number = callerNumber.substring(schemaIndex + 1);
            String schema = callerNumber.substring(0, schemaIndex);

            extrasMap.put(EXTRA_CALL_NUMBER, number);
            extrasMap.put(EXTRA_CALL_NUMBER_SCHEMA, schema);
        } else {
            extrasMap.put(EXTRA_CALL_NUMBER, callerNumber);
        }

        sendCallRequestToActivity(ACTION_ON_CREATE_CONNECTION_FAILED, extrasMap, true);
    }

    // When a listener is available for `sendCallRequestToActivity`, send delayed events.
    public static void startObserving() {
        new Handler(Looper.getMainLooper()).post(() -> {
            // Run this in a Looper to avoid : java.lang.RuntimeException: Can't create handler inside thread Thread
            int count = delayedEvents.size();
            Log.d(TAG, "[VoiceConnectionService] startObserving, event count: " + count);

            for (Bundle event : delayedEvents) {
                String action = event.getString("action");
                HashMap attributeMap = (HashMap) event.getSerializable("attributeMap");

                currentConnectionService.sendCallRequestToActivity(action, attributeMap, false);
            }

            delayedEvents = new ArrayList<>();
        });
    }

    /*
     * Send call request to the RNCallKeepModule
     */
    private void sendCallRequestToActivity(final String action, @Nullable final HashMap attributeMap, final boolean retry) {
        final VoiceConnectionService instance = this;
        final Handler handler = new Handler();

        Log.d(TAG, "[VoiceConnectionService] sendCallRequestToActivity, action:" + action);

        handler.post(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(action);
                Bundle extras = new Bundle();
                extras.putString("action", action);

                if (attributeMap != null) {
                    extras.putSerializable("attributeMap", attributeMap);
                    intent.putExtras(extras);
                }

                boolean result = LocalBroadcastManager.getInstance(instance).sendBroadcast(intent);
                if (!result && retry) {
                    // Event will be sent later when a listener will be available.
                    delayedEvents.add(extras);
                }
            }
        });
    }

    private HashMap<String, String> bundleToMap(Bundle extras) {
        HashMap<String, String> extrasMap = new HashMap<>();
        Set<String> keySet = extras.keySet();
        Iterator<String> iterator = keySet.iterator();

        while (iterator.hasNext()) {
            String key = iterator.next();
            if (extras.get(key) != null) {
                extrasMap.put(key, extras.get(key).toString());
            }
        }
        return extrasMap;
    }

    /**
     * https://stackoverflow.com/questions/5446565/android-how-do-i-check-if-activity-is-running
     *
     * @param context Context
     * @return boolean
     */
    public static boolean isRunning(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<RunningTaskInfo> tasks = activityManager.getRunningTasks(Integer.MAX_VALUE);

        for (RunningTaskInfo task : tasks) {
            if (context.getPackageName().equalsIgnoreCase(task.baseActivity.getPackageName())) {
                return true;
            }
        }

        Log.d(TAG, "[VoiceConnectionService] isRunning: no running package found.");

        return false;
    }

    private void checkForAppReachability(final String callUUID, final Integer timeout) {
        final VoiceConnectionService instance = this;

        new android.os.Handler().postDelayed(new Runnable() {
            public void run() {
                if (instance.isReachable) {
                    return;
                }
                Connection conn = VoiceConnectionService.getConnection(callUUID);
                Log.w(TAG, "[VoiceConnectionService] checkForAppReachability timeout after " + timeout + " ms, isReachable:" + instance.isReachable + ", uuid: " + callUUID);

                if (conn == null) {
                    Log.w(TAG, "[VoiceConnectionService] checkForAppReachability timeout, no connection to close with uuid: " + callUUID);

                    return;
                }
                conn.onDisconnect();
            }
        }, timeout);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        final Bundle serviceExtras = intent.getExtras();
        Log.i(TAG, "action: " + intent.getAction() + ", extras: " + serviceExtras);
        String uuid = serviceExtras.getString(KEY_UUID, null);
        String action = intent.getAction();
        switch (action) {
            case ACTION_TOGGLE_AUDIOROUTE:
                int targetAudioRoute = serviceExtras.getInt(AUDIO_ROUTE, CallAudioState.ROUTE_EARPIECE);
                if (uuid != null) {
                    RNCallKeepModule.setCallAudioState(uuid, targetAudioRoute);
                }
                onCallInProgress(serviceExtras);
                return START_STICKY;
            case ACTION_CALL_IN_PROGRESS:
                onCallInProgress(serviceExtras);
                return START_STICKY;
            case ACTION_END_CALL:
                onCallEnded(uuid);
                return START_NOT_STICKY;
            default:
                Log.i(TAG, "onStartCommand: unknown action " + action);
                return START_NOT_STICKY;
        }
    }

    //region INTERACTION IMPLEMENTATION METHODS

    private void onCallEnded(String uuid) {
        Log.w(TAG, "onCallEnded: " + uuid);
        if (uuid != null) {
            RNCallKeepModule.endCallNative(uuid);
        }
        stopForeground(true);
        stopSelf();
    }


    private String resolveChannelId() {
        ReadableMap map = VoiceConnectionService.getForegroundSettings(this);
        if (map == null) return null;
        return map.getString("channelId");
    }

    private void onCallInProgress(Bundle serviceExtras) {
        String channelId = resolveChannelId();
        Notification lastNotification = new NotificationHelper(this).getCallInProgressNotification(
                channelId,
                serviceExtras,
                onReturnToAppClicked(),
                endCallPendingIntent(),
                toggleAudioRoutePendingIntent()
        );
        startForeground(FOREGROUND_SERVICE_TYPE_MICROPHONE, lastNotification);
    }

    public static PendingIntentFactory toggleAudioRoutePendingIntent() {
        return (context, extras) -> {
            String uuid = extras.getString(KEY_UUID, null);
            int route = extras.getInt(AUDIO_ROUTE, CallAudioState.ROUTE_EARPIECE);
            final int targetRoute;
            final int requestCode;
            if (route == CallAudioState.ROUTE_EARPIECE) {
                targetRoute = CallAudioState.ROUTE_SPEAKER;
                requestCode = RequestCodes.ROUTE_SPEAKER.requestCode;
            } else {
                targetRoute = CallAudioState.ROUTE_EARPIECE;
                requestCode = RequestCodes.ROUTE_EARPIECE.requestCode;
            }
            Intent intent = toggleAudioRouteIntent(context, uuid, targetRoute);
            return getForegroundServiceCompat(context, requestCode, intent);
        };
    }

    public static PendingIntentFactory onReturnToAppClicked() {
        return (context, bundle) -> {
            Intent contentIntent = new Intent(context, resolveCallingActivityClass(context));
            contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            contentIntent.setAction(Intent.ACTION_VIEW);

            ReadableMap foregroundSettings = VoiceConnectionService.getForegroundSettings(context);
            String defaultDeeplinkScheme = "rn-callkeep";
            final String deeplinkScheme;
            if (foregroundSettings == null) {
                deeplinkScheme = defaultDeeplinkScheme;
            } else {
                String scheme = foregroundSettings.getString("deeplinkScheme");
                if (scheme == null) {
                    deeplinkScheme = defaultDeeplinkScheme;
                } else {
                    deeplinkScheme = scheme;
                }
            }

            String deeplink = new DeeplinkBuilder(deeplinkScheme, "return-to-app", bundle).toString();
            contentIntent.setData(Uri.parse(deeplink));
            return PendingIntent.getActivity(
                    context,
                    RequestCodes.OPEN_CALLING_SCREEN.requestCode,
                    contentIntent,
                    pendingIntentFlags()
            );
        };
    }


    private static int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private static String getFallbackActivityLauncherClassName(Context context) {
        String packageName = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        return launchIntent.getComponent().getClassName();
    }

    private static String resolveCallingClassName(Context context) {
        ReadableMap foregroundSettings = VoiceConnectionService.getForegroundSettings(context);
        if (foregroundSettings == null) {
            return getFallbackActivityLauncherClassName(context);
        }
        String claasName = foregroundSettings.getString("callingActivityClass");
        if (claasName == null) {
            return getFallbackActivityLauncherClassName(context);
        } else {
            return claasName;
        }
    }

    private static Class<?> resolveCallingActivityClass(Context context) {
        String activityClassName = resolveCallingClassName(context);
        try {
            return (Class<?>) Class.forName(activityClassName);
        } catch (Exception e) {
            Log.e(TAG, "resolveCallingActivityClass: ", e);
            throw new NotificationHelper.MissingActivityLauncherException(e);
        }
    }

    public static PendingIntentFactory endCallPendingIntent() {
        return (context, bundle) -> {
            Intent intent = endCall(context, bundle.getString(KEY_UUID));
            int req = RequestCodes.END_CALL.requestCode;
            // we're attempt to stop foreground service(without notification),
            // so there's not point to do it - we want to dismiss ongoing notification
            return PendingIntent.getService(context, req, intent, pendingIntentFlags());
        };
    }
    //endregion

}
