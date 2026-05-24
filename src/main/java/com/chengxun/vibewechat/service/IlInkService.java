package com.chengxun.vibewechat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Slf4j
@Service
public class IlInkService {

    @Autowired
    private IlInkConnectionHandler connectionHandler;

    @PostConstruct
    public void init() {
        connectionHandler.setMessageHandler(this::handleMessage);
    }

    private void handleMessage(String message) {
        log.info("Received message: {}", message);
        // TODO: 解析 ilink 消息并转发给 MessageRouter
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
