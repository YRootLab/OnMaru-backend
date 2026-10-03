import { fileURLToPath } from 'node:url';

// CI-only offline double. Real Toolkit verification requires an explicit override.
export function selectToolkitSource(env = process.env) {
  return env.ONMARU_TOOLKIT_SRC ?? fileURLToPath(new URL('../fixtures/ci-observability/toolkit_stub', import.meta.url));
}
