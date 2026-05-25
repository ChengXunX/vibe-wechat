package com.chengxun.vibewechat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "vibe-wechat.thinking")
public class ThinkingConfig {
    private String level = "medium";
    private List<String> levels = Arrays.asList("low", "medium", "high", "max");

    // 各级别对应的 thinking budget tokens
    private int getBudgetTokens(String level) {
        return switch (level.toLowerCase()) {
            case "low" -> 1000;
            case "medium" -> 5000;
            case "high" -> 10000;
            case "max" -> 32000;
            default -> 5000;
        };
    }

    public int getCurrentBudgetTokens() {
        return getBudgetTokens(level);
    }

    public boolean isEnabled() {
        return !"off".equalsIgnoreCase(level);
    }

    public String cycleLevel() {
        int idx = levels.indexOf(level);
        if (idx == -1 || idx >= levels.size() - 1) {
            level = levels.get(0);
        } else {
            level = levels.get(idx + 1);
        }
        return level;
    }
}
