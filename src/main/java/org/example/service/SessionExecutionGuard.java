package org.example.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Prevents overlapping CHAT/AIOps turns in one session within this application process. */
@Component
public class SessionExecutionGuard {
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    public boolean tryAcquire(String sessionId) {
        return activeSessions.add(sessionId);
    }

    public void release(String sessionId) {
        activeSessions.remove(sessionId);
    }

    public boolean isActive(String sessionId) {
        return activeSessions.contains(sessionId);
    }
}
