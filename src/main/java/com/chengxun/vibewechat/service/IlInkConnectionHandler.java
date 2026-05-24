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

    @Value("${vibe-wechat.ilink.base-url:https://api.ilink.bot}")
    private String baseUrl;

    @Value("${vibe-wechat.ilink.bot-token:}")
    private String botToken;

    private volatile boolean connected = false;
    private Consumer<String> messageHandler;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private ScheduledExecutorService scheduler;
    private String lastMessageId = "";

    @PostConstruct
    public void init() {
        if (botToken != null && !botToken.isEmpty()) {
            startPolling();
        }
    }

    @PreDestroy
    public void destroy() {
        connected = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private void startPolling() {
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
        if (!connected) {
            log.warn("Not connected to ilink");
            return;
        }
        // TODO: 使用 ilink API 发送消息
        log.info("Send to {}: {}", userId, text);
    }

    public void sendTyping(String userId) {
        if (!connected) return;
        log.debug("Typing to {}", userId);
    }

    public void sendStopTyping(String userId) {
        if (!connected) return;
        log.debug("Stop typing to {}", userId);
    }

    public boolean isConnected() {
        return connected;
    }

    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler;
    }
}
