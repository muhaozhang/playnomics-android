/*
 * 
 */
package com.playnomics.playrm;

import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.Manifest.permission;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.provider.Settings.Secure;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window.Callback;
import android.view.accessibility.AccessibilityEvent;

import com.playnomics.playrm.PlaynomicsConstants.CurrencyCategory;
import com.playnomics.playrm.PlaynomicsConstants.CurrencyType;
import com.playnomics.playrm.PlaynomicsConstants.ResponseType;
import com.playnomics.playrm.PlaynomicsConstants.TransactionType;
import com.playnomics.playrm.PlaynomicsConstants.UserInfoSex;
import com.playnomics.playrm.PlaynomicsConstants.UserInfoSource;
import com.playnomics.playrm.PlaynomicsConstants.UserInfoType;
import com.playnomics.playrm.PlaynomicsEvent.EventType;

// TODO: Auto-generated Javadoc
/**
 * The Class PlaynomicsSession.
 */
public class PlaynomicsSession {
	
	public enum APIResult {
		
		SENT,
		SWITCHED,
		STOPPED,
		ALREADY_STARTED,
		ALREADY_SWITCHED,
		ALREADY_STOPPED,
		SESSION_RESUMED,
		START_NOT_CALLED,
		NO_INTERNET_PERMISSION,
		MISSING_REQ_PARAM,
		FAIL_UNKNOWN
	};
	
	private static final String TAG = PlaynomicsSession.class.getSimpleName();
	private static final String SETTING_LAST_SESSION_START_TIME = "lastSessionStartTime";
	private static final String SETTING_LAST_USER_ID = "lastUserId";
	private static final String SETTING_LAST_SESSION_ID = "lastSessionId";
	
	private static final String FILE_PLAYNOMICS_EVENT_LIST = "playnomicsEventList";
	
	private static final int UPDATE_INTERVAL = 30000;
	private static final int COLLECTION_MODE = 7;
	
	private static boolean hasInternetPermission = true;
	
	private static  ScheduledThreadPoolExecutor eventTimer;
	
	private static List<PlaynomicsEvent> playnomicsEventList = new CopyOnWriteArrayList<PlaynomicsEvent>();
	
	private static PlaynomicsTimerTask timerTask = new PlaynomicsTimerTask();
	
	private static Activity activity;
	private static Callback activityCallback;
	private static SharedPreferences settings;
	private static Editor editor;
	private static EventSender eventSender = new EventSender();
	
	private static ScreenReceiver screenReceiver = null;
	private static IntentFilter screenIntentFilter = new IntentFilter();
	
	// Tracking values
	private static int collectMode = 7;
	private static int sequence = 0;
	private static Long applicationId;
	private static String cookieId;
	private static String internalSessionId;
	private static String instanceId;
	private static Date sessionStartTime;
	private static String userId = "";
	private static int timeZoneOffset;
	private static int clicks;
	private static int totalClicks;
	private static int keys;
	private static int totalKeys;
	private static Date pauseTime;
	private static boolean testMode;
	
	private enum SessionState {
		UNKNOWN,
		STARTED,
		PAUSED,
		STOPPED
	}
	
	private static SessionState sessionState = SessionState.UNKNOWN;
	
	// Prevent instantiation
	private PlaynomicsSession() {
	
	}
	
	/**
	 * setTestMode.
	 * 
	 * @param testMode
	 *            if true, sends data to test server
	 */
	public static void setTestMode(boolean testing) {
		testMode = testing;
		eventSender.setTestMode(testing);
	}
	
	public static boolean getTestMode(){
		return testMode;
	}
	
	public static long getAppID(){
		return applicationId;
	}
	
	public static String getUserID(){
		return userId;
	}
	
	public static String getCookieID(){
		return cookieId;
	}
	
	/**
	 * Change User.
	 * 
	 * @param activity
	 *            the activity
	 * @param applicationId
	 *            the application id
	 * @param userId
	 *            the user id
	 * @return the API Result
	 */
	public static APIResult changeUser(String userId) {
		stop();
		PlaynomicsSession.userId = userId;
		return start(activity, applicationId);
	}
	
