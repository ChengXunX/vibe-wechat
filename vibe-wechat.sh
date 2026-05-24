#!/bin/bash

# Vibe WeChat 启动/停止脚本

JAR_FILE="target/vibe-wechat-1.0-SNAPSHOT.jar"
PID_FILE="vibe-wechat.pid"
DEFAULT_PORT=9921

usage() {
    echo "用法: $0 {start|stop|restart|status|port <端口号>}"
    echo ""
    echo "命令:"
    echo "  start          启动服务"
    echo "  stop           停止服务"
    echo "  restart        重启服务"
    echo "  status         查看状态"
    echo "  port <端口号>  修改端口并重启"
    echo ""
    echo "示例:"
    echo "  $0 start"
    echo "  $0 port 8080"
}

start() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p "$PID" > /dev/null 2>&1; then
            echo "服务已在运行中 (PID: $PID)"
            return 1
        fi
    fi

    if [ ! -f "$JAR_FILE" ]; then
        echo "JAR 文件不存在，正在编译..."
        mvn clean package -DskipTests -q
    fi

    PORT=$(grep "server.port" src/main/resources/application.yml | awk '{print $2}' | tr -d '"')
    PORT=${PORT:-$DEFAULT_PORT}

    echo "启动 Vibe WeChat (端口: $PORT)..."
    nohup java -jar "$JAR_FILE" > vibe-wechat.log 2>&1 &
    echo $! > "$PID_FILE"
    echo "服务已启动 (PID: $(cat $PID_FILE))"

    QR_URL="http://localhost:$PORT/qrcode"
    echo "二维码页面: $QR_URL"

    # 自动打开浏览器
    sleep 2
    if command -v xdg-open > /dev/null 2>&1; then
        xdg-open "$QR_URL"
    elif command -v open > /dev/null 2>&1; then
        open "$QR_URL"
    elif command -v google-chrome > /dev/null 2>&1; then
        google-chrome "$QR_URL"
    elif command -v firefox > /dev/null 2>&1; then
        firefox "$QR_URL"
    else
        echo "请手动打开浏览器访问: $QR_URL"
    fi
}

stop() {
    if [ ! -f "$PID_FILE" ]; then
        echo "服务未运行"
        return 1
    fi

    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "停止服务 (PID: $PID)..."
        kill "$PID"
        rm -f "$PID_FILE"
        echo "服务已停止"
    else
        echo "服务未运行"
        rm -f "$PID_FILE"
    fi
}

status() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p "$PID" > /dev/null 2>&1; then
            echo "服务运行中 (PID: $PID)"
            PORT=$(grep "server.port" src/main/resources/application.yml | awk '{print $2}' | tr -d '"')
            echo "端口: ${PORT:-$DEFAULT_PORT}"
            return 0
        fi
    fi
    echo "服务未运行"
    return 1
}

change_port() {
    NEW_PORT=$1
    if [ -z "$NEW_PORT" ]; then
        echo "请指定端口号"
        return 1
    fi

    sed -i "s/server:.*/server:/" src/main/resources/application.yml
    sed -i "/server:/a\\  port: $NEW_PORT" src/main/resources/application.yml
    echo "端口已修改为: $NEW_PORT"

    if status > /dev/null 2>&1; then
        echo "重启服务..."
        stop
        sleep 1
        start
    fi
}

case "$1" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        stop
        sleep 1
        start
        ;;
    status)
        status
        ;;
    port)
        change_port "$2"
        ;;
    *)
        usage
        exit 1
        ;;
esac
