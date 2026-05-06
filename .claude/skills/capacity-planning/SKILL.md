---
name: capacity-planning
description: 본 과제 인프라 사이징 산출 스킬. CLAUDE.md §2 부하 모델(평균 10,000 TPS, 사용자당 100 req/10s, 인스턴스당 500~1000 TPS) + 1 vCPU/2 GB 제약 위에서 k6 벤치마크 → Little's Law (L=λW) → 인스턴스 수 산식을 적용한다. p95/p99 안전 마진, 헤드룸, HikariCP/Tomcat 풀 사이즈, MySQL/Redis/Kafka 별도 사이징, USL 비선형 한계의 README 명시 표준을 다룬다. "사이징", "용량 산정", "TPS 계산", "인스턴스 수", "벤치마크", "Little's Law" 키워드가 나오거나 PRD 가 동접/TPS 같은 NFR 을 명시할 때 PROACTIVELY 사용한다.
---

# Capacity Planning — 인프라 사이징 (promotion)

본 스킬은 CLAUDE.md §3 ⑤ (인프라 사이징) 평가 항목과 §2 의 부하 모델에 직접 대응한다.

> 본 과제 부하 모델 (CLAUDE.md §2):
> - 총 사용자: **1,000명**
> - 사용자당 요청: **10초 내 100건**
> - 평균 도착률 λ = 1,000 × 100 / 10 = **10,000 TPS**
> - 인스턴스당 목표: **500 ~ 1,000 TPS**
> - 인스턴스: 1 vCPU / 2 GB (Server A, B, C 각각)
>
> ※ "100,000명 처리" 사이징은 README §10 에서 본 모델을 100배 확장해 별도 산출.

---

## 1. 사이징 4단계

```
1) 단일 노드 벤치마크 (k6)  →  TPS, p50/p95/p99, error_rate
2) 부하 모델 정의            →  10,000 TPS (CLAUDE.md §2) / 100k 명 확장 시 1,000,000 TPS
3) Little's Law 적용         →  N = ceil(λ / 단일 노드 TPS) × (1 + 헤드룸)
4) 저장소 별도 사이징        →  MySQL connection, Redis QPS, Kafka partition
```

각 단계의 **숫자와 근거** 가 README §9 (성능 검증) 와 README §10 (확장 계획) 에 들어가야 한다.

---

## 2. Little's Law

```
L = λ × W
```

| 기호 | 의미 |
|---|---|
| L | 시스템 in-flight 요청 수 |
| λ | 도착률 (TPS) |
| W | 평균 응답시간 (sec) |

### 2.1 본 과제 가정값 (예시)

```
λ_avg  = 10,000 TPS                           ← CLAUDE.md §2
λ_peak = 1.5 × λ_avg = 15,000 TPS             ← 피크 가정 (README §6 명시)
W_p95  = 200 ms                                ← Resilience4j timeout 과 정렬

L_peak = 15,000 × 0.2 = 3,000 동시 요청
```

### 2.2 단일 노드 TPS (k6 측정값)

```
Server A 단일 노드 (1 vCPU / 2 GB / HTTPS):
  목표: 500 ~ 1,000 TPS (CLAUDE.md §2)
  측정 (예시): TPS = 620, p50 = 45ms, p95 = 180ms, p99 = 410ms, error = 0%
              CPU 95%, RSS 1.4 GB
```

### 2.3 인스턴스 수

```
헤드룸 30% 가정:

처리량 기준:
N = ceil(λ_peak / 단일 TPS) × (1 + 헤드룸)
N = ceil(15,000 / 620) × 1.3
N = ceil(24.2) × 1.3
N = 25 × 1.3 = 33 인스턴스

p95 in-flight 기준 (보수적):
node_threads = 50 (Tomcat max-threads)
N_safe = ceil(L_peak / node_threads) × 1.3
       = ceil(3,000 / 50) × 1.3
       = 60 × 1.3 = 78 인스턴스

→ 두 산식 비교 후 보수적 값 (78) 채택. README 에 두 산식 모두 명시.
```

### 2.4 100,000명 동접 확장 (CLAUDE.md §12 Day 5 산출물)

```
λ_avg (100k) = 100,000 × 100 / 10 = 1,000,000 TPS
λ_peak       = 1,500,000 TPS

처리량 기준: N = ceil(1,500,000 / 620) × 1.3 = 3,150 인스턴스
p95 기준:    N = ceil(300,000 / 50) × 1.3 = 7,800 인스턴스

→ 7,800 인스턴스 (보수). + Server B / C / DB / Redis / Kafka 별도 사이징.
→ 본 규모는 USL contention 한계로 단일 클러스터 운영 불가능 → README 한계 명시.
```

