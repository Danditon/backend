package com.danditon.common.storage;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 사진 저장 하위 폴더. 경로는 /uploads/{directory}/{uuid}.{ext}
@Getter
@RequiredArgsConstructor
public enum FileCategory {

	QUEST("quest"),
	SUBMISSION("submission");

	private final String directory;

}
