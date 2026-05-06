---
name: concurrency
description: 본 과제(선착순 쿠폰)의 동시성/성능 처리 스킬. Redis Lua 기반 atomic 재고 차감 (ADR-003), Idempotency-Key (ADR-004), 쿠폰 사용(redeem)의 낙관적 락 (@Version, ADR-007), HikariCP 튜닝 (1 vCPU 기준), N+1 회피, 인덱스, batch insert (요청 로그) 를 다룬다. "동시성", "재고", "선착순", "쿠폰", "redeem", "락", "race condition", "성능 개선" 키워드가 나오거나 k6 thresholds 미충족 시 PROACTIVELY 사용.
---

# Concurrency — 선착순 쿠폰 동시성 처리

본 스킬은 두 시점에 사용한다:
1. **사전**: 새 동시 변경 코드를 작성하기 직전 (재고 차감, 쿠폰 사용)
2. **사후**: k6 thresholds 미충족 / 통합 테스트에서 race condition 이 보일 때

> CLAUDE.md §6 의 ADR-003 (재고는 Redis Lua), ADR-004 (Idempotency-Key), ADR-007 (redeem 낙관락) 를 본 스킬보다 우선 따른다.

---

## 1. 본 과제의 동시성 지점 3곳

| 지점 | 트래픽 형태 | 권장 도구 | ADR |
|---|---|---|---|
| 재고 차감 (issue) | 폭주 — 평균 10k TPS, 단일 키 Hot Spot | **Redis Lua (atomic)** + key sharding | ADR-003 |
| 멱등성 (issue, redeem) | 같은 키 재시도 | **Idempotency-Key** + UNIQUE constraint | ADR-004 |
| 쿠폰 사용 (redeem) | 한 쿠폰을 동시 사용 시도는 드뭄 | **낙관적 락 (@Version)** + 클라이언트 재시도 | ADR-007 |

**금지** (CLAUDE.md §10):
- ❌ 재고를 RDBMS row lock 으로 관리
- ❌ Redis GET 후 검사 후 SET (race condition)
- ❌ Kafka 메시지 처리에 멱등성 없음

---

## 2. 재고 차감 — Redis Lua script

### 2.1 키 설계 (CLAUDE.md §5.2)

10개 shard 분할로 단일 키 Hot Spot 회피:

```
event:{eventId}:stock:{0..9}     ← INTEGER, 각 1,000 (총 10,000)
event:{eventId}:codes:{0..9}     ← LIST, 사전 생성된 쿠폰 코드 풀 (선택)
```

사용자 ID 또는 random 으로 shard 결정 → 해당 shard 의 카운터에서만 차감.

### 2.2 Lua script

```lua
-- KEYS[1] = stock key (event:{id}:stock:{shard})
-- KEYS[2] = codes key (event:{id}:codes:{shard}) — 선택
-- ARGV[1] = decrement amount (1)
-- ARGV[2] = idempotency_key
-- 반환: {-1} = sold out, {0, code} = success

local remaining = tonumber(redis.call('GET', KEYS[1]) or '0')
if remaining <= 0 then
    return {-1}
end

redis.call('DECR', KEYS[1])
local code = redis.call('LPOP', KEYS[2])      -- 사전 생성 코드 풀에서 꺼냄
if not code then
    -- 코드 풀 비어있으면 UUID 로 생성
    code = ARGV[2] .. '-' .. tostring(redis.call('TIME')[1])
end
return {0, code}
```

### 2.3 Java 호출

```java
@Service
@RequiredArgsConstructor
public class CouponIssueService {
    private final RedisTemplate<String, String> redis;
    private final RedisScript<List<String>> issueScript;

    public IssueResult issue(String eventId, String userId, String idempotencyKey) {
        int shard = Math.floorMod(userId.hashCode(), 10);
        String stockKey = "event:" + eventId + ":stock:" + shard;
        String codesKey = "event:" + eventId + ":codes:" + shard;

        List<String> result = redis.execute(
            issueScript,
            List.of(stockKey, codesKey),
            "1", idempotencyKey
        );

        if ("-1".equals(result.get(0))) {
            return IssueResult.soldOut();
        }
        return IssueResult.success(result.get(1));
    }
}
```

