package com.chengxun.vibewechat.service;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.function.Consumer;

@Slf4j
@Component
public class IlInkConnectionHandler {

    @Value("${vibe-wechat.ilink.host:localhost}")
    private String host;

    @Value("${vibe-wechat.ilink.port:9090}")
    private int port;

    private Channel channel;
    private EventLoopGroup group;
    private volatile boolean connected = false;
    private Consumer<String> messageHandler;

    @PostConstruct
    public void init() {
        connect();
    }

    @PreDestroy
    public void destroy() {
        connected = false;
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    private void connect() {
        new Thread(() -> {
            group = new NioEventLoopGroup();
            try {
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(group)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new StringDecoder());
                                ch.pipeline().addLast(new StringEncoder());
                                ch.pipeline().addLast(new IlInkMessageHandler());
                            }
                        });

                ChannelFuture future = bootstrap.connect(host, port).sync();
                channel = future.channel();
                connected = true;
                log.info("Connected to ilink at {}:{}", host, port);

                channel.closeFuture().sync();
            } catch (Exception e) {
                log.error("Failed to connect to ilink", e);
            } finally {
                connected = false;
                group.shutdownGracefully();
            }
        }, "ilink-connector").start();
    }

    public void sendText(String userId, String text) {
        if (!connected || channel == null) {
            log.warn("Not connected to ilink");
            return;
        }
        // TODO: 实现 ilink 消息协议
        String message = buildTextMessage(userId, text);
        channel.writeAndFlush(message);
    }

    public void sendTyping(String userId) {
        if (!connected || channel == null) return;
        // TODO: 发送输入状态
        String message = buildTypingMessage(userId);
        channel.writeAndFlush(message);
    }

    public void sendStopTyping(String userId) {
        if (!connected || channel == null) return;
        // TODO: 停止输入状态
        String message = buildStopTypingMessage(userId);
        channel.writeAndFlush(message);
    }

    private String buildTextMessage(String userId, String text) {
        // ilink 消息协议格式（示例）
        return String.format("{\"type\":\"text\",\"user\":\"%s\",\"content\":\"%s\"}", userId, text);
    }

    private String buildTypingMessage(String userId) {
        return String.format("{\"type\":\"typing\",\"user\":\"%s\"}", userId);
    }

    private String buildStopTypingMessage(String userId) {
        return String.format("{\"type\":\"stop_typing\",\"user\":\"%s\"}", userId);
    }

    public boolean isConnected() {
        return connected;
    }

    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler;
    }

    private class IlInkMessageHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            log.debug("Received from ilink: {}", msg);
            if (messageHandler != null) {
                messageHandler.accept(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("ilink connection error", cause);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            connected = false;
            log.info("ilink connection closed");
        }
    }
}
