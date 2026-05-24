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
    private String installPath = ""; // Claude 安装路径
}
