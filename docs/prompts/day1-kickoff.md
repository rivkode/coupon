# Day 1 Kickoff Prompt

> 이 파일 내용을 Claude Code 첫 세션에 그대로 붙여넣으면 됩니다.
> CLAUDE.md는 미리 repo 루트에 위치시켜 둘 것.

---

## 작업 시작 안내

5일짜리 백엔드 채용 과제의 Day 1입니다.
프로젝트 루트의 `CLAUDE.md`에 모든 컨텍스트(아키텍처, ADR, 안티패턴, 기술 스택)가 정리되어 있습니다.

**오늘 목표는 두 가지**:
1. 전체 시스템 셋업 (멀티모듈 + 인프라 + 도메인 모델 + 마이그레이션 + README 골격)
2. **Server A 완전 구현** (실제 동작하는 진입점 — Idempotency, Rate Limit, Circuit Breaker 포함)

Server B와 C는 Day 2~3에 만듭니다. 그래서 Server A는 **Server B의 stub 클라이언트**를 통해 단독으로 동작하도록 구현합니다.

---

## Phase 0: 컨텍스트 확인 (반드시 먼저)

다음을 순서대로 수행해 주세요:

1. `CLAUDE.md`를 처음부터 끝까지 정독
2. 아래 5가지를 각 1~2문장으로 요약해서 보여줄 것:
    - 우리가 구현하는 시나리오
    - A→B 동기 / B→C 비동기 결정의 핵심 이유
    - Saga + Outbox 선택 이유와 CDC 배제 이유
    - Server B에서 재고 관리 시 절대 하지 말아야 할 것
    - Server A의 핵심 책임 5가지

3. **사용자에게 질문할 것**:
    - Java 패키지 prefix (예: `com.promotion`)
    - GitHub repository 이름 (CLAUDE.md엔 `promotion`)
    - 그 외 시작 전 명확히 하고 싶은 점

답변을 받기 전까지 Phase 1로 진행하지 말 것.

---

## Day 1 산출물 전체 목록

오늘 끝나면 다음 상태가 되어야 합니다:

**시스템 셋업** (Phase 1~4)
- Gradle 멀티모듈 골격 (`common`, `server-a`, `server-b`, `server-c`)
- `docker-compose.yml` (MySQL 8 / Redis 7 / Kafka 3 KRaft)
- 모든 서비스의 도메인 모델
- Server A, C의 Flyway 마이그레이션
- `README.md` 골격

**Server A 완전 구현** (Phase 5~8)
- 진입점 API: `POST /api/v1/coupons/issue-requests`
- Idempotency-Key 헤더 처리
- Rate Limiter (Bucket4j + Redis)
- Server B 호출 클라이언트 (인터페이스 + stub 구현체 + 실제 구현체)
- Resilience4j Circuit Breaker
- 예외 처리 (@RestControllerAdvice)

**검증** (Phase 9)
- E2E 시나리오 curl 검증 통과

**오늘 만들지 말 것**:
- Server B, C의 비즈니스 로직 (entity와 마이그레이션만)
- 테스트 코드 (Day 4에 부하 테스트 시 보강)
- Kafka producer / consumer
- 쿠폰 사용(redeem) API

---

## 진행 방식 — Phase별 강제 정지

다음 9개 Phase로 진행하고, **각 Phase 종료 시 반드시 멈추고 결과 보고 후 사용자 확인 대기**. 사용자가 "다음 단계 진행"이라고 하기 전까지 자체적으로 다음 Phase로 넘어가지 말 것.

---

### Phase 1: Gradle 멀티모듈 골격

