# call-graph (Python)

Spring Kotlin/Java 프로젝트의 **호출/피호출 관계**를 소스 정적 분석으로 추출해
**node-link JSON**(`nodes[] + edges[]`)으로 출력하는 독립 실행 도구입니다.
특정 API/메서드를 검색하면 **BFS**로 그 대상의 호출(downstream)·피호출(upstream)
서브그래프를 뽑아냅니다.

루트의 `*.kt`(`README-legacy-kotlin-asm.md`)는 ASM 바이트코드 기반 초기 버전이었고,
이 Python 버전은 **소스 기반**이라 빌드 산출물 없이 동작하며 외부 의존성이 전혀 없습니다
(순수 stdlib).

> **Kotlin 버전**: 상수/`object`/`@Value`/`${...}` 참조를 의미 분석으로 따라가 외부 API의
> 실제 URL·HTTP 메서드·패키지까지 정확히 뽑는 재구현이 `kotlin-analyzer/`에 있습니다
> (동일한 node-link JSON 계약). 이 Python 버전은 의존성 0·오프라인 즉시 실행이 장점,
> Kotlin 버전은 정밀 URL 해석이 장점입니다.

## 요구사항

- Python 3.10+ (3.12에서 검증)
- 외부 패키지 불필요. 오프라인에서 즉시 실행.

## 분석 대상 레이아웃

```
.repo/<프로젝트명>/<모듈명>/.../*.kt|*.java
```

예: `.repo/sample-shop/order-api/...`, `.repo/<your-project>/<module>/...`
각 노드는 어느 `project`/`module`에서 왔는지 함께 기록됩니다.

## 사용법

```bash
# 1) 전체 분석 -> node-link JSON
python3 -m callgraph analyze --repo .repo --out graph.json
python3 -m callgraph analyze --repo .repo --project sample-shop          # 프로젝트 한정
python3 -m callgraph analyze --repo .repo --include-other                # 미주석 클래스도 포함

# 2) 특정 메서드/ API 검색 (BFS)
python3 -m callgraph search --graph graph.json --method placeOrder
python3 -m callgraph search --repo .repo --method "OrderService#placeOrder" \
        --direction both --depth 3 --out sub.json
#   --direction callees : 호출(다운스트림)만   callers : 피호출(업스트림)만   both : 둘 다
#   --method 는 메서드명 / "fqcn#method" / 부분문자열 매칭 지원

# 3) 요약 통계
python3 -m callgraph stats --graph graph.json
```

`search`는 `--graph`(미리 만든 JSON)나 `--repo`(즉석 분석) 둘 다 입력으로 받습니다.

## 산출물 스키마 (상세)

`analyze`는 두 가지 산출물을 만듭니다: **그래프 JSON**(`--out`)과 **레지스트리 JSON**
(`--registry`, 크로스-런 누적). 아래는 두 파일의 전체 스키마입니다.

### 1) 그래프 JSON (node-link)

최상위 봉투. `source`/`target` 키는 그래프 라이브러리(NetworkX, D3 등) node-link 관례를 따릅니다.

```jsonc
{
  "directed": true,
  "multigraph": true,
  "meta": { ... },        // 아래 meta 표 참고
  "nodes": [ {MethodNode}, ... ],
  "edges": [ {CallEdge}, ... ]
}
```

#### `meta` (분석 실행 정보)

| 키 | 의미 |
|---|---|
| `command` | `analyze` / `search` / `stats` |
| `repo`, `project` | 분석 대상 루트 / 한정한 프로젝트(없으면 null) |
| `files`, `nodes`, `edges` | 파싱한 파일 수 / 노드 수 / 엣지 수 |
| `query`, `roots`, `direction`, `depth` | (search 전용) 검색어/시작노드/방향/깊이 |

#### `MethodNode` (노드)

