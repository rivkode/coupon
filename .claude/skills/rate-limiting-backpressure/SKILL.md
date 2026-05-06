---
name: rate-limiting-backpressure
description: 본 과제 Server A 의 진입 흐름 흡수 스킬. Bucket4j Token Bucket 사용자별 10 req/sec (CLAUDE.md ADR-005), Redis 분산 backend (멀티 인스턴스), Resilience4j Circuit Breaker (A→B 호출 보호, ADR-001), Bulkhead, 큐 깊이 + 거부 정책, HTTP 429 + Retry-After, Tomcat / HikariCP 1 vCPU 튜닝을 다룬다. "rate limit", "유량 제어", "Backpressure", "Bulkhead", "Circuit Breaker", "429", "TPS 초과", "카스케이딩 실패" 키워드가 나오거나 1 vCPU 인스턴스가 목표 TPS 를 넘는 트래픽을 받는 시나리오에서 PROACTIVELY 사용한다.
---

# Rate Limiting & Backpressure (promotion)

본 스킬은 CLAUDE.md §3 ④ (Rate Limiting / Backpressure) 평가 항목과 ADR-001 (A→B Circuit Breaker), ADR-005 (사용자당 10 req/sec) 에 직접 대응한다.

> 본 과제 진입 흐름:
> ```
> User → Server A
>       1) Tomcat (max-threads = 50, accept-count = 100)
>       2) Bucket4j (사용자당 10 req/sec, Redis backend)
>       3) HikariCP (poolSize 10, 1 vCPU 기준)
>       4) Resilience4j Circuit Breaker (A→B 호출, timeout 200ms)
> ```

---

## 1. 결정 트리 — 어디에 무엇을 적용

```
트래픽 초과의 원인은?
├── 진입(클라이언트) 폭주
│   └── Bucket4j (사용자당 10 req/sec) — Server A 의 Filter
│       ├── 단일 인스턴스 → in-memory Bucket
│       └── 멀티 인스턴스 (CLAUDE.md 가정 — 다중 Server A) → Bucket4j + Redis
│
├── 다운스트림 (B/C) 느려짐
│   ├── Server A → B 호출 → Circuit Breaker + timeout 200ms (ADR-001)
│   └── Server B → Kafka publish → Outbox poller 가 자연 buffer 역할
│
└── 자체 처리 큐 폭주
    ├── HTTP → Tomcat acceptCount + maxThreads
    └── 비동기 (Outbox) → poller concurrency 조절
```

---

## 2. Bucket4j — 사용자별 10 req/sec (ADR-005)

### 2.1 의존성

```kotlin
implementation("com.bucket4j:bucket4j_jdk17-core:8.10.1")
implementation("com.bucket4j:bucket4j_jdk17-redis-common:8.10.1")
implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.10.1")
```

### 2.2 단일 인스턴스 (개발/테스트)

```java
@Component
public class UserRateLimiter {
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(Duration.ofHours(1))
        .build();

    public boolean tryConsume(String userId) {
        Bucket bucket = buckets.get(userId, this::newBucket);
        return bucket.tryConsume(1);
    }

    private Bucket newBucket(String userId) {
        Bandwidth limit = Bandwidth.builder()
            .capacity(10)                                    // 10 token
            .refillIntervally(10, Duration.ofSeconds(1))      // 매 초마다 10 token 충전
            .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
```

### 2.3 멀티 인스턴스 (운영) — Redis backend

```java
@Bean
public ProxyManager<String> proxyManager(RedisClient redisClient) {
    StatefulRedisConnection<String, byte[]> conn =
        redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    return LettuceBasedProxyManager.builderFor(conn)
        .withExpirationStrategy(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
        .build();
}

@Component
@RequiredArgsConstructor
public class DistributedRateLimiter {
    private final ProxyManager<String> proxyManager;

    public boolean tryConsume(String userId) {
        Bucket bucket = proxyManager.builder()
            .build("rate:" + userId, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                    .capacity(10)
                    .refillIntervally(10, Duration.ofSeconds(1))
                    .build())
                .build());
        return bucket.tryConsume(1);
    }
}
```

### 2.4 Filter

```java
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private final DistributedRateLimiter limiter;
    private final ObjectMapper mapper;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = req.getHeader("X-User-Id");           // 단순 헤더 기반 (CLAUDE.md §5.1)
        if (userId == null) {
            chain.doFilter(req, res);                         // 인증 누락은 인증 필터가 처리
            return;
        }
        if (!limiter.tryConsume(userId)) {
            writeRateLimitResponse(res, 1);
            return;
        }
        chain.doFilter(req, res);
    }

    private void writeRateLimitResponse(HttpServletResponse res, int retryAfterSec) throws IOException {
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfterSec));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse("RATE_LIMIT_EXCEEDED",
            "too many requests", Instant.now(), MDC.get("traceId"), List.of());
        res.getWriter().write(mapper.writeValueAsString(body));
    }
}
```