---

## 3. 헤드룸 (여유율)

| 헤드룸 | 적합 |
|---|---|
| 20% | 안정 트래픽, 모니터링 충실 |
| **30%** | **본 과제 권장 기본** |
| 50% | 트래픽 변동 큼 |
| 100% | 미션 크리티컬 |

근거: GC pause, JIT warmup, 배포 회전, p95 vs 평균의 long tail.

---

## 4. p95/p99 기반 안전 마진

평균 W 대신 p95 사용:

```
N_safe = ceil(λ_peak × W_p95 / threads_per_node) × (1 + 헤드룸)
```

본 과제: 평균 W = 50ms 가정 시 13 인스턴스. p95 = 200ms 가정 시 78 인스턴스. **6배 차이**. p95 가 진짜다.

---

## 5. HikariCP / Tomcat 풀 사이즈 (1 vCPU)

### 5.1 HikariCP

```
poolSize = (core_count × 2) + effective_spindle_count
1 vCPU + RDS 환경: 5~10 권장
```

### 5.2 Tomcat

```
max-threads = peak_in_flight_per_node × safety
            = (3,000 / 78) × 1.5 ≈ 60
→ 50~60 시작
```

메모리 검산:
- 50 threads × 1MB stack = 50MB
- HikariCP 10 connections × 1MB native ≈ 10MB
- JVM heap 1.4GB + 기타 → 2GB 안에 fit ✅

### 5.3 application.yml

```yaml
server:
  tomcat:
    threads:
      max: 50
      min-spare: 10
    accept-count: 100
    max-connections: 1000

spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 3000
      max-lifetime: 1800000
```

---

## 6. 저장소 별도 사이징 (앱만 늘려도 DB 가 병목)

### 6.1 MySQL (Server A 의 요청 로그, Server C 의 쿠폰 마스터)

| 항목 | 계산 |
|---|---|
| max_connections | 인스턴스 수 × HikariCP poolSize × 1.2 |
| 본 과제 기본 (33 인스턴스 × 10) | 396 — MySQL 기본 151 초과 → **파라미터 상향 또는 ProxySQL** |
| disk IOPS | TPS × write 평균 page (4~8) |
| 본 과제 (Server C, 1k TPS write) | 4k~8k IOPS — gp3 SSD 충분 |
| disk size | 일일 row 수 × 평균 byte × 보존일 |
| 100k 확장 시 | sharding 검토 (user_id hash, 4 shard 시작) |

### 6.2 Redis (Server B 의 재고, Server A 의 Idempotency)

| 항목 | 계산 |
|---|---|
| QPS 한계 | 단일 노드 ~100k QPS (Lettuce + LAN) — k6 측정 필수 |
| 본 과제 기본 (15k TPS × 캐시 hit 80%) | 25~30k QPS — 단일 노드 OK |
| Memory | 키 수 × 평균 value byte × 1.5 |
| Idempotency 캐시 (24h × 10k TPS × 200byte) | ~17 GB — Redis Cluster 필요 |
| 100k 확장 시 | sharding 6 노드 (CLAUDE.md §5.2 stock sharding 과 별개) |

### 6.3 Kafka (B → C 비동기)

| 항목 | 권장 |
|---|---|
| topic | `coupon.issued` |
| partition | 인스턴스 수 × 2 (시작) — Consumer concurrency 와 정렬 |
| replication | 3 (운영) / 1 (로컬) |
| retention | 7 days (Outbox 가 SoT 라 짧아도 OK) |

---

## 7. README §9 (성능 검증) 표준 양식

```markdown
## 9. 성능 검증

### 9.1 측정 환경
- 1 vCPU / 2 GB / HTTPS / Keep-Alive on
- 워밍업 30s, 측정 60s
- k6 외부 머신에서 부하 발생

### 9.2 결과 (Server A 단일)
| 시나리오 | TPS | p50 | p95 | p99 | error |
|---|---|---|---|---|---|
| smoke (VU=1) | - | 12 | 22 | 35 | 0% |
| load (VU=100) | 620 | 45 | 180 | 410 | 0% |
| stress (VU 2x) | 750 | 120 | 480 | 1100 | 0.3% |

### 9.3 thresholds 미달 시 회귀
- p95 < 300ms 미달 → `concurrency/SKILL.md` (인덱스, N+1)
- error_rate > 1% → `rate-limiting-backpressure` 회귀
```

## 8. README §10 (확장 계획) 표준 양식

