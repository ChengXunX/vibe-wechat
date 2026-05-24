package com.chengxun.vibewechat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private MessageRouter messageRouter;

    private final Set<String> connectedUsers = ConcurrentHashMap.newKeySet();

    private static final String WELCOME_MESSAGE = """
            欢迎使用 Vibe WeChat!

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
        // TODO: 解析 ilink 消息格式，提取 userId 和 content
        // 暂时假设消息格式为 JSON: {"user_id": "xxx", "content": "xxx"}
        String userId = extractUserId(message);
        String content = extractContent(message);

        if (userId != null && content != null) {
            // 检查是否为新用户
            if (!connectedUsers.contains(userId)) {
                connectedUsers.add(userId);
                sendText(userId, WELCOME_MESSAGE);
            }

            messageRouter.handleMessage(userId, content);
        }
    }

    private String extractUserId(String message) {
        // 简单解析 JSON，实际应用应使用 Jackson
        try {
            int start = message.indexOf("\"user_id\":\"") + 11;
            int end = message.indexOf("\"", start);
            return message.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractContent(String message) {
        try {
            int start = message.indexOf("\"content\":\"") + 11;
            int end = message.indexOf("\"", start);
            return message.substring(start, end);
        } catch (Exception e) {
            return null;
        }
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
}
