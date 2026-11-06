# 그리움 퀘스트 — Backend

> 떠난 사람이 그리워하는 대구·경북의 장소를, 남아 있는 사람과 여행자가 퀘스트로 대신 다녀와 안부를 전하는 서비스 (가칭)

2026 단디톤(멋쟁이사자처럼 14기 대구·경북 연합 해커톤) 출품작의 백엔드 API입니다.

## API (설계 계약 v0.3)

아래는 API 명세 v0.3의 전체 목록입니다. 회원가입·로그인, 퀘스트·수행 API와 내 정보(#3), 좋아요 토글(#10), 내가 등록한 퀘스트(#11), 내 수행 기록(#12)이 구현되어 있습니다. 공개 API의 Base URL은 `/api`이고, 표의 경로는 이 기준의 상대 경로입니다.

| 기능 | API |
|---|---|
| 회원가입 | `POST /auth/signup` |
| 로그인 | `POST /auth/login` |
| 내 정보 | `GET /users/me` |
| AI 정형화·난이도 미리보기 (저장하지 않음) | `POST /quests/preview` |
| 그리움(퀘스트) 등록 | `POST /quests` |
| 주변 퀘스트 목록 | `GET /quests` |
| 퀘스트 상세 | `GET /quests/{questId}` |
| 수행 + AI 검증 | `POST /quests/{questId}/submissions` |
| 수행 글 목록 (권한별) | `GET /quests/{questId}/submissions` |
| 좋아요 토글 (인증 사용자) | `PATCH /submissions/{submissionId}/like` |
| 내가 등록한 퀘스트 | `GET /users/me/quests` |
| 내 수행 기록 (PASSED만) | `GET /users/me/submissions` |

### 공통 설계 계약

아래는 공통 정책입니다. 인증 API와 내 정보·목록·좋아요의 구체적인 계약은 각 구현 섹션을 따릅니다.

- 회원가입·로그인을 제외한 모든 공개 API는 JWT `Authorization: Bearer {accessToken}`이 필요합니다. Access Token 만료는 24시간이며 Refresh Token은 없으므로 만료 시 재로그인합니다.
- 응답에 공통 래퍼는 없고 성공은 데이터만, 에러는 `{code, message}`를 반환합니다. JSON 필드는 camelCase, 시간은 ISO-8601이며 MVP에서 페이징은 적용하지 않습니다.
- 등록·수행은 `multipart/form-data`의 `request`(JSON)와 `photo`(파일) 파트를 사용합니다. 사진은 최대 1장·10MB·jpg/png/webp이며 HEIC는 FE에서 변환하거나 차단합니다. 사진은 서버 디스크에 저장하고 응답의 `photoUrl`은 전체 URL입니다.
- `GENERAL`은 등록·수행 모두 글 필수·사진 선택이고, 수행 시 서버가 위치 200m 이내를 확인하면 성공합니다 (`similarity: null`). `IMAGE`는 등록·수행 모두 글·사진 필수이며, 위치 확인 후 기준 사진과 수행 사진의 임베딩 유사도로 검증합니다. 유사도 미달은 `201`과 `verifyStatus: "FAILED"`로 저장되며 재시도할 수 있습니다.
- 주변 목록은 `lat`·`lng`가 필수이고 `radius`는 기본 50000m이며 `type`·`difficulty`로 필터링합니다. 새 퀘스트는 `OPEN`으로 등록되고 주변 목록은 OPEN 퀘스트만 조회합니다. 내 퀘스트도 포함해 `isMine`으로 구분합니다.
- 수행 글은 등록자(`AUTHOR`)와 PASSED 완료자(`COMPLETED`)에게 모든 수행자의 PASSED 글을, 그 외(`PERFORMER`)에게는 FAILED를 포함한 본인 결과만 반환합니다.

| HTTP·code | 의미 |
|---|---|
| `400 INVALID_INPUT` | 종류별 필수값 누락, 사진 용량·확장자 위반 |
| `401 UNAUTHORIZED` | 토큰 없음·만료 |
| `403 OUT_OF_RANGE` | 서버 위치 재검증에서 200m 밖이면 수행을 저장하지 않음 |
| `403 SELF_QUEST` | 본인이 등록한 퀘스트 수행 금지 |
| `404 NOT_FOUND` | 퀘스트·수행 없음 |
| `409 DUPLICATE_EMAIL` | 이미 가입된 이메일 |
| `409 ALREADY_PASSED` | 이미 성공한 퀘스트 재수행 금지 (FAILED 후 재시도 허용) |
| `502 AI_ERROR` | LLM·임베딩 호출 실패: 등록 전체 롤백 (저장한 사진 파일 삭제 포함), 수행 미저장. FAILED로 기록하지 않고 재시도 안내 |

## 인증 API (구현됨)

두 API는 `Content-Type: application/json`으로 요청하며, 인증 헤더 없이 호출할 수 있습니다. 다만 잘못된 Bearer 토큰이나 형식이 잘못된 Bearer `Authorization` 헤더를 함께 보내면 공개 인증 API에서도 `401 UNAUTHORIZED`로 거부됩니다.

### 회원가입

`POST /api/auth/signup` 요청:

```json
{
  "email": "member@example.com",
  "password": "<password-at-least-8-chars>",
  "nickname": "단디"
}
```

성공 시 `201 Created`와 다음 데이터를 반환하며 바로 로그인 상태로 사용할 수 있습니다.

```json
{
  "accessToken": "<access-token>",
  "userId": 1,
  "nickname": "단디"
}
```

### 로그인

`POST /api/auth/login` 요청:

```json
{
  "email": "member@example.com",
  "password": "<password>"
}
```

성공 시 `200 OK`와 회원가입과 동일한 `{accessToken, userId, nickname}` 구조를 반환합니다. 위 비밀번호·토큰 예시는 자리표시자입니다.

### 입력값과 오류

| 필드 | 적용 규칙 |
|---|---|
| `email` (공통) | 필수 이메일 형식. 앞뒤 공백을 제거하고 소문자로 변환한 뒤 최대 255자. 정규화된 이메일로 중복 확인·로그인 |
| `password` (회원가입) | 필수, 최소 8자, UTF-8 인코딩 기준 최대 72바이트 |
| `password` (로그인) | 필수, UTF-8 인코딩 기준 최대 72바이트. 회원가입의 최소 8자 규칙은 적용하지 않음 |
| `nickname` (회원가입) | 필수, 앞뒤 공백을 제거한 뒤 1–50자 |

비밀번호는 앞뒤 공백 제거(trim) 등 정규화를 하지 않습니다. 공백만 있는 비밀번호는 허용하지 않으며, 72바이트는 글자 수 제한과 다르므로 한글 등 다중 바이트 문자를 입력할 때 주의하세요.

오류 응답은 공통 래퍼 없이 `{code, message}`입니다.

| 상황 | HTTP·code | message |
|---|---|---|
| 필수값 누락, 형식·길이 위반, 잘못된 JSON | `400 INVALID_INPUT` | 입력값이 올바르지 않아요 |
| 가입된 이메일로 회원가입 | `409 DUPLICATE_EMAIL` | 이미 가입된 이메일이에요 |
| 로그인 비밀번호 불일치 또는 미가입 이메일 | `401 UNAUTHORIZED` | 로그인이 필요해요 |
| 잘못된 Bearer 토큰·Bearer 헤더 | `401 UNAUTHORIZED` | 로그인이 필요해요 |
| 지원하지 않는 요청 Content-Type | `415 INVALID_INPUT` | 입력값이 올바르지 않아요 |
| 예상하지 못한 서버 오류 | `500 INTERNAL_ERROR` | 서버 오류가 발생했어요 |

비밀번호 불일치와 미가입 이메일은 같은 상태 코드와 본문을 반환합니다. 액세스 토큰은 HS256으로 서명한 JWT이며 유효 기간은 24시간입니다. Refresh Token은 제공하지 않으므로 만료되면 다시 로그인합니다. 인증이 필요한 API에는 `Authorization: Bearer <accessToken>` 헤더로 전달합니다.

## 내 정보·목록·좋아요 API (구현됨)

네 API 모두 JWT가 필요하며 성공 시 `200 OK`를 반환합니다. 토큰이 없거나 유효하지 않으면 `401 UNAUTHORIZED`입니다. 사용자 식별자는 토큰에서 읽으며 요청 쿼리의 userId로 다른 사용자를 선택할 수 없습니다.

| 번호·API | 성공 응답 |
|---|---|
| #3 `GET /api/users/me` | `{userId, email, nickname, createdAt}`. 비밀번호·토큰은 포함하지 않음 |
| #10 `PATCH /api/submissions/{submissionId}/like` | `{submissionId, isLiked}`. 요청 본문 없이 호출마다 false ↔ true 토글 |
| #11 `GET /api/users/me/quests` | `[{questId, questTitle, questType, status, passedCount, createdAt}]` |
| #12 `GET /api/users/me/submissions` | `[{submissionId, questId, questTitle, placeName, lat, lng, photoUrl, createdAt}]` |

- #10은 인증된 모든 사용자가 호출할 수 있습니다. 등록자에게만 좋아요 버튼을 표시하는 제한은 프론트엔드 정책이며, 백엔드는 비등록자라는 이유로 `403 FORBIDDEN`을 반환하지 않습니다. 수행이 없으면 `404 NOT_FOUND`, 숫자가 아니거나 Long 범위를 넘는 ID는 `400 INVALID_INPUT`입니다.
- #11은 본인이 등록한 모든 상태(OPEN·CLOSED)의 퀘스트를 반환합니다. `passedCount`는 PASSED 수행만 집계하고 없으면 0입니다.
- #12는 본인의 PASSED 수행만 반환하며 FAILED는 제외합니다. 퀘스트 상태는 제한하지 않습니다. 퀘스트 ID·제목·장소·좌표는 퀘스트에서, 사진·생성 시간은 수행에서 가져옵니다. 사진은 전체 URL이고 사진이 없으면 null입니다.
- 두 목록은 `createdAt DESC, id DESC` 순서이며 결과가 없으면 `[]`를 반환합니다. 페이징은 적용하지 않습니다.

## Swagger / OpenAPI (로컬)

애플리케이션을 기본 포트 8080으로 실행한 뒤 다음 주소를 사용합니다.

- [Swagger UI](http://localhost:8080/swagger-ui.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)
- [OpenAPI YAML](http://localhost:8080/v3/api-docs.yaml)

생성된 문서는 구현된 API 계약을 제공합니다. 내 정보·좋아요·내 퀘스트·내 수행 기록도 포함하며, 인증이 필요한 경로에 bearerAuth를 적용합니다. 문서와 UI 리소스에 대한 GET 허용은 다른 비즈니스 API의 인증을 해제하지 않습니다.

Swagger UI에서 회원가입 또는 로그인 응답의 `accessToken`을 복사해 **Authorize**에 토큰 문자열만 입력하세요. `Bearer ` 접두사는 UI가 붙입니다. 회원가입·로그인 자체에는 인증이 필요 없으며, 잘못된 Bearer 헤더를 직접 보내면 공개 경로라도 거부됩니다.

문서 경로 확인용 명령(Windows에서는 `curl` 대신 `curl.exe` 사용 가능):

```bash
curl -i http://localhost:8080/swagger-ui.html
curl -i http://localhost:8080/v3/api-docs
curl -i http://localhost:8080/v3/api-docs.yaml
```

Swagger UI 진입 주소는 `/swagger-ui/index.html`로 리다이렉트됩니다. 위 주소는 로컬 실행용이며 배포 상태를 나타내지 않습니다.

## 기술 스택

| 구분 | 기술·버전 |
|---|---|
| 언어 | Java 17 |
| 빌드 | Gradle Wrapper 9.8.2 |
| 프레임워크 | Spring Boot 4.1.2, Spring Web MVC |
| 의존성 관리 | Spring Dependency Management 플러그인 1.1.7 |
| 데이터·보안·검증 | Spring Data JPA, Spring Security, Spring Validation |
| API 문서 | springdoc-openapi-starter-webmvc-ui 3.2.0 (Swagger UI / OpenAPI) |
| 개발 도구 | Lombok |
| 데이터베이스 드라이버 | MySQL Connector/J (local/prod), H2 (테스트 전용) |
| 테스트 | JUnit 5, Spring Boot 테스트 스타터 |

개별 라이브러리 버전은 Spring Boot 의존성 관리에 따릅니다. 배포 대상은 서울 리전의 Ubuntu 24.04 EC2 한 대이며, Docker Compose로 애플리케이션과 MySQL 8.4를 실행합니다.

## 실행 방법

Gradle Wrapper로 `bootJar`를 실행해 애플리케이션 JAR을 빌드할 수 있습니다 (`./gradlew bootJar`, Windows: `.\gradlew.bat bootJar`). 테스트는 `test` 프로필의 인메모리 H2를 사용하므로 외부 DB 없이 `./gradlew test`로 실행할 수 있습니다.

애플리케이션을 실행할 때는 `SPRING_PROFILES_ACTIVE`를 `local` 또는 `prod`로 설정하고 해당 프로필의 DB 접속값을 환경 변수로 전달합니다. 두 MySQL 프로필은 `ddl-auto: update`로 시작 시 스키마를 생성·갱신합니다. 필요한 변수 이름은 [`.env.example`](.env.example)을 참고하세요. `.env.example`은 예시 파일이며 Spring Boot가 자동으로 읽지는 않습니다. multipart 파일 제한은 기본 10MB, 전체 요청 제한은 파일 외 multipart 필드의 여유를 포함해 12MB이며 환경 변수로 변경할 수 있습니다.

## 배포

`develop` 대상 PR은 `Backend CI`에서 Java 17 빌드를 검증합니다. 팀원 승인 후 병합된 `develop` 커밋은 전체 SHA 태그의 GHCR 이미지로 게시되며, `production` 환경의 전용 SSH 키로 EC2에 전달됩니다. 공개 GHCR 패키지, 콘솔에서 검증한 호스트키, 호스트 초기 설정을 먼저 준비해야 합니다.

설정·상태 확인·장애 복구·암호화 백업·비용 종료 절차는 [배포 운영 문서](docs/deployment.md), AWS 콘솔 입력값은 [EC2 생성 명세](infra/aws/ec2-console.md)를 따릅니다. 초기 HTTP 주소는 민감정보 전송에 사용하지 않으며, 운영 접속은 검증된 SSH 터널을 사용합니다. 배포 중 재시작에 따른 중단이 있고, 외부 백업 회수는 운영자 작업입니다. 문서와 설정 파일의 존재만으로 실제 배포 완료를 뜻하지 않습니다.

## 협업

브랜치와 커밋 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.
