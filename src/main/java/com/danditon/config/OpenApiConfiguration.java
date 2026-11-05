package com.danditon.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

	@Bean
	OpenAPI openAPI() {
		return new OpenAPI()
			.info(new Info().title("Danditon Backend API").version("v1")
				.description("Access tokens are valid for 24 hours. Refresh tokens are not supported."))
			.components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
				.type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
			.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
	}
}
