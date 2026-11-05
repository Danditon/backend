package com.danditon.common.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

class UploadsResourceConfigurationTests {

	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
	private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3};
	private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'};
	private static final String SENTINEL = "PRIVATE-SENTINEL-DO-NOT-SERVE";

	@TempDir Path temporary;
	private Path root;
	private AnnotationConfigWebApplicationContext context;
	private MockMvc mvc;

	@BeforeEach
	void startBeforeStorageDirectoriesExist() {
		root = temporary.resolve("uploads space");
		context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
			"danditon.storage.root=" + temporary.resolve("unused/../uploads space").toString().replace('\\', '/'));
		context.register(MvcConfiguration.class, UploadsResourceConfiguration.class);
		context.refresh();
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@AfterEach
	void closeContext() {
		context.close();
	}

	@ParameterizedTest
	@CsvSource({"quest,png,image/png", "submission,png,image/png",
		"quest,jpg,image/jpeg", "submission,jpg,image/jpeg",
		"quest,webp,image/webp", "submission,webp,image/webp"})
	void servesExactImageBytesFromEachCategory(String category, String extension, String type) throws Exception {
		byte[] bytes = switch (extension) {
			case "png" -> PNG;
			case "jpg" -> JPEG;
			case "webp" -> WEBP;
			default -> throw new IllegalArgumentException(extension);
		};
		Files.createDirectories(root.resolve(category));
		Files.write(root.resolve(category).resolve("image." + extension), bytes);
		var response = mvc.perform(get("/uploads/" + category + "/image." + extension)).andReturn().getResponse();
		assertEquals(200, response.getStatus());
		assertEquals(type, response.getContentType());
		assertArrayEquals(bytes, response.getContentAsByteArray());
		System.out.println("FIXTURE category=" + category + " extension=" + extension + " sha256="
			+ HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
	}

	@Test
	void registersOnlyTheTwoCategoryPatterns() {
		var mapping = context.getBean("resourceHandlerMapping", SimpleUrlHandlerMapping.class);
		assertEquals(Set.of("/uploads/quest/**", "/uploads/submission/**"), mapping.getHandlerMap().keySet());
	}

	@Test
	void nonexistentRootDoesNotPreventStartupOrCreateDirectories() throws Exception {
		assertTrue(context.isActive());
		assertFalse(Files.exists(root));
		assertRejected("/uploads/quest/missing.png");
		assertRejected("/uploads/submission/missing.png");
	}

	@ParameterizedTest
	@ValueSource(strings = {"/uploads/quest/submission-only.png", "/uploads/submission/quest-only.png",
		"/uploads/root-secret.png", "/uploads/unknown/unknown-secret.png",
		"/uploads/quest/missing.png", "/uploads/submission/missing.png",
		"/uploads/quest/", "/uploads/submission/", "/uploads/quest/nested/"})
	void inaccessibleFilesAndDirectoriesReturn404WithoutListing(String path) throws Exception {
		createSentinels();
		assertRejected(path);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/uploads/quest/../../outside.png", "/uploads/quest/../root-secret.png",
		"/uploads/quest/../submission/submission-only.png",
		"/uploads/submission/../quest/quest-only.png",
		"/uploads/quest/%2e%2e/%2e%2e/outside.png",
		"/uploads/quest/%2e%2e/root-secret.png",
		"/uploads/quest/%2e%2e/submission/submission-only.png",
		"/uploads/quest/..%2f..%2foutside.png",
		"/uploads/quest/%252e%252e/%252e%252e/outside.png",
		"/uploads/quest/..%5c..%5coutside.png"})
	void traversalCannotReadSentinels(String path) throws Exception {
		createSentinels();
		assertRejected(path);
	}

	private void createSentinels() throws Exception {
		Files.createDirectories(root.resolve("quest/nested"));
		Files.createDirectories(root.resolve("submission"));
		Files.createDirectories(root.resolve("unknown"));
		for (String path : new String[]{"quest/quest-only.png", "submission/submission-only.png",
			"root-secret.png", "unknown/unknown-secret.png", "quest/nested/listing-secret.png"}) {
			Files.writeString(root.resolve(path), SENTINEL);
		}
		Files.writeString(temporary.resolve("outside.png"), SENTINEL);
	}

	private void assertRejected(String path) throws Exception {
		var response = mvc.perform(get(URI.create(path))).andReturn().getResponse();
		assertEquals(404, response.getStatus(), path);
		assertFalse(response.getContentAsString().contains(SENTINEL), path);
		assertFalse(response.getContentAsString().contains("listing-secret.png"), path);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	static class MvcConfiguration {
	}
}
