#!/usr/bin/env python3
"""LLM draft summaries for exact official filming candidates; never publishes claims."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

MODEL = 'gemini-3.5-flash-lite'
PROMPT_VERSION = 'official-scene-summary-v1'
SOURCE_URL = 'https://www.data.go.kr/data/15111405/fileData.do'


def build_items(labels_path, official_path):
    with open(official_path, encoding='cp949', newline='') as handle:
        official = {row['연번']: row for row in csv.DictReader(handle)}
    items = []
    for line in Path(labels_path).read_text().splitlines():
        label = json.loads(line)
        for candidate in label['officialFilmingCandidates']:
            source = official[candidate['officialRow']]
            items.append({
                'key': label['contentId'] + ':' + candidate['officialRow'],
                'contentId': label['contentId'], 'officialRow': candidate['officialRow'],
                'place': label['sourceTitle'], 'work': candidate['workTitle'],
                'description': source['장소설명'],
            })
    return items


def prompt(items):
    public_inputs = [{key: item[key] for key in ('key', 'place', 'work', 'description')} for item in items]
    return ('다음 공식 촬영지 CSV의 각 장소설명에서 실제 장면에 관한 내용만 한국어 한 문장으로 요약하세요. '
            '원문에 없는 사실을 추가하지 마세요. 요약을 뒷받침하는 원문 10자 이상의 연속된 구절을 '
            'sourceQuote에 그대로 복사하세요. 장면 설명이 없다면 summary와 sourceQuote를 모두 빈 문자열로 두세요. '
            '촬영 관계의 확정 여부는 판단하지 마세요. '
            'JSON 객체 {"items":[{"key":"...","summary":"...","sourceQuote":"..."}]}만 출력. 입력: '
            + json.dumps(public_inputs, ensure_ascii=False))


def request_batch(items, api_key):
    payload = {'contents': [{'parts': [{'text': prompt(items)}]}],
               'generationConfig': {'responseMimeType': 'application/json', 'temperature': 0,
                                    'maxOutputTokens': 8192}}
    request = urllib.request.Request(
        f'https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent',
        data=json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json', 'x-goog-api-key': api_key})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                document = json.load(response)
            return json.loads(document['candidates'][0]['content']['parts'][0]['text'])['items']
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 3:
                raise RuntimeError(f'Gemini HTTP {error.code}') from error
            time.sleep(2 ** attempt)
        except (TimeoutError, urllib.error.URLError, KeyError, IndexError, json.JSONDecodeError) as error:
            if attempt == 3:
                raise RuntimeError(f'Gemini {type(error).__name__}') from error
            time.sleep(2 ** attempt)


def validate_batch(source_items, drafts, official_sha):
    source = {item['key']: item for item in source_items}
    if len(drafts) != len(source_items) or {item.get('key') for item in drafts} != set(source):
        raise ValueError('model omitted, duplicated, or added candidate keys')
    result = []
    for draft in drafts:
        original = source[draft['key']]
        summary = draft.get('summary', '')
        quote = draft.get('sourceQuote', '')
        if not isinstance(summary, str) or not isinstance(quote, str):
            raise ValueError('model summary or quote is not text')
        supported = (bool(summary) == bool(quote) and len(summary) <= 500 and
                     (not quote or (len(quote) >= 10 and quote in original['description'])))
        result.append({
            'contentId': original['contentId'], 'officialRow': original['officialRow'],
            'workTitle': original['work'], 'summaryCandidate': summary if supported else '',
            'sourceQuote': quote if supported else '',
            'status': 'REVIEW_REQUIRED' if supported else 'MODEL_OUTPUT_REJECTED',
            'sourceUrl': SOURCE_URL, 'officialSourceSha256': official_sha,
            'model': MODEL, 'promptVersion': PROMPT_VERSION,
        })
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('labels')
    parser.add_argument('official_csv')
    parser.add_argument('--output', required=True)
    parser.add_argument('--batch-size', type=int, default=8)
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 16:
        parser.error('batch-size must be 1..16')
    api_key = os.environ.get('ONMARU_SECRET_GEMINI_API_KEY_CURRENT')
    if not api_key:
        parser.error('ONMARU_SECRET_GEMINI_API_KEY_CURRENT is required')
    items = build_items(args.labels, args.official_csv)
    official_sha = hashlib.sha256(Path(args.official_csv).read_bytes()).hexdigest()
    output = Path(args.output)
    completed = {}
    if output.exists():
        for line in output.read_text().splitlines():
            row = json.loads(line)
            completed[row['contentId'] + ':' + row['officialRow']] = row
    with output.open('a') as handle:
        os.chmod(output, 0o600)
        pending = [item for item in items if item['key'] not in completed]
        for start in range(0, len(pending), args.batch_size):
            batch = pending[start:start + args.batch_size]
            drafts = request_batch(batch, api_key)
            valid = validate_batch(batch, drafts, official_sha)
            for row in valid:
                handle.write(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n')
            handle.flush()
            print(json.dumps({'completed': len(completed) + start + len(batch), 'total': len(items)}))
    print(json.dumps({'total': len(items), 'modelDrafts': len(items), 'published': 0}))


if __name__ == '__main__':
    main()
