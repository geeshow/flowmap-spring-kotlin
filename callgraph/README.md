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

## 출력 스키마 (node-link)

```jsonc
{
  "directed": true, "multigraph": true,
  "meta": { ... },
  "nodes": [
    { "id": "com.shop.order.OrderService#placeOrder",
      "fqcn": "com.shop.order.OrderService", "method": "placeOrder",
      "layer": "SERVICE",            // CONTROLLER|SERVICE|REPOSITORY|COMPONENT|CONFIG|BATCH|EXTERNAL|OTHER
      "visibility": "public",
      "async": false,               // suspend / @Async / reactive 반환 여부
      "returnType": "Order",
      "httpMethod": null,           // 컨트롤러 엔드포인트면 GET/POST/...
      "endpoint": null,             // 컨트롤러면 풀 URL 경로, 외부 Feign이면 외부 경로
      "externalService": null,      // 외부 노드: Feign client name / 클라이언트 타입
      "externalUrl": null,          // 외부 노드: 전체 외부 URL (baseUrl + path), 알 수 있을 때
      "file": "sample-shop/order-api/.../OrderService.kt", "line": 14,
      "project": "sample-shop", "module": "order-api" }
  ],
  "edges": [
    { "source": "...#placeOrder", "target": "...#save",
      "mode": "sync",               // sync | async  (동기/비동기)
      "kind": "internal",           // internal | external | batch
      "relation": "call",           // call | batch:step | batch:reader | batch:processor | batch:writer | batch:tasklet | batch:listener
      "callSiteFile": "...", "callSiteLine": 16 }
  ]
}
```

> `source`/`target` 키는 그래프 라이브러리(NetworkX, D3 등) node-link 관례를 따릅니다.

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

## 코드 구조

```
callgraph/
  model.py        # MethodNode / CallEdge / CallGraph (node-link 직렬화)
  classify.py     # 레이어/외부/async/배치 시그니처 테이블 (여기서 커스터마이즈)
  sourceparse.py  # 주석·문자열 제거 후 클래스/함수/호출/지역변수 파싱
  build.py        # 타입 기반 호출 해석 + 배치 와이어링 -> CallGraph
  search.py       # callers/callees BFS 서브그래프
  scanner.py      # .repo/<project>/<module> 워킹 + provenance
  cli.py          # analyze / search / stats
```
