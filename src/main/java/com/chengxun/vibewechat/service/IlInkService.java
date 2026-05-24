package com.chengxun.vibewechat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class IlInkService {

    @Autowired
    private IlInkConnectionHandler connectionHandler;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final Set<String> connectedUsers = ConcurrentHashMap.newKeySet();

    private static final String WELCOME_MESSAGE = """
            **🎉 欢迎使用 Vibe We Chat!**

            ━━━━━━━━━━━━━━━━━━━━━━
            **⚡ 快速开始**
            ━━━━━━━━━━━━━━━━━━━━━━
            发送 `v-config <API_KEY>` 一键配置
            发送 `v-switch <名称>` 快速切换配置
            发送 `v-help` 查看所有命令

            ━━━━━━━━━━━━━━━━━━━━━━
            **🔧 常用命令**
            ━━━━━━━━━━━━━━━━━━━━━━
            `v-status`  查看配置
            `v-save`    保存当前配置
            `v-cd`      切换目录
            `v-block`   屏蔽关键词
            `v-new`     新建会话

            *直接发送消息即可与 Claude 对话*

            ---
            *Copyright 2026 ChengXun*
            *GitHub: https://github.com/ChengXunX/vibe-wechat*
            """;

    @PostConstruct
    public void init() {
        connectionHandler.setMessageHandler(this::handleMessage);
    }

    private void handleMessage(String message) {
        log.info("Received message: {}", message);

        // ilink 消息格式: {"msgs":[{"from_user_id":"xxx","item_list":[{"type":1,"text_item":{"text":"xxx"}}],...}]}
        try {
            int msgsStart = message.indexOf("\"msgs\":[");
            if (msgsStart == -1) return;

            // 提取每条消息
            int searchStart = msgsStart + 8;
            while (true) {
                int objStart = message.indexOf("{", searchStart);
                if (objStart == -1 || objStart > message.indexOf("]", msgsStart)) break;

                int objEnd = findMatchingBrace(message, objStart);
                if (objEnd == -1) break;

                String msgObj = message.substring(objStart, objEnd + 1);
                searchStart = objEnd + 1;

                // 提取 from_user_id
                String userId = extractField(msgObj, "from_user_id");
                // 提取 context_token
                String contextToken = extractField(msgObj, "context_token");

                // 提取 text_item.text
                String content = null;
                int textItemStart = msgObj.indexOf("\"text_item\":{");
                if (textItemStart != -1) {
                    content = extractField(msgObj.substring(textItemStart), "text");
                }

                if (userId != null && content != null) {
                    if (!connectedUsers.contains(userId)) {
                        connectedUsers.add(userId);
                        sendText(userId, WELCOME_MESSAGE, contextToken);
                    }

                    eventPublisher.publishEvent(new IlInkMessageEvent(this, userId, content, contextToken));
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse message: {}", e.getMessage());
        }
    }

    private int findMatchingBrace(String json, int start) {
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            if (json.charAt(i) == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private String extractField(String json, String field) {
        try {
            String pattern = "\"" + field + "\":\"";
            int start = json.indexOf(pattern) + pattern.length();
            int end = json.indexOf("\"", start);
            if (start > pattern.length() - 1 && end > start) {
                return json.substring(start, end);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public void sendText(String userId, String text) {
        connectionHandler.sendText(userId, text, null);
    }

    public void sendText(String userId, String text, String contextToken) {
        connectionHandler.sendText(userId, text, contextToken);
    }

    public void sendTyping(String userId) {
        connectionHandler.sendTyping(userId);
    }

    public void sendStopTyping(String userId) {
        connectionHandler.sendStopTyping(userId);
    }

    public boolean isConnected() {
        return connectionHandler.isConnected();
    }

    public void setBotToken(String token) {
        connectionHandler.setBotToken(token);
    }

    public static class IlInkMessageEvent {
        private final String userId;
        private final String content;
        private final String contextToken;

        public IlInkMessageEvent(Object source, String userId, String content, String contextToken) {
            this.userId = userId;
            this.content = content;
            this.contextToken = contextToken;
        }

        public String getUserId() { return userId; }
        public String getContent() { return content; }
        public String getContextToken() { return contextToken; }
    }
}
