#!/usr/bin/env node

const requiredJobs = [
  ['hygiene', process.env.HYGIENE_RESULT],
  ['contract', process.env.CONTRACT_RESULT],
  ['module-tests', process.env.MODULE_JOB_RESULT],
];
const acceptedToolkitResults = new Set(['improved', 'unchanged', 'regressed', 'inconclusive']);
const manifestPattern = /^https:\/\/github\.com\/YRootLab\/OnMaru-backend\/actions\/runs\/\d+$/;
const errors = [];

for (const [name, result] of requiredJobs) {
  if (result !== 'success') errors.push(`${name} result must be success, received ${result || 'missing'}`);
}
if (!acceptedToolkitResults.has(process.env.TOOLKIT_RESULT ?? '')) {
  errors.push(`Toolkit result is not successful: ${process.env.TOOLKIT_RESULT || 'missing'}`);
}
if (!manifestPattern.test(process.env.MANIFEST_URI ?? '')) {
  errors.push('Toolkit manifest URI is missing or not an immutable OnMaru-backend Actions run URL');
}
if (process.env.REPORT_ARTIFACT !== 'module-benchmark-report') {
  errors.push(`Toolkit report artifact must be module-benchmark-report, received ${process.env.REPORT_ARTIFACT || 'missing'}`);
}

if (errors.length > 0) {
  for (const error of errors) console.error(`required fan-in failed: ${error}`);
  process.exitCode = 1;
} else {
  console.log(`required fan-in passed with Toolkit result ${process.env.TOOLKIT_RESULT}`);
}
