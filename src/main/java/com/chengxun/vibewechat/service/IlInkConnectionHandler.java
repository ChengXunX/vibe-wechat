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
        if (scheduler != null) {
            scheduler.shutdown();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ilink-poller");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                pollUpdates();
            } catch (Exception e) {
                log.debug("Poll error: {}", e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);

        connected = true;
        log.info("Started ilink polling with bot_token");
    }

    private String lastUpdatesBuf = "";

    private void pollUpdates() throws Exception {
        String url = baseUrl + "/ilink/bot/getupdates";

        String jsonBody = String.format("{\"get_updates_buf\":\"%s\",\"base_info\":{}}", lastUpdatesBuf);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("iLink-App-ClientVersion", "1")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(35))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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
                    log.info("Received messages, calling handler");
                    if (messageHandler != null) {
                        messageHandler.accept(body);
                    }
                }
            }
        } else {
            log.warn("Poll failed with status: {}", response.statusCode());
        }
    }

    public void sendText(String userId, String text) {
        if (!connected || botToken.isEmpty()) {
            log.warn("Not connected to ilink");
            return;
        }
        try {
            String url = baseUrl + "/ilink/bot/sendmessage";

            // 使用正确的 ilink 消息格式
            String jsonBody = String.format(
                "{\"msg\":{\"from_user_id\":\"\",\"to_user_id\":\"%s\",\"client_id\":\"%s\",\"message_type\":2,\"message_state\":2,\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"%s\"}}]}}",
                userId,
                java.util.UUID.randomUUID().toString(),
                text.replace("\"", "\\\"").replace("\n", "\\n")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("iLink-App-ClientVersion", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("Send message response: {} - {}", response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("Failed to send message", e);
        }
    }

    public void sendTyping(String userId) {
        if (!connected || botToken.isEmpty()) return;
        try {
            String url = baseUrl + "/ilink/bot/send_typing";
            String jsonBody = String.format("{\"user_id\":\"%s\",\"status\":1}", userId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + botToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("Failed to send typing status", e);
        }
    }

    public void sendStopTyping(String userId) {
        if (!connected || botToken.isEmpty()) return;
        try {
            String url = baseUrl + "/ilink/bot/send_typing";
            String jsonBody = String.format("{\"user_id\":\"%s\",\"status\":0}", userId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + botToken)
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
