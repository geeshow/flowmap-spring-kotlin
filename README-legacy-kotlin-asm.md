# callgraph-sync

Kotlin/Spring 프로젝트의 **컴파일된 바이트코드**를 ASM으로 스캔해서
`Controller → Service → Repository → 외부호출` 관계를 **public 메서드 단위**로 추출하고,
**JSON 파일**과 **Postgres**에 동기화하는 독립 실행 도구입니다.
각 노드/엣지에 코드 위치(파일·라인)를 함께 담습니다.

## 무엇을 담나

- **노드**: 추적 대상 레이어(`@RestController`/`@Controller`/`@Service`/`@Repository`/`@Component`,
  그리고 Spring Data 리포지토리 인터페이스)의 **public, 비합성** 메서드.
  각 노드에 `source_file`, `source_path`, `line`(선언 위치).
- **엣지**: 메서드 간 호출. 각 엣지에 `call_site_line`/`call_site_file`(호출이 일어난 정확한 줄),
  그리고 `kind`(INTERNAL/EXTERNAL).
- **코드 path 두 가지 의미**:
  1. 노드·엣지마다 파일:라인 → IDE에서 바로 점프 가능
  2. nodes+edges 그래프 → 컨트롤러부터 리포지토리/외부호출까지의 **호출 경로**를
     그래프 탐색(예: `schema.sql`의 재귀 CTE)으로 복원

## 사전 조건

- 분석 대상 프로젝트가 **컴파일되어 있어야** 합니다 (`build/classes/...` 또는 jar).
- 디버그 정보(라인 넘버)가 켜져 있어야 합니다. Kotlin/Gradle 기본값이면 이미 포함됩니다.

## 빌드 & 실행

```bash
./gradlew installDist

# JSON만
./build/install/callgraph-sync/bin/callgraph-sync \
  --classes /path/to/your-app/build/classes/kotlin/main \
  --sources /path/to/your-app/src/main/kotlin \
  --out callgraph.json

# JSON + Postgres 동기화
./build/install/callgraph-sync/bin/callgraph-sync \
  --classes /path/to/app/build/classes/kotlin/main \
  --classes /path/to/app/build/libs/app.jar \
  --sources /path/to/app/src/main/kotlin \
  --out callgraph.json \
  --db jdbc:postgresql://localhost:5432/cg --db-user cg --db-pass cg
```

- `--classes` 는 디렉터리/jar 모두 가능하며 여러 번 줄 수 있습니다.
- `--sources` 는 `source_path`(실제 파일 경로) 해석용. 생략하면 패키지 기반 상대경로만 채워집니다.
- 외부호출 판별 대상(RestTemplate/WebClient/Feign/JdbcTemplate 등)은
  `Model.kt`의 `SpringSignatures.defaultExternalPrefixes`에서 자유롭게 조정하세요.

## 출력(JSON) 예시

```json
{
  "generatedAt": "...",
  "nodes": [
    { "id": "com.example.OrderService#place(...)", "fqcn": "com.example.OrderService",
      "method": "place", "layer": "SERVICE", "visibility": "public",
      "sourceFile": "OrderService.kt",
      "sourcePath": "src/main/kotlin/com/example/OrderService.kt", "line": 42 }
  ],
  "edges": [
    { "from": "com.example.OrderController#create(...)",
      "to": "com.example.OrderService#place(...)",
      "callSiteLine": 28, "callSiteFile": "OrderController.kt", "kind": "INTERNAL" }
  ]
}
```

## 동기화 전략

`PostgresSync`는 트랜잭션 안에서 `TRUNCATE` 후 일괄 insert 하는 **전체 스냅샷 교체** 방식입니다
(코드베이스의 "현재 상태"를 그대로 반영). 증분 동기화가 필요하면 run id를 키로
upsert + stale 삭제로 바꾸면 됩니다.

## Kotlin 관련 주의 (도구에 이미 반영됨)

- `$default`, `access$`, `$lambda`, `component1`, bridge/synthetic 메서드는 제외합니다.
- **inline 함수**는 호출 지점에 인라이닝되어 호출 엣지가 사라지거나 위치가 바뀔 수 있습니다(바이트코드 한계).
- **suspend 함수**는 시그니처가 변형됩니다. 디스크립터 표기는 바이트코드 기준입니다.
- top-level/확장 함수는 `XxxKt` 클래스의 static public 메서드로 잡힙니다.
- 정밀도가 더 필요하면 소스(PSI/Kotlin Analysis API) 기반으로 바꾸는 선택지가 있습니다.

## 더 가벼운 대안

직접 스캐너를 유지하기 부담되면, jQAssistant로 바이트코드를 Neo4j에 적재한 뒤
Cypher로 `INVOKES` + `visibility:'public'` + 레이어 라벨을 필터링해
그 결과만 JSON/DB로 export 하는 얇은 프로그램으로 대체할 수 있습니다.
이 경우 어노테이션·호출 관계·visibility 추출을 jQAssistant가 대신 해줍니다.
