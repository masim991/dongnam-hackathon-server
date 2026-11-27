# 동남권 이노베이션 해커톤

- 전체 목적은 해커톤 서비스 개발이다. 현재는 개발 착수 전 Cloudflare 서버 기반 준비 단계이며 완료됐다(TypeScript + Workers API 기본 틀, workers.dev 배포·HTTPS 검증). 서비스 주제와 화면은 아직 정하지 않았고, 사용자 요구가 오면 이 기반 위에서 기능을 추가한다.
- 기능 추가 위치: API는 `src/index.ts`(라우팅이 커지면 `src/`에 파일 분리), 테스트는 `test/`, 바인딩(D1·KV 등)은 `wrangler.jsonc`. 요구가 정해지기 전에는 기능·DB 스키마·인증·프론트를 추측해서 만들지 않는다.
- 개발: `npm run dev`, 검사: `npm run typecheck`, `npm test`, `npm run build`.
- 상태 확인: `GET /api/health`. 실행 주소는 개발 서버 출력으로 확인한다.
- `src/index.ts`가 API 진입점이고 `wrangler.jsonc`가 배포 설정이다.
- `.mcp.json`에 Cloudflare 공식 API와 문서 MCP가 등록되어 있다. API MCP는 OAuth 승인이 필요하다.
- 토큰, 비밀번호, `.dev.vars`, `.env`는 Git이나 대화 출력에 포함하지 않는다.
- 공개 배포, 유료 서비스, 도메인 구매, 운영 데이터 변경은 대상과 범위에 대한 사용자 승인을 확인한다.
- 프론트엔드, DB, 인증, AI 모델은 서비스 요구가 정해진 뒤 필요한 것만 추가한다.
- 사용자 작업을 보존하고 한국어 존댓말로 간결하게 보고한다.
