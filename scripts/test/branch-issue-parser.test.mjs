import assert from 'node:assert/strict';
import test from 'node:test';

import {
  parseIssueNumberFromBranch,
  requireIssueNumberFromBranch,
  shouldRequireIssueBranch,
} from '../lib/branch-issue-parser.mjs';

test('parses issue numbers from work branch prefixes', () => {
  assert.equal(parseIssueNumberFromBranch('feature/192-git-flow-harness-branch-parser'), 192);
  assert.equal(parseIssueNumberFromBranch('fix/61-odii-redaction-hardening'), 61);
  assert.equal(parseIssueNumberFromBranch('docs/49-next-20-issue-execution-plan'), 49);
  assert.equal(parseIssueNumberFromBranch('hotfix/204-prod-cookie-scope'), 204);
});

test('ignores long-lived and malformed branches', () => {
  assert.equal(parseIssueNumberFromBranch('develop'), null);
  assert.equal(parseIssueNumberFromBranch('main'), null);
  assert.equal(parseIssueNumberFromBranch('release/v0.3.2'), null);
  assert.equal(parseIssueNumberFromBranch('feature/git-flow-without-number'), null);
  assert.equal(parseIssueNumberFromBranch('feature/abc-123-wrong-order'), null);
});

test('requires issue numbers only for short-lived work branches', () => {
  assert.equal(shouldRequireIssueBranch('feature/192-git-flow-harness-branch-parser'), true);
  assert.equal(shouldRequireIssueBranch('fix/61-odii-redaction-hardening'), true);
  assert.equal(shouldRequireIssueBranch('docs/49-next-20-issue-execution-plan'), true);
  assert.equal(shouldRequireIssueBranch('hotfix/204-prod-cookie-scope'), true);
  assert.equal(shouldRequireIssueBranch('release/v0.3.2'), false);
  assert.equal(shouldRequireIssueBranch('develop'), false);
  assert.equal(shouldRequireIssueBranch('main'), false);
});

test('throws a helpful error when a work branch omits the issue number', () => {
  assert.throws(
    () => requireIssueNumberFromBranch('feature/git-flow-without-number'),
    /must start with feature\/<issue-number>-/,
  );
  assert.equal(requireIssueNumberFromBranch('feature/192-git-flow-harness-branch-parser'), 192);
});
