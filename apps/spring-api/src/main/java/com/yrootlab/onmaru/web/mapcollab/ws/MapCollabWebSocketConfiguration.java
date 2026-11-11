package com.yrootlab.onmaru.web.mapcollab.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Objects;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class MapCollabWebSocketConfiguration implements WebSocketConfigurer {

    private final MapCollabWebSocketHandler webSocketHandler;
    private final MapCollabHandshakeInterceptor handshakeInterceptor;

    public MapCollabWebSocketConfiguration(
            MapCollabWebSocketHandler webSocketHandler,
            MapCollabHandshakeInterceptor handshakeInterceptor) {
        this.webSocketHandler = Objects.requireNonNull(webSocketHandler, "webSocketHandler must not be null");
        this.handshakeInterceptor = Objects.requireNonNull(handshakeInterceptor, "handshakeInterceptor must not be null");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry.addHandler(webSocketHandler, "/api/v1/map-rooms/{roomId}/ws");
        registration.addInterceptors(handshakeInterceptor);
        registration.setAllowedOriginPatterns("*");
    }
}