	/**
	 * Start.
	 * 
	 * @param activity
	 *            the activity
	 * @param applicationId
	 *            the application id
	 * @param userId
	 *            the user id
	 * @return the API Result
	 */
	public static APIResult start(Activity activity, Long applicationId, String userId) {	
		PlaynomicsSession.userId = userId;
		return start(activity, applicationId);
	}
	
	/**
	 * Start.
	 * 
	 * @param activity
	 *            the activity
	 * @param applicationId
	 *            the application id
	 * @return the API Result
	 */
	@SuppressWarnings("unchecked")
	public static APIResult start(Activity activity, Long applicationId) {
	
		APIResult result;
		
		try {
			Log.i(TAG, "start() called");
			
			if (activity.getApplication().getPackageManager()
				.checkPermission(permission.INTERNET, activity.getApplication().getPackageName()) != PackageManager.PERMISSION_GRANTED) {
				hasInternetPermission = false;
				return APIResult.NO_INTERNET_PERMISSION;
			}
			
			if (sessionState == SessionState.STARTED)
				return APIResult.ALREADY_STARTED;
			
			// If paused, resume and get out of here
			if (sessionState == SessionState.PAUSED) {
				resume();
				return APIResult.SESSION_RESUMED;
			}
			
			sessionState = SessionState.STARTED;
			PlaynomicsSession.applicationId = applicationId;
			
			// Setup the activity callback function(s)
			setActivityCallback(activity);
			if (!screenIntentFilter.hasAction(Intent.ACTION_SCREEN_OFF))
				screenIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
			if (!screenIntentFilter.hasAction(Intent.ACTION_SCREEN_ON))
				screenIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
			
			if (screenReceiver == null) {
				screenReceiver = new ScreenReceiver();
				activity.registerReceiver(screenReceiver, screenIntentFilter);
			}
			
			sequence = 1;
			clicks = 0;
			totalClicks = 0;
			keys = 0;
			totalKeys = 0;
			
			sessionStartTime = new Date();
			
			// Calc to conform to minute offset format
			timeZoneOffset = TimeZone.getDefault().getRawOffset() / -60000;
			// Collection mode for Android
			collectMode = COLLECTION_MODE;
			
			settings = activity.getSharedPreferences("playnomics", Context.MODE_PRIVATE);
			editor = settings.edit();
			
			playnomicsEventList.clear();
			List<?> eventList = (List<BasicEvent>) ObjectCacheUtil.getObject(activity, FILE_PLAYNOMICS_EVENT_LIST);
			if (eventList != null)
				playnomicsEventList.addAll((List<PlaynomicsEvent>) eventList);
			
			EventType eventType;
			
			// Send an appStart if it has been > 3 min since the last session or
			// a
			// different user
			// otherwise send an appPage
			if (sessionStartTime.getTime() - settings.getLong(SETTING_LAST_SESSION_START_TIME, 0) > 180000
				|| !settings.getString(SETTING_LAST_USER_ID, "").equals(userId)) {
				internalSessionId = RandomGenerator.createRandomHex();
				editor.putString(SETTING_LAST_SESSION_ID, internalSessionId);
				instanceId = internalSessionId;
				eventType = EventType.appStart;
			}
			else {
				sessionStartTime = new Date(settings.getLong(SETTING_LAST_SESSION_START_TIME, 0));
				internalSessionId = settings.getString(SETTING_LAST_SESSION_ID, "");
				instanceId = RandomGenerator.createRandomHex();
				eventType = EventType.appPage;
			}
			
			editor.putString(SETTING_LAST_USER_ID, userId);
			editor.putLong(SETTING_LAST_SESSION_START_TIME, sessionStartTime.getTime());
			editor.commit();
			
			// Get unique ID for device; may be null on emulator
			cookieId = Secure.getString(activity.getContentResolver(), Secure.ANDROID_ID);
			if (cookieId == null)
				cookieId = "UNKNOWN_DEVICE_ID";
			
			// Set userId to cookieId if it isn't present
			if (userId == null)
				userId = cookieId;
			
			BasicEvent be = new BasicEvent(eventType, applicationId, userId, cookieId, internalSessionId,
				instanceId, timeZoneOffset);
			
			// Try to send and queue if unsuccessful
			sendEvent(be);
			result = APIResult.SENT;
			
			// Startup the event timer to send events back to the server
			if (eventTimer == null) {
				eventTimer = new ScheduledThreadPoolExecutor(1);
				eventTimer.scheduleAtFixedRate(timerTask, UPDATE_INTERVAL, UPDATE_INTERVAL, TimeUnit.MILLISECONDS);
			}
			
		} catch (Exception e) {
			result = APIResult.FAIL_UNKNOWN;
			
			ErrorEvent ee = new ErrorEvent(e);
			// Not sure if we should try to send immediately, but whatever
			sendEvent(ee);
		}
		
		return result;
	}
	
