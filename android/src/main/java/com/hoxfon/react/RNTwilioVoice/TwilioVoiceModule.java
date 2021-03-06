package com.hoxfon.react.RNTwilioVoice;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.content.DialogInterface;

import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import android.support.v7.app.AlertDialog;

import com.facebook.react.bridge.JSApplicationIllegalArgumentException;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableMap;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import com.google.firebase.FirebaseApp;
import com.google.firebase.iid.FirebaseInstanceId;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.LogLevel;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.Voice;

import java.util.HashMap;
import java.util.Map;

public class TwilioVoiceModule extends ReactContextBaseJavaModule implements ActivityEventListener, LifecycleEventListener {

    public static String TAG = "RNTwilioVoice";

    private static final int MIC_PERMISSION_REQUEST_CODE = 1;

    private AudioManager audioManager;
    private int savedAudioMode = AudioManager.MODE_INVALID;

    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;

    // Empty HashMap, contains parameters for the Outbound call
    private HashMap<String, String> twiMLParams = new HashMap<>();

    public static final String EVENT_DEVICE_READY = "deviceReady";
    public static final String EVENT_DEVICE_NOT_READY = "deviceNotReady";
    public static final String EVENT_CONNECTION_DID_CONNECT = "connectionDidConnect";
    public static final String EVENT_CONNECTION_DID_DISCONNECT = "connectionDidDisconnect";
    public static final String EVENT_DEVICE_DID_RECEIVE_INCOMING = "deviceDidReceiveIncoming";


    public static final String INCOMING_CALL_INVITE          = "INCOMING_CALL_INVITE";
    public static final String INCOMING_CALL_NOTIFICATION_ID = "INCOMING_CALL_NOTIFICATION_ID";
    public static final String NOTIFICATION_TYPE             = "NOTIFICATION_TYPE";

    public static final String ACTION_INCOMING_CALL = "com.hoxfon.react.TwilioVoice.INCOMING_CALL";
    public static final String ACTION_FCM_TOKEN     = "com.hoxfon.react.TwilioVoice.ACTION_FCM_TOKEN";
    public static final String ACTION_MISSED_CALL   = "com.hoxfon.react.TwilioVoice.MISSED_CALL";
    public static final String ACTION_ANSWER_CALL   = "com.hoxfon.react.TwilioVoice.ANSWER_CALL";
    public static final String ACTION_REJECT_CALL   = "com.hoxfon.react.TwilioVoice.REJECT_CALL";
    public static final String ACTION_HANGUP_CALL   = "com.hoxfon.react.TwilioVoice.HANGUP_CALL";
    public static final String ACTION_CLEAR_MISSED_CALLS_COUNT = "com.hoxfon.react.TwilioVoice.CLEAR_MISSED_CALLS_COUNT";

    public static final String CALL_SID_KEY = "CALL_SID";
    public static final String INCOMING_NOTIFICATION_PREFIX = "Incoming_";
    public static final String MISSED_CALLS_GROUP = "MISSED_CALLS";
    public static final int MISSED_CALLS_NOTIFICATION_ID = 1;
    public static final int HANGUP_NOTIFICATION_ID = 11;
    public static final int CLEAR_MISSED_CALLS_NOTIFICATION_ID = 21;

    public static final String PREFERENCE_KEY = "com.hoxfon.react.TwilioVoice.PREFERENCE_FILE_KEY";

    private android.app.NotificationManager notificationManager;
    private CallNotificationManager callNotificationManager;

    private String accessToken;

    private String toNumber = "";
    private String toName = "";

    static Map<String, Integer> callNotificationMap;

    private RegistrationListener registrationListener = registrationListener();
    private Call.Listener callListener = callListener();

    private CallInvite activeCallInvite;
    private Call activeCall;
    private PowerManager.WakeLock wakeLock;

    private KeyguardManager keyguardManager;

    // this variable determines when to create missed calls notifications
    private Boolean callAccepted = false;

    private AlertDialog alertDialog;

    public TwilioVoiceModule(ReactApplicationContext reactContext) {
        super(reactContext);
        if (BuildConfig.DEBUG) {
            Voice.setLogLevel(LogLevel.DEBUG);
        } else {
            Voice.setLogLevel(LogLevel.ERROR);
        }
        reactContext.addActivityEventListener(this);
        reactContext.addLifecycleEventListener(this);

        callNotificationManager = new CallNotificationManager();

        notificationManager = (android.app.NotificationManager) reactContext.getSystemService(Context.NOTIFICATION_SERVICE);

        /*
         * Setup the broadcast receiver to be notified of GCM Token updates
         * or incoming call messages in this Activity.
         */
        voiceBroadcastReceiver = new VoiceBroadcastReceiver();
        registerReceiver();

        TwilioVoiceModule.callNotificationMap = new HashMap<>();

        /*
         * Needed for setting/abandoning audio focus during a call
         */
        audioManager = (AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE);

        PowerManager powerManager;

        powerManager = (PowerManager) reactContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, TAG);

