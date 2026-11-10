package com.danditon;

import java.util.List;

import com.danditon.security.SecurityErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler errors,
			UrlBasedCorsConfigurationSource corsConfigurationSource) throws Exception {
		var authentication = new JwtAuthenticationConverter();
		authentication.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
		var paths = PathPatternRequestMatcher.withDefaults();
		var publicAuthPosts = new OrRequestMatcher(paths.matcher(HttpMethod.POST, "/api/auth/signup"),
			paths.matcher(HttpMethod.POST, "/api/auth/login"));
		var bearerTokens = new DefaultBearerTokenResolver();
		http.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.cors(cors -> cors.configurationSource(corsConfigurationSource))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
			.oauth2ResourceServer(resource -> resource
				.bearerTokenResolver(request -> publicAuthPosts.matches(request) ? null : bearerTokens.resolve(request))
				.authenticationEntryPoint(errors).accessDeniedHandler(errors)
				.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication)));
		http.authorizeHttpRequests(requests -> requests
			.requestMatchers(publicAuthPosts).permitAll()
			.requestMatchers(HttpMethod.GET, "/actuator/health/readiness").permitAll()
			.requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
			.requestMatchers(HttpMethod.HEAD, "/uploads/**").permitAll()
			.anyRequest().authenticated());
		return http.build();
	}

	@Bean
	UrlBasedCorsConfigurationSource corsConfigurationSource() {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(List.of("https://marshrnello.cloud","https://www.marshrnello.cloud"));
		cors.setAllowCredentials(false);
		cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}
}
