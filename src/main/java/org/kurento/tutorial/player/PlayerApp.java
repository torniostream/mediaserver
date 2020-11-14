package org.kurento.tutorial.player;

import org.kurento.client.KurentoClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@EnableWebSocket
@SpringBootApplication
public class PlayerApp implements WebSocketConfigurer {

  @Bean
  public PlayerHandler handler() {
    return new PlayerHandler();
  }

  @Bean
  public KurentoClient kurentoClient() {
    return KurentoClient.create();
  }

  @Bean
  public ServletServerContainerFactoryBean createServletServerContainerFactoryBean() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(32768);
    return container;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    // This wildcard is needed otherwise Spring Boot whines with a 403
    // It's actually CORS and not some unauthorized error.
    // TODO: check if we can allow tornio.stream and 127.0.0.1
    registry.addHandler(handler(), "/player").setAllowedOrigins("*");
  }

  public static void main(String[] args) throws Exception {
    SpringApplication.run(PlayerApp.class, args);
  }
}
