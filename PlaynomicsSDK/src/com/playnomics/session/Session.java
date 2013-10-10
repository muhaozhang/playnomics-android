package com.playnomics.session;

import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.Activity;
import android.content.Context;

import com.playnomics.client.EventQueue;
import com.playnomics.client.EventWorker;
import com.playnomics.util.*;
import com.playnomics.util.Logger.LogLevel;
import com.playnomics.client.HttpConnectionFactory;
import com.playnomics.events.AppPageEvent;
import com.playnomics.events.AppPauseEvent;
import com.playnomics.events.AppResumeEvent;
import com.playnomics.events.AppRunningEvent;
import com.playnomics.events.AppStartEvent;
import com.playnomics.events.ImplicitEvent;
import com.playnomics.events.MilestoneEvent;
import com.playnomics.events.TransactionEvent;
import com.playnomics.events.UserInfoEvent;

public class Session implements SessionStateMachine, TouchEventHandler, HeartBeatHandler {
	// session
	private SessionState sessionState;

	public SessionState getSessionState() {
		return sessionState;
	}

	private EventWorker eventWorker;
	private EventQueue eventQueue;
	private Util util;
	private Config config;
	private static final Object syncLock = new Object();
	private ServiceManager serviceManager;
	private DeviceManager deviceManager;
	private UIObserver observer;
	private HeartBeatProducer producer;
	
	// session data
	private long applicationId;
	private String userId;
	private String breadcrumbId;
	private LargeGeneratedId sessionId;
	private LargeGeneratedId instanceId;
	private EventTime sessionStartTime;
	private EventTime sessionPauseTime;

	private AtomicInteger sequence;

	private AtomicInteger touchEvents;

	public int getTouchEvents() {
		return touchEvents.get();
	}

	private AtomicInteger allTouchEvents;

	public int getAllTouchEvents() {
		return allTouchEvents.get();
	}

	private boolean enablePushNotifications;

	public void setEnabledPushNotifications(boolean value) {
		this.enablePushNotifications = value;
	}

	private boolean testMode = false;

	public void setTestMode(boolean value) {
		this.testMode = value;
	}

	private String overrideEventsUrl;

	public void setOverrideEventsUrl(String url) {
		this.overrideEventsUrl = url;
	}

	public String getEventsUrl() {
		if (!util.stringIsNullOrEmpty(this.overrideEventsUrl)) {
			return this.overrideEventsUrl;
		}
		if (this.testMode) {
			return config.getTestEventsUrl();
		}
		return config.getProdEventsUrl();
	}

	private String overrideMessagingUrl;

	public void setOverrideMessagingUrl(String url) {
		this.overrideMessagingUrl = url;
	}

	public String getMessagingUrl() {
		if (!util.stringIsNullOrEmpty(this.overrideMessagingUrl)) {
			return this.overrideMessagingUrl;
		}
		if (this.testMode) {
			return config.getTestMessagingUrl();
		}
		return config.getProdMessagingUrl();
	}

	private Session() {
		this.sessionState = SessionState.NOT_STARTED;
		this.util = new Util();
		this.config = new Config();
		this.eventQueue = new EventQueue(util, this.getEventsUrl());
		this.eventWorker = new EventWorker(this.eventQueue,
				new HttpConnectionFactory());
		this.observer = new UIObserver(this, this);
		this.producer = new HeartBeatProducer(this, config);
	}

	private static Session instance;

	public static Session getInstance() {
		synchronized (Session.syncLock) {
			if (instance == null) {
				instance = new Session();
			}
			return Session.instance;
		}
	}

	// Session life-cycle
	public void start(long applicationId, String userId, Context context) {
		this.userId = userId;
		start(applicationId, context);
	}

	public void start(long applicationId, Context context) {

		try {
			this.applicationId = applicationId;
			// session start code here
			if (sessionState == SessionState.STARTED) {
				return;
			}

			if (sessionState == SessionState.PAUSED) {
				resume();
				return;
			}

			sessionState = SessionState.STARTED;

			this.serviceManager = new ServiceManager(context);
			this.deviceManager = new DeviceManager(context, this.serviceManager);
			boolean settingsChanged = this.deviceManager
					.synchronizeDeviceSettings();

			this.breadcrumbId = this.deviceManager.getAndroidDeviceId();

			if (util.stringIsNullOrEmpty(this.userId)) {
				this.userId = this.breadcrumbId;
			}

			this.sequence = new AtomicInteger(1);
			this.touchEvents = new AtomicInteger(0);
			this.allTouchEvents = new AtomicInteger(0);
			this.sequence = new AtomicInteger(0);

			// start the background UI service

			// send appRunning or appPage
			LargeGeneratedId lastSessionId = deviceManager
					.getPreviousSessionId();

			EventTime lastEventTime = deviceManager.getLastEventTime();
			GregorianCalendar threeMinutesAgo = new GregorianCalendar(
					Util.TIME_ZONE_GMT);
			threeMinutesAgo.add(Calendar.MINUTE, -3);

			boolean sessionLapsed = (lastEventTime.compareTo(threeMinutesAgo) < 0)
					|| lastSessionId == null;

			ImplicitEvent implicitEvent;
			if (sessionLapsed) {
				this.sessionId = new LargeGeneratedId(util);
				this.instanceId = this.sessionId;

				implicitEvent = new AppStartEvent(config, getSessionInfo(),
						instanceId);
				sessionStartTime = implicitEvent.getEventTime();
				this.deviceManager.setLastSessionStartTime(sessionStartTime);
			} else {
				this.sessionId = lastSessionId;
				this.instanceId = new LargeGeneratedId(util);
				implicitEvent = new AppPageEvent(config, getSessionInfo(),
						instanceId);
				sessionStartTime = this.deviceManager.getLastSessionStartTime();
			}

			eventQueue.enqueueEvent(implicitEvent);
			eventWorker.start();
			producer.start();
			if (settingsChanged) {
				onDeviceSettingsUpdated();
			}
		} catch (Exception ex) {
			Logger.log(LogLevel.ERROR, ex, "Could not start session");
			sessionState = SessionState.NOT_STARTED;
		}
	}

