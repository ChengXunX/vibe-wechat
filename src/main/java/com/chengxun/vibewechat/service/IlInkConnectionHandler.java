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

    private void pollUpdates() throws Exception {
        String url = baseUrl + "/ilink/bot/getupdates";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + botToken)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200 && messageHandler != null) {
            String body = response.body();
            if (body != null && !body.isEmpty() && !body.equals("{}")) {
                messageHandler.accept(body);
            }
        }
    }

    public void sendText(String userId, String text) {
        if (!connected || botToken.isEmpty()) {
            log.warn("Not connected to ilink");
            return;
        }
        try {
            String url = baseUrl + "/ilink/bot/sendmessage";
            String jsonBody = String.format("{\"content\":\"%s\",\"user_id\":\"%s\"}", text, userId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + botToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("Send message response: {}", response.statusCode());
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
