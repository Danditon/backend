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

미정 (언어·프레임워크, DB, 배포 환경 결정 후 작성)

## 실행 방법

스택 결정 후 작성

## 협업

브랜치와 커밋 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.
