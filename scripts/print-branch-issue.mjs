#!/usr/bin/env node

import { execFileSync } from 'node:child_process';

import { requireIssueNumberFromBranch } from './lib/branch-issue-parser.mjs';

const branchName =
  process.argv[2] ??
  execFileSync('git', ['branch', '--show-current'], { encoding: 'utf8' }).trim();

const issueNumber = requireIssueNumberFromBranch(branchName);
if (issueNumber !== null) {
  console.log(issueNumber);
}
