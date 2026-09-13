package com.paytm.wallettransfer.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StructuredEventLogger {

    private static final Logger log = LoggerFactory.getLogger(StructuredEventLogger.class);
    private static final int MAX_BUFFER_SIZE = 1000;

    private final ObjectMapper objectMapper;
    private final Deque<LogEvent> logBuffer = new ArrayDeque<>();

    public StructuredEventLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void logDomainEvent(String eventType, Map<String, Object> details) {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        String timestamp = Instant.now().toString();
        LogEvent logEvent = new LogEvent(timestamp, correlationId, eventType, details);

        try {
            String json = objectMapper.writeValueAsString(logEvent);
            log.info("{}", json);
        } catch (JsonProcessingException e) {
            log.info("EVENT: {} | correlationId: {} | details: {}", eventType, correlationId, details);
        }

        synchronized (logBuffer) {
            if (logBuffer.size() >= MAX_BUFFER_SIZE) {
                logBuffer.pollFirst();
            }
            logBuffer.addLast(logEvent);
        }
    }

    public List<LogEvent> getRecentLogs(int limit) {
        synchronized (logBuffer) {
            List<LogEvent> list = new ArrayList<>(logBuffer);
            if (limit <= 0 || limit >= list.size()) {
                return list;
            }
            return list.subList(list.size() - limit, list.size());
        }
    }
}
