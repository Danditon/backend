package com.danditon.common.storage;

import org.springframework.web.multipart.MultipartFile;

// 사진 저장소. MVP는 서버 디스크(LocalFileStorage), 확장 시 S3 구현으로 교체한다.
// DB(photo_url)에는 save()가 돌려준 상대 경로(/uploads/quest/{uuid}.jpg)를 저장하고,
// 응답에는 toUrl()로 만든 전체 URL을 내려준다.
public interface FileStorage {

	// 검증 후 저장하고 상대 경로를 반환한다. 위반 시 400 INVALID_INPUT, 디스크 오류 시 500
	String save(MultipartFile file, FileCategory category);

	// 저장한 파일 삭제. 실패해도 예외를 던지지 않고 로그만 남긴다 (롤백 보상용). null·빈 값은 무시
	void delete(String path);

	// 상대 경로 → 전체 URL. null이면 null (사진 없는 GENERAL 퀘스트)
	String toUrl(String path);

}