| 키 | 타입 | 의미 / 채워지는 경우 |
|---|---|---|
| `id` | string | 노드 고유 ID. 형식은 노드 종류별로 다름 — 아래 "노드 ID 형식" 참고 |
| `fqcn` | string | 패키지 포함 클래스명(리소스 노드는 식별자) |
| `method` | string | 메서드명(리소스 노드는 라벨: 토픽/테이블/`Redis`) |
| `layer` | enum | `CONTROLLER`·`SERVICE`·`REPOSITORY`·`COMPONENT`·`CONFIG`·`BATCH`·`EXTERNAL`·`RESOURCE`·`OTHER` |
| `visibility` | string | `public`·`private`·`protected`·`internal` |
| `async` | bool | `suspend` / `@Async`·`@Scheduled` / reactive 반환(`Mono`·`Flux`·`CompletableFuture`…) |
| `returnType` | string? | 반환 타입(제네릭 제거된 단순명) |
| `httpMethod` | string? | 컨트롤러 엔드포인트·외부/S2S 노드의 HTTP 메서드(`GET`/`POST`/…/`ANY`) |
| `endpoint` | string? | 컨트롤러: 풀 URL 경로(클래스 `@RequestMapping` + 메서드 매핑). 외부/S2S: 대상 경로 |
| `externalService` | string? | **EXTERNAL 노드**: Feign client name 또는 클라이언트 타입(RestTemplate 등) |
| `externalUrl` | string? | **EXTERNAL 노드**: 전체 외부 URL(base+path). `${...}` placeholder면 그대로 |
| `resourceType` | string? | **RESOURCE 노드**: `kafka-topic` / `redis` / `db-table` |
| `description` | string? | API 한글 설명(REST Docs 연동 시). S2S 타깃에도 전파됨 |
| `file`, `line` | string?, int? | 선언 위치(repo 상대경로 : 1-based 라인). 합성/스텁 노드는 null |
| `project`, `module` | string? | 출처: `.repo/<project>/<module>`. **노드 그룹핑·S2S 매칭의 키** |

#### 노드 ID 형식

| 종류 | `id` 예시 |
|---|---|
| 내부 메서드 | `com.acme.order.OrderService#placeOrder` |
| 외부(3rd-party) | `ext:RestTemplate#exchange`, `ext:PaymentClient#charge` |
| S2S 타깃(=provider의 내부 노드) | `com.acme.user.UserController#getUser` |
| Kafka 토픽 | `kafka:order.created` |
| DB 테이블 / JDBC | `db:table:orders`, `db:jdbc` |
| Redis | `redis` |

#### `CallEdge` (엣지)

| 키 | 타입 | 의미 |
|---|---|---|
| `source`, `target` | string | 호출자 / 피호출자 노드 `id` |
| `mode` | enum | `sync` / `async`(동기/비동기) |
| `kind` | enum | `internal`(같은 코드베이스) · `external`(3rd-party) · `s2s`(분석된 다른 서비스) · `batch`(배치 와이어링) · `resource`(Kafka/Redis/DB) |
| `relation` | string | `call` · `batch:step`·`batch:reader`·`batch:processor`·`batch:writer`·`batch:tasklet`·`batch:listener` · `kafka:produce`·`kafka:consume` · `redis:io` · `db:io` |
| `callSiteFile`, `callSiteLine` | string?, int? | 호출 지점(호출자 파일 : 라인) |

> 엣지 dedup 키 = `(source, target, relation, callSiteLine)`.

### 2) 레지스트리 JSON (`.flowmap/registry.json`)

여러 `analyze` 실행에 걸쳐 **노출 엔드포인트 + Kafka producer/consumer**를 누적합니다.
새 서비스를 분석하면 여기 기록된 기존 서비스와 자동으로 S2S/이벤트 연결됩니다.

```jsonc
{
  "version": 1,
  "services": { "<project>": { } },          // 분석된 적 있는 서비스 목록
  "endpoints": [                              // 서비스가 노출하는 HTTP 엔드포인트
    { "project": "user-service",
      "nodeId": "com.acme.user.UserController#getUser",
      "fqcn": "com.acme.user.UserController", "method": "getUser",
      "httpMethod": "GET", "endpoint": "/internal/users/{id}",
      "description": "사용자 단건 조회" }
  ],
  "kafka": {                                  // 토픽별 producer/consumer
    "order.created": {
      "producers": [ { "project": "order-service", "nodeId": "...#placeOrder",
                       "fqcn": "...", "method": "placeOrder", "layer": "SERVICE" } ],
      "consumers": [ { "project": "notification-service", "nodeId": "...#onOrderCreated",
                       "fqcn": "...", "method": "onOrderCreated", "layer": "COMPONENT" } ]
    }
  }
}
```

**S2S 매칭 규칙**: Feign 호출의 (HTTP 메서드, 경로)를 `endpoints`와 비교 — 경로는 `{var}`
정규화(`/users/{id}` == `/users/{userNo}`)하고, 여러 후보면 Feign `name`이 provider
`project`와 일치하는 것을 우선합니다. 매칭되면 `kind:"s2s"` 엣지로 그 provider 노드에 연결.
**Kafka**: 같은 토픽의 producer/consumer가 서로 다른 실행에서 등록돼도 공유 토픽 노드로 이어집니다.

## 동기 / 비동기 판별 규칙

