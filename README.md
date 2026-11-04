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

개별 라이브러리 버전은 Spring Boot 의존성 관리에 따릅니다. 배포 대상은 서울 리전의 Ubuntu 24.04 EC2 한 대이며, Docker Compose로 애플리케이션과 MySQL 8.4를 실행합니다.

## 실행 방법

Gradle Wrapper로 `bootJar`를 실행해 애플리케이션 JAR을 빌드할 수 있습니다 (`./gradlew bootJar`, Windows: `.\gradlew.bat bootJar`). 테스트는 `test` 프로필의 인메모리 H2를 사용하므로 외부 DB 없이 `./gradlew test`로 실행할 수 있습니다.

애플리케이션을 실행할 때는 `SPRING_PROFILES_ACTIVE`를 `local` 또는 `prod`로 설정하고 해당 프로필의 DB 접속값을 환경 변수로 전달합니다. 두 MySQL 프로필은 `ddl-auto: update`로 시작 시 스키마를 생성·갱신합니다. 필요한 변수 이름은 [`.env.example`](.env.example)을 참고하세요. `.env.example`은 예시 파일이며 Spring Boot가 자동으로 읽지는 않습니다. multipart 파일 제한은 기본 10MB, 전체 요청 제한은 파일 외 multipart 필드의 여유를 포함해 12MB이며 환경 변수로 변경할 수 있습니다.

## 배포

`develop` 대상 PR은 `Backend CI`에서 Java 17 빌드를 검증합니다. 팀원 승인 후 병합된 `develop` 커밋은 전체 SHA 태그의 GHCR 이미지로 게시되며, `production` 환경의 전용 SSH 키로 EC2에 전달됩니다. 공개 GHCR 패키지, 콘솔에서 검증한 호스트키, 호스트 초기 설정을 먼저 준비해야 합니다.

설정·상태 확인·장애 복구·암호화 백업·비용 종료 절차는 [배포 운영 문서](docs/deployment.md), AWS 콘솔 입력값은 [EC2 생성 명세](infra/aws/ec2-console.md)를 따릅니다. 초기 HTTP 주소는 민감정보 전송에 사용하지 않으며, 운영 접속은 검증된 SSH 터널을 사용합니다. 배포 중 재시작에 따른 중단이 있고, 외부 백업 회수는 운영자 작업입니다. 문서와 설정 파일의 존재만으로 실제 배포 완료를 뜻하지 않습니다.

## 협업

브랜치와 커밋 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.
