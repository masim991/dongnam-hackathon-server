# 동남권 이노베이션 해커톤

Claude Code + Cloudflare Workers 개발·배포 기본 틀입니다. 전체 목적은 해커톤 서비스 개발이며, 현재는 개발 착수 전 서버 기반 준비 단계(완료)입니다. API 시작점만 있고 서비스 화면, DB, 사용자 인증은 요구가 정해진 뒤 추가합니다.

기능은 `src/index.ts`(필요하면 `src/` 하위로 분리)에 추가하고 테스트는 `test/`에, DB·KV 같은 바인딩은 `wrangler.jsonc`에 추가합니다.

## 로컬 실행

Node.js 22.12 이상이 필요합니다.

```powershell
npm ci
npm run dev
```

서버에 표시된 주소의 `/api/health`에서 `{"ok":true,"service":"dongnam-hackathon"}` 응답을 확인합니다. 개발 서버는 로컬 컴퓨터에서만 접근합니다.

## 검증

```powershell
npm run typecheck
npm test
npm run build
```

`build`는 Cloudflare에 업로드하지 않는 배포 사전 검사입니다.

## Claude Code 연결

이 폴더에서 `claude`를 실행해 프로젝트를 확인하고 `.mcp.json` 서버를 승인합니다. 이후 다음 명령으로 Cloudflare OAuth 로그인을 시작합니다.

```powershell
claude mcp login cloudflare
claude mcp list
```

브라우저의 계정 및 권한을 확인하고 직접 승인합니다. 문서 MCP는 `cloudflare-docs`, 계정 API MCP는 `cloudflare`입니다. MCP 로그인과 Wrangler 로그인은 별개입니다.

## Cloudflare 배포 준비

```powershell
npm run cf:login
npm run cf:whoami
```

`cf:login`은 계정 조회와 Workers 스크립트·경로·로그 권한만 요청합니다. DB, 스토리지, 결제 권한은 요청하지 않습니다. 해당 기능을 추가할 때 필요한 권한으로 다시 로그인합니다.
`cf:whoami`에 다른 OAuth 권한이 없다는 경고가 나올 수 있습니다. 요청 범위를 좁힌 결과이며, 현재 로그인 상태와 실제 배포 성공 여부는 구분해서 확인합니다.

배포 전에 `wrangler.jsonc`의 Worker 이름과 대상 계정을 확인합니다. 배포 명령은 `npm run deploy`이며 공개 배포가 필요할 때 실행합니다. 현재 설정은 리소스나 유료 구독을 생성하지 않습니다.

로컬 비밀값은 `.dev.vars.example`을 `.dev.vars`로 복사한 뒤 입력합니다. 운영 비밀값은 `npx wrangler secret put 이름`으로 등록하고 소스에 작성하지 않습니다.

## 배포 현황

- 주소: https://dongnam-hackathon.magabus.workers.dev (Worker `dongnam-hackathon`, 계정 ID `dfe9c409…`)
- 재배포: `npm run typecheck`, `npm test`, `npm run build`를 통과한 뒤 `npm run deploy`
- 계정: `Magabus@naver.com's Account`(ID `dfe9c409…`). CLI와 MCP가 같은 계정(계정 1개)으로 확인됨
- 도메인: 계정 공통 workers.dev 서브도메인 `magabus`가 이미 등록되어 있어 변경하지 않았습니다. 연결된 커스텀 도메인(zone)은 없으며, 해커톤 기본 주소는 위 workers.dev HTTPS 주소입니다. 커스텀 도메인을 쓰려면 사용자가 도메인과 연결할 호스트명을 지정한 뒤 진행합니다.
- 2026-11-27 11:56(KST) 검증(외부 HTTPS): `GET /`와 `GET /api/health` 200·`application/json`·`{"ok":true,"service":"dongnam-hackathon"}`, `HEAD` 200·본문 없음, 없는 경로 404 `{"error":"Not found"}`, `POST` 405 `{"error":"Method not allowed"}`(`Allow: GET, HEAD`)
- 남은 문제: 현재 Wrangler 로그인은 `cf:login`보다 넓은 권한(D1, Pages, AI 등)으로 승인된 상태입니다. 권한을 줄이려면 `npm run cf:login`으로 다시 로그인합니다.
- 상세 결과: `CLOUDFLARE_SETUP_STATUS.md`

## 파일

- `src/index.ts`: API 진입점과 상태 확인
- `test/worker.test.ts`: 정상·오류 응답 검사
- `wrangler.jsonc`: Worker 설정
- `.mcp.json`: 프로젝트 전용 Claude MCP 서버
- `CLAUDE.md`: Claude Code 작업 안내

## 공식 문서

- [Cloudflare MCP](https://github.com/cloudflare/mcp-server-cloudflare)
- [Wrangler 설정](https://developers.cloudflare.com/workers/wrangler/configuration/)
- [Claude Code MCP](https://code.claude.com/docs/en/mcp)