### 2.5 안티패턴 (CLAUDE.md §10 회귀)

- ❌ 503 으로 응답 — 클라이언트 무한 재시도 유발. **429** 사용
- ❌ Retry-After 헤더 누락 — 클라이언트에 재시도 가이드 없음
- ❌ buckets Map 무제한 보관 → 메모리 누수. Caffeine + TTL
- ❌ 인증 전에 rate limit (인증 실패 트래픽까지 카운트) → 단, 본 과제는 단순 user_id 라 영향 없음

---

## 3. Resilience4j — A → B 호출 보호 (ADR-001)

### 3.1 Circuit Breaker + Timeout (필수)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      server-b-issue:
        sliding-window-size: 50
        sliding-window-type: COUNT_BASED
        failure-rate-threshold: 50           # 50% 실패 → OPEN
        slow-call-rate-threshold: 50
        slow-call-duration-threshold: 200ms  # 200ms 초과 = slow
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 5
        minimum-number-of-calls: 20

  timelimiter:
    instances:
      server-b-issue:
        timeout-duration: 200ms              # ADR-001 명시값
        cancel-running-future: true
```

```java
@Service
@RequiredArgsConstructor
public class CouponIssueClient {

    private final RestClient restClient;

    @CircuitBreaker(name = "server-b-issue", fallbackMethod = "fallback")
    @TimeLimiter(name = "server-b-issue")
    public CompletableFuture<IssueResponse> issue(IssueRequest req) {
        return CompletableFuture.supplyAsync(() ->
            restClient.post()
                .uri("/internal/v1/coupons/issue")
                .body(req)
                .retrieve()
                .body(IssueResponse.class));
    }

    private CompletableFuture<IssueResponse> fallback(IssueRequest req, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return CompletableFuture.failedFuture(
                new ServiceUnavailableException("server B is currently unavailable"));
        }
        if (t instanceof TimeoutException) {
            return CompletableFuture.failedFuture(
                new GatewayTimeoutException("server B timeout"));
        }
        return CompletableFuture.failedFuture(t);
    }
}
```

응답 매핑:
- `CallNotPermittedException` → 503 + Retry-After
- `TimeoutException` → 504
- 다른 예외 → 502

### 3.2 Bulkhead (선택, 동시 호출 제한)

```yaml
resilience4j:
  bulkhead:
    instances:
      server-b-issue:
        max-concurrent-calls: 50               # vCPU/threads 와 균형
        max-wait-duration: 10ms                # 즉시 거절 가까이
```

`@Bulkhead(name = "server-b-issue")` 추가. Tomcat 스레드 폭주 방지.

### 3.3 데코레이터 순서

```
Retry → CircuitBreaker → TimeLimiter → Bulkhead → 실제 호출
```

본 과제는 **Retry 권장하지 않음** — 선착순 흐름은 짧은 시간에 폭주하므로 retry 가 다운스트림 회복 방해.

---

## 4. Tomcat / HikariCP 튜닝 (1 vCPU)

### 4.1 application.yml

```yaml
server:
  tomcat:
    threads:
      max: 50                  # 1 vCPU + IO-bound 평균 100ms 가정
      min-spare: 10
    max-connections: 1000
    accept-count: 100          # OS backlog
    connection-timeout: 5s
    keep-alive-timeout: 30s

spring:
  datasource:
    hikari:
      maximum-pool-size: 10    # vCPU × 4 ~ 10
      minimum-idle: 5
      connection-timeout: 3000
      max-lifetime: 1800000
```

### 4.2 한계 메모리 검산

- 50 threads × 1MB stack = 50MB
- HikariCP 10 connections × 작은 buffer = 미미
- JVM heap 1.4GB + 기타 → 2GB 안에 맞음

### 4.3 안티패턴

- ❌ max-threads = 200 (1 vCPU) — 컨텍스트 스위치 폭주
- ❌ HikariCP poolSize = 100 — DB 측 connection 폭주
- ❌ accept-count = 0 — 순간 폭주 흡수 못함
- ❌ keep-alive-timeout 너무 김 → idle connection 적재

---

## 5. 큐 깊이 + 거부 정책

본 과제 큐:
- HTTP 진입: Tomcat accept queue (100)
- 요청 로그 batch: `LinkedBlockingQueue<>(10_000)` + Discard (`concurrency/SKILL.md §6`)
- Outbox poller: 별도 thread, batch 단위로 처리
- Bucket4j 거절: 즉시 429 (큐 없음)

| 거부 정책 | 사용처 |
|---|---|
| **Reject (429/503)** | 사용자 트래픽 — 빠른 피드백 |
| **Discard** | 요청 로그 (감사 로그, 손실 허용) |
| **CallerRunsPolicy** | (사용 안 함 — 진입 스레드 보호) |

---

## 6. promotion 적용 정리

```
[Client]
  │ HTTPS
  ▼