        keyguardManager = (KeyguardManager) reactContext.getSystemService(Context.KEYGUARD_SERVICE);

        /*
         * Ensure the microphone permission is enabled
         */
        /*  
        if (!checkPermissionForMicrophone()) {
            requestPermissionForMicrophone();
        } else {
            registerForCallInvites();
        }
        */

    }

    @Override
    public void onHostResume() {
        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        getCurrentActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        registerReceiver();
    }

    @Override
    public void onHostPause() {
        // the library needs to listen for events even when the app is paused
//        unregisterReceiver();
    }

    @Override
    public void onHostDestroy() {
    }

    @Override
    public String getName() {
        return TAG;
    }

    public void onNewIntent(Intent intent) {
        // This is called only when the App is in the foreground
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onNewIntent " + intent.toString());
        }
        handleIncomingCallIntent(intent);
    }

    private RegistrationListener registrationListener() {
        return new RegistrationListener() {
            @Override
            public void onRegistered(String accessToken, String fcmToken) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Successfully registered FCM");
                }
                sendEvent(EVENT_DEVICE_READY, null);
            }

            @Override
            public void onError(RegistrationException error, String accessToken, String fcmToken) {
                Log.e(TAG, String.format("Registration Error: %d, %s", error.getErrorCode(), error.getMessage()));
                WritableMap params = Arguments.createMap();
                params.putString("err", error.getMessage());
                sendEvent(EVENT_DEVICE_NOT_READY, params);
            }
        };
    }

    private Call.Listener callListener() {
        return new Call.Listener() {
            @Override
            public void onConnected(Call call) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "CALL CONNECTED callListener().onConnected call state = "+call.getState());
                }
                setAudioFocus(true);

                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
                WritableMap params = Arguments.createMap();
                if (call != null) {
                    params.putString("call_sid",   call.getSid());
                    params.putString("call_state", call.getState().name());
                    params.putString("call_from", call.getFrom());
                    params.putString("call_to", call.getTo());
                    String caller = "Show call details in the app";
                    if (!toName.equals("")) {
                        caller = toName;
                    } else if (!toNumber.equals("")) {
                        caller = toNumber;
                    }
                    activeCall = call;
                    callNotificationManager.createHangupLocalNotification(getReactApplicationContext(),
                            call.getSid(), caller);
                }
                sendEvent(EVENT_CONNECTION_DID_CONNECT, params);
            }

            @Override
            public void onDisconnected(Call call, CallException error) {
                setAudioFocus(false);
                callAccepted = false;

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "call disconnected");
                }
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }

                WritableMap params = Arguments.createMap();
                String callSid = "";
                if (call != null) {
                    callSid = call.getSid();
                    params.putString("call_sid", callSid);
                    params.putString("call_state", call.getState().name());
                    params.putString("call_from", call.getFrom());
                    params.putString("call_to", call.getTo());
                }
                if (error != null) {
                    Log.e(TAG, String.format("CallListener onDisconnected error: %d, %s",
                            error.getErrorCode(), error.getMessage()));
                    params.putString("err", error.getMessage());
                }
                if (callSid != null && activeCall != null && activeCall.getSid() != null && activeCall.getSid().equals(callSid)) {
                    activeCall = null;
                }
                sendEvent(EVENT_CONNECTION_DID_DISCONNECT, params);
                callNotificationManager.removeHangupNotification(getReactApplicationContext());
                toNumber = "";
                toName = "";
            }
        };
    }

    /**
     * Register the Voice broadcast receiver
     */
    private void registerReceiver() {
        if (!isReceiverRegistered) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_INCOMING_CALL);
            intentFilter.addAction(ACTION_MISSED_CALL);
            LocalBroadcastManager.getInstance(getReactApplicationContext()).registerReceiver(
                    voiceBroadcastReceiver, intentFilter);
            registerActionReceiver();
            isReceiverRegistered = true;
        }
    }

    private void registerActionReceiver() {

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_ANSWER_CALL);
        intentFilter.addAction(ACTION_REJECT_CALL);
        intentFilter.addAction(ACTION_HANGUP_CALL);
        intentFilter.addAction(ACTION_CLEAR_MISSED_CALLS_COUNT);

        getReactApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                switch (action) {
                    case ACTION_ANSWER_CALL:
                        accept();
                        break;
                    case ACTION_REJECT_CALL:
                        reject();
                        break;
                    case ACTION_HANGUP_CALL:
                        disconnect();
                        break;
                    case ACTION_CLEAR_MISSED_CALLS_COUNT:
                        SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_KEY, Context.MODE_PRIVATE);
                        SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();
                        sharedPrefEditor.putInt(MISSED_CALLS_GROUP, 0);
                        sharedPrefEditor.commit();
                }
                // Dismiss the notification when the user tap on the relative notification action
                // eventually the notification will be cleared anyway
                // but in this way there is no UI lag
                notificationManager.cancel(intent.getIntExtra(INCOMING_CALL_NOTIFICATION_ID, 0));
            }
        }, intentFilter);
    }

    // removed @Override temporarily just to get it working on different versions of RN
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        onActivityResult(requestCode, resultCode, data);
    }

    // removed @Override temporarily just to get it working on different versions of RN
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Ignored, required to implement ActivityEventListener for RN 0.33
    }

    private DialogInterface.OnClickListener answerCallClickListener() {
        return new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();
                accept();
                alertDialog.dismiss();
            }
        };
    }

    private DialogInterface.OnClickListener cancelCallClickListener() {
        return new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();
                reject();
                alertDialog.dismiss();
            }
        };
    }

    public static AlertDialog createIncomingCallDialog(
            Context context,
            CallInvite callInvite,
            DialogInterface.OnClickListener answerCallClickListener,
            DialogInterface.OnClickListener cancelClickListener) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
        alertDialogBuilder.setIcon(R.drawable.ic_call_black_24dp);
        alertDialogBuilder.setTitle("Incoming Call");
        alertDialogBuilder.setPositiveButton("Accept", answerCallClickListener);
        alertDialogBuilder.setNegativeButton("Reject", cancelClickListener);
        alertDialogBuilder.setMessage(callInvite.getFrom() + " is calling.");
        return alertDialogBuilder.create();
    }

    private void handleIncomingCallIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {

            if (intent.getAction().equals(ACTION_INCOMING_CALL)) {
                activeCallInvite = intent.getParcelableExtra(INCOMING_CALL_INVITE);

                if (activeCallInvite != null && (activeCallInvite.getState() == CallInvite.State.PENDING)) {
                    callAccepted = false;
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "handleIncomingCallIntent state = PENDING");
                    }
                    SoundPoolManager.getInstance(getReactApplicationContext()).playRinging();

                    KeyguardManager.KeyguardLock lock = keyguardManager.newKeyguardLock(TAG);
                    lock.disableKeyguard();
                    wakeLock.acquire();

                    if (getReactApplicationContext().getCurrentActivity() != null) {
                        Window window = getReactApplicationContext().getCurrentActivity().getWindow();
                        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
                    }
                    // send a JS event ONLY if the app's importance is FOREGROUND or SERVICE
                    // at startup the app would try to fetch the activeIncoming calls
                    int appImportance = callNotificationManager.getApplicationImportance(getReactApplicationContext());
                    if (appImportance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                            appImportance == RunningAppProcessInfo.IMPORTANCE_SERVICE) {

                        WritableMap params = Arguments.createMap();
                        params.putString("call_sid", activeCallInvite.getCallSid());
                        params.putString("call_from", activeCallInvite.getFrom());
                        params.putString("call_to", activeCallInvite.getTo());
                        params.putString("call_state", activeCallInvite.getState().name());
                        sendEvent(EVENT_DEVICE_DID_RECEIVE_INCOMING, params);
                    }


                } else {
                    KeyguardManager.KeyguardLock lock = keyguardManager.newKeyguardLock(TAG);
                    lock.reenableKeyguard();

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "====> BEGIN handleIncomingCallIntent when activeCallInvite != PENDING");
                    }

                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                    }

                    SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();

                    // the call is not active yet
                    if (activeCall == null) {

                        if (activeCallInvite != null) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "activeCallInvite state = " + activeCallInvite.getState());
                            }
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "activeCallInvite was cancelled by " + activeCallInvite.getFrom());
                            }
                            if (!callAccepted) {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "creating a missed call, activeCallInvite state: " + activeCallInvite.getState());
                                }
                                callNotificationManager.createMissedCallNotification(getReactApplicationContext(), activeCallInvite);
                                int appImportance = callNotificationManager.getApplicationImportance(getReactApplicationContext());
                                if (appImportance != RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                                    WritableMap params = Arguments.createMap();
                                    params.putString("call_sid", activeCallInvite.getCallSid());
                                    params.putString("call_from", activeCallInvite.getFrom());
                                    params.putString("call_to", activeCallInvite.getTo());
                                    params.putString("call_state", activeCallInvite.getState().name());
                                    sendEvent(EVENT_CONNECTION_DID_DISCONNECT, params);
                                }
                            }
                        }
                        clearIncomingNotification(activeCallInvite);
                    } else {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "activeCallInvite was answered. Call " + activeCall);
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "====> END");
                    }
                }
            } else if (intent.getAction().equals(ACTION_FCM_TOKEN)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "handleIncomingCallIntent ACTION_FCM_TOKEN");
                }
                registerForCallInvites();
            }
        } else {
            Log.e(TAG, "handleIncomingCallIntent intent is null");
        }
    }

    private class VoiceBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_INCOMING_CALL)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "VoiceBroadcastReceiver.onReceive ACTION_INCOMING_CALL. Intent "+ intent.getExtras());
                }
                handleIncomingCallIntent(intent);
            } else if (action.equals(ACTION_MISSED_CALL)) {
                SharedPreferences sharedPref = getReactApplicationContext().getSharedPreferences(PREFERENCE_KEY, Context.MODE_PRIVATE);
                SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();
                sharedPrefEditor.remove(MISSED_CALLS_GROUP);
                sharedPrefEditor.commit();
            } else {
                Log.e(TAG, "received broadcast unhandled action " + action);
            }
        }
    }

    @ReactMethod
    public void initWithAccessToken(final String accessToken, Promise promise) {
        if (accessToken.equals("")) {
            promise.reject(new JSApplicationIllegalArgumentException("Invalid access token"));
            return;
        }

        TwilioVoiceModule.this.accessToken = accessToken;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "initWithAccessToken ACTION_FCM_TOKEN");
        }
        registerForCallInvites();
        WritableMap params = Arguments.createMap();
        params.putBoolean("initialized", true);
        promise.resolve(params);
    }

    private void clearIncomingNotification(CallInvite callInvite) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "clearIncomingNotification() callInvite state: "+ callInvite.getState());
        }
        if (callInvite != null && callInvite.getCallSid() != null) {
            // remove incoming call notification
            String notificationKey = INCOMING_NOTIFICATION_PREFIX + callInvite.getCallSid();
            int notificationId = 0;
            if (TwilioVoiceModule.callNotificationMap.containsKey(notificationKey)) {
                notificationId = TwilioVoiceModule.callNotificationMap.get(notificationKey);
            }
            callNotificationManager.removeIncomingCallNotification(getReactApplicationContext(), null, notificationId);
            TwilioVoiceModule.callNotificationMap.remove(notificationKey);
        }
