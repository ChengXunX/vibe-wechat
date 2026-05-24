package com.chengxun.vibewechat.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SwitchConfig {
    private List<SwitchProfile> profiles = new ArrayList<>();
    private String activeProfile = "";

    @Data
    public static class SwitchProfile {
        private String name;
        private String apiUrl;
        private String apiKey;
        private String model;
        private int maxTokens = 4096;

        public SwitchProfile() {}

        public SwitchProfile(String name, String apiUrl, String apiKey, String model) {
            this.name = name;
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
            this.model = model;
        }
    }
}