생성할 파일:
- `settings.gradle.kts` — `include(":common", ":server-a", ":server-b", ":server-c")`
- `build.gradle.kts` (root) — 공통 plugin/dependency 관리
- `common/build.gradle.kts` — `java-library` (Spring Boot bootJar 비활성)
- `server-a/build.gradle.kts`, `server-b/build.gradle.kts`, `server-c/build.gradle.kts`
- `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.x)
- `.gitignore`, `.editorconfig`

기술 제약:
- Gradle Kotlin DSL (Groovy DSL 금지)
- Java toolchain 21 명시
- Spring Boot 3.5.14 (Java 21 호환 LTS)
- Lombok 사용 가능, **`@Data` 금지** (`@Getter`, `@RequiredArgsConstructor` 권장)
- jakarta.* 패키지 (javax.* 금지)
- 각 server 모듈은 `bootJar` 활성, `jar` 비활성
- `common`은 `bootJar` 비활성, `jar` 활성

각 server의 초기 의존성:
- `spring-boot-starter-web`
- `spring-boot-starter-actuator`
- `spring-boot-starter-validation`
- `project(":common")`

Server A, C 추가:
- `spring-boot-starter-data-jpa`
- `mysql-connector-j`
- `org.flywaydb:flyway-core`, `org.flywaydb:flyway-mysql`

Server A는 추가로:
- `spring-boot-starter-data-redis` (Idempotency, Rate Limit용)
- `com.bucket4j:bucket4j_jdk17-core`, `com.bucket4j:bucket4j_jdk17-redis-common`, `com.bucket4j:bucket4j_jdk17-lettuce` (Rate Limiter)
- `io.github.resilience4j:resilience4j-spring-boot3`, `resilience4j-circuitbreaker`, `resilience4j-timelimiter`

Server B 추가:
- `spring-boot-starter-data-redis`

완료 조건:
- `./gradlew clean build -x test` 성공 (각 server는 빈 main 클래스로 가능)
- 결과로 보여줄 것: 디렉토리 트리 + 각 build.gradle.kts 핵심 부분

**여기서 멈추고 사용자 확인 대기.**

---

### Phase 2: docker-compose 인프라

생성할 파일: `docker-compose.yml` (repo 루트)

요구사항:
- **MySQL 8.x**: 단일 컨테이너에 두 schema (`server_a`, `server_c`). 포트 3306. healthcheck.
- **Redis 7.x**: 포트 6379. healthcheck.
- **Kafka 3.x (KRaft mode)**: Zookeeper 없는 단일 broker. 포트 9092. healthcheck.
- (선택) **Kafka UI**: 디버깅 (provectuslabs/kafka-ui). 포트 8080.
- 모든 데이터는 named volume에 영속화
- 단일 bridge network

추가 파일:
- `docker/mysql/init.sql` — 두 schema 생성 + 권한 부여

완료 조건:
- `docker-compose up -d` 후 모든 서비스 healthy
- MySQL에 `server_a`, `server_c` schema 존재 확인
- `redis-cli ping` 성공
- Kafka 토픽 생성 테스트 통과

**여기서 멈추고 사용자 확인 대기.**

---

### Phase 3: 도메인 모델 + Flyway 마이그레이션

이 단계가 가장 중요합니다. 도메인 모델이 잘못 잡히면 이후 모든 게 영향을 받습니다.

#### Server A 도메인 모델
- `Event` (entity)
    - `id: Long` (PK)
    - `name: String`, `totalStock: Integer`, `startedAt: Instant`, `endedAt: Instant`
    - 마스터 데이터 (CRUD API는 만들지 않음, 마이그레이션에 seed data 1건 포함)
- `IssueRequest` (entity)
    - `id: Long` (PK)
    - `requestId: String` (UUID, 외부 노출용)
    - `userId: Long`, `eventId: Long`
    - `idempotencyKey: String`
    - `status: enum` (RECEIVED, FORWARDED, SUCCEEDED, FAILED, REJECTED)
    - `couponCode: String?` (성공 시 채워짐)
    - `failureReason: String?`
    - `createdAt: Instant`, `updatedAt: Instant`
    - UNIQUE: `(userId, idempotencyKey)`

#### Server B 도메인 (Redis 키 정의)
별도 entity 클래스 없음. 상수 클래스 `RedisKeys`로 키 컨벤션 정의:
- `event:{eventId}:stock:{shardId}` — INTEGER (재고 샤드)
- `coupon:code:{code}` — HASH (임시 발급 정보, TTL)
- `idem:{userId}:{key}` — STRING (idempotency 캐시, TTL 24h)

도메인 record (Server A에서도 사용하므로 `common` 모듈에 배치):
- `IssueResult(couponCode: String?, status: IssueStatus, failureReason: String?)`
- `IssueStatus enum`: ISSUED, SOLD_OUT, ALREADY_ISSUED, INTERNAL_ERROR

#### Outbox 결정 (사용자에게 질문)
Outbox는 RDBMS 보조 테이블 vs Redis Streams 두 후보. **사용자에게 어느 쪽으로 갈지 질문**.
권장은 **RDBMS 보조 테이블** (Server B 자체는 NoSQL이지만, Outbox는 트랜잭션 정합성을 위해 RDBMS 활용. Server B에 별도의 작은 MySQL 테이블 또는 Server C의 MySQL을 공유). 결정 전까지 Outbox 관련 마이그레이션은 만들지 말 것.

#### Server C 도메인 모델
- `Coupon` (entity)
    - `id: Long` (PK)
    - `code: String` (UNIQUE)
    - `userId: Long`, `eventId: Long`
    - `idempotencyKey: String` (UNIQUE — 멱등성의 최후 보루)
    - `issuedAt: Instant`, `usedAt: Instant?` (nullable)
    - `version: Long` (`@Version` — 낙관적 락)
- 인덱스: `(userId, eventId)`, `code` UNIQUE, `idempotencyKey` UNIQUE

#### Flyway 마이그레이션 파일
- `server-a/src/main/resources/db/migration/V1__create_event.sql` (+ seed data)
- `server-a/src/main/resources/db/migration/V2__create_issue_request.sql`
- `server-c/src/main/resources/db/migration/V1__create_coupon.sql`

SQL 작성 규칙:
- 테이블/컬럼명 snake_case
- charset `utf8mb4`, collation `utf8mb4_unicode_ci`
- engine `InnoDB`
- 모든 테이블에 `created_at`, `updated_at`
- UNIQUE constraint, 인덱스를 마이그레이션에 명시 (entity 어노테이션이 아닌 SQL이 진실)

#### 공통 작성 규칙
- Lombok: `@Getter`, `@RequiredArgsConstructor`, `@NoArgsConstructor(access = PROTECTED)`
- `@Data`, `@Setter` 전체 적용 금지
- DTO는 Java record
- `Instant` 사용 (LocalDateTime 금지)
- equals/hashCode는 PK 기반 명시 작성

#### 패키지 구조 (각 server 모듈)
```
com.{prefix}.{servera|serverb|serverc}/
├── ServerXApplication.java
├── domain/                          # entity, value object, enum
├── application/                     # service (Day 1 비어있음, Phase 6에서 추가)
├── infrastructure/                  # config, repository impl, external client
└── api/                             # controller, dto, exception handler
```

완료 조건:
- `./gradlew :server-a:bootRun` 시 Server A의 Flyway 마이그레이션 적용
- `./gradlew :server-c:bootRun` 시 Server C의 Flyway 마이그레이션 적용
- MySQL에 테이블 생성 확인
- 결과로 보여줄 것: 모든 entity 코드 + ER 다이어그램 텍스트 + Outbox 결정 사항 확정

**여기서 멈추고 사용자 확인 대기. Outbox 결정 답변 받을 것.**

---

### Phase 4: README.md 골격

생성할 파일: `README.md` (repo 루트)

다음 섹션 헤더만 만들고 본문은 비우거나 TODO만 표기:

```markdown
# Weverse 프로모션 시스템 - 백엔드 채용 과제

