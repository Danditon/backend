package com.danditon.common.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;

class LocalFileStorageTests {

	private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
	private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'};

	@TempDir
	Path root;

	LocalFileStorage storage;

	@BeforeEach
	void setUp() {
		storage = new LocalFileStorage(root.toString(), "https://api.danditon.test/");
	}

	@Test
	void 정상_저장하면_상대경로를_돌려주고_파일이_UUID_이름으로_생긴다() throws IOException {
		var file = new MockMultipartFile("photo", "내 사진.JPG", "image/jpeg", JPEG);

		String path = storage.save(file, FileCategory.QUEST);

		assertTrue(Pattern.matches("/uploads/quest/[0-9a-f-]{36}\\.jpg", path), path);
		Path saved = root.resolve(path.substring("/uploads/".length()));
		assertArrayEquals(JPEG, Files.readAllBytes(saved));
	}

	@Test
	void 수행_사진은_submission_폴더에_저장된다() {
		String path = storage.save(new MockMultipartFile("photo", "a.png", "image/png", PNG), FileCategory.SUBMISSION);

		assertTrue(path.startsWith("/uploads/submission/"));
		assertTrue(path.endsWith(".png"));
	}

	@Test
	void webp와_jpeg_확장자를_허용하고_jpeg는_jpg로_통일한다() {
		String webp = storage.save(new MockMultipartFile("photo", "a.webp", "image/webp", WEBP), FileCategory.QUEST);
		String jpeg = storage.save(new MockMultipartFile("photo", "a.jpeg", "image/jpeg", JPEG), FileCategory.QUEST);

		assertTrue(webp.endsWith(".webp"));
		assertTrue(jpeg.endsWith(".jpg"));
	}

	@Test
	void 같은_파일을_두_번_저장해도_경로가_다르다() {
		var file = new MockMultipartFile("photo", "a.jpg", "image/jpeg", JPEG);

		assertFalse(storage.save(file, FileCategory.QUEST).equals(storage.save(file, FileCategory.QUEST)));
	}

	@Test
	void 원본_파일명에_경로가_섞여도_저장_위치에는_영향이_없다() {
		var file = new MockMultipartFile("photo", "../../etc/evil.jpg", "image/jpeg", JPEG);

		String path = storage.save(file, FileCategory.QUEST);

		assertTrue(Pattern.matches("/uploads/quest/[0-9a-f-]{36}\\.jpg", path), path);
	}

	@Test
	void 허용하지_않는_확장자는_INVALID_INPUT() {
		assertInvalid(new MockMultipartFile("photo", "a.gif", "image/gif", JPEG));
		assertInvalid(new MockMultipartFile("photo", "a.heic", "image/heic", JPEG));
		assertInvalid(new MockMultipartFile("photo", "a.jpg.exe", "image/jpeg", JPEG));
		assertInvalid(new MockMultipartFile("photo", "noext", "image/jpeg", JPEG));
		assertInvalid(new MockMultipartFile("photo", null, "image/jpeg", JPEG));
	}

	@Test
	void 허용하지_않는_content_type은_INVALID_INPUT() {
		assertInvalid(new MockMultipartFile("photo", "a.jpg", "text/html", JPEG));
		assertInvalid(new MockMultipartFile("photo", "a.jpg", "application/octet-stream", JPEG));
		assertInvalid(new MockMultipartFile("photo", "a.jpg", null, JPEG));
	}

	@Test
	void 확장자와_content_type이_서로_다르면_INVALID_INPUT() {
		assertInvalid(new MockMultipartFile("photo", "a.png", "image/jpeg", JPEG));
	}

	@Test
	void 내용이_이미지가_아니면_INVALID_INPUT() {
		assertInvalid(new MockMultipartFile("photo", "a.jpg", "image/jpeg", "<script>alert(1)</script>".getBytes()));
	}

	@Test
	void 빈_파일과_null은_INVALID_INPUT() {
		assertInvalid(new MockMultipartFile("photo", "a.jpg", "image/jpeg", new byte[0]));
		assertInvalid(null);
	}

