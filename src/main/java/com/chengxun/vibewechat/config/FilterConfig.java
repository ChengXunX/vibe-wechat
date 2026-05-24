package com.chengxun.vibewechat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "vibe-wechat.filter")
public class FilterConfig {
    private boolean showToolCalls = false;
    private boolean showFileRead = false;
    private boolean showFileEdit = false;
    private boolean showFileOperations = false;
    private boolean showDecisionsOnly = true;
    private boolean showResultsOnly = true;
    private boolean showSubtaskCompletion = true;
    private boolean showTaskCompletion = true;
    private boolean showTaskDuration = true;
    private boolean showTokenUsage = true;
    private int maxMessagesPerUser = 10;
    private List<String> blockedKeywords = new ArrayList<>();
}
