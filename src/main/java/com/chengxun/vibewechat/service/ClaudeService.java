package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.FilterConfig;
import com.chengxun.vibewechat.model.ClaudeMessage;
import com.chengxun.vibewechat.model.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ClaudeService {

    @Autowired
    private FilterConfig filterConfig;

    private final Map<String, ClaudeSession> sessions = new ConcurrentHashMap<>();

    public ClaudeSession getOrCreateSession(String userId) {
        return sessions.computeIfAbsent(userId, k -> new ClaudeSession(userId));
    }

    public ClaudeSession getSession(String userId) {
        return sessions.get(userId);
    }

    public String sendMessage(String userId, String message) {
        ClaudeSession session = getOrCreateSession(userId);
        // TODO: 调用 Claude API
        // 模拟响应
        return "Claude 收到消息: " + message;
    }

    public boolean shouldShowMessage(ClaudeMessage message) {
        MessageType type = message.getType();
        return switch (type) {
            case TOOL_CALL -> filterConfig.isShowToolCalls();
            case FILE_OPERATION -> filterConfig.isShowFileOperations();
            case SUBTASK_COMPLETION -> filterConfig.isShowSubtaskCompletion();
            case TASK_COMPLETION -> filterConfig.isShowTaskCompletion();
            default -> true;
        };
    }

    public static class ClaudeSession {
        private final String userId;
        private final String sessionId;
        private long lastActiveTime;

        public ClaudeSession(String userId) {
            this.userId = userId;
            this.sessionId = "session_" + System.currentTimeMillis();
            this.lastActiveTime = System.currentTimeMillis();
        }

        public String getUserId() { return userId; }
        public String getSessionId() { return sessionId; }
        public long getLastActiveTime() { return lastActiveTime; }
        public void setLastActiveTime(long time) { this.lastActiveTime = time; }
    }
}
