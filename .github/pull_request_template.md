## 🎯 목적 (Why)

<!-- 이 PR 이 왜 필요한가? 어떤 평가 항목 / ADR과 연결되는가? -->

연관: 평가 항목 #X (CLAUDE.md §3 참조) / ADR-XXX

## 📋 변경 사항 (What)

<!-- 주요 변경을 개념 수준으로. 파일 나열 X. -->

-

## 🧪 검증

<!-- 어떻게 동작 확인했는지 (수동 curl, 로컬 부하 테스트, 단위 테스트) -->

- [ ] 단위 테스트 추가 (해당하는 경우)
- [ ] curl 시나리오 검증 (아래에 명령어와 응답)
- [ ] 동시성 테스트 (해당하는 경우)
- [ ] `./gradlew :server-X:bootRun` 정상 기동 (해당하는 경우)
- [ ] Redis/MySQL/Kafka 상태 확인 (해당하는 경우)

```bash
# 검증에 사용한 명령어와 결과
```

## ⚠️ 트레이드오프 / 결정 메모

<!-- 평가자가 특히 봐야 할 부분, 의도적으로 단순화한 부분 -->

-

## 🔄 Breaking Change

- [x] **없음**
- [ ] **있음**: 영향 범위 / 마이그레이션 방법 기술

## ✅ 체크리스트

### 일반
- [ ] CLAUDE.md §6 ADR / §10 안티패턴 위반 없음
- [ ] 패키지 구조 (api / application / domain / infrastructure) 유지
- [ ] `./gradlew clean build` 로컬 성공
- [ ] 새 의존성 추가 시 CLAUDE.md §7 기술 스택 표 업데이트

### 코드 컨벤션 (CLAUDE.md §11)
- [ ] `@Data` 사용 안 함
- [ ] entity 에 `@Setter` 전체 적용 안 함
- [ ] `LocalDateTime` 대신 `Instant`
- [ ] DTO 는 Java record
- [ ] javax.* 대신 jakarta.*
- [ ] `@Transactional` 안에서 외부 API/Kafka 호출 없음

### 분산 시스템 / 신뢰성 (해당하는 경우)
- [ ] 외부 노출 API 에 Idempotency-Key 처리
- [ ] 외부 노출 API 에 Rate Limit 정책
- [ ] 외부 호출에 timeout 설정
- [ ] 외부 호출에 Circuit Breaker / retry
- [ ] Kafka consumer 가 멱등 동작 (DB UNIQUE constraint 등)
- [ ] 재고/한정 자원 관리 시 Redis atomic 사용 (RDBMS row lock 금지)

### 문서
- [ ] README 의 관련 섹션 갱신 (해당하는 경우)
- [ ] 새 ADR 필요 시 `docs/decisions/` 추가
- [ ] 셀프 리뷰 완료

## 📎 관련 문서

- CLAUDE.md §X
- ADR-XXX