	/**
	 * Sets the activity callback.
	 * 
	 * @param activity
	 *            the new activity callback
	 */
	private static void setActivityCallback(Activity activity) {
	
		try {
			PlaynomicsSession.activity = activity;
			activityCallback = activity.getWindow().getCallback();
			
			activity.getWindow().setCallback(new Callback() {
				
				@Override
				public void onWindowFocusChanged(boolean hasFocus) {
				
					activityCallback.onWindowFocusChanged(hasFocus);
					
					// Get out of here is we are finishing the activity
					if (PlaynomicsSession.activity.isFinishing())
						return;
					
					// Are we pausing?
					if (hasFocus) {
						resume();
					}
					else {
						pause();
					}
				}
				
				@Override
				public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) {
				
					activityCallback.onWindowAttributesChanged(attrs);
				}
				
				@Override
				public boolean onSearchRequested() {
				
					return activityCallback.onSearchRequested();
				}
				
				@Override
				public boolean onPreparePanel(int featureId, View view, Menu menu) {
					return activityCallback.onPreparePanel(featureId, view, menu);
				}
				
				@Override
				public void onPanelClosed(int featureId, Menu menu) {
				
					activityCallback.onPanelClosed(featureId, menu);
				}
				
				@Override
				public boolean onMenuOpened(int featureId, Menu menu) {
				
					return activityCallback.onMenuOpened(featureId, menu);
				}
				
				@Override
				public boolean onMenuItemSelected(int featureId, MenuItem item) {
				
					return activityCallback.onMenuItemSelected(featureId, item);
				}
				
				@Override
				public void onDetachedFromWindow() {
				
					activityCallback.onDetachedFromWindow();
				}
				
				@Override
				public View onCreatePanelView(int featureId) {
				
					return activityCallback.onCreatePanelView(featureId);
				}
				
				@Override
				public boolean onCreatePanelMenu(int featureId, Menu menu) {
				
					return activityCallback.onCreatePanelMenu(featureId, menu);
				}
				
				@Override
				public void onContentChanged() {
				
					activityCallback.onContentChanged();
				}
				
				@Override
				public void onAttachedToWindow() {
				
					activityCallback.onAttachedToWindow();
				}
				
				@Override
				public boolean dispatchTrackballEvent(MotionEvent event) {
					return activityCallback.dispatchTrackballEvent(event);
				}
				
				@Override
				public boolean dispatchTouchEvent(MotionEvent event) {				
					if (event.getAction() == MotionEvent.ACTION_DOWN) {
						clicks += 1;
						totalClicks += 1;
					}
					return activityCallback.dispatchTouchEvent(event);
				}
				
				@Override
				public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
					return activityCallback.dispatchPopulateAccessibilityEvent(event);
				}
				
				@Override
				public boolean dispatchKeyEvent(KeyEvent event) {
					if (event.getAction() == KeyEvent.ACTION_DOWN) {
						keys += 1;
						totalKeys += 1;
					}
					return activityCallback.dispatchKeyEvent(event);
				}
				
			});
		} catch (Exception e) {
			ErrorEvent ee = new ErrorEvent(e);
			sendEvent(ee);
		}
	}
	
	/**
	 * Pause.
	 */
	protected static void pause() {
	
		try {
			Log.i(TAG, "pause() called");
			if (sessionState.equals(SessionState.PAUSED))
				return;
			
			sessionState = SessionState.PAUSED;
			
			BasicEvent be = new BasicEvent(EventType.appPause, applicationId, userId, cookieId,
				internalSessionId, instanceId, sessionStartTime, sequence, clicks, totalClicks, keys, totalKeys,
				collectMode);
			pauseTime = new Date();
			sequence += 1;
			be.setSequence(sequence);
			be.setSessionStartTime(sessionStartTime);
			// Try to send and queue if unsuccessful
			sendEvent(be);
		} catch (Exception e) {
			ErrorEvent ee = new ErrorEvent(e);
			sendEvent(ee);
		}
	}
	
	/**
	 * Resume.
	 */
	protected static void resume() {
	
		try {
			Log.i(TAG, "resume() called");
			if (sessionState.equals(SessionState.STARTED))
				return;
			
			sessionState = SessionState.STARTED;
			// reset timer
			timerTask.cancel();
			eventTimer.shutdownNow();
			eventTimer = new ScheduledThreadPoolExecutor(1);
			eventTimer.scheduleAtFixedRate(timerTask, UPDATE_INTERVAL, UPDATE_INTERVAL, TimeUnit.MILLISECONDS);	

			BasicEvent be = new BasicEvent(EventType.appResume, applicationId, userId, cookieId,
				internalSessionId, instanceId, sessionStartTime, sequence, clicks, totalClicks, keys, totalKeys,
				collectMode);
			be.setPauseTime(pauseTime);
			be.setSessionStartTime(sessionStartTime);
			be.setSequence(sequence);
			// Try to send and queue if unsuccessful
			sendEvent(be);
		} catch (Exception e) {
			ErrorEvent ee = new ErrorEvent(e);
			sendEvent(ee);
		}
	}
	
	/**
	 * Switch activity.
	 * 
	 * @param activity
	 *            the activity
	 * @return the API Result
	 */
	public static APIResult switchActivity(Activity activity) {
	
		APIResult result;
		
		try {
			Log.i(TAG, "switchActivity() called");
			if (sessionState != SessionState.STARTED) {
				return APIResult.START_NOT_CALLED;
			}
			
			if (PlaynomicsSession.activity != activity) {
				// Restore original callback
				PlaynomicsSession.activity.getWindow().setCallback(activityCallback);
				setActivityCallback(activity);
				result = APIResult.SWITCHED;
			}
			else {
				result = APIResult.ALREADY_SWITCHED;
			}
		} catch (Exception e) {
			result = APIResult.FAIL_UNKNOWN;
			
			ErrorEvent ee = new ErrorEvent(e);
			// Not sure if we should try to send immediately, but whatever
			sendEvent(ee);
		}
		
		return result;
	}
	
	/**
	 * Stop.
	 * 
	 * @return the API Result
	 */
	public static APIResult stop() {
	
		APIResult result;
		
		try {
			Log.i(TAG, "stop() called");
			if (sessionState.equals(SessionState.STOPPED))
				return APIResult.ALREADY_STOPPED;
			
			if (activity.isFinishing()) {
				sessionState = SessionState.STOPPED;
				eventTimer.shutdownNow();
				activity.unregisterReceiver(screenReceiver);
				screenReceiver = null;
				// Save eventList to disk for later sending
				ObjectCacheUtil.saveObject(playnomicsEventList, activity, FILE_PLAYNOMICS_EVENT_LIST);
				
				// Restore original callback
				activity.getWindow().setCallback(activityCallback);
				PlaynomicsSession.activity = null;
			}
			
			sessionState = SessionState.STOPPED;
			result = APIResult.STOPPED;
		} catch (Exception e) {
			result = APIResult.FAIL_UNKNOWN;
			
			ErrorEvent ee = new ErrorEvent(e);
			sendEvent(ee);
		}
		
		return result;
	}
	
	/**
	 * User info.
	 * 
	 * @param type
	 *            the type
	 * @param country
	 *            the country
	 * @param subdivision
	 *            the subdivision
	 * @param sex
	 *            the sex
	 * @param birthyear
	 *            the birthyear
	 * @param source
	 *            the source
	 * @param sourceCampaign
	 *            the source campaign
	 * @param installTime
	 *            the install time
	 * @return the API Result
	 */
	public static APIResult userInfo(UserInfoType type, String country, String subdivision, UserInfoSex sex,
		Date birthyear, UserInfoSource source, String sourceCampaign, Date installTime) {
	
		return userInfo(type, country, subdivision, sex, birthyear, source.toString(), sourceCampaign, installTime);
	}
	
	/**
	 * User info.
	 * 
	 * @param type
	 *            the type
	 * @param country
	 *            the country
	 * @param subdivision
	 *            the subdivision
	 * @param sex
	 *            the sex
	 * @param birthyear
	 *            the birthyear
	 * @param source
	 *            the source
	 * @param sourceCampaign
	 *            the source campaign
	 * @param installTime
	 *            the install time
	 * @return the API Result
	 */
	public static APIResult userInfo(UserInfoType type, String country, String subdivision, UserInfoSex sex,
		Date birthyear, String source, String sourceCampaign, Date installTime) {
	
		UserInfoEvent uie = new UserInfoEvent(internalSessionId, applicationId, userId, type, country, subdivision, sex,
			birthyear,
			source, sourceCampaign, installTime);
		return sendOrQueueEvent(uie);
	}
	
	/**
	 * Session start.
	 * 
	 * @param internalSessionId
	 *            the session id
	 * @param site
	 *            the site
	 * @return the API Result
	 */
	public static APIResult sessionStart(long sessionId, String site) {
	
		GameEvent ge = new GameEvent(EventType.sessionStart, internalSessionId, applicationId, userId, sessionId, site,
			null, null, null,
			null);
		return sendOrQueueEvent(ge);
		
	}
	
	/**
	 * Session end.
	 * 
	 * @param internalSessionId
	 *            the session id
	 * @param reason
	 *            the reason
	 * @return the API Result
	 */
	public static APIResult sessionEnd(long sessionId, String reason) {
	
		GameEvent ge = new GameEvent(EventType.sessionEnd, internalSessionId, applicationId, userId, sessionId, null, null,
			null, null,
			reason);
		return sendOrQueueEvent(ge);
	}
	
	/**
	 * Game start.
	 * 
	 * @param instanceId
	 *            the instance id
	 * @param internalSessionId
	 *            the session id
	 * @param site
	 *            the site
	 * @param type
	 *            the type
	 * @param gameId
	 *            the game id
	 * @return the API Result
	 */
	public static APIResult gameStart(long instanceId, long sessionId, String site, String type, String gameId) {
	
		GameEvent ge = new GameEvent(EventType.gameStart, internalSessionId, applicationId, userId, sessionId, site,
			instanceId, type,
			gameId, null);
		return sendOrQueueEvent(ge);
	}
	
	/**
	 * Game end.
	 * 
	 * @param instanceId
	 *            the instance id
	 * @param internalSessionId
	 *            the session id
	 * @param reason
	 *            the reason
	 * @return the API Result
	 */
	public static APIResult gameEnd(long instanceId, long sessionId, String reason) {
	
		GameEvent ge = new GameEvent(EventType.gameEnd, internalSessionId, applicationId, userId, sessionId, null,
			instanceId, null, null,
			reason);
		return sendOrQueueEvent(ge);
	}
	
	/**
	 * Transaction.
	 * 
	 * @param transactionId
	 *            the transaction id
	 * @param itemId
	 *            the item id
	 * @param quantity
	 *            the quantity
	 * @param type
	 *            the type
	 * @param otherUserId
	 *            the other user id
	 * @param currencyType
	 *            the currency type
	 * @param currencyValue
	 *            the currency value
	 * @param currencyCategory
	 *            the currency category
	 * @return the API Result
	 */
	public static APIResult transaction(long transactionId, String itemId, double quantity, TransactionType type,
		String otherUserId, CurrencyType currencyType, double currencyValue, CurrencyCategory currencyCategory) {
	
		return transaction(transactionId, itemId, quantity, type, otherUserId,
			currencyType.toString(), currencyValue, currencyCategory);
	}
	
	/**
	 * Transaction.
	 * 
	 * @param transactionId
	 *            the transaction id
	 * @param itemId
	 *            the item id
	 * @param quantity
	 *            the quantity
	 * @param type
	 *            the type
	 * @param otherUserId
	 *            the other user id
	 * @param currencyType
	 *            the currency type
	 * @param currencyValue
	 *            the currency value
	 * @param currencyCategory
	 *            the currency category
	 * @return the API Result
	 */
	public static APIResult transaction(long transactionId, String itemId, double quantity, TransactionType type,
		String otherUserId, String currencyType, double currencyValue, CurrencyCategory currencyCategory) {
	
		String[] currencyTypes = { currencyType };
		double[] currencyValues = { currencyValue };
		CurrencyCategory[] currencyCategories = { currencyCategory };
		
		TransactionEvent te = new TransactionEvent(EventType.transaction, internalSessionId, applicationId, userId,
			transactionId, itemId,
			quantity, type, otherUserId, currencyTypes, currencyValues, currencyCategories);
		return sendOrQueueEvent(te);
	}
	
	/**
	 * Transaction.
	 * 
	 * @param transactionId
	 *            the transaction id
	 * @param itemId
	 *            the item id
	 * @param quantity
	 *            the quantity
	 * @param type
	 *            the type
	 * @param otherUserId
	 *            the other user id
	 * @param currencyTypes
	 *            the currency types
	 * @param currencyValues
	 *            the currency values
	 * @param currencyCategories
	 *            the currency categories
	 * @return the API Result
	 */
	public static APIResult transaction(long transactionId, String itemId, double quantity, TransactionType type,
		String otherUserId, CurrencyType[] currencyTypes, double[] currencyValues, CurrencyCategory[] currencyCategories) {
	
		try {
			String[] currencyTypeStrings = new String[currencyTypes.length];
			
			for (int i = 0; i < currencyTypes.length; i++) {
				currencyTypeStrings[i] = currencyTypes[i].toString();
			}
			
			TransactionEvent te = new TransactionEvent(EventType.transaction, internalSessionId, applicationId, userId,
				transactionId, itemId,
				quantity, type, otherUserId, currencyTypeStrings, currencyValues, currencyCategories);
			return sendOrQueueEvent(te);
		} catch (Exception e) {
			return APIResult.FAIL_UNKNOWN;
		}
	}
	
	/**
	 * Transaction.
	 * 
	 * @param transactionId
	 *            the transaction id
	 * @param itemId
	 *            the item id
	 * @param quantity
	 *            the quantity
	 * @param type
	 *            the type
	 * @param otherUserId
	 *            the other user id
	 * @param currencyTypes
	 *            the currency types
	 * @param currencyValues
	 *            the currency values
	 * @param currencyCategories
	 *            the currency categories
	 * @return the API Result
	 */
	public static APIResult transaction(long transactionId, String itemId, double quantity, TransactionType type,
		String otherUserId, String[] currencyTypes, double[] currencyValues, CurrencyCategory[] currencyCategories) {
	
		TransactionEvent te = new TransactionEvent(EventType.transaction, internalSessionId, applicationId, userId,
			transactionId, itemId,
			quantity, type, otherUserId, currencyTypes, currencyValues, currencyCategories);
		return sendOrQueueEvent(te);
	}
	
	/**
	 * Invitation sent.
	 * 
	 * @param invitationId
	 *            the invitation id
	 * @param recipientUserId
	 *            the recipient user id
	 * @param recipientAddress
	 *            the recipient address
	 * @param method
	 *            the method
	 * @return the API Result
	 */
	public static APIResult invitationSent(long invitationId, String recipientUserId, String recipientAddress,
		String method) {
	
		SocialEvent se = new SocialEvent(EventType.invitationSent, internalSessionId, applicationId, userId, invitationId,
			recipientUserId, recipientAddress, method, null);
		return sendOrQueueEvent(se);
	}
	
	/**
	 * Invitation response.
	 * 
	 * @param invitationId
	 *            the invitation id
	 * @param response
	 *            the response
	 * @return the API Result
	 */
	public static APIResult invitationResponse(long invitationId, String recipientUserId, ResponseType response) {
	
		SocialEvent se = new SocialEvent(EventType.invitationResponse, internalSessionId, applicationId, userId, invitationId,
			recipientUserId, null, null, response);
		return sendOrQueueEvent(se);
	}
	
	public static APIResult milestone(long milestoneId, String milestoneName) {
		
		MilestoneEvent ms = new MilestoneEvent(EventType.milestone, internalSessionId, applicationId, userId, cookieId,
			milestoneId, milestoneName);
		return sendOrQueueEvent(ms);
	}
	
	public static void errorReport(ErrorDetail errorDetail){
		ErrorEvent error = new ErrorEvent(errorDetail);
		sendEvent(error);
	}
	
	private static APIResult sendOrQueueEvent(PlaynomicsEvent pe) {
	
		APIResult result;
		
		try {
			if (sessionState != SessionState.STARTED) {
				return APIResult.START_NOT_CALLED;
			}
			else if (!hasInternetPermission) {
				return APIResult.NO_INTERNET_PERMISSION;
			}
			
			sendEvent(pe);
			result = APIResult.SENT;
			
		} catch (Exception e) {
			result = APIResult.FAIL_UNKNOWN;
			
			ErrorEvent ee = new ErrorEvent(e);
			sendEvent(ee);
		}
		
		return result;
	}
	
	// Send events to server in background thread
	private static void sendEvent(final PlaynomicsEvent pe) {
	
		try {
			new AsyncTask<Void, Void, Void>() {
				
				@Override
				protected Void doInBackground(Void... arg0) {
				
					// Queue event on failure
					if (!eventSender.sendToServer(pe)) {
						playnomicsEventList.add(pe);
					}
					
					return null;
				}
			}.doInBackground();
		} catch (Exception e) {
			// Do nothing?
		}
	}
	
	/**
	 * The Class PlaynomicsTimerTask.
	 */
	protected static class PlaynomicsTimerTask extends TimerTask {
		
		/*
		 * (non-Javadoc)
		 * 
		 * @see java.util.TimerTask#run()
		 */
		@Override
		public void run() {
		
			try {
				// Only send appRunning event if we are started
				if (sessionState == SessionState.STARTED) {
					sequence += 1;
					BasicEvent runningBE = new BasicEvent(EventType.appRunning, applicationId, userId, cookieId,
						internalSessionId, instanceId, sessionStartTime, sequence, clicks, totalClicks, keys, totalKeys,
						collectMode);
					playnomicsEventList.add(runningBE);
					
					// Reset keys/clicks
					keys = 0;
					clicks = 0;
				}
				
				// Exit method if any sendToServer call fails (we'll try again
				// next time)
				for (PlaynomicsEvent pe : playnomicsEventList) {
					
					if (eventSender.sendToServer(pe))
						playnomicsEventList.remove(pe);
					else
						return;
				}
			} catch (Exception e) {
				ErrorEvent ee = new ErrorEvent(e);
				// Not sure if we should try to send immediately, but whatever
				if (!eventSender.sendToServer(ee))
					playnomicsEventList.add(ee);
			}
		}
	}
}