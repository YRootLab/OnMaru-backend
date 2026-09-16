# AI 품질·안전·비용·지연 offline 평가 harness

Issue #111의 평가 harness는 고정된 입력과 관측 결과를 네트워크 없이 다시 채점한다. 모델명이나
prompt의 인상 대신 같은 gold set에서 계산한 품질, 근거, 안전, 지연, 비용을 출시 판단 자료로
남기는 것이 목적이다. RAG 활성화와 실제 공급자 호출은 이 범위에 포함하지 않는다.

## 고정 입력

`ai/evals/journey-held-out-v1.json`은 다음 네 버전을 필수로 고정한다.

- `model`: 관측 결과를 만든 model 또는 deterministic fixture 식별자
- `prompt`: provider에 사용한 prompt contract 버전
- `ranking`: 후보 검색·정렬 버전
- `dataset`: gold set 자체의 immutable 버전

case는 사람이 정한 relevance grade, 지원 가능한 evidence, allowlist, pin, exclude, 기대 outcome과
관측된 검색·proposal·latency·cost를 함께 가진다. 동일 case ID는 허용하지 않으며 pin과 relevance는
allowlist 안에 있어야 한다. dataset 내용을 바꾸면 기존 이름을 덮어쓰지 말고 새 dataset 버전을
발행한다.

현재 체크인된 4건은 계산·gate·재현성 경로를 검증하는 synthetic seed다. 설계 목표인 60건
한국어 질의와 두 번의 사람 검토를 완료한 운영 품질 근거가 아니며, 실제 모델 출시 승인으로
해석하지 않는다.

## 지표와 gate

| gate | 지표 | 계산과 초기 기준 |
|---|---|---|
| quality | retrieval recall@5 | grade 1 이상 gold ref의 top 5 포함 비율, `>= 0.85` |
| quality | nDCG@3 | relevance grade 0/1/2의 query별 nDCG 평균, `>= 0.80` |
| evidence faithfulness | supported evidence | proposal evidence 중 같은 ref의 gold evidence 비율, `>= 0.90` |
| safety | constraint pass | 기대 outcome, allowed ID, pin, exclude, reason/evidence 위반 `0`, pass rate `1.0` |
| latency | p95 | nearest-rank p95, `<= 8,000ms` |
| cost | 평균·단건 micros | 평균 `<= 1,000`, 단건 `<= 2,000` |

분모가 없는 recall과 evidence metric은 해당 fixture에서 위반할 기회가 없으므로 `1.0`으로 기록한다.
반면 답 가능한 질의에서 검색 결과가 비면 nDCG는 `0`이다. threshold는 fixture에 명시되며 변경도
dataset review 대상으로 취급한다.

## 실행과 report 계약

저장소 루트에서 실행한다.

```bash
uv run --project ai python scripts/run-ai-evals \
  --input ai/evals/journey-held-out-v1.json \
  --output /tmp/onmaru-ai-eval-report.json
```

report는 실행 시각이나 host 정보를 넣지 않고 canonical input SHA-256, case 수, version pins, 원시
분자·분모, 지표, gate별 관측값과 threshold를 기록한다. 같은 의미의 fixture는 같은 report bytes를
만든다. JSON 계약은 `ai/evals/report.schema.json`이며 CI가 runtime Pydantic schema와 drift를
검사한다.

이전 report와 비교할 때는 `--compare <report>`를 추가한다. dataset 버전과 case 수가 같을 때만
`compatible=true`이며 각 품질 지표, p95, 비용의 현재 값 minus 기준 값 delta가 report에 포함된다.
비교값은 gate 판정을 대신하지 않는다.

gate가 하나라도 실패하면 report를 기록한 뒤 종료 코드 `1`을 반환한다. 이 동작으로 CI와 별도
release job이 같은 offline command를 재사용할 수 있다.

## 운영 데이터로 확장할 때

1. 개발 set과 frozen held-out set을 분리하고 query family와 place/source 누설을 점검한다.
2. relevance와 evidence support를 두 명이 독립 검토하고 불일치를 조정한다.
3. 공급자 실행 결과는 질문 원문이나 비밀을 report에 넣지 않고 이 입력 계약으로 정규화한다.
4. 한 번에 prompt, model, ranking, dataset 중 한 축만 바꾸고 이전 report와 비교한다.
5. RAG 비교와 activation은 Issue #119에서 baseline과 같은 dataset으로 별도 구현한다.
