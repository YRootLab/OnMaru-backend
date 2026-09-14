const ISSUE_REFERENCE_PATTERN =
  /(?:https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/issues\/)?#?(\d+)/gi;
const CLOSING_SEGMENT_PATTERN =
  /\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+((?:(?:https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/issues\/)?#?\d+)(?:\s*(?:,|and)\s*(?:(?:https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/issues\/)?#?\d+))*)/gi;

export function parseClosingIssueNumbers(body) {
  const numbers = new Set();
  for (const segment of String(body ?? '').matchAll(CLOSING_SEGMENT_PATTERN)) {
    for (const issue of segment[1].matchAll(ISSUE_REFERENCE_PATTERN)) {
      numbers.add(Number(issue[1]));
    }
  }
  return [...numbers].sort((left, right) => left - right);
}

export function shouldReconcileDevelopMerge({ merged, baseRefName }) {
  return Boolean(merged) && baseRefName === 'develop';
}

export function buildDevelopAutoCloseComment({ prNumber, verificationSummary }) {
  const verification = verificationSummary?.trim()
    ? ` 검증: ${verificationSummary.trim()}`
    : '';
  return `PR #${prNumber}가 develop에 병합되었습니다. 이 저장소는 기본 브랜치가 main이라 develop 대상 PR의 GitHub auto-close가 동작하지 않을 수 있어 post-merge workflow가 이슈를 종료합니다.${verification}`;
}