## 1. 개요
## 2. 시스템 아키텍처
   ### 2.1 다이어그램 (Data Flow 포함)
   ### 2.2 기술 스택
## 3. 시나리오 및 도메인
## 4. 핵심 설계 결정 (ADR)
   ### 4.1 A→B 동기, B→C 비동기
   ### 4.2 Saga (Choreography) + Outbox
   ### 4.3 Redis 기반 재고 관리
   ### 4.4 Idempotency-Key 전략
   ### 4.5 Rate Limiting / Backpressure
   ### 4.6 Database per Service
## 5. 평가 항목별 구현
   ### 5.1 동시성 제어
   ### 5.2 분산 정합성 + 멱등성
   ### 5.3 캐시 + Hot Spot
   ### 5.4 Rate Limiting
   ### 5.5 인프라 사이징
## 6. 부하 테스트 결과
## 7. 100,000명 처리 시 인프라 계산
## 8. 로컬 실행 방법
## 9. 한계 및 개선 방향 (CDC 전환 등)
```

다이어그램은 Mermaid로 1차 버전 작성 (CLAUDE.md Section 4 기반).
"로컬 실행 방법"은 Phase 2 결과 기반으로 실제 작동 명령어 작성.

완료 조건: 모든 섹션 헤더 존재, Mermaid 구문 유효

**여기서 멈추고 사용자 확인 대기. 시스템 셋업 종료.**

---

### Phase 5: Server A 인프라 셋업

여기서부터 Server A 구현 시작. 인프라/공통 코드부터.

생성할 파일:
- `server-a/src/main/resources/application.yml` — DataSource, JPA, Redis, Logging, Resilience4j
- `server-a/src/main/resources/application-local.yml` — 로컬 환경 (stub 클라이언트 활성화)
- `ServerAApplication.java` — `@SpringBootApplication`
- `infrastructure/config/RedisConfig.java` — Lettuce 기반 RedisConnectionFactory + RedisTemplate
- `infrastructure/config/WebConfig.java` — (필요 시)
- `api/exception/GlobalExceptionHandler.java` — `@RestControllerAdvice`
- `api/exception/BusinessException.java` (`common` 모듈에 두는 것도 검토)
- `api/dto/ApiResponse.java` (record) — 공통 응답 형식 `{success, data, error}`
- `api/dto/ErrorResponse.java` (record)

application.yml 주의사항:
- DataSource는 환경변수로 override 가능하게 (`${DB_HOST:localhost}` 형태)
- HikariCP: `maximum-pool-size: 20`, `connection-timeout: 3000` (1 vCPU 환경 기준 보수적)
- JPA: `hibernate.ddl-auto: validate` (Flyway가 스키마 관리)
- Resilience4j Circuit Breaker 설정은 Phase 8에서 본격 추가, 여기선 placeholder

예외 매핑:
- `BusinessException` → 4xx (사용자 입력 문제)
- 기타 RuntimeException → 500 + 로깅
- `MethodArgumentNotValidException` → 400 + 필드별 에러
- `RateLimitExceededException` → 429 (Phase 7에서 추가)
- `IdempotencyConflictException` → 409 (Phase 7에서 추가)
- 향후 추가될 `CircuitBreakerOpenException` 등 → 503

완료 조건:
- `./gradlew :server-a:bootRun --args='--spring.profiles.active=local'` 성공
- `curl http://localhost:8080/actuator/health` → UP
- 결과로 보여줄 것: application.yml + 핵심 config 클래스

