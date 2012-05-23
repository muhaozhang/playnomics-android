package com.playnomics.api;

import java.io.Serializable;

class GameEvent extends PlaynomicsEvent  implements Serializable {
	
	private static final long serialVersionUID = 1L;
	
	private String sessionId;
	private String site;
	private String instanceId;
	private String type;
	private String gameId;
	private String reason;
	
	public GameEvent(EventType eventType, Long applicationId, String userId, String sessionId, String site,
		String instanceId, String type, String gameId, String reason) {
	
		super(eventType, applicationId, userId);
		this.sessionId = sessionId;
		this.site = site;
		this.instanceId = instanceId;
		this.type = type;
		this.gameId = gameId;
		this.reason = reason;
	}
	
	
	public String getSessionId() {
	
		return sessionId;
	}

	
	public void setSessionId(String sessionId) {
	
		this.sessionId = sessionId;
	}

	public String getInstanceId() {
	
		return instanceId;
	}
	
	public void setInstanceId(String instanceId) {
	
		this.instanceId = instanceId;
	}
	
	public String getType() {
	
		return type;
	}
	
	public void setType(String type) {
	
		this.type = type;
	}
	
	public String getGameId() {
	
		return gameId;
	}
	
	public void setGameId(String gameId) {
	
		this.gameId = gameId;
	}
	
	public String getReason() {
	
		return reason;
	}
	
	public void setReason(String reason) {
	
		this.reason = reason;
	}
	
	public String getSite() {
	
		return site;
	}
	
	public void setSite(String site) {
	
		this.site = site;
	}


	@Override
	public String toQueryString() {
		// Set common params
		String queryString =  getEventType()
			+ "?t=" + getEventTime().getTime()
			+ "&a=" + getApplicationId()
			+ "&u=" + getUserId();
		// sessionId is optional for game events
		if (getEventType() == EventType.gameStart || getEventType() == EventType.gameEnd)
			queryString = addOptionalParam(queryString, "s", getSessionId());
		else
			queryString += "&s=" + getSessionId();
		// Optional params
		queryString = addOptionalParam(queryString, "ss", getSite());
		queryString = addOptionalParam(queryString, "r", getReason());
		queryString = addOptionalParam(queryString, "g", getInstanceId());
		queryString = addOptionalParam(queryString, "ss", getSite());
		queryString = addOptionalParam(queryString, "gt", getType());
		queryString = addOptionalParam(queryString, "gi", getGameId());
		
		return queryString;
	}
}