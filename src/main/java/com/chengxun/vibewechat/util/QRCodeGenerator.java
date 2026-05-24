package com.chengxun.vibewechat.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class QRCodeGenerator {

    public static String generateBase64(String content, int width, int height) throws WriterException, IOException {
        BitMatrix bitMatrix = createBitMatrix(content, width, height);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    public static void generateFile(String content, int width, int height, String filePath) throws WriterException, IOException {
        BitMatrix bitMatrix = createBitMatrix(content, width, height);
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", Path.of(filePath));
    }

    private static BitMatrix createBitMatrix(String content, int width, int height) throws WriterException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        return qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height, hints);
    }
}
