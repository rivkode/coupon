---
name: cache-strategy
description: 본 과제(선착순 쿠폰)의 Redis 캐시/Hot Spot 회피 스킬. 재고 키 sharding (`event:{id}:stock:{0..9}`, ADR-003), TTL + jitter, request coalescing / probabilistic early expiration, Idempotency 응답 캐싱 (24h TTL), L1(Caffeine) + L2(Redis) 다단 (선택), negative caching 을 다룬다. "캐시", "Cache", "Hot Spot", "Cache Stampede", "Thundering Herd", "TTL", "request coalescing", "캐시 무효화" 키워드가 나오거나 PRD 가 "Hot Spot / 캐시" 평가 항목을 명시할 때 PROACTIVELY 사용한다.
---

# Cache Strategy — Hot Spot 과 Stampede 방어 (promotion)

본 스킬은 CLAUDE.md §3 ③ (캐시 + Hot Spot 해결) 평가 항목에 직접 대응한다.

> 본 과제 캐시 사용 지점:
> - **Server B 의 재고 카운터** (`event:{id}:stock:{0..9}`) — 단일 핫 키 분산 (Hot Spot)
> - **Server A 의 Idempotency 응답** (`idem:{userId}:{key}`) — TTL 24h
> - **Server B 의 쿠폰 코드 풀** (`event:{id}:codes:{0..9}`) — LIST, 사전 생성 (선택)

5일 일정에서 **재고 sharding 1가지 + TTL + jitter + Idempotency 캐싱** 만 코드로 증명. 나머지(L1 다단, PEE 등)는 README 트레이드오프로 흡수.

---

## 1. Hot Key — 재고 카운터 (CLAUDE.md §5.2 / ADR-003)

### 1.1 문제

```
event:{id}:stock  ← 단일 키
- 모든 사용자 트래픽이 이 한 키에 집중
- Redis Cluster 의 한 노드만 CPU 100%
- p95 응답시간 폭주
```

### 1.2 해결: 10개 shard 분할

```
event:{id}:stock:0     ← 1,000
event:{id}:stock:1     ← 1,000
...
event:{id}:stock:9     ← 1,000
                       총 10,000
```

각 사용자가 한 shard 만 접근 → 트래픽 1/10 분산.

### 1.3 shard 라우팅

```java
private int selectShard(String userId, int shardCount) {
    return Math.floorMod(userId.hashCode(), shardCount);
}
```

> **userId 해시 라우팅 vs 랜덤 라우팅**:
> - 해시: 같은 사용자가 같은 shard 만 조회 — 재시도 시 일관성 (권장)
> - 랜덤: 부하 균등 — 단, "한 사용자가 여러 shard 에 흩어 있는 재고를 다 못 봄" 문제 가능
> 본 과제는 사용자당 1쿠폰이 일반적이므로 **해시 라우팅** 권장.

### 1.4 재고 sharding 의 트레이드오프

| 트레이드오프 | 영향 | 대응 |
|---|---|---|
| 일부 shard 가 먼저 sold out | 사용자가 매진을 빨리 인지 | retry 시 다른 shard 시도 또는 랜덤 라우팅 |
| 메모리 N 배 (10 shard = 10 키) | 미미 (INTEGER 만 저장) | 무시 |
| 모니터링 복잡 | shard 별 메트릭 필요 | Micrometer tag |
| 전체 재고 합산 비싸짐 | `MGET event:{id}:stock:*` | 운영 도구에서만 필요 |

---

## 2. 재고 차감 — Lua script

`concurrency/SKILL.md §2.2` 참조. **반드시 atomic Lua** — GET → 검사 → DECR 순차 호출 금지.

---

## 3. Idempotency 응답 캐싱 (Server A)

### 3.1 키 / TTL

```
키:  idem:{userId}:{idempotencyKey}
값:  JSON ({status, body})  또는 직접 직렬화한 응답
TTL: 24시간 (사용자 재시도 합리적 윈도우)
```

### 3.2 코드

```java
@Service
@RequiredArgsConstructor
public class IdempotencyCache {
    private final RedisTemplate<String, String> redis;
    private static final Duration TTL = Duration.ofHours(24);

    public Optional<String> find(String userId, String key) {
        return Optional.ofNullable(redis.opsForValue().get(makeKey(userId, key)));
    }

    public void store(String userId, String key, String responseJson) {
        redis.opsForValue().set(makeKey(userId, key), responseJson, TTL);
    }

    private String makeKey(String userId, String key) {
        return "idem:" + userId + ":" + key;
    }
}
```

