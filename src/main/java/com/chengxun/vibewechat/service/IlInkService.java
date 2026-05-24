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
            欢迎使用 Vibe We Chat!

            常用命令:
            v-help    - 显示所有命令
            v-status  - 查看当前配置
            v-api     - 设置 Claude API 地址
            v-key     - 设置 Claude API Key
            v-model   - 设置 Claude 模型
            v-claude  - 设置 Claude 安装路径
            v-tools   - 开关工具类消息
            v-fileread - 开关读取文件消息
            v-fileedit - 开关编辑文件消息
            v-token   - 查看 Token 使用
            v-new     - 新建会话
            v-clear   - 清空会话

            直接发送消息即可与 Claude 对话。
            """;

    @PostConstruct
    public void init() {
        connectionHandler.setMessageHandler(this::handleMessage);
    }

    private void handleMessage(String message) {
        log.info("Received message: {}", message);

        // ilink 消息格式: {"msgs":[{"content":"xxx","user_id":"xxx",...}]}
        try {
            // 提取 msgs 数组中的消息
            int msgsStart = message.indexOf("\"msgs\":[");
            if (msgsStart == -1) return;

            int msgsEnd = message.indexOf("]", msgsStart);
            if (msgsEnd == -1) return;

            String msgsStr = message.substring(msgsStart + 8, msgsEnd);

            // 简单解析每条消息
            int msgStart = 0;
            while (true) {
                int objStart = msgsStr.indexOf("{", msgStart);
                if (objStart == -1) break;

                int objEnd = msgsStr.indexOf("}", objStart);
                if (objEnd == -1) break;

                String msgObj = msgsStr.substring(objStart, objEnd + 1);
                msgStart = objEnd + 1;

                String userId = extractField(msgObj, "user_id");
                String content = extractField(msgObj, "content");

                if (userId != null && content != null) {
                    if (!connectedUsers.contains(userId)) {
                        connectedUsers.add(userId);
                        sendText(userId, WELCOME_MESSAGE);
                    }

                    eventPublisher.publishEvent(new IlInkMessageEvent(this, userId, content));
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse message: {}", e.getMessage());
        }
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
        connectionHandler.sendText(userId, text);
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

        public IlInkMessageEvent(Object source, String userId, String content) {
            this.userId = userId;
            this.content = content;
        }

        public String getUserId() { return userId; }
        public String getContent() { return content; }
    }
}
