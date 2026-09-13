package com.paytm.wallettransfer.logging;

import java.util.Map;

public class LogEvent {
    private String timestamp;
    private String correlationId;
    private String eventType;
    private Map<String, Object> details;

    public LogEvent() { }

    public LogEvent(String timestamp, String correlationId, String eventType, Map<String, Object> details) {
        this.timestamp = timestamp;
        this.correlationId = correlationId;
        this.eventType = eventType;
        this.details = details;
    }

    public String getTimestamp() { return timestamp; }
    public String getCorrelationId() { return correlationId; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getDetails() { return details; }
}