	@Test
	void 용량은_10MB까지만_허용한다() {
		int limit = 10 * 1024 * 1024;
		byte[] ok = new byte[limit];
		System.arraycopy(JPEG, 0, ok, 0, JPEG.length);
		byte[] tooBig = new byte[limit + 1];
		System.arraycopy(JPEG, 0, tooBig, 0, JPEG.length);

		assertTrue(storage.save(new MockMultipartFile("photo", "a.jpg", "image/jpeg", ok), FileCategory.QUEST)
			.endsWith(".jpg"));
		assertInvalid(new MockMultipartFile("photo", "a.jpg", "image/jpeg", tooBig));
	}

	@Test
	void 검증에_실패하면_디스크에_아무것도_남지_않는다() throws IOException {
		assertInvalid(new MockMultipartFile("photo", "a.gif", "image/gif", JPEG));

		try (Stream<Path> files = Files.walk(root)) {
			assertEquals(0, files.filter(Files::isRegularFile).count());
		}
	}

	@Test
	void 삭제하면_파일이_사라진다() {
		String path = storage.save(new MockMultipartFile("photo", "a.jpg", "image/jpeg", JPEG), FileCategory.QUEST);
		Path saved = root.resolve(path.substring("/uploads/".length()));
		assertTrue(Files.exists(saved));

		storage.delete(path);

		assertFalse(Files.exists(saved));
	}

	@Test
	void 없는_파일_null_빈값_삭제는_예외없이_넘어간다() {
		storage.delete("/uploads/quest/not-exist.jpg");
		storage.delete(null);
		storage.delete("  ");
	}

	@Test
	void 삭제가_실패해도_예외를_던지지_않는다() throws IOException {
		// 비어 있지 않은 디렉터리는 deleteIfExists가 IOException을 던진다
		Path dir = root.resolve("quest/dir.jpg");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("child"), "x");

		storage.delete("/uploads/quest/dir.jpg");

		assertTrue(Files.exists(dir));
	}

	@Test
	void 경로_조작으로는_루트_밖_파일을_지울_수_없다() throws IOException {
		Path outside = Files.createFile(root.resolveSibling(root.getFileName() + "-outside.jpg"));
		Path inside = root.resolve("secret.jpg");
		Files.createFile(inside);
		try {
			storage.delete("/uploads/../" + outside.getFileName());
			storage.delete("/uploads/quest/../../" + outside.getFileName());
			storage.delete("/uploads/quest/../../../" + outside.getFileName());
			storage.delete("/uploads/quest/../secret.jpg"); // 루트 안이지만 quest/submission 밖
			storage.delete("/uploads/secret.jpg");
			storage.delete("/uploads/quest/%2e%2e/%2e%2e/secret.jpg");
			storage.delete("/etc/passwd");
			storage.delete(outside.toString());
			storage.delete(inside.toString());

			assertTrue(Files.exists(outside));
			assertTrue(Files.exists(inside));
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	void 전체_URL로는_삭제하지_않는다() {
		String path = storage.save(new MockMultipartFile("photo", "a.jpg", "image/jpeg", JPEG), FileCategory.QUEST);

		storage.delete(storage.toUrl(path));

		assertTrue(Files.exists(root.resolve(path.substring("/uploads/".length()))));
	}

	@Test
	void toUrl은_베이스_URL_끝_슬래시와_상관없이_전체_URL을_만든다() {
		assertEquals("https://api.danditon.test/uploads/quest/a.jpg", storage.toUrl("/uploads/quest/a.jpg"));
		assertEquals("https://api.danditon.test/uploads/quest/a.jpg",
			new LocalFileStorage(root.toString(), "https://api.danditon.test").toUrl("/uploads/quest/a.jpg"));
	}

	@Test
	void toUrl은_null이면_null() {
		assertNull(storage.toUrl(null));
		assertNull(storage.toUrl(" "));
	}

	private void assertInvalid(MockMultipartFile file) {
		BusinessException e = assertThrows(BusinessException.class, () -> storage.save(file, FileCategory.QUEST));
		assertEquals(ErrorCode.INVALID_INPUT, e.getErrorCode());
	}

}