**여기서 멈추고 사용자 확인 대기.**

---

### Phase 6: Server A 핵심 API

생성할 파일:
- `api/IssueRequestController.java`
- `api/dto/IssueCouponRequest.java` (record) — `eventId`, 그 외 10개 필드 (과제에 "10개 필드" 명시되어 있음 — 적절히 채울 것: `userId, eventId, deviceId, channel, requestedAt, clientVersion, region, language, marketingConsent, metadata` 정도)
- `api/dto/IssueCouponResponse.java` (record)
- `application/IssueRequestService.java` — orchestration
- `infrastructure/repository/IssueRequestRepository.java` — Spring Data JPA
- `infrastructure/client/CouponIssuingClient.java` (interface, in `application` package)
- `infrastructure/client/StubCouponIssuingClient.java` (`@Profile("local")`, `@Component`)
    - 단순히 `IssueResult(랜덤 쿠폰코드, ISSUED, null)` 반환
    - 추후 SOLD_OUT 시뮬레이션을 위한 환경변수 hook은 만들어두되 기본은 항상 성공

API 명세:
- `POST /api/v1/coupons/issue-requests`
- Headers: `X-User-Id` (인증 단순화), `Idempotency-Key` (UUID, 필수)
- Body: `IssueCouponRequest` (10 fields)
- 응답:
    - 200: `{success: true, data: {requestId, couponCode, status}}`
    - 400: validation 실패
    - 409: idempotency conflict (Phase 7)
    - 429: rate limit (Phase 7)
    - 503: B 호출 실패 (Phase 8)

서비스 흐름 (Phase 6 시점, idempotency/rate limit 없음):
```
1. IssueRequest entity 생성 (status=RECEIVED) → save
2. CouponIssuingClient.issue() 호출 (stub)
3. 결과에 따라 IssueRequest.status 업데이트 (SUCCEEDED/FAILED) → save
4. 응답 반환
```

