package com.archemidia.config;
import com.archemidia.handler.GameWebSocketHandler;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.socket.config.annotation.*;

@Configuration @EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final GameWebSocketHandler handler;
    public WebSocketConfig(GameWebSocketHandler h) { this.handler = h; }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
        r.addHandler(handler, "/game").setAllowedOrigins("*");
    }
    @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(c->c.disable()).authorizeHttpRequests(a->a.requestMatchers("/game","/api/**").permitAll().anyRequest().authenticated());
        return http.build();
    }
}