//        activeCallInvite = null;
    }

    /*
     * Register your FCM token with Twilio to receive incoming call invites
     *
     * If a valid google-services.json has not been provided or the FirebaseInstanceId has not been
     * initialized the fcmToken will be null.
     *
     * In the case where the FirebaseInstanceId has not yet been initialized the
     * VoiceFirebaseInstanceIDService.onTokenRefresh should result in a LocalBroadcast to this
     * activity which will attempt registerForCallInvites again.
     *
     */
    private void registerForCallInvites() {
        FirebaseApp.initializeApp(getReactApplicationContext());
        final String fcmToken = FirebaseInstanceId.getInstance().getToken();
        if (fcmToken != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Registering with FCM");
            }
            Voice.register(getReactApplicationContext(), accessToken, Voice.RegistrationChannel.FCM, fcmToken, registrationListener);
        }
    }

    @ReactMethod
    public void accept() {
        callAccepted = true;
        SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();
        if (activeCallInvite != null){
            if (activeCallInvite.getState() == CallInvite.State.PENDING) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "accept() activeCallInvite.getState() PENDING");
                }
                activeCallInvite.accept(getReactApplicationContext(), callListener);
                clearIncomingNotification(activeCallInvite);
            } else {
                // when the user answers a call from a notification before the react-native App
                // is completely initialised, and the first event has been skipped
                // re-send connectionDidConnect message to JS
                WritableMap params = Arguments.createMap();
                params.putString("call_sid",   activeCallInvite.getCallSid());
                params.putString("call_from",  activeCallInvite.getFrom());
                params.putString("call_to",    activeCallInvite.getTo());
                params.putString("call_state", activeCallInvite.getState().name());
                callNotificationManager.createHangupLocalNotification(getReactApplicationContext(),
                        activeCallInvite.getCallSid(),
                        activeCallInvite.getFrom());
                sendEvent(EVENT_CONNECTION_DID_CONNECT, params);
            }
        } else {
            sendEvent(EVENT_CONNECTION_DID_DISCONNECT, null);
        }
    }

    @ReactMethod
    public void reject() {
        callAccepted = false;
        SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();
        if (activeCallInvite != null){
            activeCallInvite.reject(getReactApplicationContext());
            clearIncomingNotification(activeCallInvite);
        }
        sendEvent(EVENT_CONNECTION_DID_DISCONNECT, null);
    }

    @ReactMethod
    public void ignore() {
        callAccepted = false;
        SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();
        if (activeCallInvite != null){
            clearIncomingNotification(activeCallInvite);
        }
        sendEvent(EVENT_CONNECTION_DID_DISCONNECT, null);
    }

    @ReactMethod
    public void connect(ReadableMap params) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "connect params: "+params);
        }

        WritableMap errParams = Arguments.createMap();
        if (accessToken == null) {
            errParams.putString("err", "Invalid access token");
            sendEvent(EVENT_DEVICE_NOT_READY, errParams);
            return;
        }
        if (params == null) {
            errParams.putString("err", "Invalid parameters");
            sendEvent(EVENT_CONNECTION_DID_DISCONNECT, errParams);
            return;
        } else if (!params.hasKey("To")) {
            errParams.putString("err", "Invalid To parameter");
            sendEvent(EVENT_CONNECTION_DID_DISCONNECT, errParams);
            return;
        }
        toNumber = params.getString("To");
        if (params.hasKey("ToName")) {
            toName = params.getString("ToName");
        }
        // optional parameter that will be delivered to the server
        if (params.hasKey("CallerId")) {
            twiMLParams.put("CallerId", params.getString("CallerId"));
        }
        twiMLParams.put("To", params.getString("To"));
        activeCall = Voice.call(getReactApplicationContext(), accessToken, twiMLParams, callListener);
    }

    @ReactMethod
    public void disconnect() {
        if (activeCall != null) {
            activeCall.disconnect();
            activeCall = null;
        }
    }

    @ReactMethod
    public void setMuted(Boolean muteValue) {
        if (activeCall != null) {
            activeCall.mute(muteValue);
        }
    }

    @ReactMethod
    public void sendDigits(String digits) {
        if (activeCall != null) {
            activeCall.sendDigits(digits);
        }
    }

    @ReactMethod
    public void getActiveCall(Promise promise) {
        if (activeCall != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Active call found state = "+activeCall.getState());
            }
            WritableMap params = Arguments.createMap();
            params.putString("call_sid",   activeCall.getSid());
            params.putString("call_from",  activeCall.getFrom());
            params.putString("call_to",    activeCall.getTo());
            params.putString("call_state", activeCall.getState().name());
            promise.resolve(params);
            return;
        }
        if (activeCallInvite != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Active call invite found state = "+activeCallInvite.getState());
            }
            WritableMap params = Arguments.createMap();
            params.putString("call_sid",   activeCallInvite.getCallSid());
            params.putString("call_from",  activeCallInvite.getFrom());
            params.putString("call_to",    activeCallInvite.getTo());
            params.putString("call_state", activeCallInvite.getState().name());
            promise.resolve(params);
            return;
        }
        promise.resolve(null);
    }

    @ReactMethod
    public void setSpeakerPhone(Boolean value) {
        setAudioFocus(value);
        audioManager.setSpeakerphoneOn(value);
    }

    private void sendEvent(String eventName, @Nullable WritableMap params) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "sendEvent "+eventName+" params "+params);
        }
        if (getReactApplicationContext().hasActiveCatalystInstance()) {
            getReactApplicationContext()
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, params);
        } else {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "failed Catalyst instance not active");
            }
        }
    }

    /*
     * Register your GCM token with Twilio to enable receiving incoming calls via GCM
     */
    private void register() {
        final String fcmToken = FirebaseInstanceId.getInstance().getToken();
        Voice.register(getReactApplicationContext(), accessToken, fcmToken, registrationListener);
    }

    private void setAudioFocus(boolean setFocus) {
        if (audioManager != null) {
            if (setFocus) {
                savedAudioMode = audioManager.getMode();
                // Request audio focus before making any device switch.
                audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

                /*
                 * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
                 * required to be in this mode when playout and/or recording starts for
                 * best possible VoIP performance. Some devices have difficulties with speaker mode
                 * if this is not set.
                 */
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                audioManager.setMode(savedAudioMode);
                audioManager.abandonAudioFocus(null);
            }
        }
    }
    
}