### 3.3 안티패턴

- TTL 무한 (영속) — Redis 메모리 폭주
- TTL 너무 짧음 (<1h) — 사용자 재시도 윈도우 안에 만료 → 멱등성 깨짐
- 응답 본문이 너무 큼 → Redis 메모리 빠르게 소진 (응답 압축 또는 요약 저장)

---

## 4. TTL 과 Jitter (Cache Cliff 방지)

여러 키가 같은 시점에 만료되면 한꺼번에 DB 로 폭주 (cache cliff).

```java
public void cache(String key, String value, Duration baseTtl) {
    long jitterSec = ThreadLocalRandom.current().nextLong(60);   // 0~60초 랜덤
    redis.opsForValue().set(key, value, baseTtl.plusSeconds(jitterSec));
}
```

본 과제에서 jitter 가 필요한 곳:
- 인기 promotion 가공 결과 (있다면)
- 사용자 세션/프로필 (있다면)
- Idempotency 캐시는 **24h TTL** 이므로 동시 만료 위험 낮음 — jitter 생략 가능

---

## 5. Cache Stampede 방어 — Request Coalescing

만료 직후 동시 요청이 DB 로 폭주.

본 과제에는 stampede 위험 지점이 적음. 인기 캠페인 정보 조회 같은 **읽기 캐시** 가 필요하면 다음 중 한 가지:

### 5.1 Caffeine AsyncCache (single-flight 자동)

```java
AsyncLoadingCache<String, Event> eventCache = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(5))
    .maximumSize(10_000)
    .buildAsync(eventId -> repo.findById(eventId).orElse(null));

public CompletableFuture<Event> findEvent(String eventId) {
    return eventCache.get(eventId);          // 같은 키 동시 호출 시 1번만 로드
}
```

### 5.2 분산 환경에서 mutex (Redis SETNX)

```java
public Event findEventWithLock(String eventId) {
    String key = "event:" + eventId;
    String cached = redis.opsForValue().get(key);
    if (cached != null) return mapper.fromJson(cached);

    String lockKey = "lock:" + key;
    Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(5));
    if (Boolean.TRUE.equals(locked)) {
        try {
            Event e = repo.findById(eventId).orElseThrow();
            redis.opsForValue().set(key, mapper.toJson(e), Duration.ofMinutes(5));
            return e;
        } finally {
            redis.delete(lockKey);
        }
    }
    Thread.sleep(50);
    return findEventWithLock(eventId);    // 짧은 대기 후 재시도 (다른 인스턴스가 채웠을 것)
}
```

> 5일 일정에서 stampede 방어가 시급하지 않으면 README §7 에 "단일 인스턴스 가정으로 stampede 방어 미구현, 운영 시 single-flight 도입" 한 줄.

---

## 6. L1 + L2 다단 캐시 (선택, Nice-to-have)

각 인스턴스가 짧은 L1 (Caffeine) + 분산 L2 (Redis) — Redis 트래픽 1/10 절감 효과.

```java
@Bean
public CacheManager twoLayerManager(RedisConnectionFactory cf) {
    CaffeineCache l1 = new CaffeineCache("events",
        Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(10))
            .build());

    RedisCache l2 = RedisCache.builder(cf)
        .ofTtl(Duration.ofMinutes(5))
        .build("events");

    return new CompositeCacheManager(l1, l2);
}
```

**규칙**:
- L1 TTL << L2 TTL (예: 10s vs 5m)
- 무효화 시 L1 + L2 동시 — 다른 인스턴스의 L1 은 Redis Pub/Sub 으로 broadcast (없으면 짧은 TTL 로 자연 만료)
- 본 과제는 **재고 자체를 캐시 하지 않음** (재고 = Redis 카운터가 SoT). L1+L2 는 **읽기 데이터** (캠페인 메타) 에만 적용

---

## 7. 캐시 무효화 — 본 과제 전략

