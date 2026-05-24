package com.chengxun.vibewechat.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClaudeMessage {
    private String userId;
    private String content;
    private MessageType type;
    private long timestamp;

    public ClaudeMessage(String userId, String content, MessageType type) {
        this.userId = userId;
        this.content = content;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }
}
