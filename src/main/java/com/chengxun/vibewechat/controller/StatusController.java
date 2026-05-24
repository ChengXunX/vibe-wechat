package com.chengxun.vibewechat.controller;

import com.chengxun.vibewechat.config.ClaudeConfig;
import com.chengxun.vibewechat.service.IlInkService;
import com.google.zxing.WriterException;
import com.chengxun.vibewechat.util.QRCodeGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
public class StatusController {

    @Autowired
    private IlInkService ilinkService;

    @Value("${vibe-wechat.ilink.base-url:https://ilinkai.weixin.qq.com}")
    private String ilinkBaseUrl;

    @Value("${vibe-wechat.ilink.bot-token:}")
    private String botToken;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "status", "running",
            "ilink-connected", ilinkService.isConnected()
        );
    }

    @GetMapping("/qrcode")
    public String qrcode() throws WriterException, IOException {
        // ilink 二维码登录 URL
        String qrContent = ilinkBaseUrl + "/cgi-bin/mmchatbotlogin?action=getqrcode&bot_token=" + botToken;
        String qrBase64 = QRCodeGenerator.generateBase64(qrContent, 300, 300);
        String status = ilinkService.isConnected() ? "已连接" : "未连接";
        return "<!DOCTYPE html>" +
               "<html><head><meta charset=\"UTF-8\"><title>Vibe WeChat - QR Code</title>" +
               "<style>body{font-family:Arial;text-align:center;padding:50px;}" +
               "h1{color:#333;}img{border:2px solid #ccc;padding:10px;}p{color:#666;}</style></head>" +
               "<body><h1>Vibe WeChat</h1><p>扫描二维码连接微信 ilink</p>" +
               "<img src=\"data:image/png;base64," + qrBase64 + "\" alt=\"QR Code\">" +
               "<p>服务状态: " + status + "</p></body></html>";
    }
}