```markdown
## 10. 인프라 사이징

### 10.1 부하 모델 (CLAUDE.md §2)
- 1,000 명 × 사용자당 100 req / 10s
- 평균 λ = 10,000 TPS, 피크 1.5× = 15,000 TPS
- 인스턴스당 목표 500~1,000 TPS (1 vCPU / 2 GB)

### 10.2 단일 노드 측정값
(§9 표 인용)

### 10.3 산식 (Little's Law)
- 처리량 기준: N = ceil(15,000 / 620) × 1.3 = 33 인스턴스
- p95 기준:    N = ceil(3,000 / 50) × 1.3 = 78 인스턴스
- **채택**: 78 인스턴스 (보수)

### 10.4 100,000 명 확장 시
- λ_peak = 1,500,000 TPS
- N = ceil(300,000 / 50) × 1.3 = 7,800 인스턴스
- 본 규모는 단일 클러스터 한계 — region 분산 필요

### 10.5 저장소 사이징
- MySQL: 78 × 10 × 1.2 = 936 connections — sharding 또는 ProxySQL
- Redis: 30k QPS — 단일 노드. 100k 시 6 shard
- Kafka: 156 partitions (78 × 2)

### 10.6 한계 (USL)
본 계산은 선형 가정. 실제로는 contention (DB 락) / coherence (Kafka rebalance)
로 비선형. 200 인스턴스 단위로 USL 측정 후 재계획.

### 10.7 비용 (대략)
- 78 (A) + 78 (B) + 78 (C) = 234 인스턴스
- AWS m5.large 기준 월 약 $XX,XXX
- CDN/Edge 캐시 도입 시 30% 절감 가능
```

---

## 9. USL — 선형 가정의 한계 명시

```
C(N) = N / (1 + α(N-1) + β·N(N-1))
```

| 기호 | 의미 |
|---|---|
| α | contention (직렬화) — DB 락, Redis 단일 키 |
| β | coherence (동기화) — Kafka rebalance, cluster 동기화 |

5일 일정에서 USL 회귀 측정은 어렵다. **선형 가정임을 README 에 명시 + 한계 단서**.

---

## 10. 자가 검증 체크리스트

- [ ] **부하 모델** 이 README §6 또는 §10 에 명시 (CLAUDE.md §2 인용)
- [ ] **단일 노드 측정값** 이 README §9 에 표로 존재
- [ ] 측정 환경: 1 vCPU / 2 GB / HTTPS / 워밍업 30s / 측정 60s
- [ ] **산식** 이 README §10 에 명시 (Little's Law, 어느 가정)
- [ ] **헤드룸** 30% 명시
- [ ] p95 기준 산식 + 평균 기준 산식 둘 다 제시 후 보수 채택
- [ ] **앱 외 DB / Redis / Kafka 사이징** 도 함께 계산
- [ ] **선형 스케일 가정** 의 한계 명시 (USL)
- [ ] HikariCP / Tomcat 풀 사이즈가 vCPU 기반 산식
- [ ] 인스턴스 수 × poolSize 가 DB max_connections 안에 들어감 (또는 sharding 명시)
- [ ] **100k 확장** 시 한계 (region, sharding, 비용) 명시
- [ ] k6 결과 캡처 또는 summary JSON 이 PR 에 첨부

---

## 11. 안티패턴 (CLAUDE.md §10 회귀)

| 안티패턴 | 문제 | 교정 |
|---|---|---|
| 추정값으로 계산 (측정 없이) | "이 정도면 될 거 같다" | k6 측정 후 산식 적용 (CLAUDE.md §10 직접 위반) |
| 평균 응답시간만 사용 | p95 long tail 무시 | p95 기준 |
| 헤드룸 0% | 변동/배포/GC 흡수 못 함 | 30%+ |
| 앱만 사이징 | DB / Redis / Kafka 가 병목 | 모든 stateful 자원 사이징 |
| HikariCP 100 (1 vCPU) | DB connection 폭주 | 5~10 |
| max-threads 200 (1 vCPU) | 컨텍스트 스위치 폭주 | 50 |
| HTTP 로 측정 (운영 HTTPS) | TLS overhead 30~50% 누락 | HTTPS 측정 |
| 동일 머신에서 k6 + 앱 | CPU 경쟁 | 분리 |
| 워밍업 없이 측정 | JIT/cache 미반영 | 30s 워밍업 |
| USL 무시 | 1000+ 인스턴스에서 비선형 발견 | README 에 한계 명시 |

---

## 12. 다음 단계

- 측정 → `k6-load-testing`
- thresholds 미충족 → `concurrency` (DB 락 / N+1) 또는 `cache-strategy` (sharding)
- 진입 한계 → `rate-limiting-backpressure`
- 결과 검토 → `code-reviewer` agent
