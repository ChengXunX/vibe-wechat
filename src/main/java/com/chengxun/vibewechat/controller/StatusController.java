package com.chengxun.vibewechat.controller;

import com.chengxun.vibewechat.service.IlInkService;
import com.google.zxing.WriterException;
import com.chengxun.vibewechat.util.QRCodeGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
public class StatusController {

    @Autowired
    private IlInkService ilinkService;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "status", "running",
            "ilink-connected", ilinkService.isConnected()
        );
    }

    @GetMapping("/qrcode")
    public Map<String, String> qrcode() throws WriterException, IOException {
        // TODO: 生成实际的 ilink 连接二维码
        String qrContent = "https://ilink.example.com/connect";
        String qrBase64 = QRCodeGenerator.generateBase64(qrContent, 300, 300);
        return Map.of("qrcode", "data:image/png;base64," + qrBase64);
    }
}
