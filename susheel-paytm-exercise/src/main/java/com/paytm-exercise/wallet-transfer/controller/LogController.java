package com.example.demo.controller;

import com.example.demo.logging.LogEvent;
import com.example.demo.logging.StructuredEventLogger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/logs")
public class LogController {

    private final StructuredEventLogger eventLogger;

    public LogController(StructuredEventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    @GetMapping
    public List<LogEvent> getLogs(@RequestParam(defaultValue = "100") int limit) {
        return eventLogger.getRecentLogs(limit);
    }
}
