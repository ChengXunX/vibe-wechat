package com.chengxun.vibewechat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vibe-wechat.claude")
public class ClaudeConfig {
    private String apiKey = "";
    private String apiUrl = "https://api.anthropic.com";
    private String model = "claude-sonnet-4-20250514";
    private int maxTokens = 4096;
    private String installPath = "";
    private int contextWindowSize = 200000;
    private String workDir = "";

    // 常驻进程配置
    private int maxProcessesPerUser = 5;
    private long processIdleTimeoutMs = 86400000;  // 24小时空闲自动销毁
    private long processStartTimeoutMs = 30000;    // 启动超时30秒

    // 自动压缩配置
    private int contextCompactionThreshold = 80;   // 上下文使用超过80%时自动压缩

    public void setMaxProcessesPerUser(int max) {
        int maxAllowed = Runtime.getRuntime().availableProcessors() * 2;
        this.maxProcessesPerUser = Math.max(1, Math.min(max, maxAllowed));
    }
}
