# OnMaru Backend Release Evidence Directory

본 디렉토리는 OnMaru 백엔드 시스템의 각 Track 및 릴리스 게이트별 검증 증적(Evidence)을 체계적으로 보관하고 추적하는 공식 디렉토리입니다.

## 📁 증적 문서 구조

- [staging-acceptance-signoff.md](staging-acceptance-signoff.md): Backend 전체 Staging Release Gate 종합 평가 및 최종 인수 서명서
- [r1/README.md](r1/README.md): R1 한옥 카탈로그 및 TourAPI 공공데이터 동기화 릴리스 증적
- [r2/README.md](r2/README.md): R2 지도, 사용자 후기, Odii 오디오 및 Cloudflare R2 연동 증적
- [journey/README.md](journey/README.md): R3 Journey AI 탐색, SSE 실시간 스트리밍, OpenAPI 및 Fallback 증적
- [grafana-o02-checklist.md](grafana-o02-checklist.md): O02 Grafana Cloud 대시보드 및 Alert 라우팅 검증 체크리스트
- [benchmark.md](benchmark.md): Release tag부터 benchmark manifest, artifact link, promotion gate까지의 W4 증적

## 🚀 게이트 자동화 검증

전체 릴리스 게이트의 유효성과 필수 문서 정합성은 다음 테스트 스크립트를 통해 CI 및 배포 파이프라인에서 자동으로 검증됩니다:

```bash
node --test scripts/test/staging-release-gate.test.mjs
```
