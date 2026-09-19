const WORK_BRANCH_PATTERN = /^(feature|fix|docs|hotfix)\/(\d+)-[a-z0-9][a-z0-9-]*$/;
const WORK_BRANCH_PREFIX_PATTERN = /^(feature|fix|docs|hotfix)\//;

export function parseIssueNumberFromBranch(branchName) {
  const match = String(branchName ?? '').trim().match(WORK_BRANCH_PATTERN);
  return match ? Number(match[2]) : null;
}

export function shouldRequireIssueBranch(branchName) {
  return WORK_BRANCH_PREFIX_PATTERN.test(String(branchName ?? '').trim());
}

export function requireIssueNumberFromBranch(branchName) {
  const issueNumber = parseIssueNumberFromBranch(branchName);
  if (issueNumber !== null) return issueNumber;

  if (shouldRequireIssueBranch(branchName)) {
    throw new Error(
      'Work branch names must start with feature/<issue-number>-, fix/<issue-number>-, docs/<issue-number>-, or hotfix/<issue-number>-.',
    );
  }

  return null;
}
