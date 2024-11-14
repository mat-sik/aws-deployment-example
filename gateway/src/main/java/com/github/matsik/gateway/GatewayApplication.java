package com.github.matsik.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}

	@Bean
	public RouteLocator customRouteLocator(
			@Value("${client.messages.eurekaServiceId}") String messageServiceId,
			RouteLocatorBuilder builder
	) {
		return builder.routes()
				.route("push-message", r -> r.path("/messages")
						.and().method("POST")
						.uri("lb://" + messageServiceId))
				.route("get-messages", r -> r.path("/messages")
						.and().method("GET")
						.uri("lb://" + messageServiceId))
				.build();
	}
}