**규칙**:
- 재고 차감과 코드 발급은 **반드시 같은 Lua 안에서**. 별도 호출이면 race condition.
- Outbox INSERT 는 Java 단에서 별도 트랜잭션으로 (Redis ↔ RDBMS 는 분리). Outbox INSERT 실패 시 Redis INCR 보상.

---

## 3. Idempotency-Key (ADR-004)

### 3.1 Server A 1차 캐시 (Redis)

```java
@Service
@RequiredArgsConstructor
public class IdempotencyService {
    private final RedisTemplate<String, String> redis;
    private static final Duration TTL = Duration.ofHours(24);

    public Optional<String> findCachedResponse(String userId, String key) {
        String cached = redis.opsForValue().get(idemKey(userId, key));
        return Optional.ofNullable(cached);
    }

    public void cacheResponse(String userId, String key, String responseJson) {
        redis.opsForValue().set(idemKey(userId, key), responseJson, TTL);
    }

    private String idemKey(String userId, String key) {
        return "idem:" + userId + ":" + key;
    }
}
```

### 3.2 Server C 최종 보장 (MySQL UNIQUE)

```sql
CREATE TABLE coupons (
    ...
    idempotency_key VARCHAR(64) NOT NULL,
    UNIQUE KEY uq_coupon_idem (idempotency_key)
);
```

```java
// Kafka consumer
@KafkaListener(topics = "coupon.issued")
public void onIssued(IssuedEvent event) {
    try {
        couponRepository.save(event.toCoupon());
    } catch (DataIntegrityViolationException e) {
        // UNIQUE 위반 = 같은 이벤트 재처리 — 정상 ack
        log.debug("duplicate event ignored: {}", event.idempotencyKey());
    }
}
```

---

## 4. 쿠폰 사용(redeem) — 낙관적 락 (ADR-007)

### 4.1 도메인 객체 (순수, JPA 어노테이션 없음 — CLAUDE.md §11)

```java
// server-c/.../domain/coupon/Coupon.java
public class Coupon {

    private final Long id;                 // null 가능 (신규 생성 시)
    private final String code;
    private final String userId;
    private final String eventId;
    private final String idempotencyKey;
    private final Instant issuedAt;
    private Instant usedAt;
    private long version;                  // 낙관적 락 (JpaEntity 와 정렬)

    private Coupon(Long id, String code, String userId, String eventId,
                   String idempotencyKey, Instant issuedAt, Instant usedAt, long version) {
        this.id = id;
        this.code = code;
        this.userId = userId;
        this.eventId = eventId;
        this.idempotencyKey = idempotencyKey;
        this.issuedAt = issuedAt;
        this.usedAt = usedAt;
        this.version = version;
    }

    public static Coupon issue(String code, String userId, String eventId,
                               String idempotencyKey, Instant issuedAt) {
        // 불변식 검증
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code required");
        return new Coupon(null, code, userId, eventId, idempotencyKey, issuedAt, null, 0);
    }

    /** Infrastructure 가 DB 에서 복원 시 사용 (불변식 검증 우회) */
    public static Coupon reconstitute(Long id, String code, String userId, String eventId,
                                      String idempotencyKey, Instant issuedAt,
                                      Instant usedAt, long version) {
        return new Coupon(id, code, userId, eventId, idempotencyKey, issuedAt, usedAt, version);
    }

    public void redeem(Instant now) {
        if (this.usedAt != null) {
            throw new CouponAlreadyUsedException(this.code);
        }
        this.usedAt = now;
    }

    public boolean belongsTo(String userId) { return this.userId.equals(userId); }
    // getters ...
}
```

### 4.2 JpaEntity (Infrastructure — DB 매핑 전용)

