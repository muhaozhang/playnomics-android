package com.playnomics.android.events;

import com.playnomics.android.session.GameSessionInfo;
import com.playnomics.android.util.*;

public class AppStartEvent extends ImplicitEvent {

	public AppStartEvent(IConfig config, GameSessionInfo sessionInfo,
			LargeGeneratedId instanceId) {
		super(config, sessionInfo, instanceId);
		appendParameter(config.getTimeZoneOffsetKey(),
				EventTime.getMinutesTimezoneOffset());
	}

	@Override
	public String getUrlPath() {
		return config.getEventPathAppStart();
	}
}