주의:
- `@Transactional`을 외부 호출(`CouponIssuingClient.issue()`) 감싸지 말 것 — 트랜잭션을 외부 호출 전후로 분리
- save를 `REQUIRES_NEW`로 하거나, 두 번의 트랜잭션으로 분리

완료 조건:
- `curl -X POST http://localhost:8080/api/v1/coupons/issue-requests -H "X-User-Id: 1" -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" -d '{...}'` 성공
- DB의 `issue_request` 테이블에 RECEIVED → SUCCEEDED 흐름 기록
- 결과로 보여줄 것: 코드 + 실제 curl 응답

**여기서 멈추고 사용자 확인 대기.**

---

### Phase 7: Idempotency + Rate Limiter

#### Idempotency 처리

전략: HandlerInterceptor 또는 Filter 형태로 controller 진입 직전에 처리.

생성할 파일:
- `infrastructure/idempotency/IdempotencyKey.java` (record, value object)
- `infrastructure/idempotency/IdempotencyStore.java` (interface)
- `infrastructure/idempotency/RedisIdempotencyStore.java` (Lettuce 기반 구현)
- `api/filter/IdempotencyFilter.java` 또는 `infrastructure/web/IdempotencyInterceptor.java`

핵심 로직:
- `Idempotency-Key` 헤더 누락 시 400
- Redis key: `idem:{userId}:{key}`
- `SET key value NX EX 86400` (24시간 TTL)
    - 성공: 처음 요청, 정상 처리 진행
    - 실패: 이미 처리 중이거나 완료됨 → 캐시된 응답 반환 또는 409 conflict
- 응답을 받은 후 결과를 같은 키에 저장 (status, body)
- 동일 키로 재요청 시 캐시된 응답 그대로 반환

선택: 처리 중인 요청에 대한 race condition 처리 (요청 lock vs 응답 캐싱). 단순화를 위해 "응답 캐싱 후 재현" 방식 권장.

#### Rate Limiter

전략: Filter 형태, controller 진입 직전.

생성할 파일:
- `infrastructure/ratelimit/RateLimiterConfig.java` — Bucket4j Lettuce-backed bucket factory
- `api/filter/RateLimitFilter.java`

정책:
- 사용자당 10 req/sec, burst 20 (CLAUDE.md ADR-005)
- key: `rate:user:{userId}`
- 초과 시 429 + 응답 헤더 `X-RateLimit-Remaining`, `X-RateLimit-Retry-After-Seconds`
- 예외 메시지에 retry 안내 포함

Filter 순서:
1. RateLimitFilter (먼저, 차단부터)
2. IdempotencyFilter (그 다음, 멱등 캐시 hit 시 빠르게 반환)
3. Controller

완료 조건:
- 같은 Idempotency-Key로 두 번 호출 → 같은 응답 (DB에 추가 record 없음)
- 1초에 11번 호출 → 11번째는 429
- 결과로 보여줄 것: filter 코드 + curl 시나리오 + Redis 키 상태

**여기서 멈추고 사용자 확인 대기.**

---

### Phase 8: Server B 호출 클라이언트 + Circuit Breaker

생성할 파일:
- `infrastructure/client/RestClientCouponIssuingClient.java` (`@Profile("!local")` 또는 `@Profile("dev", "prod")`)
    - Spring 6.1+ `RestClient` 사용 (WebClient도 가능, 단순함 우선이면 RestClient)
    - Server B URL은 application.yml에서 `app.server-b.url`
    - timeout: connect 1s, read 200ms (CLAUDE.md ADR-001)
- Resilience4j Circuit Breaker 설정 (application.yml)
    - failure-rate-threshold: 50%
    - sliding-window: count-based 20
    - wait-duration-in-open-state: 5s
    - permitted-calls-in-half-open: 3
- `@CircuitBreaker(name = "couponIssuing", fallbackMethod = "issueFallback")` 적용
- fallback: `IssueResult(null, INTERNAL_ERROR, "circuit-open")`
- TimeLimiter 적용 (200ms timeout)

stub vs 실제 클라이언트 전환:
- 로컬 개발은 `local` profile (stub 활성화)
- 실제 환경은 default profile (RestClient 활성화)
- 두 구현체 모두 `CouponIssuingClient` 인터페이스 구현, Spring이 profile에 따라 주입

