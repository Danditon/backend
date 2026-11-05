package com.danditon.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.danditon.common.exception.BusinessException;
import com.danditon.common.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

// 서버 디스크 저장 구현. 설정은 환경 변수로 덮어쓴다 (application.yaml 수정 불필요)
//  - DANDITON_STORAGE_ROOT      디스크 루트 (기본 /app/uploads, 운영은 Docker 볼륨)
//  - DANDITON_STORAGE_BASE_URL  응답 photoUrl의 서버 주소 (기본 http://localhost:8080)
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

	static final String URL_PREFIX = "/uploads/";
	static final long MAX_SIZE = 10L * 1024 * 1024;

	// 확장자 → 허용 content-type. jpeg는 jpg로 통일
	private static final Map<String, String> EXTENSION_TO_CONTENT_TYPE = Map.of(
		"jpg", "image/jpeg",
		"jpeg", "image/jpeg",
		"png", "image/png",
		"webp", "image/webp");
	private static final Pattern DELETABLE_PATH = Pattern.compile("^/uploads/(quest|submission)/[A-Za-z0-9._-]+$");

	private final Path root;
	private final String baseUrl;

	public LocalFileStorage(
		@Value("${danditon.storage.root:/app/uploads}") String root,
		@Value("${danditon.storage.base-url:http://localhost:8080}") String baseUrl) {
		this.root = Path.of(root).toAbsolutePath().normalize();
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}

	@Override
	public String save(MultipartFile file, FileCategory category) {
		String extension = validate(file);
		String fileName = UUID.randomUUID() + "." + ("jpeg".equals(extension) ? "jpg" : extension);
		Path directory = root.resolve(category.getDirectory());
		Path target = directory.resolve(fileName);

		try {
			Files.createDirectories(directory);
			try (InputStream in = file.getInputStream()) {
				Files.copy(in, target);
			}
		} catch (IOException e) {
			log.error("사진 저장 실패: {}", target, e);
			deleteQuietly(target);
			throw new BusinessException(ErrorCode.INTERNAL_ERROR);
		}
		return URL_PREFIX + category.getDirectory() + "/" + fileName;
	}

	@Override
	public void delete(String path) {
		if (!StringUtils.hasText(path)) {
			return;
		}
		// 우리가 만든 형태(/uploads/{quest|submission}/{파일명})만 허용. 그 외·경로 조작은 무시
		if (!DELETABLE_PATH.matcher(path).matches() || path.contains("..")) {
			log.warn("허용되지 않는 사진 삭제 경로를 무시함: {}", path);
			return;
		}
		Path target = root.resolve(path.substring(URL_PREFIX.length())).normalize();
		if (!target.startsWith(root)) {
			log.warn("루트 밖 사진 삭제 경로를 무시함: {}", path);
			return;
		}
		deleteQuietly(target);
	}

	@Override
	public String toUrl(String path) {
		if (!StringUtils.hasText(path)) {
			return null;
		}
		return baseUrl + path;
	}

	// 통과하면 정규화된(소문자) 확장자를 반환, 위반이면 INVALID_INPUT
	private String validate(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw invalid("사진 파일이 필요해요");
		}
		if (file.getSize() > MAX_SIZE) {
			throw invalid("사진은 10MB 이하만 올릴 수 있어요");
		}
		String extension = extensionOf(file.getOriginalFilename());
		String expectedType = EXTENSION_TO_CONTENT_TYPE.get(extension);
		if (expectedType == null) {
			throw invalid("jpg, png, webp 사진만 올릴 수 있어요");
		}
		String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
		if (!expectedType.equals(contentType)) {
			throw invalid("사진 형식이 올바르지 않아요");
		}
		if (!hasImageSignature(file, expectedType)) {
			throw invalid("사진 파일이 올바르지 않아요");
		}
		return extension;
	}

	private String extensionOf(String originalFilename) {
		if (originalFilename == null) {
			return "";
		}
		// 브라우저에 따라 경로가 섞여 올 수 있어 마지막 이름만 본다. 확장자 외에는 저장에 쓰지 않는다
		String name = originalFilename.substring(Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\')) + 1);
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	// content-type은 클라이언트가 속일 수 있어 파일 앞부분(매직 바이트)도 확인한다
	private boolean hasImageSignature(MultipartFile file, String contentType) {
		byte[] head;
		try (InputStream in = file.getInputStream()) {
			head = in.readNBytes(12);
		} catch (IOException e) {
			throw invalid("사진 파일을 읽을 수 없어요");
		}
		return switch (contentType) {
			case "image/jpeg" -> head.length >= 3
				&& head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF;
			case "image/png" -> head.length >= 8 && Arrays.equals(Arrays.copyOf(head, 8),
				new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
			case "image/webp" -> head.length >= 12
				&& new String(head, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
				&& new String(head, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
			default -> false;
		};
	}

	private void deleteQuietly(Path target) {
		try {
			Files.deleteIfExists(target);
		} catch (IOException | RuntimeException e) {
			log.warn("사진 삭제 실패(무시): {}", target, e);
		}
	}

	private BusinessException invalid(String message) {
		return new BusinessException(ErrorCode.INVALID_INPUT, message);
	}

}
