package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.ClaudeConfig;
import com.chengxun.vibewechat.config.FilterConfig;
import com.chengxun.vibewechat.config.SwitchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ConfigService {

    @Autowired
    private ClaudeConfig claudeConfig;

    @Autowired
    private FilterConfig filterConfig;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String CONFIG_FILE = "vibe-wechat-config.json";

    private final SwitchConfig switchConfig = new SwitchConfig();

    @PostConstruct
    public void init() {
        loadConfig();
    }

    public void saveConfig() {
        try {
            Map<String, Object> config = new HashMap<>();

            Map<String, Object> claude = new HashMap<>();
            claude.put("apiUrl", claudeConfig.getApiUrl());
            claude.put("apiKey", claudeConfig.getApiKey());
            claude.put("model", claudeConfig.getModel());
            claude.put("installPath", claudeConfig.getInstallPath());
            config.put("claude", claude);

            Map<String, Object> filter = new HashMap<>();
            filter.put("showToolCalls", filterConfig.isShowToolCalls());
            filter.put("showFileRead", filterConfig.isShowFileRead());
            filter.put("showFileEdit", filterConfig.isShowFileEdit());
            filter.put("showFileOperations", filterConfig.isShowFileOperations());
            filter.put("showDecisionsOnly", filterConfig.isShowDecisionsOnly());
            filter.put("showResultsOnly", filterConfig.isShowResultsOnly());
            filter.put("showSubtaskCompletion", filterConfig.isShowSubtaskCompletion());
            filter.put("showTaskCompletion", filterConfig.isShowTaskCompletion());
            filter.put("showTaskDuration", filterConfig.isShowTaskDuration());
            filter.put("showTokenUsage", filterConfig.isShowTokenUsage());
            filter.put("showMessageStatus", filterConfig.isShowMessageStatus());
            filter.put("maxMessagesPerUser", filterConfig.getMaxMessagesPerUser());
            filter.put("blockedKeywords", filterConfig.getBlockedKeywords());
            config.put("filter", filter);

            objectMapper.writeValue(new File(CONFIG_FILE), config);
            log.info("Config saved to {}", CONFIG_FILE);
        } catch (IOException e) {
            log.error("Failed to save config", e);
        }
    }

    public void loadConfig() {
        try {
            File file = new File(CONFIG_FILE);
            if (!file.exists()) {
                log.info("No config file found, using defaults");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(file, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> claude = (Map<String, Object>) config.get("claude");
            if (claude != null) {
                if (claude.containsKey("apiUrl") && claude.get("apiUrl") != null) {
                    claudeConfig.setApiUrl((String) claude.get("apiUrl"));
                }
                if (claude.containsKey("apiKey") && claude.get("apiKey") != null) {
                    claudeConfig.setApiKey((String) claude.get("apiKey"));
                }
                if (claude.containsKey("model") && claude.get("model") != null) {
                    claudeConfig.setModel((String) claude.get("model"));
                }
                if (claude.containsKey("installPath") && claude.get("installPath") != null) {
                    claudeConfig.setInstallPath((String) claude.get("installPath"));
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) config.get("filter");
            if (filter != null) {
                if (filter.containsKey("showToolCalls")) filterConfig.setShowToolCalls((Boolean) filter.get("showToolCalls"));
                if (filter.containsKey("showFileRead")) filterConfig.setShowFileRead((Boolean) filter.get("showFileRead"));
                if (filter.containsKey("showFileEdit")) filterConfig.setShowFileEdit((Boolean) filter.get("showFileEdit"));
                if (filter.containsKey("showFileOperations")) filterConfig.setShowFileOperations((Boolean) filter.get("showFileOperations"));
                if (filter.containsKey("showDecisionsOnly")) filterConfig.setShowDecisionsOnly((Boolean) filter.get("showDecisionsOnly"));
                if (filter.containsKey("showResultsOnly")) filterConfig.setShowResultsOnly((Boolean) filter.get("showResultsOnly"));
                if (filter.containsKey("showSubtaskCompletion")) filterConfig.setShowSubtaskCompletion((Boolean) filter.get("showSubtaskCompletion"));
                if (filter.containsKey("showTaskCompletion")) filterConfig.setShowTaskCompletion((Boolean) filter.get("showTaskCompletion"));
                if (filter.containsKey("showTaskDuration")) filterConfig.setShowTaskDuration((Boolean) filter.get("showTaskDuration"));
                if (filter.containsKey("showTokenUsage")) filterConfig.setShowTokenUsage((Boolean) filter.get("showTokenUsage"));
                if (filter.containsKey("showMessageStatus")) filterConfig.setShowMessageStatus((Boolean) filter.get("showMessageStatus"));
                if (filter.containsKey("maxMessagesPerUser")) filterConfig.setMaxMessagesPerUser((Integer) filter.get("maxMessagesPerUser"));
                if (filter.containsKey("blockedKeywords")) {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> keywords = (java.util.List<String>) filter.get("blockedKeywords");
                    filterConfig.setBlockedKeywords(keywords != null ? keywords : new java.util.ArrayList<>());
                }
            }

            log.info("Config loaded from {}", CONFIG_FILE);
        } catch (IOException e) {
            log.error("Failed to load config", e);
        }
    }

    public void saveProfile(String profileName) {
        SwitchConfig.SwitchProfile profile = new SwitchConfig.SwitchProfile(
                profileName,
                claudeConfig.getApiUrl(),
                claudeConfig.getApiKey(),
                claudeConfig.getModel()
        );

        // 更新或添加配置
        switchConfig.getProfiles().removeIf(p -> p.getName().equals(profileName));
        switchConfig.getProfiles().add(profile);
        switchConfig.setActiveProfile(profileName);

        saveConfig();
    }

    public boolean switchProfile(String profileName) {
        for (SwitchConfig.SwitchProfile profile : switchConfig.getProfiles()) {
            if (profile.getName().equals(profileName)) {
                claudeConfig.setApiUrl(profile.getApiUrl());
                claudeConfig.setApiKey(profile.getApiKey());
                claudeConfig.setModel(profile.getModel());
                switchConfig.setActiveProfile(profileName);
                saveConfig();
                return true;
            }
        }
        return false;
    }

    public String getActiveProfile() {
        return switchConfig.getActiveProfile();
    }

    public String getSwitchProfiles() {
        List<SwitchConfig.SwitchProfile> profiles = switchConfig.getProfiles();
        if (profiles.isEmpty()) {
            return "暂无保存的配置\n\n使用 `v-save <名称>` 保存当前配置";
        }

        StringBuilder sb = new StringBuilder("**已保存的配置:**\n");
        String active = switchConfig.getActiveProfile();
        for (SwitchConfig.SwitchProfile profile : profiles) {
            String marker = profile.getName().equals(active) ? " ✅" : "";
            String maskedKey = profile.getApiKey() != null && profile.getApiKey().length() > 8 ?
                    profile.getApiKey().substring(0, 4) + "****" : "未设置";
            sb.append("- `").append(profile.getName()).append("`").append(marker)
              .append("\n  模型: ").append(profile.getModel())
              .append("\n  Key: ").append(maskedKey).append("\n");
        }
        return sb.toString();
    }
}
