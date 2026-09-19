#!/usr/bin/env node

import { parseClosingIssueNumbers } from './lib/issue-autoclose-reconcile.mjs';

const issues = parseClosingIssueNumbers(process.env.PR_BODY ?? '');
for (const issue of issues) {
  console.log(issue);
}
