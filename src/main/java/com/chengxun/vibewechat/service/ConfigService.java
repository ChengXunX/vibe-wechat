package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.ClaudeConfig;
import com.chengxun.vibewechat.config.FilterConfig;
import com.chengxun.vibewechat.config.SwitchConfig;
import com.chengxun.vibewechat.config.ThinkingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Autowired
    private ThinkingConfig thinkingConfig;

    @Autowired
    private IlInkConnectionHandler ilinkConnectionHandler;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String CONFIG_FILE = "vibe-wechat-config.json";
    private static final String CLAUDE_SETTINGS_FILE = System.getProperty("user.home") + "/.claude/settings.json";

    private final SwitchConfig switchConfig = new SwitchConfig();
    private String lastKnownSettingsHash = "";
    private boolean conflictDetected = false;

    @PostConstruct
    public void init() {
        loadConfig();
        lastKnownSettingsHash = computeSettingsHash();
    }

    public void saveConfig() {
        try {
            Map<String, Object> config = new HashMap<>();

            Map<String, Object> claude = new HashMap<>();
            claude.put("apiUrl", claudeConfig.getApiUrl());
            claude.put("apiKey", claudeConfig.getApiKey());
            claude.put("model", claudeConfig.getModel());
            claude.put("installPath", claudeConfig.getInstallPath());
            claude.put("workDir", claudeConfig.getWorkDir());
            config.put("claude", claude);

            Map<String, Object> thinking = new HashMap<>();
            thinking.put("level", thinkingConfig.getLevel());
            thinking.put("levels", thinkingConfig.getLevels());
            config.put("thinking", thinking);

            Map<String, Object> filter = new HashMap<>();
            filter.put("showToolCalls", filterConfig.isShowToolCalls());
            filter.put("showFileRead", filterConfig.isShowFileRead());
            filter.put("showFileEdit", filterConfig.isShowFileEdit());
            filter.put("showFileOperations", filterConfig.isShowFileOperations());
            filter.put("showSubtaskCompletion", filterConfig.isShowSubtaskCompletion());
            filter.put("showTaskCompletion", filterConfig.isShowTaskCompletion());
            filter.put("showTokenUsage", filterConfig.isShowTokenUsage());
            filter.put("showMessageStatus", filterConfig.isShowMessageStatus());
            filter.put("showSubtaskStatus", filterConfig.isShowSubtaskStatus());
            filter.put("maxMessagesPerUser", filterConfig.getMaxMessagesPerUser());
            filter.put("blockedKeywords", filterConfig.getBlockedKeywords());
            config.put("filter", filter);

            // 保存 switch 配置
            Map<String, Object> switchCfg = new HashMap<>();
            switchCfg.put("activeProfile", switchConfig.getActiveProfile());
            List<Map<String, Object>> profiles = new ArrayList<>();
            for (SwitchConfig.SwitchProfile profile : switchConfig.getProfiles()) {
                Map<String, Object> p = new HashMap<>();
                p.put("name", profile.getName());
                p.put("apiUrl", profile.getApiUrl());
                p.put("apiKey", profile.getApiKey());
                p.put("model", profile.getModel());
                profiles.add(p);
            }
            switchCfg.put("profiles", profiles);
            config.put("switch", switchCfg);

            // 保存 ilink bot_token
            String botToken = ilinkConnectionHandler.getBotToken();
            if (botToken != null && !botToken.isEmpty()) {
                config.put("ilink", Map.of("botToken", botToken));
            }

            objectMapper.writeValue(new File(CONFIG_FILE), config);
            saveClaudeSettings();
            log.info("Config saved to {}", CONFIG_FILE);
        } catch (IOException e) {
            log.error("Failed to save config", e);
        }
    }

    public void saveClaudeSettings() {
        try {
            Path settingsPath = Path.of(CLAUDE_SETTINGS_FILE);
            Map<String, Object> settings;
            if (Files.exists(settingsPath)) {
                String content = new String(Files.readAllBytes(settingsPath));
                settings = objectMapper.readValue(content, Map.class);
            } else {
                settings = new HashMap<>();
                Files.createDirectories(settingsPath.getParent());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> env = (Map<String, Object>) settings.get("env");
            if (env == null) {
                env = new HashMap<>();
                settings.put("env", env);
            }

            env.put("ANTHROPIC_BASE_URL", claudeConfig.getApiUrl());
            env.put("ANTHROPIC_AUTH_TOKEN", claudeConfig.getApiKey());
            env.put("ANTHROPIC_MODEL", claudeConfig.getModel());

            objectMapper.writeValue(settingsPath.toFile(), settings);
            lastKnownSettingsHash = computeSettingsHash();
            log.info("Claude settings synced to {}", CLAUDE_SETTINGS_FILE);
        } catch (IOException e) {
            log.error("Failed to save Claude settings", e);
        }
    }

    private String computeSettingsHash() {
        try {
            Path settingsPath = Path.of(CLAUDE_SETTINGS_FILE);
            if (Files.exists(settingsPath)) {
                String content = new String(Files.readAllBytes(settingsPath));
                return String.valueOf(content.hashCode());
            }
        } catch (IOException e) {
            log.debug("Failed to compute settings hash", e);
        }
        return "";
    }

    public boolean detectConflict() {
        conflictDetected = false;
        String currentHash = computeSettingsHash();
        if (!lastKnownSettingsHash.isEmpty() && !currentHash.equals(lastKnownSettingsHash)) {
            log.warn("Claude settings file changed externally, saving as conflict preset");
            saveConflictPreset();
            lastKnownSettingsHash = currentHash;
            conflictDetected = true;
        }
        if (lastKnownSettingsHash.isEmpty() && !currentHash.isEmpty()) {
            lastKnownSettingsHash = currentHash;
        }
        return conflictDetected;
    }

    private void saveConflictPreset() {
        try {
            Path settingsPath = Path.of(CLAUDE_SETTINGS_FILE);
            if (!Files.exists(settingsPath)) return;

            String content = new String(Files.readAllBytes(settingsPath));
            @SuppressWarnings("unchecked")
            Map<String, Object> settings = objectMapper.readValue(content, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> env = (Map<String, Object>) settings.get("env");
            if (env == null) return;

            String extUrl = (String) env.getOrDefault("ANTROPIC_BASE_URL", "");
            String extKey = (String) env.getOrDefault("ANTROPIC_AUTH_TOKEN", "");
            String extModel = (String) env.getOrDefault("ANTHROPIC_MODEL", "");

            SwitchConfig.SwitchProfile conflictProfile = new SwitchConfig.SwitchProfile(
                    "冲突配置", extUrl, extKey, extModel
            );
            switchConfig.getProfiles().removeIf(p -> p.getName().equals("冲突配置"));
            switchConfig.getProfiles().add(conflictProfile);
            saveConfigWithoutSync();

            log.info("Conflict preset saved: url={}, model={}", extUrl, extModel);
        } catch (IOException e) {
            log.error("Failed to save conflict preset", e);
        }
    }

    private void saveConfigWithoutSync() {
        try {
            Map<String, Object> config = new HashMap<>();

            Map<String, Object> claude = new HashMap<>();
            claude.put("apiUrl", claudeConfig.getApiUrl());
            claude.put("apiKey", claudeConfig.getApiKey());
            claude.put("model", claudeConfig.getModel());
            claude.put("installPath", claudeConfig.getInstallPath());
            claude.put("workDir", claudeConfig.getWorkDir());
            config.put("claude", claude);

            Map<String, Object> thinking = new HashMap<>();
            thinking.put("level", thinkingConfig.getLevel());
            thinking.put("levels", thinkingConfig.getLevels());
            config.put("thinking", thinking);

            Map<String, Object> filter = new HashMap<>();
            filter.put("showToolCalls", filterConfig.isShowToolCalls());
            filter.put("showFileRead", filterConfig.isShowFileRead());
            filter.put("showFileEdit", filterConfig.isShowFileEdit());
            filter.put("showFileOperations", filterConfig.isShowFileOperations());
            filter.put("showSubtaskCompletion", filterConfig.isShowSubtaskCompletion());
            filter.put("showTaskCompletion", filterConfig.isShowTaskCompletion());
            filter.put("showTokenUsage", filterConfig.isShowTokenUsage());
            filter.put("showMessageStatus", filterConfig.isShowMessageStatus());
            filter.put("showSubtaskStatus", filterConfig.isShowSubtaskStatus());
            filter.put("maxMessagesPerUser", filterConfig.getMaxMessagesPerUser());
            filter.put("blockedKeywords", filterConfig.getBlockedKeywords());
            config.put("filter", filter);

            Map<String, Object> switchCfg = new HashMap<>();
            switchCfg.put("activeProfile", switchConfig.getActiveProfile());
            List<Map<String, Object>> profiles = new ArrayList<>();
            for (SwitchConfig.SwitchProfile profile : switchConfig.getProfiles()) {
                Map<String, Object> p = new HashMap<>();
                p.put("name", profile.getName());
                p.put("apiUrl", profile.getApiUrl());
                p.put("apiKey", profile.getApiKey());
                p.put("model", profile.getModel());
                profiles.add(p);
            }
            switchCfg.put("profiles", profiles);
            config.put("switch", switchCfg);

            objectMapper.writeValue(new File(CONFIG_FILE), config);
        } catch (IOException e) {
            log.error("Failed to save config without sync", e);
        }
    }

    public boolean isConflictDetected() {
        return conflictDetected;
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
                if (claude.containsKey("workDir") && claude.get("workDir") != null) {
                    claudeConfig.setWorkDir((String) claude.get("workDir"));
                    String wd = (String) claude.get("workDir");
                    if (!wd.isEmpty()) {
                        System.setProperty("user.dir", wd);
                    }
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> thinking = (Map<String, Object>) config.get("thinking");
            if (thinking != null) {
                if (thinking.containsKey("level") && thinking.get("level") != null) {
                    thinkingConfig.setLevel((String) thinking.get("level"));
                }
                if (thinking.containsKey("levels") && thinking.get("levels") != null) {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> levels = (java.util.List<String>) thinking.get("levels");
                    thinkingConfig.setLevels(levels);
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) config.get("filter");
            if (filter != null) {
                if (filter.containsKey("showToolCalls")) filterConfig.setShowToolCalls((Boolean) filter.get("showToolCalls"));
                if (filter.containsKey("showFileRead")) filterConfig.setShowFileRead((Boolean) filter.get("showFileRead"));
                if (filter.containsKey("showFileEdit")) filterConfig.setShowFileEdit((Boolean) filter.get("showFileEdit"));
                if (filter.containsKey("showFileOperations")) filterConfig.setShowFileOperations((Boolean) filter.get("showFileOperations"));
                if (filter.containsKey("showSubtaskCompletion")) filterConfig.setShowSubtaskCompletion((Boolean) filter.get("showSubtaskCompletion"));
                if (filter.containsKey("showTaskCompletion")) filterConfig.setShowTaskCompletion((Boolean) filter.get("showTaskCompletion"));
                if (filter.containsKey("showTokenUsage")) filterConfig.setShowTokenUsage((Boolean) filter.get("showTokenUsage"));
                if (filter.containsKey("showMessageStatus")) filterConfig.setShowMessageStatus((Boolean) filter.get("showMessageStatus"));
                if (filter.containsKey("showSubtaskStatus")) filterConfig.setShowSubtaskStatus((Boolean) filter.get("showSubtaskStatus"));
                if (filter.containsKey("maxMessagesPerUser")) filterConfig.setMaxMessagesPerUser((Integer) filter.get("maxMessagesPerUser"));
                if (filter.containsKey("blockedKeywords")) {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> keywords = (java.util.List<String>) filter.get("blockedKeywords");
                    filterConfig.setBlockedKeywords(keywords != null ? keywords : new java.util.ArrayList<>());
                }
            }

            // 加载 switch 配置
            @SuppressWarnings("unchecked")
            Map<String, Object> switchCfg = (Map<String, Object>) config.get("switch");
            if (switchCfg != null) {
                if (switchCfg.containsKey("activeProfile")) {
                    switchConfig.setActiveProfile((String) switchCfg.get("activeProfile"));
                }
                if (switchCfg.containsKey("profiles")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> profiles = (List<Map<String, Object>>) switchCfg.get("profiles");
                    if (profiles != null) {
                        for (Map<String, Object> p : profiles) {
                            SwitchConfig.SwitchProfile profile = new SwitchConfig.SwitchProfile();
                            profile.setName((String) p.get("name"));
                            profile.setApiUrl((String) p.get("apiUrl"));
                            profile.setApiKey((String) p.get("apiKey"));
                            profile.setModel((String) p.get("model"));
                            switchConfig.getProfiles().add(profile);
                        }
                    }
                }
            }

            // 加载 ilink bot_token
            @SuppressWarnings("unchecked")
            Map<String, Object> ilinkCfg = (Map<String, Object>) config.get("ilink");
            if (ilinkCfg != null && ilinkCfg.containsKey("botToken")) {
                String botToken = (String) ilinkCfg.get("botToken");
                if (botToken != null && !botToken.isEmpty()) {
                    ilinkConnectionHandler.setBotToken(botToken);
                    log.info("Loaded ilink bot_token from config");
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
              .append("\n  API: ").append(profile.getApiUrl() != null ? profile.getApiUrl() : "未设置")
              .append("\n  模型: ").append(profile.getModel())
              .append("\n  Key: ").append(maskedKey).append("\n");
        }
        return sb.toString();
    }
}