[Server A] (1 vCPU / 2 GB / Tomcat threads=50, accept=100)
  │ Filter chain:
  │  1. TraceIdFilter (MDC)
  │  2. AuthFilter (단순 user_id 헤더)
  │  3. RateLimitFilter (Bucket4j, 사용자 10 req/sec)
  │  4. IdempotencyFilter (Redis 1차 검사)
  │
  │ Controller → ApplicationService:
  │   - Issue request 로그 (batch)
  │   - issueClient.issue(...)  ← Resilience4j (CB + timeout 200ms)
  │
  ▼
[Server B] (재고/Outbox)
  │ Lua DECR + Outbox INSERT
  │ poller → Kafka (자연 backpressure: lag 가 보임)
  │
  ▼
[Kafka] → [Server C] (consume + UNIQUE)
```

진입 흐름의 한계:
- **Bucket4j** 가 막아도 인증 통과 사용자가 누적되면 Tomcat 큐가 가득 → 503
- **Tomcat accept-count** 도 가득 → OS reject
- 정상 동작: Bucket4j → 429 / CB OPEN → 503 / Timeout → 504. 5xx 폭주 없음.

---

## 7. HTTP 429 / 503 / 504 표준 응답

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 1
X-Request-Id: <traceId>

{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "too many requests",
  "timestamp": "2026-05-06T05:00:00Z",
  "traceId": "..."
}
```

```http
HTTP/1.1 503 Service Unavailable
Retry-After: 30
{ "code": "SERVICE_UNAVAILABLE", ... }
```

`@RestControllerAdvice` 에서 일관되게 매핑:

```java
@ExceptionHandler(CallNotPermittedException.class)
public ResponseEntity<ErrorResponse> circuitOpen(CallNotPermittedException e) {
    return ResponseEntity.status(503)
        .header("Retry-After", "30")
        .body(ErrorResponse.of("SERVICE_UNAVAILABLE", "downstream service unavailable", traceId()));
}

@ExceptionHandler(TimeoutException.class)
public ResponseEntity<ErrorResponse> timeout(TimeoutException e) {
    return ResponseEntity.status(504)
        .body(ErrorResponse.of("GATEWAY_TIMEOUT", "downstream timeout", traceId()));
}
```

---

## 8. 자가 검증 체크리스트

- [ ] Server A 에 **Bucket4j** 사용자당 **10 req/sec** (ADR-005) ?
- [ ] 멀티 인스턴스라면 **Redis backend** ?
- [ ] **Caffeine** 로 bucket Map 메모리 한계 (`maximumSize`) ?
- [ ] A→B 호출에 **Resilience4j Circuit Breaker** + **timeout 200ms** (ADR-001) ?
- [ ] CB OPEN / timeout 응답이 **503 / 504** + Retry-After ?
- [ ] Rate limit 응답이 **429** + Retry-After ?
- [ ] Tomcat `max-threads` 가 **vCPU 기반** (50 권장) ?
- [ ] HikariCP `maximumPoolSize` 가 **vCPU × 2~4** (10 이하) ?
- [ ] 5xx 응답 본문이 표준 ErrorResponse 형식 ?
- [ ] traceId 가 응답 헤더 + 본문 ?
- [ ] k6 stress 시나리오에서 **5xx 폭주 없음** + 429/503/504 만 발생 ?
- [ ] 메트릭(Micrometer) — rate limit reject count, CB OPEN duration, queue depth ?

---

## 9. 안티패턴 (CLAUDE.md §10 회귀)

| 안티패턴 | 문제 | 교정 |
|---|---|---|
| A→B 호출에 timeout / CB 없음 | 카스케이딩 실패 (CLAUDE.md §10 직접 위반) | Resilience4j |
| Tomcat max-threads 200 (1 vCPU) | 컨텍스트 스위치 폭주 | 50 |
| HikariCP poolSize 100 (1 vCPU) | DB connection 폭주 | 10 |
| 무한 큐 (`LinkedBlockingQueue` 기본) | OOM | 유한 큐 + 거부 |
| Rate limit 을 503 으로 응답 | 클라이언트 무한 재시도 | 429 |
| Retry-After 누락 | 클라이언트 가이드 없음 | 헤더 추가 |
| in-memory bucket Map 영구 보관 | 메모리 누수 | Caffeine LRU + TTL |
| Retry 가 CB 안쪽 | OPEN 무시하고 재시도 | 데코레이터 순서: Retry → CB → TimeLimiter → Bulkhead |
| Bucket4j 알고리즘 미정 / 근거 없음 | 평가 시 감점 | Token Bucket (버스트 허용) 명시 |
| Backoff 없는 Retry | 다운스트림 회복 방해 | 본 과제 retry 권장 안함 |

---

## 10. 다음 단계

- 알고리즘 한계 검증 → `k6-load-testing` (stress / spike)
- 한계 도달 시 → `concurrency` (DB 인덱스 / N+1) 또는 `cache-strategy` (sharding)
- 단일 인스턴스 한계 측정 → `capacity-planning`
- 결과 검토 → `code-reviewer` agent
