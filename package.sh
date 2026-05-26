#!/bin/bash
# VibeWeChat 跨平台打包脚本

set -e

echo "=== VibeWeChat 跨平台打包 ==="

# 检查 Java 版本
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d '"' -f 2 | cut -d '.' -f 1)
if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "错误: 需要 JDK 21+ (当前: $JAVA_VERSION)"
    exit 1
fi

# 检查 jpackage
if ! command -v jpackage &> /dev/null; then
    echo "错误: 未找到 jpackage 命令"
    echo "请确保使用 JDK 21+ (jpackage 在 JDK 14+ 中引入)"
    exit 1
fi

# 打包 jar
echo ""
echo "1. 打包 JAR..."
mvn clean package -DskipTests -q

# 检测操作系统
OS=$(uname -s)
case $OS in
    Linux*)     PLATFORM="linux";;
    Darwin*)    PLATFORM="macos";;
    MINGW*|MSYS*|CYGWIN*)  PLATFORM="windows";;
    *)          PLATFORM="unknown";;
esac

echo ""
echo "2. 检测到平台: $PLATFORM"

# 打包原生安装包
echo ""
echo "3. 打包原生安装包..."

JAR_FILE="target/vibe-wechat-1.0-SNAPSHOT.jar"
MAIN_CLASS="com.chengxun.vibewechat.VibeWeChatApplication"
APP_NAME="VibeWeChat"
OUTPUT_DIR="target/jpackage"

mkdir -p "$OUTPUT_DIR"

case $PLATFORM in
    linux)
        echo "   打包 RPM..."
        jpackage --input target \
            --main-jar "$(basename $JAR_FILE)" \
            --main-class "$MAIN_CLASS" \
            --name "$APP_NAME" \
            --type rpm \
            --linux-package-name vibe-wechat \
            --dest "$OUTPUT_DIR" \
            --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
            --java-options "--add-opens=java.base/java.util=ALL-UNNAMED" \
            2>/dev/null || echo "   RPM 打包失败，尝试 DEB..."

        echo "   打包 DEB..."
        jpackage --input target \
            --main-jar "$(basename $JAR_FILE)" \
            --main-class "$MAIN_CLASS" \
            --name "$APP_NAME" \
            --type deb \
            --linux-package-name vibe-wechat \
            --dest "$OUTPUT_DIR" \
            --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
            --java-options "--add-opens=java.base/java.util=ALL-UNNAMED" \
            2>/dev/null || echo "   DEB 打包失败"
        ;;
    macos)
        echo "   打包 PKG..."
        jpackage --input target \
            --main-jar "$(basename $JAR_FILE)" \
            --main-class "$MAIN_CLASS" \
            --name "$APP_NAME" \
            --type pkg \
            --mac-package-identifier com.chengxun.vibewechat \
            --dest "$OUTPUT_DIR" \
            --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
            --java-options "--add-opens=java.base/java.util=ALL-UNNAMED"
        ;;
    windows)
        echo "   打包 EXE..."
        jpackage --input target \
            --main-jar "$(basename $JAR_FILE)" \
            --main-class "$MAIN_CLASS" \
            --name "$APP_NAME" \
            --type exe \
            --win-menu \
            --win-shortcut \
            --dest "$OUTPUT_DIR" \
            --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" \
            --java-options "--add-opens=java.base/java.util=ALL-UNNAMED"
        ;;
    *)
        echo "   不支持的平台: $OS"
        echo "   仅打包 JAR"
        cp "$JAR_FILE" "$OUTPUT_DIR/"
        ;;
esac

echo ""
echo "=== 打包完成 ==="
echo "输出目录: $OUTPUT_DIR"
ls -lh "$OUTPUT_DIR/" 2>/dev/null || echo "无输出文件"