```java
// server-c/.../infrastructure/persistence/CouponJpaEntity.java
@Entity
@Table(name = "coupons", indexes = @Index(name = "idx_coupon_user", columnList = "user_id"))
public class CouponJpaEntity {

    @Id @GeneratedValue
    private Long id;

    @Column(unique = true, nullable = false, length = 40)
    private String code;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Version
    private long version;            // 낙관적 락 (ADR-007)

    protected CouponJpaEntity() { }  // JPA 요구

    // 매퍼에서 사용할 정적 팩토리 / getter / setter (제한적)
    static CouponJpaEntity from(Coupon c) {
        CouponJpaEntity e = new CouponJpaEntity();
        e.id = c.id();
        e.code = c.code();
        e.userId = c.userId();
        e.eventId = c.eventId();
        e.idempotencyKey = c.idempotencyKey();
        e.issuedAt = c.issuedAt();
        e.usedAt = c.usedAt();
        e.version = c.version();
        return e;
    }

    Coupon toDomain() {
        return Coupon.reconstitute(id, code, userId, eventId, idempotencyKey,
                                   issuedAt, usedAt, version);
    }
}
```

### 4.3 Repository 인터페이스 (Domain) + 구현 (Infrastructure)

```java
// Domain
public interface CouponRepository {
    Optional<Coupon> findByCode(String code);
    Coupon save(Coupon coupon);
}

// Infrastructure
@Repository
@RequiredArgsConstructor
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository jpaRepository;

    @Override
    public Optional<Coupon> findByCode(String code) {
        return jpaRepository.findByCode(code).map(CouponJpaEntity::toDomain);
    }

    @Override
    public Coupon save(Coupon coupon) {
        CouponJpaEntity saved = jpaRepository.save(CouponJpaEntity.from(coupon));
        return saved.toDomain();
    }
}

interface CouponJpaRepository extends JpaRepository<CouponJpaEntity, Long> {
    Optional<CouponJpaEntity> findByCode(String code);
}
```

### 4.4 Application Service (Domain 만 의존)

```java
@Service
@RequiredArgsConstructor
public class CouponRedeemService {
    private final CouponRepository repository;
    private final Clock clock;

    @Transactional
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public RedeemResult redeem(String code, String userId) {
        Coupon coupon = repository.findByCode(code)
            .orElseThrow(() -> new CouponNotFoundException(code));

        if (!coupon.belongsTo(userId)) {
            throw new CouponNotOwnedException(code);
        }

        coupon.redeem(Instant.now(clock));
        repository.save(coupon);   // version 충돌 → OptimisticLockingFailureException
        return RedeemResult.of(coupon);
    }
}
```

**규칙**:
- 비관적 락 (`PESSIMISTIC_WRITE`) 금지 — 트래픽 급증 시 락 경합 (ADR-007).
- 재시도 최대 3회 — 실패 시 클라이언트에 409 + retry 안내.
- `Clock` 주입으로 테스트 가능성 확보.

---

## 5. HikariCP 튜닝 (1 vCPU)

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10        # vCPU × 4 ~ 10 시작
      minimum-idle: 5
      connection-timeout: 3000     # 풀에서 못 받으면 3초 → 503
      validation-timeout: 1000
      idle-timeout: 600000
      max-lifetime: 1800000        # 30분 — DB 측 wait_timeout 보다 짧게
      leak-detection-threshold: 5000
```

- 1 vCPU 에서 100 으로 설정하면 컨텍스트 스위치 폭주 → TPS 하락 (CLAUDE.md §10 / `capacity-planning` §6).
- 인스턴스 수 × poolSize 가 MySQL `max_connections` 안에 들어가야 함.

---

## 6. Server A 의 요청 로그 batch insert

요청 로그를 매 요청마다 INSERT 하면 트랜잭션 폭주 → 진입 SLA 폭락. Batch / async 로:

```java
@Service
@RequiredArgsConstructor
public class IssueRequestLogger {
    private final JdbcTemplate jdbc;
    private final BlockingQueue<IssueRequestRow> queue = new LinkedBlockingQueue<>(10_000);