| 데이터 | 무효화 방식 | 근거 |
|---|---|---|
| 재고 카운터 | TTL 없음 (이벤트 종료 후 수동 삭제 또는 긴 TTL) | SoT 가 Redis. 사라지면 안 됨 |
| Idempotency 응답 | TTL 24h | 사용자 재시도 윈도우 |
| 쿠폰 메타 (조회용) | TTL 5m + jitter, 변경 시 evict | 이벤트 정보가 변할 일 거의 없음 |
| Outbox poller offset | TTL 없음 (RDBMS 가 SoT) | 비동기 처리 |

---

## 8. Negative Caching (선택)

존재하지 않는 키 (예: 잘못된 promotion id) 를 반복 조회하는 공격 / 버그 방어:

```java
public Optional<Event> findEvent(String eventId) {
    String key = "event:" + eventId;
    String cached = redis.opsForValue().get(key);
    if ("__NULL__".equals(cached)) return Optional.empty();
    if (cached != null) return Optional.of(mapper.fromJson(cached));

    Optional<Event> result = repo.findById(eventId);
    if (result.isEmpty()) {
        redis.opsForValue().set(key, "__NULL__", Duration.ofSeconds(30));   // 짧은 TTL
    } else {
        redis.opsForValue().set(key, mapper.toJson(result.get()), Duration.ofMinutes(5));
    }
    return result;
}
```

> 5일 일정에서 negative caching 우선순위 낮음. 인증/Rate Limit 이 1차 방어.

---

## 9. promotion 과제 — 캐시 적용 정리

```
[Server A]
└─ Idempotency 응답 캐시 (Redis, TTL 24h)
└─ Rate Limit Bucket (Redis, Bucket4j ProxyManager)

[Server B]
├─ 재고 카운터 (Redis, sharded — event:{id}:stock:{0..9})
├─ 쿠폰 코드 풀 (Redis LIST, sharded — 선택, 사전 생성 시)
└─ Outbox (MySQL — 캐시 아님, RDBMS SoT)

[Server C]
└─ (캐시 사용 없음 — RDBMS 가 SoT)
```

---

## 10. 자가 검증 체크리스트

- [ ] 재고 키가 **shard** 분할 (`event:{id}:stock:{0..9}`) ?
- [ ] shard 라우팅이 **결정적** (userId 해시) ?
- [ ] 재고 차감이 **atomic Lua** (GET → DECR 분리 금지) ?
- [ ] Idempotency 캐시 TTL **24h** 명시 ?
- [ ] 모든 캐시 키에 **TTL** 설정 (영속 캐시 없음) ?
- [ ] 인기 데이터 (있다면) TTL **jitter** 적용 ?
- [ ] (선택) L1 + L2 다단 적용 — 메모리 limit (`maximumSize`) ?
- [ ] 캐시 메트릭 (hit ratio, miss ratio) Micrometer 노출 ?
- [ ] 무효화 트리거 명시 (TTL only / explicit evict) ?
- [ ] README §3 (③ 캐시 + Hot Spot) 에 sharding 결정 명시 ?

---

## 11. 안티패턴 (CLAUDE.md §10 회귀)

| 안티패턴 | 문제 | 교정 |
|---|---|---|
| 단일 Redis 키에 모든 재고 | Hot Spot, 한 노드 폭주 | 10 shard |
| GET 후 검사 후 SET | race condition | atomic Lua |
| TTL 무한 (Idempotency 캐시) | 메모리 폭주 | 24h TTL |
| TTL jitter 없음 (다중 키) | 동시 만료 → DB 폭주 | 0~60s jitter |
| stampede 무대응 | 만료 직후 DB N배 부하 | single-flight / mutex |
| L1 의 maximumSize 미설정 | OOM | LRU + size 제한 |
| 캐시를 SoT 로 사용 (재고 외) | 장애 시 비즈니스 결정 불가 | DB 가 SoT |
| 키 prefix 불일치 (`@CacheEvict` 누락) | stale 영구 | 표준 prefix + 명시적 evict |
| 캐시에 큰 객체 직접 저장 | Redis 메모리 폭주 | 직렬화 압축 또는 ID + DB 조회 |

---

## 12. 다음 단계

- 재고 차감 코드 → `concurrency/SKILL.md` §2 (Lua)
- Hot Spot 부하 검증 → `k6-load-testing` (spike + stress)
- Redis 한계 측정 → `capacity-planning/SKILL.md` §7.2
- 결과 검토 → `code-reviewer` agent
