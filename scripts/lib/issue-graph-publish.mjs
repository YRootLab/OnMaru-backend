export function parsePublishMode(args) {
  const dryRun = args.includes('--dry-run');
  const apply = args.includes('--apply');
  if (dryRun && apply) throw new Error('--dry-run and --apply are mutually exclusive');
  return apply ? 'apply' : 'dry-run';
}

export function planRelationshipChanges(currentNumbers, desiredNumbers) {
  const current = new Set(currentNumbers.map(Number));
  const desired = new Set(desiredNumbers.map(Number));
  return {
    add: [...desired].filter((number) => !current.has(number)).sort((a, b) => a - b),
    remove: [...current].filter((number) => !desired.has(number)).sort((a, b) => a - b),
  };
}
