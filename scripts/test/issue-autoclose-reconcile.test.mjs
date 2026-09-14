import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildDevelopAutoCloseComment,
  parseClosingIssueNumbers,
  shouldReconcileDevelopMerge,
} from '../lib/issue-autoclose-reconcile.mjs';

test('parses unique closing issue references from pull request body', () => {
  const body = [
    'Closes #132, #134',
    'Fixes #61 and resolves #133',
    'Refs #999',
    'Closed https://github.com/YRootLab/OnMaru-backend/issues/69',
    'closes #132',
  ].join('\n');

  assert.deepEqual(parseClosingIssueNumbers(body), [61, 69, 132, 133, 134]);
});

test('ignores non-closing references and empty bodies', () => {
  assert.deepEqual(parseClosingIssueNumbers('Refs #61\nRelated to #132'), []);
  assert.deepEqual(parseClosingIssueNumbers(''), []);
  assert.deepEqual(parseClosingIssueNumbers(null), []);
});

test('reconciles only merged develop pull requests', () => {
  assert.equal(shouldReconcileDevelopMerge({ merged: true, baseRefName: 'develop' }), true);
  assert.equal(shouldReconcileDevelopMerge({ merged: false, baseRefName: 'develop' }), false);
  assert.equal(shouldReconcileDevelopMerge({ merged: true, baseRefName: 'main' }), false);
});

test('builds a manual close comment that explains the develop auto-close limitation', () => {
  const comment = buildDevelopAutoCloseComment({
    prNumber: 149,
    verificationSummary: 'python3 scripts/test/validate-identity-saved-contract.py',
  });

  assert.match(comment, /PR #149/);
  assert.match(comment, /develop/);
  assert.match(comment, /main/);
  assert.match(comment, /auto-close/);
  assert.match(comment, /validate-identity-saved-contract/);
});
