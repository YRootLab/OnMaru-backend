# Changelog

## [0.4.0](https://github.com/YRootLab/OnMaru-backend/compare/v0.3.13...v0.4.0) (2026-09-27)


### Features

* **api:** FE 요청 API 계약과 JDBC 파이프라인 완성 ([fcf53ca](https://github.com/YRootLab/OnMaru-backend/commit/fcf53caacf53f1b0c8f30333c6a09d645d20f0d8))
* **api:** 위치 기반 수결첩 API 추가 ([192121b](https://github.com/YRootLab/OnMaru-backend/commit/192121b9d4704f48143c7f20073e7ea2d1432b64))
* **api:** 익명 수결 랭킹 API 추가 ([af7b59e](https://github.com/YRootLab/OnMaru-backend/commit/af7b59e6dd05fbb483b4903090b9739fabc15c5a))
* **audio:** broaden Odii curated synchronization ([551b8fa](https://github.com/YRootLab/OnMaru-backend/commit/551b8fa4bb7c4f68171fd7b002ffee8502aaa17b))
* **audio:** Odii 전체 스토리 수집 및 제외 정책 적용 ([#362](https://github.com/YRootLab/OnMaru-backend/issues/362)) ([2843c8c](https://github.com/YRootLab/OnMaru-backend/commit/2843c8c2dd86e5f4e105f05446a5f2e4886a7f6d))
* **audio:** 광역 지역 그룹별 오디오 스토리 카운트 조회 API 구현 ([04333f6](https://github.com/YRootLab/OnMaru-backend/commit/04333f657d081404bf55c4d59b2986d2967dbe72))
* **audio:** 광역 지역 그룹별 오디오 스토리 카운트 조회 API 구현 ([#306](https://github.com/YRootLab/OnMaru-backend/issues/306)) ([2d1f649](https://github.com/YRootLab/OnMaru-backend/commit/2d1f649000de281dbd6f9257547d951a1adaf86b))
* **audio:** 소리마루 Single Source of Truth API 구현 ([942e3d0](https://github.com/YRootLab/OnMaru-backend/commit/942e3d0c1d20a376a2924a5e4435f8d782ec8690))
* **audio:** 소리마루 검색 근처 추천 API 추가 ([679b1e9](https://github.com/YRootLab/OnMaru-backend/commit/679b1e90bcab9391d48ac82e9700f36f02fedfc3))
* **benchmark:** [#336](https://github.com/YRootLab/OnMaru-backend/issues/336) release metadata와 evidence manifest 구현 ([e0954ee](https://github.com/YRootLab/OnMaru-backend/commit/e0954ee8eb5518572ca85b04f3830dc5842a2088))
* **benchmark:** [#338](https://github.com/YRootLab/OnMaru-backend/issues/338) release benchmark workflow 통합 ([d580db6](https://github.com/YRootLab/OnMaru-backend/commit/d580db6209967da137405821f1d2a2f179cad999))
* **benchmark:** [#338](https://github.com/YRootLab/OnMaru-backend/issues/338) release benchmark workflow 통합 ([e22d28a](https://github.com/YRootLab/OnMaru-backend/commit/e22d28ae77423e6aa39bf669aea59a70b8076206))
* **benchmark:** add serial verify baseline manifest ([#371](https://github.com/YRootLab/OnMaru-backend/issues/371)) ([afe6349](https://github.com/YRootLab/OnMaru-backend/commit/afe6349462e5fd224fb63f70c5f8e4b7a3294628))
* **benchmark:** collect CI baseline evidence ([#389](https://github.com/YRootLab/OnMaru-backend/issues/389)) ([6a6b1de](https://github.com/YRootLab/OnMaru-backend/commit/6a6b1de6ad3aac1a93f59994d70fa5ee45e988be))
* **benchmark:** implement release metadata and evidence manifest ([43891a9](https://github.com/YRootLab/OnMaru-backend/commit/43891a94cbb5c9acc0da2e85c51fb3de877a366d))
* **benchmark:** pipeline-toolkit adapter 구현 ([32cde18](https://github.com/YRootLab/OnMaru-backend/commit/32cde1846e4b617e9a67cfc885fed80eab62d671))
* **benchmark:** pipeline-toolkit adapter 구현 ([b6f5cbb](https://github.com/YRootLab/OnMaru-backend/commit/b6f5cbbcf0a3b455d7958b19ef9ae51a21af1601))
* **catalog:** TourAPI와 Odii 전체 데이터 적재 ([d3eab57](https://github.com/YRootLab/OnMaru-backend/commit/d3eab57e05a313d2c18807c77d7a56f1e761ca41))
* **catalog:** TourAPI와 Odii 전체 데이터 적재 ([ae38050](https://github.com/YRootLab/OnMaru-backend/commit/ae3805066ace067fe1425fbd212fab259bc52c80))
* **ci:** Toolkit module benchmark shadow caller 추가 ([b74fe87](https://github.com/YRootLab/OnMaru-backend/commit/b74fe8753d79e89ca5ec2f344ef8091d24cab0e8))
* **ci:** Toolkit module benchmark shadow caller 추가 ([7ced735](https://github.com/YRootLab/OnMaru-backend/commit/7ced735d62763c2777de03f33422d44b8f3fd19f))
* FE 요청 API 계약과 JDBC 파이프라인 완성 ([8fd899d](https://github.com/YRootLab/OnMaru-backend/commit/8fd899d9e8eb6570c2708c6a99ab731c47c5ae98))
* **insights:** DataLab registry와 staging smoke 구축 ([#401](https://github.com/YRootLab/OnMaru-backend/issues/401)) ([764c2c1](https://github.com/YRootLab/OnMaru-backend/commit/764c2c187f351fa7d49e172fd5187abb408a74e0))
* **release:** connect trend evidence to toolkit ([#387](https://github.com/YRootLab/OnMaru-backend/issues/387)) ([3ee5122](https://github.com/YRootLab/OnMaru-backend/commit/3ee51228b82df3db3f2d73dd6bcad3567e152264))
* **saved,audio:** 찜 데이터 Neon DB 영속화(V017)와 이번 주 인기 한옥 소리 TOP N API ([42f928c](https://github.com/YRootLab/OnMaru-backend/commit/42f928c036a409a3ab739b9ef9f34d73cf5f2a5a))
* **saved,audio:** 찜 영속화(V017)와 이번 주 인기 한옥 소리 TOP N API ([#318](https://github.com/YRootLab/OnMaru-backend/issues/318),[#317](https://github.com/YRootLab/OnMaru-backend/issues/317)) ([8faa65e](https://github.com/YRootLab/OnMaru-backend/commit/8faa65e26626f5f8846532b4efede4e011ee1e8e))
* **stamp:** PostGIS 체크인 영속 파이프라인 구현 ([6ea8b90](https://github.com/YRootLab/OnMaru-backend/commit/6ea8b9023f1946f63969c4926c7681afc6b98e8c))
* **stamp:** 공개 랭킹 JDBC 집계 구현 ([041392a](https://github.com/YRootLab/OnMaru-backend/commit/041392a25afc735b0742e179ea16f77f855e6523))
* **stamp:** 로그인 회원용 한옥 수결첩 구현 ([ae5ba28](https://github.com/YRootLab/OnMaru-backend/commit/ae5ba28df8a841cea1139208349faa505e2e856b))
* **stamp:** 수결첩 관계형 스키마 추가 ([1a39a4d](https://github.com/YRootLab/OnMaru-backend/commit/1a39a4dcffc7a264e7a43ab28f24aa384ef0dc2a))
* **stamp:** 위치 체크인과 수결 지급 도메인 구현 ([dbe6e68](https://github.com/YRootLab/OnMaru-backend/commit/dbe6e68346f090cf6d5474171bc3544ea7eb7016))
* **stamp:** 익명 랭킹 도메인 추가 ([afd0a22](https://github.com/YRootLab/OnMaru-backend/commit/afd0a22de6301b62ed4a922b0ac2e7d9aacc2b4f))
* **stamp:** 익명 랭킹 참여 스키마 추가 ([a79e7c3](https://github.com/YRootLab/OnMaru-backend/commit/a79e7c39d7dedfc8992dc403c7ba3098b9215137))


### Bug Fixes

* **ai:** AI 이미지 pip CVE 및 Trivy 스캔 게이트 통과를 위한 예외 처리 ([a496a37](https://github.com/YRootLab/OnMaru-backend/commit/a496a3779765e942dd179e125ccea87925dfc1fd))
* **ai:** pip 업그레이드 및 Trivy vendored-pkg 오탐 예외로 이미지 스캔 게이트 통과\n\n- ai/Dockerfile: 시스템 pip을 26.2.1로 업그레이드해 CVE-2026-8643(HIGH) 해소\n- .trivyignore 추가: pip vendored msgpack(GHSA-6v7p-g79w-8964)과 오탐 setuptools(CVE-2025-47273) 근거 문서화\n- deploy.yml AI 이미지 스캔에 trivyignores 연결\n- 로컬 검증: docker build + trivy CRITICAL,HIGH --exit-code 1 → EXIT 0\n\nCloses [#314](https://github.com/YRootLab/OnMaru-backend/issues/314) ([9eeb852](https://github.com/YRootLab/OnMaru-backend/commit/9eeb8521b9077b57262c622db1e3402a4992750b))
* **ai:** setuptools 83.0.0+ 명시 설치로 CVE-2026-59890 이미지 스캔 게이트 통과 ([de30c14](https://github.com/YRootLab/OnMaru-backend/commit/de30c14d5409d149a827a27aacb34e43a27b1573))
* **ai:** setuptools 83.0.0+ 명시 설치로 CVE-2026-59890 이미지 스캔 게이트 통과 ([78a3b8c](https://github.com/YRootLab/OnMaru-backend/commit/78a3b8ca51dd16f3ef1b8a1b69748bbef583619a))
* **api:** 운영 한옥 저장소 wiring 수정 ([7f473cd](https://github.com/YRootLab/OnMaru-backend/commit/7f473cdda1e9adc924c5f1eed7c8b67a058e9c63))
* **api:** 운영 한옥 저장소 포트 주입 ([3a2003c](https://github.com/YRootLab/OnMaru-backend/commit/3a2003cda633a40cb61a9f7ca43d68f4d6fb13eb))
* **api:** 운영 한옥과 전국 히트맵 계약 복구 ([7ac5557](https://github.com/YRootLab/OnMaru-backend/commit/7ac5557e8e19e01c1f0166ca28c4ccb52bb91ccd))
* **api:** 운영 한옥과 전국 히트맵 계약 복구 ([0c8c344](https://github.com/YRootLab/OnMaru-backend/commit/0c8c3440fbebce3759187fac2dc9100e8d74f0e4))
* **audio:** [#440](https://github.com/YRootLab/OnMaru-backend/issues/440) 소리마루 Odii 조회 502 해결 ([7c9f09b](https://github.com/YRootLab/OnMaru-backend/commit/7c9f09b841cf94a4494cd6dcc366bf4cc84208f8))
* **audio:** initialize Odii dataset before scheduled sync ([1470b85](https://github.com/YRootLab/OnMaru-backend/commit/1470b850c04ed83efa6bd2248e53eb691bc4aab2))
* **audio:** Odii 동기화 적재 및 큐레이션 보강 ([a7611b5](https://github.com/YRootLab/OnMaru-backend/commit/a7611b53e7db2928f86c224ab93bfeb85a0e2905))
* **audio:** Odii 스냅샷 조립 성능 개선 ([6962ddf](https://github.com/YRootLab/OnMaru-backend/commit/6962ddf5b482c30111e914046c3a3b57be2f8626))
* **audio:** PR 계약과 변경 범위 정리 ([f0fa1b3](https://github.com/YRootLab/OnMaru-backend/commit/f0fa1b3bd918dd73a56c16af8b30e9437e1efbf0))
* **audio:** 관광공사 Odii CDN 기본 허용 ([ee9d11c](https://github.com/YRootLab/OnMaru-backend/commit/ee9d11c4b810c93350919fbbc4aef5938a77fd5a))
* **audio:** 빈 Odii 호스트 설정 정규화 ([d8af6d7](https://github.com/YRootLab/OnMaru-backend/commit/d8af6d72f16adc5c322cbe48f42c05b2109c32f9))
* **audio:** 빈 Odii 호스트 설정 정규화 ([5bdaf05](https://github.com/YRootLab/OnMaru-backend/commit/5bdaf052dfca41b76a801b2e6c586f65de98cdae))
* **audio:** 운영 Odii JDBC 저장소 선택 보장 ([#405](https://github.com/YRootLab/OnMaru-backend/issues/405)) ([acb436e](https://github.com/YRootLab/OnMaru-backend/commit/acb436efc7b43afbe241dbb264ed6533e39875e1))
* **audio:** 운영 Odii 재생 URL 허용 ([da1391c](https://github.com/YRootLab/OnMaru-backend/commit/da1391c42be5dbc2a65cbd111e60ba9b1a1015b3))
* **benchmark:** stabilize timeout fixture ([f27d048](https://github.com/YRootLab/OnMaru-backend/commit/f27d048d9634d3ec4f5893d8b79c1c112d91ae31))
* **ci:** AI 이미지 미수정 취약점으로 배포 차단 해소 ([8aba126](https://github.com/YRootLab/OnMaru-backend/commit/8aba12696c77e4c2f4a8fc4b80148398c23f1df6))
* **ci:** bootstrap uv for AI module benchmark ([#379](https://github.com/YRootLab/OnMaru-backend/issues/379)) ([f5b75f0](https://github.com/YRootLab/OnMaru-backend/commit/f5b75f0dff536358890344383bfd33791ac99607))
* **ci:** ignore unfixed AI image vulnerabilities ([65523b9](https://github.com/YRootLab/OnMaru-backend/commit/65523b93b80c543edbc69ae4c98e17a93af79b8e))
* **ci:** make staging scan and rollback gates deterministic ([27f8775](https://github.com/YRootLab/OnMaru-backend/commit/27f8775df746bf346e8e5270e9f8b1b53214f2ed))
* **ci:** Toolkit v0.1.2 SHA로 caller 갱신 ([c687b40](https://github.com/YRootLab/OnMaru-backend/commit/c687b4033bab1ae3509f10ad17e41cbfd1cb498c))
* **ci:** Trivy SARIF 심각도 필터 적용 ([8bfde96](https://github.com/YRootLab/OnMaru-backend/commit/8bfde96d55fa63e6d7c5f2668879ae4df0e51cc1))
* **ci:** Trivy SARIF 심각도 필터 적용 ([a40cde2](https://github.com/YRootLab/OnMaru-backend/commit/a40cde273bf4aa146a213007b3db89de137ba914))
* **ci:** 배포 계약 검증 hotfix를 develop에 반영 ([55e623d](https://github.com/YRootLab/OnMaru-backend/commit/55e623d3deba75524f0e28e0ea462057cceab0c1))
* **ci:** 배포 계약 검증 의존성 설치 ([6464e1b](https://github.com/YRootLab/OnMaru-backend/commit/6464e1b986393fce3c70c1ed0c5b0dd13b8b99fe))
* **ci:** 배포 계약 검증 의존성 설치 ([86e0811](https://github.com/YRootLab/OnMaru-backend/commit/86e08118b3d5377e0d19ab07a9cc912387dde003))
* **cors:** allow Render frontend Odii requests ([4ee0ff7](https://github.com/YRootLab/OnMaru-backend/commit/4ee0ff78907af76274fae57ec7c913dec76676d3))
* **cors:** Render 프론트 Odii API 403 차단 해소 ([cf50bb0](https://github.com/YRootLab/OnMaru-backend/commit/cf50bb0e6e18871675d35144b31ba6c72a919978))
* **cors:** Vercel 운영 도메인 허용 ([38562a6](https://github.com/YRootLab/OnMaru-backend/commit/38562a62b7dea2c8dc93d6002cd91cd0ab10e48f))
* **cors:** Vercel 운영 도메인에서 API 호출 허용 ([3489135](https://github.com/YRootLab/OnMaru-backend/commit/3489135b7b82a361b305727c36ff1d06d8240462))
* **datalab:** 마지막 페이지에서 수집 종료 ([f770d2f](https://github.com/YRootLab/OnMaru-backend/commit/f770d2f73c67ffb22f2508ee248e590d52066129))
* **datalab:** 시군구 마지막 페이지 수집 종료 복구 ([fb4ec86](https://github.com/YRootLab/OnMaru-backend/commit/fb4ec8692c2b95de0eaa6daacbab227dc282d1fc))
* **db:** V017 migration registry 등록 ([ece8d5d](https://github.com/YRootLab/OnMaru-backend/commit/ece8d5d0da035f6e1d1db1c39d34f549f5e547e3))
* **db:** 복원 드릴과 DataLab seed 호환 ([ab70089](https://github.com/YRootLab/OnMaru-backend/commit/ab70089e6581a956aa070ca07bf6dca81dfc303a))
* **db:** 수결첩 마이그레이션 예약 버전 정합성 보강 ([3461b57](https://github.com/YRootLab/OnMaru-backend/commit/3461b5787ae01ab54bd702965911d4d687094bf6))
* **deploy:** Spring 기본 production 프로필 적용 ([259a2c7](https://github.com/YRootLab/OnMaru-backend/commit/259a2c736965052b6b59c2f1724badb72589deca))
* **deploy:** Trivy SARIF 업로드 category 중복으로 staging 배포 실패하는 문제 수정 ([e24bb56](https://github.com/YRootLab/OnMaru-backend/commit/e24bb565aa5fc31f484a314d38170f2d657aa4bc))
* **deploy:** Trivy SARIF 업로드 category 중복으로 staging 배포 실패하는 문제 수정\n\n- Spring API/ai-service SARIF 업로드에 각각 고유 category 지정\n- handoff.md에 릴리즈 v0.3.13 세션 결과 기록\n\nCloses [#310](https://github.com/YRootLab/OnMaru-backend/issues/310) ([e69095f](https://github.com/YRootLab/OnMaru-backend/commit/e69095fd7eafa4b3f055c69b7fe2ff353a6a938a))
* **deploy:** 운영 이미지 production 프로필 기본 적용 ([382f21d](https://github.com/YRootLab/OnMaru-backend/commit/382f21d3b4fcaeac3375f6dd62ed4baa26d31a28))
* **deploy:** 운영 이미지 production 프로필 기본 적용 ([ef0bfd9](https://github.com/YRootLab/OnMaru-backend/commit/ef0bfd95dbf43b27ed62374c2795a3f3940db902))
* **home:** 인기 소리 시간 조건 바인딩 수정 ([ca80116](https://github.com/YRootLab/OnMaru-backend/commit/ca801161a24484aa9a96c440934b102b676dec0c))
* **release:** [#430](https://github.com/YRootLab/OnMaru-backend/issues/430) 운영 조회 hotfix를 develop에 역반영 ([75c162c](https://github.com/YRootLab/OnMaru-backend/commit/75c162cf9028c483e99b9b3f677b169501891367))
* **release:** master 이력을 release/0.3.24에 동기화 ([6bf75de](https://github.com/YRootLab/OnMaru-backend/commit/6bf75de9ec54e6a60c983ab77a20c1b2483713de))
* **release:** 누락된 master 이력을 develop에 역병합 ([726cdf9](https://github.com/YRootLab/OnMaru-backend/commit/726cdf924ad8fc19c8be1f780ba1c7a6583bde43))
* **retention:** 수결 삭제 원장 배치 경계 보장 ([c79741b](https://github.com/YRootLab/OnMaru-backend/commit/c79741b4aa28bf9a4cbb30ade2593a8a021e7e43))
* **retention:** 체크인 멱등성 개인정보 삭제 ([7efce53](https://github.com/YRootLab/OnMaru-backend/commit/7efce533834e7c6cc1993b813b7090ecce4586cf))
* **stamp:** production 체크인 경계 보강 ([dac4567](https://github.com/YRootLab/OnMaru-backend/commit/dac4567e13b855fe26c6a27a51f8ba546c72e722))
* **stamp:** 운영 회원 원장과 탈퇴 정리 연결 ([ca795bd](https://github.com/YRootLab/OnMaru-backend/commit/ca795bd18f0f8bbb8bcd0e2f32d89a0cb59fc263))
* **storage:** 데이터셋 revision 2벌 retention 자동화 ([ff9a9bf](https://github.com/YRootLab/OnMaru-backend/commit/ff9a9bfa444de43bb5f470c9faa0389e9ff17e2a))
* **storage:** 데이터셋 revision 2벌 retention 자동화 ([59fc763](https://github.com/YRootLab/OnMaru-backend/commit/59fc7639942559166945c6a59405f9159ccd345a))
* **test:** V017 마이그레이션 계약 버전 갱신과 editorial 테스트 컨텍스트 격리 ([4b86a11](https://github.com/YRootLab/OnMaru-backend/commit/4b86a11ace5d68f22ef295da5f49be4983c9d953))


### Performance Improvements

* **ci:** 공유 workspace 기반 하이브리드 lane 도입 ([#404](https://github.com/YRootLab/OnMaru-backend/issues/404)) ([0a1bced](https://github.com/YRootLab/OnMaru-backend/commit/0a1bced334503acbf1ec54147aab84d683135cf1))

## Changelog

This project uses semantic version tags from `main`. Release notes should be generated from Conventional Commits through Release Please once releasable backend changes exist.

## Unreleased

- Issue #444의 한옥 목록 필터·주소/좌표 계약, DataLab 35일 제공 지연 대응, 전국 285개 행정구역 registry와 DB 기반 원형 히트맵을 추가했다.
- Issue #375의 TourAPI 국문 v4.4 전체 원천 적재, 공식 분류체계 기반 공개 필터, Neon 지도·한옥 snapshot과 TourAPI·Odii 03:00 KST 전체 동기화를 추가했다.
- Issue #262의 로그인 회원용 위치 기반 한옥 수결첩, PostGIS 체크인, 관계형 수결 지급, OpenAPI와 FE 연동 문서를 추가했다.
- Issue #262의 명시적 참여형 익명 수결 랭킹, 참여·철회 API, OpenAPI 1.3과 FE 연동 안내를 추가했다.
- Issue #262의 production OAuth 회원 원장을 JDBC로 연결하고, 탈퇴 시 수결 체크인·획득·랭킹 및 체크인 멱등성 응답 개인정보 삭제와 재생성 차단을 보강했다.
- Issue #307의 production Odii 저장소 선택 경쟁을 제거하고, JDBC store 필수 구성·fail-fast와 설정 순서 회귀 테스트를 추가했다.
- Issue #399의 검증 가능한 DataLab 지역 registry, fail-closed 수집, skip/quarantine metric과 staging smoke 자동화를 추가했다.
- Issues #342, #343, #344, #346의 FE 호환 API, 온기 후기 필드, 스크린 한옥 JDBC 저장, 방문자 수 조회를 구현했다.
- VisitReview·신고·멱등성·DataLab 관측의 PostgreSQL 영속화와 원자적 revision 게시, 공식 지역 코드 매핑을 추가했다.
- Issue #228의 Journey 탐색 thread 기억 보존·LLM enrichment 계약 설계 및 FE 전달 문서를 추가했다.
- Issue #125의 TTL cleanup, revision GC, 회원 탈퇴 saved-data 정리, replay-safe deletion ledger와 late saved write 차단을 추가했다.
- Issue #124의 Saved journey 생성·목록·상세·재개·삭제 API, owner-scoped 404, cursor 목록, unavailableRefs 재개 응답과 구조화 로그를 추가했다.
- Issue #121의 Journey run cancel, 20초 deadline, lease expiry sweeper, durable REST cancel bridge와 재시작 orphan 회수 검증을 추가했다.
- Issue #119의 Optional RAG activation gate, corpus revision pinned rollback/activation 기록, fail-closed retrieval 연결을 추가했다.
- Issue #216의 얇은 Gradle convention plugin과 Spring module dependency direction 문서를 추가했다.
- Issue #117의 guest/member Journey AI KST 일일 quota, active admission, 영속 audit, 429 Retry-After, cancel slot 반환과 경쟁 검증을 추가했다.
- Issue #116의 Journey run SSE stage/terminal/heartbeat/replay/reset, auth close와 원인별 telemetry를 추가했다.
- Issue #115의 Exploration/run snapshot 복구 조회, current public board hydration, unavailableRefs와 run invariant 계약 검증을 추가했다.
- Issue #140의 지도·후기·Odii·찜 runtime/OpenAPI/fixture 통합 E2E 출시 gate를 추가했다.
- Issue #106의 revision-pinned corpus manifest/document export, FastAPI pull/ACK idempotency, hash mismatch·out-of-order rollback 방지를 추가했다.
- Issue #109의 PostgreSQL 기반 run 상태 머신, CAS terminal 전이, durable command replay와 active run 제약을 추가했다.
- Issue #113의 회원 전용 Odii story 저장·삭제와 type별 current-public saved-resource 목록, actor-bound signed cursor를 추가했다.
- Issue #101의 guest/member Exploration 생성·조회·turn intake, 소유권 은닉과 지역 clarification fast-path를 추가했다.
- Issue #103의 active Odii story 목록·상세 조회, 언어 fallback, 자막 provenance, 안전한 media URL 정책, 원자 published revision snapshot과 승인된 canonical place 연결을 추가했다.
- Issue #111의 deterministic AI held-out 평가, 품질·근거·안전·p95·비용 gate와 비교 가능한 report schema를 추가했다.
- Issue #94의 암호화 PostgreSQL logical backup, 격리 복원, deletion ledger replay, RPO/RTO·무결성 evidence 자동화를 추가한다.
- Issue #105의 AI proposal closed schema, candidate·pin·exclude·evidence allowlist와 typed rejection을 추가했다.
- Issue #104의 Odii–canonical place 후보 검수 상태, 단일 승인 projection, 공개 장소 hydration을 추가한다.
- Issue #134의 Journey actions·SavedJourney OpenAPI와 stateVersion·idempotency·재개 fixture 검증을 추가했다.
- Issue #91의 Gemini structured-output adapter, timeout·cancel·typed failure와 sanitized usage/cost 계측을 추가했다.
- Issue #139의 보호된 moderation queue, rotation token과 actor 기반 operator 인증, SLA projection, synthetic report/hide/restore/remove drill과 운영 runbook을 추가한다.
- Issue #96의 Odii provider client/parser, 다국어 audio revision mapping, 원자 LKG 게시와 tombstone 검증을 추가한다.
- Issue #97의 revision-pinned deterministic baseline ranking, hard filter, pin·diversity와 held-out fixture 검증을 추가했다.
- Add Git Flow branch issue parser, AGENTS release/harness policy updates, and branch-number tests for Issue #192.
- Add Spring/FastAPI multi-stage Dockerfiles, staging deploy workflow, image scan gate, and rollback release plan for Issue #82.
- Add Grafana Cloud dashboard, alert rule, notification policy exports, and staging synthetic alert checklist for Issue #81.
- Add Spring and FastAPI observability correlation boundaries with OpenTelemetry dependencies, redaction tests, and FastAPI lint/type CI gates for Issue #71.
- Add TourAPI HTTP client, URI builder, envelope parser, Spring configuration binding, and provider contract tests for Issue #73.
- Add ArchUnit module boundary checks for Spring core framework isolation, consumer-owned ports, app bridge API boundaries, and module cycle fixtures for Issue #68.
- Add Issue #69 contract validator tests for OpenAPI lint, JSON fixture/schema mismatch, DBML compile, and generated artifact drift checks.
- Add identity/member/SavedResource OpenAPI fixtures and contract validation for Issue #132.
- Add R2 map, region, Odii, and tourism insights OpenAPI fixtures with R1/R2 contract validation.
- Add shared Spring web contracts for schemaVersion 1.2 error envelopes, request IDs, signed cursors, and idempotency primitives for Issue #70.
- Add D01 Flyway baseline migration, migration registry policy checks, and PostgreSQL role separation tests for Issue #67.
- Add server-only secret loading, rotation overlap, and log redaction wiring for Spring and FastAPI runtimes.
- Add R1 hanok/place/saved-resource OpenAPI fixtures and CI contract validation for OpenAPI, JSON Schema, DBML, generated SQL, Spring, and FastAPI checks.
- Harden Odii fixture validation to reject long URL-encoded token query values in captured manifest URLs.
- Capture redacted Odii API qualification fixtures and license/provenance manifest for Issue #61.
- Add TourAPI qualification manifest, redacted provider fixtures, and CI fixture validation for Issue #66.
- Add reproducible backend planning-input snapshots with provenance manifest, hash verification, and secret scanning in CI.
- Add PostgreSQL/PostGIS Testcontainers smoke coverage, clean test DB reset helper, and local compose readiness checks.
- Add reproducible Java 21/Spring Boot and Python 3.12/FastAPI development scaffolds with locked dependencies, health checks, and offline-cache verification.
- Standardize the Java namespace and Gradle group on `com.yrootlab.onmaru` across source, planning graphs, and published Issue paths.
- Document post-audit backend planning refinements, DBML ERD modules, and Azimutt PNG handoff workflow.
- Prepare backend implementation issue graph, P0 triage, and runtime ADR follow-up after PR #48.
- Initialize project operating harness, Git Flow policy, release automation baseline, and ADR directory.
