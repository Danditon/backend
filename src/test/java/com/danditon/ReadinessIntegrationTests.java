package com.danditon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.CookieManager;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.security.user.name=readiness-test",
	"spring.security.user.password=fixture-only"
})
@ActiveProfiles({"prod", "test"})
@Import(ReadinessIntegrationTests.Fixtures.class)
class ReadinessIntegrationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private RestartableDataSource dataSource;

	@Autowired
	private ConfigurableApplicationContext context;

	private final HttpClient client = HttpClient.newBuilder()
		.cookieHandler(new CookieManager())
		.connectTimeout(Duration.ofSeconds(5)).build();

	@Test
	void readinessTracksRealDatabaseConnectionsAndRecoversWithoutDetails() throws Exception {
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT 1")) {
			result.next();
			assertEquals(1, result.getInt(1));
		}
		assertHealth("UP", 200);
		dataSource.close();
		try {
			assertHealth("DOWN", 503);
		} finally {
			dataSource.start();
		}
		assertHealth("UP", 200);
	}

	@Test
	void readinessAlsoRequiresApplicationReadinessState() throws Exception {
		AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
		try {
			assertHealth("OUT_OF_SERVICE", 503);
		} finally {
			AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
		}
		assertHealth("UP", 200);
	}

	@Test
	void otherRoutesAndHealthMethodsRemainProtected() throws Exception {
		for (String path : new String[]{"/test/protected", "/actuator/health",
				"/actuator/health/liveness", "/actuator/health/readiness/db", "/actuator/env"}) {
			assertEquals(401, send(HttpRequest.newBuilder(uri(path)).GET()).statusCode());
		}
		assertEquals(401, send(HttpRequest.newBuilder(uri("/actuator/health/readiness"))
			.method("HEAD", HttpRequest.BodyPublishers.noBody())).statusCode());
		assertEquals(401, send(HttpRequest.newBuilder(uri("/actuator/health/readiness"))
			.POST(HttpRequest.BodyPublishers.noBody())).statusCode());
		String authorization = "Basic " + Base64.getEncoder()
			.encodeToString("readiness-test:fixture-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(200, send(HttpRequest.newBuilder(uri("/test/protected"))
			.header("Authorization", authorization).GET()).statusCode());
		assertEquals(404, send(HttpRequest.newBuilder(uri("/actuator/env"))
			.header("Authorization", authorization).GET()).statusCode());
		String token = loginToken();
		assertEquals(302, send(HttpRequest.newBuilder(uri("/login"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(
				"username=readiness-test&password=fixture-only&_csrf=" + token))).statusCode());
		assertEquals(200, send(HttpRequest.newBuilder(uri("/test/protected")).GET()).statusCode());
		assertEquals(403, send(HttpRequest.newBuilder(uri("/test/protected"))
			.POST(HttpRequest.BodyPublishers.noBody())).statusCode());
		assertEquals(200, send(HttpRequest.newBuilder(uri("/test/protected"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString("_csrf=" + loginToken()))).statusCode());
	}

	private String loginToken() throws Exception {
		var response = client.send(HttpRequest.newBuilder(uri("/login"))
			.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(response.body());
		org.junit.jupiter.api.Assertions.assertTrue(token.find());
		return URLEncoder.encode(token.group(1), java.nio.charset.StandardCharsets.UTF_8);
	}

	@Test
	void productionBindsToAllContainerInterfacesAndMySqlProfilesUpdateSchema() throws Exception {
		var production = new YamlPropertySourceLoader()
			.load("production", new ClassPathResource("application-prod.yaml")).get(0);
		var local = new YamlPropertySourceLoader()
			.load("local", new ClassPathResource("application-local.yaml")).get(0);
		assertEquals("0.0.0.0", production.getProperty("server.address"));
		assertEquals(8080, production.getProperty("server.port"));
		assertEquals("update", production.getProperty("spring.jpa.hibernate.ddl-auto"));
		assertEquals("update", local.getProperty("spring.jpa.hibernate.ddl-auto"));
		assertEquals("0.0.0.0", context.getEnvironment().getProperty("server.address"));
		assertEquals("update", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
		System.out.println("PRODUCTION_BIND address=0.0.0.0 port=8080 ddl-auto=update");
	}

	private void assertHealth(String status, int code) throws Exception {
		var response = send(HttpRequest.newBuilder(uri("/actuator/health/readiness")).GET());
		assertEquals(code, response.statusCode());
		assertEquals("{\"status\":\"" + status + "\"}", response.body());
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		var built = request.header("Accept", "application/json").timeout(Duration.ofSeconds(10)).build();
		var response = client.send(built, HttpResponse.BodyHandlers.ofString());
		var headers = new java.util.TreeMap<>(response.headers().map());
		headers.remove("set-cookie");
		System.out.println("HTTP_QA " + built.method() + " " + built.uri() + " status="
			+ response.statusCode() + " headers=" + headers + " body=" + response.body());
		return response;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Fixtures {
		@Bean(destroyMethod = "close")
		RestartableDataSource dataSource() {
			return new RestartableDataSource();
		}

		@Bean
		ProtectedController protectedController() {
			return new ProtectedController();
		}
	}

	static class RestartableDataSource extends DelegatingDataSource implements AutoCloseable {
		private HikariDataSource pool;

		RestartableDataSource() {
			start();
		}

		void start() {
			pool = new HikariDataSource();
			pool.setJdbcUrl("jdbc:h2:mem:readiness-test;DB_CLOSE_DELAY=-1");
			pool.setUsername("sa");
			pool.setPassword("");
			setTargetDataSource(pool);
		}

		@Override
		public void close() {
			pool.close();
		}
	}

	@RestController
	static class ProtectedController {
		@GetMapping("/test/protected")
		String get() {
			return "protected";
		}

		@PostMapping("/test/protected")
		String post() {
			return "protected";
		}
	}
}
