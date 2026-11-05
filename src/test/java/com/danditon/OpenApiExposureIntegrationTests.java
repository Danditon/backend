package com.danditon;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OpenApiExposureIntegrationTests.Fixtures.class)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiExposureIntegrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String UNAUTHORIZED = "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요해요\"}";
	private static final Path EVIDENCE = Path.of(System.getenv().getOrDefault("OPENAPI_EVIDENCE_DIR",
		".omo/evidence/auth-signup-login/task-5"));
	private static final String[] DOC_PATHS = {"/v3/api-docs", "/v3/api-docs.yaml",
		"/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html",
		"/swagger-ui/swagger-ui.css", "/swagger-ui/swagger-ui-bundle.js"};
	@LocalServerPort private int port;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@BeforeAll
	void initializeEvidence() throws Exception {
		Files.createDirectories(EVIDENCE);
		Files.writeString(EVIDENCE.resolve("http-matrix.tsv"),
			"method\tpath\tmalformedBearer\tstatus\tbytes\tcontentType\tsetCookie\n");
	}

	@Test
	void generatedJsonHasExactBearerContractAndOnlyApiPaths() throws Exception {
		var response = send("GET", "/v3/api-docs", false);
		assertDocument(response, "application/json");
		var spec = JSON.readTree(response.body());
		assertEquals("Danditon Backend API", spec.at("/info/title").asText());
		assertEquals("v1", spec.at("/info/version").asText());
		assertTrue(spec.at("/info/description").asText().contains("24 hours"));
		assertTrue(spec.at("/info/description").asText().contains("Refresh tokens are not supported"));
		assertEquals(JSON.readTree("{\"bearerAuth\":{\"type\":\"http\",\"scheme\":\"bearer\",\"bearerFormat\":\"JWT\"}}"),
			spec.at("/components/securitySchemes"));
		assertEquals(JSON.readTree("[{\"bearerAuth\":[]}]"), spec.get("security"));
		assertTrue(spec.get("paths").has("/api/openapi-probe"));
		assertFalse(spec.get("paths").has("/outside-openapi-probe"));
		for (String path : spec.get("paths").propertyNames()) assertTrue(path.startsWith("/api/"));
		assertFalse(spec.at("/paths/~1api~1openapi-probe/get/responses").has("500"));
		Files.writeString(EVIDENCE.resolve("generated-openapi.json"), response.body());
	}

	@Test
	void yamlAndSwaggerConfigLoadAnonymously() throws Exception {
		var yaml = send("GET", "/v3/api-docs.yaml", false);
		assertDocument(yaml, "application/vnd.oai.openapi");
		assertTrue(yaml.body().contains("Danditon Backend API"));
		assertTrue(yaml.body().contains("bearerAuth:"));
		Files.writeString(EVIDENCE.resolve("generated-openapi.yaml"), yaml.body());
		var config = send("GET", "/v3/api-docs/swagger-config", false);
		assertDocument(config, "application/json");
		assertEquals("/v3/api-docs", JSON.readTree(config.body()).get("url").asText());
		assertFalse(config.body().contains("petstore"));
		Files.writeString(EVIDENCE.resolve("swagger-config.json"), config.body());
	}

	@Test
	void swaggerRedirectHtmlAndReferencedCssAndScriptsLoadAnonymously() throws Exception {
		var redirect = send("GET", "/swagger-ui.html", false);
		assertEquals(302, redirect.statusCode());
		String location = redirect.headers().firstValue("location").orElseThrow();
		assertEquals("/swagger-ui/index.html", location);
		var html = send("GET", location, false);
		assertDocument(html, "text/html");
		assertTrue(html.body().contains("Swagger UI"));
		Files.writeString(EVIDENCE.resolve("swagger-ui.html"), html.body());
		var assets = Pattern.compile("(?:src|href)=\"([^\"]+\\.(?:css|js))\"").matcher(html.body());
		int cssCount = 0;
		int jsCount = 0;
		while (assets.find()) {
			String asset = URI.create(location).resolve(assets.group(1)).getPath();
			assertTrue(asset.startsWith("/swagger-ui/"));
			var response = send("GET", asset, false);
			assertEquals(200, response.statusCode(), asset);
			assertFalse(response.body().isBlank(), asset);
			assertFalse(response.body().startsWith("<!DOCTYPE"), asset);
			if (asset.endsWith(".css")) {
				cssCount++;
				assertTrue(response.headers().firstValue("content-type").orElseThrow().contains("text/css"));
			} else {
				jsCount++;
				assertTrue(response.headers().firstValue("content-type").orElseThrow().contains("javascript"));
			}
			Files.writeString(EVIDENCE.resolve(Path.of(asset).getFileName()), response.body());
		}
		assertTrue(cssCount >= 1);
		assertTrue(jsCount >= 3);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/users/me", "/api/quests", "/api/openapi-probe", "/outside-openapi-probe",
		"/v3/api-docs-extra", "/v3/api-docs/private", "/v3/api-docs/", "/v3/api-docs.yaml-extra",
		"/v3/api-docs/swagger-config/private", "/swagger-ui.html-extra", "/swagger-ui-private/index.html",
		"/webjars/private"})
	void businessAndLookalikeRoutesStayProtected(String path) throws Exception {
		assertUnauthorized(send("GET", path, false), false);
	}

	@ParameterizedTest
	@ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"})
	void nonGetDocumentationRoutesStayProtected(String method) throws Exception {
		for (String path : DOC_PATHS) assertUnauthorized(send(method, path, false), method.equals("HEAD"));
	}

	@Test
	void malformedBearerCannotBypassAuthenticationOnPublicDocumentation() throws Exception {
		for (String path : DOC_PATHS) assertUnauthorized(send("GET", path, true), false);
	}

	private void assertDocument(HttpResponse<String> response, String contentType) {
		assertEquals(200, response.statusCode());
		assertFalse(response.body().isBlank());
		assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith(contentType));
	}

	private void assertUnauthorized(HttpResponse<String> response, boolean head) {
		assertEquals(401, response.statusCode());
		assertEquals(head ? "" : UNAUTHORIZED, response.body());
		assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
	}

	private HttpResponse<String> send(String method, String path, boolean malformedBearer) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
		if (malformedBearer) request.header("Authorization", "Bearer malformed");
		var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		Files.writeString(EVIDENCE.resolve("http-matrix.tsv"), method + "\t" + path + "\t" + malformedBearer
			+ "\t" + response.statusCode() + "\t" + response.body().length() + "\t"
			+ response.headers().firstValue("content-type").orElse("") + "\t"
			+ response.headers().firstValue("set-cookie").isPresent() + "\n", StandardOpenOption.APPEND);
		assertTrue(response.headers().firstValue("set-cookie").isEmpty());
		return response;
	}

	@AfterAll
	void recordPortForTeardownVerification() throws Exception {
		Files.writeString(EVIDENCE.resolve("server-port.txt"), Integer.toString(port));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Fixtures {
		@Bean ProbeController openApiProbeController() { return new ProbeController(); }
	}

	@RestController
	static class ProbeController {
		@GetMapping("/api/openapi-probe") String apiProbe() { return "probe"; }
		@GetMapping("/outside-openapi-probe") String outsideProbe() { return "probe"; }
	}
}