    @Async
    public void log(IssueRequestRow row) {
        if (!queue.offer(row)) {
            // 큐 가득 — 로그 손실 허용 (감사용이지 SoT 아님)
        }
    }

    @Scheduled(fixedDelay = 100)
    public void flush() {
        List<IssueRequestRow> batch = new ArrayList<>(500);
        queue.drainTo(batch, 500);
        if (batch.isEmpty()) return;

        jdbc.batchUpdate("INSERT INTO issue_request (...) VALUES (?, ?, ?, ?, ?)",
            batch, 500, (ps, r) -> {
                ps.setString(1, r.requestId());
                ps.setString(2, r.userId());
                // ...
            });
    }
}
```

큐 깊이는 유한 (`LinkedBlockingQueue<>(10_000)`), 초과 시 손실 허용 — 감사 로그용이라 SoT 가 아님.

---

## 7. N+1 회피 (특히 `GET /users/{userId}/coupons`)

```java
// ❌ N+1 위험 — 쿠폰마다 event 조회
List<Coupon> coupons = repo.findByUserId(userId);
return coupons.stream().map(c -> new CouponView(c, eventRepo.findById(c.eventId()).get())).toList();

// ✅ JPQL JOIN FETCH
@Query("SELECT c FROM Coupon c WHERE c.userId = :userId")
List<Coupon> findByUserId(@Param("userId") String userId);

// ✅ 또는 application 단 batch
List<Coupon> coupons = repo.findByUserId(userId);
Set<String> eventIds = coupons.stream().map(Coupon::eventId).collect(toSet());
Map<String, Event> events = eventRepo.findAllByIdIn(eventIds).stream()
    .collect(toMap(Event::id, identity()));
return coupons.stream().map(c -> new CouponView(c, events.get(c.eventId()))).toList();
```

검증 (테스트):
```java
@DataJpaTest
class CouponRepositoryTest {
    @Test
    void findByUserId_should_not_trigger_n_plus_one() {
        // given: 사용자 1명에 쿠폰 5장
        Statistics stats = ((SessionFactory) emf).getStatistics();
        stats.clear();

        repo.findByUserId("u1");

        assertThat(stats.getQueryExecutionCount()).isLessThanOrEqualTo(2);
    }
}
```

---

## 8. 인덱스 가이드 (Flyway 마이그레이션에 명시)

```sql
-- V1__init_coupons.sql
ALTER TABLE coupons ADD INDEX idx_coupon_user (user_id);
ALTER TABLE coupons ADD UNIQUE KEY uq_coupon_code (code);
ALTER TABLE coupons ADD UNIQUE KEY uq_coupon_idem (idempotency_key);

-- 요청 로그
ALTER TABLE issue_request ADD INDEX idx_request_user_created (user_id, created_at);
ALTER TABLE issue_request ADD UNIQUE KEY uq_request_idem (user_id, idempotency_key);

-- Outbox
ALTER TABLE coupon_outbox ADD INDEX idx_outbox_unpublished (published_at, created_at);
```

규칙:
- WHERE 절, JOIN 조건, ORDER BY 컬럼에 인덱스
- 복합 인덱스는 좌측 컬럼이 더 자주 단독 사용되는 순서
- `created_at` DESC 정렬이 흔하면 `(user_id, created_at DESC)` 같은 정렬 인덱스

---

## 9. 동시성 테스트

### 9.1 Lua script 동시 호출 테스트 (Testcontainers Redis)

```java
@Testcontainers
class StockLuaTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void concurrent_decr_should_not_oversell() throws Exception {
        // given: 10,000 재고 (1,000 × 10 shard)
        seedStock(10_000);

        int threads = 100;
        int requestsPerThread = 200;     // 총 20,000 요청 — 50% sold out 예상
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        IssueResult r = service.issue("ev1", "u" + j, UUID.randomUUID().toString());
                        if (r.isSuccess()) success.incrementAndGet();
                        else soldOut.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(60, TimeUnit.SECONDS);

