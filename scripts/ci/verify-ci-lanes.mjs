const lanes = [
  ['hygiene', process.env.HYGIENE_ENABLED, process.env.HYGIENE_RESULT],
  ['java', process.env.JAVA_ENABLED, process.env.JAVA_RESULT],
  ['contract', process.env.CONTRACT_ENABLED, process.env.CONTRACT_RESULT],
  ['ai', process.env.AI_ENABLED, process.env.AI_RESULT],
];

const failures = [];
for (const [name, enabled, result] of lanes) {
  if (!['true', 'false'].includes(enabled)) failures.push(`${name}: invalid enabled flag '${enabled ?? ''}'`);
  else if (!result) failures.push(`${name}: missing job result`);
  else if (enabled === 'true' && result !== 'success') failures.push(`${name}: required lane finished as '${result}'`);
  else if (enabled === 'false' && result !== 'skipped') failures.push(`${name}: disabled lane must be skipped, received '${result}'`);
}

if (failures.length > 0) {
  console.error(`required CI fan-in failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`);
  process.exitCode = 1;
} else {
  console.log('required CI fan-in passed');
}
