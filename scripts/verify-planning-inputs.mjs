#!/usr/bin/env node
import { verifyPlanningInputs } from './lib/planning-inputs-verifier.mjs';

const result = verifyPlanningInputs(process.cwd());

if (!result.ok) {
  for (const error of result.errors) console.error(`planning-inputs: ${error}`);
  process.exit(1);
}

console.log('planning-inputs: manifest hashes and secret scan passed');
