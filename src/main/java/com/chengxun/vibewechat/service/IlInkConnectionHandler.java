package com.chengxun.vibewechat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Component
public class IlInkConnectionHandler {

    @Value("${vibe-wechat.ilink.base-url:https://ilinkai.weixin.qq.com}")
    private String baseUrl;

    private volatile boolean connected = false;
    private Consumer<String> messageHandler;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private ScheduledExecutorService scheduler;
    private String botToken = "";

    @PostConstruct
    public void init() {
        log.info("ilink base URL: {}", baseUrl);
    }

    @PreDestroy
    public void destroy() {
        connected = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public void setBotToken(String token) {
        this.botToken = token;
        if (token != null && !token.isEmpty()) {
            startPolling();
        }
    }

    public String getBotToken() {
        return botToken;
    }

    private void startPolling() {
        if (scheduler != null && !scheduler.isShutdown()) {
            log.info("Polling already running, skipping");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ilink-poller");
            t.setDaemon(true);
            return t;
        });

        connected = true;
        log.info("Started ilink polling with bot_token");

        // 使用独立线程进行长轮询，避免阻塞调度器
        new Thread(() -> {
            while (connected && !Thread.currentThread().isInterrupted()) {
                try {
                    pollUpdates();
                } catch (Exception e) {
                    log.debug("Poll error: {}", e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "ilink-poll-worker").start();
    }

    private String lastUpdatesBuf = "";

    private String randomUin() {
        return String.valueOf(1000000000L + (long)(Math.random() * 9000000000L));
    }

    private void pollUpdates() throws Exception {
        String url = baseUrl + "/ilink/bot/getupdates";

        String jsonBody = String.format("{\"get_updates_buf\":\"%s\",\"base_info\":{\"channel_version\":\"1.0\"}}", lastUpdatesBuf);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", "Bearer " + botToken)
                .header("X-WECHAT-UIN", randomUin())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(35))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("Poll response: {}", response.statusCode());

        if (response.statusCode() == 200) {
            String body = response.body();
            if (body != null && !body.isEmpty()) {
                // 更新 get_updates_buf
                int bufStart = body.indexOf("\"get_updates_buf\":\"") + 19;
                int bufEnd = body.indexOf("\"", bufStart);
                if (bufStart > 18 && bufEnd > bufStart) {
                    lastUpdatesBuf = body.substring(bufStart, bufEnd);
                }

                // 检查是否有消息
                if (body.contains("\"msgs\"") && !body.contains("\"msgs\":[]")) {
                    log.info("Received messages");
                    if (messageHandler != null) {
                        messageHandler.accept(body);
                    }
                }
            }
        } else {
            log.warn("Poll failed: {} - {}", response.statusCode(), response.body());
        }
    }

    public void sendText(String userId, String text, String contextToken) {
        if (!connected || botToken.isEmpty()) {
            log.warn("Not connected to ilink");
            return;
        }
        try {
            String url = baseUrl + "/ilink/bot/sendmessage";

            // 格式化 markdown 为纯文本
            String formattedText = formatMarkdown(text);
            String escapedText = formattedText.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
            String contextTokenStr = (contextToken != null && !contextToken.isEmpty()) ?
                String.format(",\"context_token\":\"%s\"", contextToken) : "";

            String jsonBody = String.format(
                "{\"msg\":{\"from_user_id\":\"\",\"to_user_id\":\"%s\",\"client_id\":\"%s\",\"message_type\":2,\"message_state\":2%s,\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"%s\"}}]}}",
                userId,
                java.util.UUID.randomUUID().toString(),
                contextTokenStr,
                escapedText
            );

            log.debug("Send message body: {}", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", randomUin())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Send message to {}: {} - {}", userId, response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("Failed to send message", e);
        }
    }

    private String formatMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;

        // 去除代码块
        java.util.regex.Pattern codeBlockPattern = java.util.regex.Pattern.compile("```[\\s\\S]*?```");
        java.util.regex.Matcher codeBlockMatcher = codeBlockPattern.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (codeBlockMatcher.find()) {
            String code = codeBlockMatcher.group();
            code = code.substring(3, code.length() - 3);
            int firstNewline = code.indexOf('\n');
            if (firstNewline != -1) {
                code = code.substring(firstNewline + 1);
            }
            codeBlockMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(code.trim()));
        }
        codeBlockMatcher.appendTail(sb);
        result = sb.toString();

        // 去除图片
        result = result.replaceAll("!\\[.*?\\]\\(.*?\\)", "");

        // 去除链接，保留文本
        result = result.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");

        // 去除加粗
        result = result.replaceAll("\\*\\*(.+?)\\*\\*", "$1");

        // 去除删除线
        result = result.replaceAll("~~(.+?)~~", "$1");

        // 去除斜体（星号）
        result = result.replaceAll("\\*(.+?)\\*", "$1");

        // 去除斜体（下划线）
        result = result.replaceAll("_(.+?)_", "$1");

        // 去除行内代码
        result = result.replaceAll("`(.+?)`", "$1");

        // 去除标题
        result = result.replaceAll("(?m)^#{1,6}\\s+", "");

        // 去除水平线
        result = result.replaceAll("(?m)^---+$", "---");

        return result.strip();
    }

    public void sendTyping(String userId) {
        if (!connected || botToken.isEmpty()) {
            log.info("Cannot send typing: connected={}, tokenEmpty={}", connected, botToken.isEmpty());
            return;
        }
        try {
            String url = baseUrl + "/ilink/bot/sendtyping";
            String jsonBody = String.format("{\"ilink_user_id\":\"%s\",\"typing_ticket\":\"\",\"status\":1}", userId);

            log.info("Sending typing status to {}", userId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", randomUin())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Typing status response: {}", response.statusCode());
        } catch (Exception e) {
            log.info("Failed to send typing status: {}", e.getMessage());
        }
    }

    public void sendStopTyping(String userId) {
        if (!connected || botToken.isEmpty()) return;
        try {
            String url = baseUrl + "/ilink/bot/sendtyping";
            String jsonBody = String.format("{\"ilink_user_id\":\"%s\",\"typing_ticket\":\"\",\"status\":2}", userId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("Authorization", "Bearer " + botToken)
                    .header("X-WECHAT-UIN", randomUin())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("Failed to send stop typing status", e);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler;
    }
}
