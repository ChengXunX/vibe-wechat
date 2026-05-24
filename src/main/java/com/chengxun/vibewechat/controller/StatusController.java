package com.chengxun.vibewechat.controller;

import com.chengxun.vibewechat.service.IlInkService;
import com.google.zxing.WriterException;
import com.chengxun.vibewechat.util.QRCodeGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@RestController
public class StatusController {

    @Autowired
    private IlInkService ilinkService;

    @Value("${vibe-wechat.ilink.base-url:https://ilinkai.weixin.qq.com}")
    private String ilinkBaseUrl;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "status", "running",
            "ilink-connected", ilinkService.isConnected()
        );
    }

    @GetMapping("/qrcode")
    public String qrcode() throws Exception {
        String apiUrl = ilinkBaseUrl + "/ilink/bot/get_bot_qrcode?bot_type=3";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String qrcode = "";
        String rawResponse = response.body();

        if (response.statusCode() == 200 && rawResponse != null && !rawResponse.isEmpty()) {
            int start = rawResponse.indexOf("\"qrcode\":\"") + 10;
            int end = rawResponse.indexOf("\"", start);
            if (start > 9 && end > start) {
                qrcode = rawResponse.substring(start, end);
            }
        }

        String status = ilinkService.isConnected() ? "已连接" : "未连接";

        if (!qrcode.isEmpty()) {
            String qrImgUrl = "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=" + qrcode + "&bot_type=3";
            String qrBase64 = QRCodeGenerator.generateBase64(qrImgUrl, 300, 300);

            return "<!DOCTYPE html>" +
                   "<html><head><meta charset=\"UTF-8\"><title>Vibe WeChat - QR Code</title>" +
                   "<style>body{font-family:Arial;text-align:center;padding:50px;}" +
                   "h1{color:#333;}img{border:2px solid #ccc;padding:10px;max-width:300px;}p{color:#666;}" +
                   ".refresh{color:#0066cc;cursor:pointer;}</style>" +
                   "<script>var countdown=30;function timer(){document.getElementById('countdown').textContent=countdown;if(countdown<=0){location.reload();}countdown--;setTimeout(timer,1000);}window.onload=timer;</script></head>" +
                   "<body><h1>Vibe WeChat</h1><p>扫描二维码连接微信 ilink</p>" +
                   "<img src=\"data:image/png;base64," + qrBase64 + "\" alt=\"QR Code\">" +
                   "<p>服务状态: " + status + "</p>" +
                   "<p>QR Code Token: " + qrcode + "</p>" +
                   "<p class=\"refresh\">页面将在 <span id=\"countdown\">30</span> 秒后自动刷新</p></body></html>";
        } else {
            return "<!DOCTYPE html>" +
                   "<html><head><meta charset=\"UTF-8\"><title>Vibe WeChat - QR Code</title>" +
                   "<style>body{font-family:Arial;text-align:center;padding:50px;}" +
                   "h1{color:#333;}p{color:#666;pre{background:#f5f5f5;padding:10px;text-align:left;}</style></head>" +
                   "<body><h1>Vibe WeChat</h1><p>获取二维码失败</p>" +
                   "<p>API 响应:</p><pre>" + rawResponse + "</pre>" +
                   "<p>服务状态: " + status + "</p></body></html>";
        }
    }

    @GetMapping("/callback")
    public Map<String, String> callback(@RequestParam String qrcode) {
        // 轮询二维码扫描状态
        try {
            String apiUrl = ilinkBaseUrl + "/ilink/bot/get_qrcode_status?qrcode=" + qrcode;

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("iLink-App-ClientVersion", "1")
                    .GET()
                    .timeout(Duration.ofSeconds(35))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                // 提取 bot_token
                int start = body.indexOf("\"bot_token\":\"") + 13;
                int end = body.indexOf("\"", start);
                if (start > 12 && end > start) {
                    String botToken = body.substring(start, end);
                    ilinkService.setBotToken(botToken);
                    return Map.of("status", "confirmed", "bot_token", botToken);
                }
                // 提取状态
                start = body.indexOf("\"status\":\"") + 10;
                end = body.indexOf("\"", start);
                if (start > 9 && end > start) {
                    String status = body.substring(start, end);
                    return Map.of("status", status);
                }
            }
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
        return Map.of("status", "wait");
    }
}