        assertThat(success.get()).isEqualTo(10_000);   // 정확히 10,000장만 발급
        assertThat(soldOut.get()).isEqualTo(10_000);
    }
}
```

### 9.2 Redeem 낙관락 동시 사용 테스트

```java
@SpringBootTest
class CouponRedeemConcurrencyTest {
    @Test
    void concurrent_redeem_only_one_succeeds() throws Exception {
        // given: 한 쿠폰
        Coupon c = saveCoupon();

        int threads = 50;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try {
                    redeemService.redeem(c.code(), c.userId());
                    success.incrementAndGet();
                } catch (CouponAlreadyUsedException e) {
                    // expected for losers
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);

        assertThat(success.get()).isEqualTo(1);   // 정확히 1번만 사용 성공
    }
}
```

---

## 10. 자가 검증 체크리스트

- [ ] 재고 차감이 **Redis Lua script** (atomic) ?
- [ ] 재고 키가 **shard** 로 분리 (`event:{id}:stock:{0..9}`) ?
- [ ] Lua script 가 GET → DECR → CODE 발급을 **하나의 단위** 로 처리 ?
- [ ] Idempotency 1차 검사가 Server A 의 Redis 캐시 (TTL 24h) ?
- [ ] Server C 의 `coupons` 테이블에 `(idempotency_key)` UNIQUE ?
- [ ] Kafka consumer 가 UNIQUE 위반을 정상으로 ack ?
- [ ] redeem 에 `@Version` 낙관락 + `@Retryable` ?
- [ ] redeem 에 비관적 락 (PESSIMISTIC_WRITE) 사용하지 않음 ?
- [ ] HikariCP `maximumPoolSize` 가 **1 vCPU 기준 10 이하** ?
- [ ] Server A 의 요청 로그가 **batch insert** ?
- [ ] N+1 쿼리 없음 (특히 `/users/{id}/coupons`) — 테스트로 검증 ?
- [ ] 인덱스가 Flyway 마이그레이션에 명시 ?

---

## 11. 안티패턴 (CLAUDE.md §10 회귀)

| 안티패턴 | 문제 | 교정 |
|---|---|---|
| 재고를 RDBMS row lock 관리 | 1 vCPU 에서 즉사 | Redis Lua |
| Redis GET → 검사 → SET | race condition | Lua atomic |
| 단일 Redis 키 (`event:{id}:stock`) | Hot Spot | 10 shard |
| Idempotency 를 UNIQUE 만으로 | 사용자 500 / 모호한 에러 | A 의 1차 캐시 + 저장된 응답 재반환 |
| Kafka consumer 멱등성 부재 | 중복 발급 | UNIQUE constraint + INSERT IGNORE 패턴 |
| `@Transactional` 안에 외부 호출 | 트랜잭션 길어짐 | 외부 호출은 트랜잭션 밖 |
| redeem 에 비관적 락 | 트래픽 급증 시 경합 | 낙관락 + retry |
| `@Version` 만 추가, 재시도 없음 | 사용자에게 503 새어 나감 | `@Retryable` + 클라이언트 가이드 |
| `LocalDateTime.now()` 직접 호출 | 테스트 불가 | `Clock` 주입 |
| 1 vCPU 에 HikariCP 100 | 컨텍스트 스위치 폭주 | 3~10 시작 |
| `findAll()` 후 메모리 필터링 | 풀스캔 + 메모리 폭주 | Repository 메서드 푸시 다운 |

---

## 12. 다음 단계

- 재고 캐시 Hot Spot / 무효화 → `cache-strategy`
- 진입 흐름 흡수 (Bucket4j, Resilience4j) → `rate-limiting-backpressure`
- 부하 검증 → `k6-load-testing` (특히 stress + spike)
- thresholds 미충족 → 본 스킬 §7 (N+1) / §8 (인덱스) / `cache-strategy` 회귀
- 단일 노드 한계 → `capacity-planning`