추가:
- `application.yml`에 Resilience4j 설정
- `actuator` 엔드포인트로 circuit 상태 확인 가능 (`/actuator/circuitbreakers`)

완료 조건:
- `local` profile에서 stub으로 정상 동작 (Phase 6과 동일)
- (시뮬레이션) 실제 클라이언트 활성화 후 Server B 부재 시 → 503 fallback 응답 + circuit 상태 변경 확인
- 결과로 보여줄 것: 두 클라이언트 구현체 + circuit 설정 + actuator 응답

**여기서 멈추고 사용자 확인 대기.**

---

### Phase 9: E2E 시나리오 검증

다음 시나리오를 curl로 직접 실행하고 결과 보고:

**시나리오 1: 정상 발급**
```bash
KEY=$(uuidgen)
curl -i -X POST http://localhost:8080/api/v1/coupons/issue-requests \
  -H "X-User-Id: 1001" \
  -H "Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{ "eventId": 1, "deviceId": "test", "channel": "web", ... }'
```
기대: 200, `couponCode` 포함, DB에 SUCCEEDED 기록

**시나리오 2: 멱등성**
같은 KEY로 즉시 재요청 → 같은 응답 본문, DB에 추가 record 없음

**시나리오 3: 다른 사용자 같은 KEY**
다른 X-User-Id로 같은 KEY → 정상 처리 (key는 user-scoped)

**시나리오 4: Rate Limit**
```bash
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/... \
    -H "X-User-Id: 1001" -H "Idempotency-Key: $(uuidgen)" \
    -H "Content-Type: application/json" -d '{...}'
done
```
기대: 처음 10~20개는 200, 이후 429

**시나리오 5: Idempotency-Key 누락**
헤더 없이 요청 → 400 + 명확한 에러 메시지

**시나리오 6: validation 실패**
`eventId` 누락 → 400 + 필드 에러

각 시나리오에 대해:
- 실제 curl 응답
- 응답 시간
- DB/Redis 상태
- 로그에 traceId 일관성 확인

마지막으로 결과 정리 보고:
- 작동하는 시나리오 / 실패한 시나리오
- Day 2에 손볼 항목 (있다면)
- 단일 Server A의 측정된 응답 시간 (단순 측정, Day 4 부하 테스트의 사전 데이터)

**Day 1 종료.**

---

## 기술적 안티패턴 재확인

CLAUDE.md Section 10을 따르되, Day 1 특별 주의사항:

- ❌ Spring Boot 플러그인을 `common` 모듈에 적용
- ❌ MySQL 단일 schema에 모든 테이블
- ❌ Kafka에 Zookeeper 의존성 (KRaft 필수)
- ❌ Java 21이 아닌 다른 버전
- ❌ DTO에 entity 그대로 사용
- ❌ `LocalDateTime` (`Instant` 사용)
- ❌ `@Data`, entity에 `@Setter` 전체 적용
- ❌ `@Transactional` 안에서 외부 API 호출 (CouponIssuingClient 호출)
- ❌ 패키지 구조 무시 (api/application/domain/infrastructure 4계층 유지)
- ❌ Idempotency 처리를 service layer에 분산 (filter/interceptor에서 일괄)
- ❌ Rate Limit을 controller annotation 방식으로 (filter 방식 권장)
- ❌ Server B URL을 코드에 하드코딩
- ❌ Stub 구현체와 실제 구현체를 같은 profile에서 동시 활성화

---

## 작업 스타일 가이드

- 한 Phase 안에서도 큰 변경 전에는 짧게 계획 공유 후 진행
- 의존성 버전 임의 선택 금지, 모르면 질문
- 각 Phase 종료 시 다음 형식으로 보고:
  ```
  ## Phase N 완료 보고
  - 생성/수정 파일: ...
  - 검증 명령어와 결과: ...
  - 다음 Phase로 가기 전 사용자 확인 필요 사항: ...
  ```
- 다음 Phase는 사용자의 명시적 "다음 진행" 후에만

---

## 시작

Phase 0 (CLAUDE.md 정독 + 5가지 요약 + 사전 질문)부터 시작해 주세요.