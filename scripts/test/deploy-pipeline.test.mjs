import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(path) {
  return readFileSync(join(root, path), 'utf8');
}

function readJson(path) {
  return JSON.parse(read(path));
}

describe('container and staging release pipeline', () => {
  it('defines least-privilege multi-stage runtime images for both services', () => {
    for (const [path, expectedPort] of [
      ['Dockerfile', '8080'],
      ['ai/Dockerfile', '8000'],
    ]) {
      assert.ok(existsSync(join(root, path)), `${path} missing`);
      const dockerfile = read(path);
      assert.match(dockerfile, /FROM .+ AS builder/, `${path} needs a builder stage`);
      assert.match(dockerfile, /FROM .+ AS runner/, `${path} needs a runner stage`);
      assert.match(dockerfile, /USER onmaru/, `${path} must run as non-root onmaru user`);
      assert.match(dockerfile, new RegExp(`EXPOSE ${expectedPort}`), `${path} exposes wrong port`);
      assert.match(dockerfile, /HEALTHCHECK/, `${path} needs a runtime smoke healthcheck`);
      assert.doesNotMatch(dockerfile, /ONMARU_SECRET_|SERVICE_KEY|PASSWORD|TOKEN/, `${path} must not bake secrets`);
    }

    assert.match(
      read('ai/Dockerfile'),
      /CMD \["python", "-m", "uvicorn", "onmaru_ai\.main:app"/,
      'ai/Dockerfile must avoid relocated virtualenv script shebangs',
    );
    assert.match(
      read('ai/Dockerfile'),
      /uv sync --frozen --no-dev --no-editable/,
      'ai/Dockerfile must install the app non-editably before copying the venv',
    );
  });

  it('keeps staging deploy order gated by migration and rollback evidence', () => {
    const plan = readJson('infra/staging/release-plan.json');

    assert.deepEqual(plan.services.map((service) => service.name), ['spring-api', 'ai-service']);
    assert.equal(plan.deployOrder[0], 'build-and-scan-images');
    assert.equal(plan.deployOrder[1], 'migration-gate');
    assert.equal(plan.deployOrder.at(-2), 'staging-smoke');
    assert.equal(plan.deployOrder.at(-1), 'rollback-on-failure');
    assert.equal(plan.migration.failurePolicy, 'block-deploy-and-keep-previous-release');
    assert.equal(plan.rollback.strategy, 'previous-image-digest');

    assert.equal(plan.credentials.runtime.canDdl, false);
    assert.equal(plan.credentials.migration.canDdl, true);
    assert.notEqual(plan.credentials.runtime.secretRef, plan.credentials.migration.secretRef);
    assert.notEqual(plan.credentials.runtime.secretRef, plan.credentials.backup.secretRef);
    assert.ok(plan.smoke.endpoints.includes('/actuator/health'));
    assert.ok(plan.smoke.endpoints.includes('/ready'));
  });

  it('publishes a manual staging workflow with image build scan smoke and rollback jobs', () => {
    const workflow = read('.github/workflows/deploy.yml');

    assert.match(workflow, /workflow_dispatch:/);
    assert.match(workflow, /branches:\n\s+- master/);
    assert.doesNotMatch(workflow, /branches:\n(?:\s+- .+\n)*\s+- main/);
    assert.match(workflow, /permissions:\n\s+contents: read\n\s+packages: write\n\s+security-events: write/);
    assert.match(workflow, /concurrency:/);
    assert.match(workflow, /docker\/build-push-action@v6/);
    assert.match(workflow, /aquasecurity\/trivy-action@/);
    assert.match(workflow, /aquasecurity\/trivy-action@v[0-9]+\.[0-9]+\.[0-9]+/);
    assert.match(workflow, /Dockerfile/);
    assert.match(workflow, /ai\/Dockerfile/);
    assert.match(workflow, /migration-gate:/);
    assert.match(workflow, /staging-smoke:/);
    assert.match(workflow, /STAGING_SPRING_URL/);
    assert.match(workflow, /STAGING_AI_URL/);
    assert.match(workflow, /curl --fail --silent --show-error/);
    assert.match(workflow, /rollback-on-failure:/);
    assert.match(workflow, /environment:\s+staging/);
  });

  it('runs release and CI workflows from the master production branch', () => {
    const releasePlease = read('.github/workflows/release-please.yml');
    const ci = read('.github/workflows/ci.yml');

    for (const workflow of [releasePlease, ci]) {
      assert.match(workflow, /branches:\n(?:\s+- develop\n)?\s+- master/);
      assert.doesNotMatch(workflow, /branches:\n(?:\s+- .+\n)*\s+- main/);
    }
  });
});