	public void pause() {
		try {
			if (this.sessionState != SessionState.STARTED) {
				return;
			}
			this.sessionPauseTime = new EventTime();
			AppPauseEvent event = new AppPauseEvent(this.config,
					getSessionInfo(), this.instanceId, this.sessionStartTime,
					this.sequence.get(), getTouchEvents(), getAllTouchEvents());
			this.sequence.incrementAndGet();
			this.eventQueue.enqueueEvent(event);
			eventWorker.stop();
			producer.stop();
		} catch (Exception ex) {
			Logger.log(LogLevel.ERROR, ex, "Could not pause session");
		}
	}

	public void resume() {
		try {
			if (this.sessionState != SessionState.PAUSED) {
				return;
			}

			AppResumeEvent event = new AppResumeEvent(config, getSessionInfo(),
					this.instanceId, this.sessionStartTime,
					this.sessionPauseTime, this.sequence.get());
			eventQueue.enqueueEvent(event);
			eventWorker.start();
			producer.start();
		} catch (Exception ex) {
			Logger.log(LogLevel.ERROR, ex, "Could not pause session");
		}
	}

	public void onHeartBeat(int heartBeatIntervalSeconds){
		try{
			sequence.incrementAndGet();
			AppRunningEvent event = new AppRunningEvent(config, getSessionInfo(), instanceId, sessionStartTime, sequence.get(), touchEvents.get(), allTouchEvents.get());
			eventQueue.enqueueEvent(event);
			//reset the touch events
			touchEvents.set(0);
			allTouchEvents.set(0);
			
		} catch(UnsupportedEncodingException exception){
			Logger.log(LogLevel.ERROR, exception, "Could not log appRunning");
		}
	}
	
	public void onTouchEventReceived() {
		touchEvents.incrementAndGet();
		allTouchEvents.incrementAndGet();
	}

	private GameSessionInfo getSessionInfo() {
		return new GameSessionInfo(this.applicationId, this.userId,
				this.breadcrumbId, this.sessionId);
	}

	private void assertSessionStarted() {
		if (this.sessionState != SessionState.STARTED
				|| this.sessionState != SessionState.PAUSED) {
			throw new IllegalStateException("Session must be started");
		}
	}

	private void onDeviceSettingsUpdated() throws UnsupportedEncodingException {
		if (enablePushNotifications
				&& this.deviceManager.getPushRegistrationId() == null) {
			registerForPushNotifcations();
		} else {
			UserInfoEvent event = new UserInfoEvent(this.config,
					getSessionInfo(),
					this.deviceManager.getPushRegistrationId(),
					this.deviceManager.getAndroidDeviceId());
			eventQueue.enqueueEvent(event);
		}
	}

	void registerForPushNotifcations() {

	}

	// explicit events
	public void transactionInUSD(float priceInUSD, int quantity) {
		try {
			assertSessionStarted();
			TransactionEvent event = new TransactionEvent(this.config,
					this.util, getSessionInfo(), quantity, priceInUSD);
			eventQueue.enqueueEvent(event);
		} catch (Exception ex) {
			Logger.log(LogLevel.ERROR, ex, "Could not send transaction");
		}
	}

	public void attributeInstall(String source) {
		attributeInstall(source, null, null);
	}

	public void attributeInstall(String source, String campaign) {
		attributeInstall(source, campaign, null);
	}

	public void attributeInstall(String source, String campaign,
			Date installDate) {
		try {
			assertSessionStarted();
			UserInfoEvent event = new UserInfoEvent(this.config,
					getSessionInfo(), source, campaign, installDate);
			eventQueue.enqueueEvent(event);
		} catch (Exception ex) {
			Logger.log(LogLevel.ERROR, ex,
					"Could not send install attribution information");
		}
	}

	public void milestone(MilestoneEvent.MilestoneType milestoneType) {
		try {
			assertSessionStarted();
			MilestoneEvent event = new MilestoneEvent(this.config, this.util,
					getSessionInfo(), milestoneType);
			eventQueue.enqueueEvent(event);
		} catch (Exception ex) {
			Logger.log(LogLevel.ERROR, ex, "Could not send milestone");
		}
	}

	//activity attach/detach
	
	public void attachActivity(Activity activity){
		observer.observeNewActivity(activity);
	}
	
	public void detachActivity(){
		observer.forgetLastActivity();
	}
}
