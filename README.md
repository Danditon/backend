# 그리움 퀘스트 — Backend

> 떠난 사람이 그리워하는 대구·경북의 장소를, 남아 있는 사람과 여행자가 퀘스트로 대신 다녀와 안부를 전하는 서비스 (가칭)

2026 단디톤(멋쟁이사자처럼 14기 대구·경북 연합 해커톤) 출품작의 백엔드 API입니다.

## API (설계서 v0.1 기준)

| 기능 | API | 담당 |
|---|---|---|
| 회원·로그인 | `POST /auth/signup`, `POST /auth/login`, `GET /users/me` | 박성준 |
| AI 미리보기 | `POST /quests/preview` | 김영재 (AI 연동: 강영한) |
| 그리움(퀘스트) 등록 | `POST /quests` | 김영재 |
| 퀘스트 조회 | `GET /quests?lat=&lng=&radius=`, `GET /quests/{id}` | 김영재 |
| 수행 + AI 검증 | `POST /quests/{id}/submissions` | 김영재 (AI 연동: 강영한) |
| 안부 전달 | `GET /users/me/quests` | 김영재 |
| 퀘스트 기록 | `GET /users/me/submissions` | 김영재 |
| 감사 메시지 | `POST /submissions/{id}/thanks` | 박성준 |

이미지 업로드와 저장소는 박성준, 배포는 김영재가 맡습니다.

## 기술 스택

| 구분 | 기술·버전 |
|---|---|
| 언어 | Java 17 |
| 빌드 | Gradle Wrapper 9.8.2 |
| 프레임워크 | Spring Boot 4.1.2, Spring Web MVC |
| 의존성 관리 | Spring Dependency Management 플러그인 1.1.7 |
| 데이터·보안·검증 | Spring Data JPA, Spring Security, Spring Validation |
| 개발 도구 | Lombok |
| 데이터베이스 드라이버 | MySQL Connector/J (local/prod), H2 (테스트 전용) |
| 테스트 | JUnit 5, Spring Boot 테스트 스타터 |

개별 라이브러리 버전은 Spring Boot 의존성 관리에 따릅니다. 배포 환경은 아직 정해지지 않았습니다.

## 실행 방법

Gradle Wrapper로 `bootJar`를 실행해 애플리케이션 JAR을 빌드할 수 있습니다 (`./gradlew bootJar`, Windows: `.\gradlew.bat bootJar`). 테스트는 `test` 프로필의 인메모리 H2를 사용하므로 외부 DB 없이 `./gradlew test`로 실행할 수 있습니다.

애플리케이션을 실행할 때는 `SPRING_PROFILES_ACTIVE`를 `local` 또는 `prod`로 설정하고 해당 프로필의 DB 접속값을 환경 변수로 전달합니다. 필요한 변수 이름은 [`.env.example`](.env.example)을 참고하세요. `.env.example`은 예시 파일이며 Spring Boot가 자동으로 읽지는 않습니다. multipart 파일·요청 제한은 기본 10MB이고, 환경 변수로 변경할 수 있습니다.

## 협업

브랜치와 커밋 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.