엣지 `mode`는 다음 중 하나라도 만족하면 **async**:

- 호출 대상 메서드가 `suspend` 이거나 `@Async`/`@Scheduled` 가 붙음
- 호출 대상 반환 타입이 reactive/future (`Mono`,`Flux`,`CompletableFuture`,`Future`,`Deferred`,`Flow`,`Publisher`)
- 호출 지점이 코루틴/비동기 빌더 블록 안 (`launch`,`async`,`withContext`,`runBlocking`,`coroutineScope`,`supplyAsync`,`thenApplyAsync` ...)

그 외에는 **sync**. (예: 같은 `notify()`라도 `launch{}` 안에서 부른 호출은 async,
일반 호출은 sync 로 구분됩니다.)

## 스프링 배치 관계

`@Configuration @EnableBatchProcessing` 의 `@Bean` 메서드(Job/Step/Reader/Processor/
Writer/Tasklet 반환 또는 빌더 사용)를 BATCH 노드로 잡고, 빌더 와이어링을 엣지로 만듭니다:

```
settlementJob  -[batch:step]->      settlementStep
settlementStep -[batch:reader]->    settlementReader
settlementStep -[batch:processor]-> settlementProcessor
settlementStep -[batch:writer]->    settlementWriter
```

(`.start()/.next()/.flow()/.reader()/.processor()/.writer()/.tasklet()/.listener()`
인자로 참조되는 형제 `@Bean`을 추적. 빈 이름 기반 주입 파라미터도 매칭.)

## 호출 해석 방식 (휴리스틱)

타입 기반 해석입니다:

1. 수신자 변수 → 타입 해석: 지역변수 → 메서드 파라미터 → 생성자 DI 필드 → 클래스 필드
   (스프링 생성자 주입 패턴) 순으로 조회
2. 타입 → FQCN: import → 같은 패키지 → 선언된 타입 인덱스
3. 수신자 없는 호출은 자기 클래스(this) 기준으로 해석
4. `JpaRepository` 등 상속 메서드(`save`/`findById`/...)는 선언이 없어도 엣지로 연결
5. `RestTemplate`/`WebClient`/`@FeignClient`/`JdbcTemplate`/`KafkaTemplate` 호출은
   external 엣지(`ext:Type#method` 합성 노드)로 표시

외부 클라이언트/사내 HTTP/gRPC 패키지는 `callgraph/classify.py`의
`EXTERNAL_SIMPLE_TYPES` / `EXTERNAL_PREFIXES`에 추가하면 됩니다.

### 외부 API 호출의 URL 표현

외부 호출 노드(`ext:...`)는 가능한 만큼 호출 대상 URL을 담습니다:

- **`@FeignClient` 인터페이스** — URL이 정적으로 확정됩니다. 인터페이스의
  `@FeignClient(name=, url=)` 베이스에 메서드의 `@GetMapping("/path")` 등을 합쳐
  `httpMethod` + `endpoint`(외부 경로) + `externalUrl`(전체 URL) + `externalService`(name)에
  채웁니다. `url`이 `${...}` 프로퍼티 플레이스홀더면 그대로 보존합니다.
- **`RestTemplate`/`WebClient`** — 대상 URL이 보통 런타임 인자(`.uri(...)`)라 정적
  추출이 불가합니다. `externalService`(클라이언트 타입)만 채우고 `externalUrl`은 null.
  (정밀 해석은 `kotlin-analyzer/` 버전 사용)
- 호출 **지점**은 항상 엣지의 `callSiteFile:callSiteLine`(호출자 위치)에 남습니다.

## 알려진 한계

- 정규식+중괄호 추적 기반 휴리스틱이라, 관용적으로 포맷된 코드에 맞춰져 있습니다.
  한 줄 압축/매크로성 코드, 고차함수로 넘기는 메서드 참조 등은 놓칠 수 있습니다.
- 오버로드는 메서드명 단위로 합쳐집니다(시그니처 미구분).
- 같은 클래스에 동명 메서드가 있으면 stdlib/확장함수 호출이 자기 자신으로 잘못
  연결되어 드물게 self-loop가 생길 수 있습니다(실측 0.8% 수준). 진짜 재귀와
  구분이 어려워 보존합니다.
- 기본적으로 OTHER(미주석) 클래스는 노드로 만들지 않습니다(`--include-other`로 포함).
  단, tracked 노드가 호출하는 OTHER 메서드는 엣지 종점으로 끌려 들어옵니다.

## MSA: 서버 간 호출 · Kafka · Redis · DB

여러 서비스를 분석할 때, 한 서비스의 외부 호출을 **다른 분석된 서비스**로 연결하고
인프라(Kafka/Redis/DB) 사용 관계를 표현합니다. 각 노드는 `project`(= `.repo/<project>`)로
그룹핑됩니다.

### 크로스-런 레지스트리 (`.flowmap/registry.json`)

분석 결과(노출 엔드포인트, Kafka producer/consumer)를 누적 저장합니다. **동시에 분석할
필요가 없습니다** — 나중에 분석한 서비스가 기존에 분석된 서비스와 자동으로 연결됩니다.

```bash
# 각각 따로 분석해도 자동으로 이어진다
python3 -m callgraph analyze --repo .repo --project user-service          # 엔드포인트 등록
python3 -m callgraph analyze --repo .repo --project order-service         # user-service로 S2S 연결됨
python3 -m callgraph analyze --repo .repo --project notification-service  # order의 이벤트/ user S2S 연결됨
```

기본 레지스트리는 `.flowmap/registry.json`(`--registry`로 변경, `--no-registry`로 읽기 전용).

### Server-to-Server (S2S)

`@FeignClient`(/`@HttpExchange`) 호출이 **분석된 다른 서비스의 컨트롤러 엔드포인트와
매칭**(HTTP 메서드 + 경로, Feign `name`으로 서비스 확정)되면, `external`이 아니라
그 **provider 노드(프로젝트명 포함)** 로 `kind="s2s"` 엣지로 연결됩니다. 매칭 안 되면
기존처럼 `external`. 경로는 `{var}` 정규화로 비교(`/users/{id}` == `/users/{userNo}`).

### Kafka 이벤트 S2S

`kafkaTemplate.send("topic", …)` → `kafka:<topic>`(RESOURCE) 노드로 `kafka:produce`,
`@KafkaListener(topics=["topic"])` → `kafka:consume`. 토픽 노드는 **서비스 간 공유**되어
`producer → topic → consumer`가 이어집니다(레지스트리 경유, 별도 분석돼도 연결).

### Redis / DB

- Redis: `RedisTemplate`/`StringRedisTemplate` 호출 → `redis` RESOURCE 노드(`redis:io`).
- DB: `JpaRepository<Entity,…>` → `@Entity`/`@Table(name=)`을 따라 `db:table:<table>`
  RESOURCE 노드(`db:io`). 두 서비스가 같은 테이블을 쓰면 같은 노드로 보입니다.
- JdbcTemplate → `db:jdbc` RESOURCE 노드.

### API 한글 설명 (Spring REST Docs)

`--restdocs <generated-snippets dir>`를 주면, REST Docs 스니펫의 `http-request.adoc`에서
(메서드, 경로)를, 같은 폴더의 `description.adoc`(관례)에서 한글 설명을 읽어 컨트롤러
엔드포인트 노드의 `description`에 붙입니다. 이 설명은 레지스트리를 거쳐 S2S 엣지의 타깃까지
전파됩니다.

```bash
python3 -m callgraph analyze --repo .repo --project user-service \
        --restdocs .repo/user-service/build/generated-snippets
```

> 한계: 표준 REST Docs 스니펫엔 "설명" 필드가 없어, 한글 설명은 `description.adoc`를
> 명시적으로 남기는 관례가 필요합니다(테스트에서 커스텀 스니펫). 더 풍부한 설명이 필요하면
> `restdocs-api-spec`(OpenAPI 생성) 또는 springdoc의 `@Operation(summary=)` 연동이 대안입니다.

새 엣지 종류: `s2s`(서버 간), `resource`(kafka/redis/db). 새 노드 레이어: `RESOURCE`.
새 노드 키: `resourceType`(`kafka-topic`/`redis`/`db-table`), `description`.

## 코드 구조

```
callgraph/
  model.py        # MethodNode / CallEdge / CallGraph (node-link 직렬화)
  classify.py     # 레이어/외부/async/배치/Kafka/Redis/DB 시그니처 테이블
  sourceparse.py  # 주석·문자열 제거 후 클래스/함수/호출/Kafka·엔티티 파싱
  build.py        # 타입 기반 호출 해석 + 배치/S2S/리소스 와이어링 -> CallGraph
  registry.py     # 크로스-런 레지스트리(.flowmap/registry.json): 엔드포인트·Kafka 누적
  restdoc.py      # Spring REST Docs 스니펫 -> (메서드,경로)별 한글 설명
  search.py       # callers/callees BFS 서브그래프
  scanner.py      # .repo/<project>/<module> 워킹 + provenance
  cli.py          # analyze / search / stats
```
