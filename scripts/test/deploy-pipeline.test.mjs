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
    assert.match(
      read('Dockerfile'),
      /ENV SPRING_PROFILES_ACTIVE=production/,
      'the deployed Spring image must select JDBC-backed production stores by default',
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

  it('keeps staging fixtures deterministic by disabling startup source sync', () => {
    const compose = read('infra/lightsail/staging/compose.yaml');

    assert.match(compose, /ONMARU_TOURAPI_SYNC_ON_STARTUP:\s*["']false["']/);
    assert.match(compose, /ONMARU_ODII_SYNC_ON_STARTUP:\s*["']false["']/);
  });

  it('isolates staging AI with an immutable image and coordinated lifecycle', () => {
    const compose = read('infra/lightsail/staging/compose.yaml');
    const envExample = read('infra/lightsail/staging/.env.example');
    const start = read('infra/lightsail/staging/start.sh');
    const stop = read('infra/lightsail/staging/stop.sh');
    const status = read('infra/lightsail/staging/status.sh');

    assert.match(
      envExample,
      /ONMARU_AI_IMAGE=ghcr\.io\/yrootlab\/onmaru-ai-engine\/ai-engine@sha256:50121191624330efe2d3749604be608dab5e0e6d6bd714f11f2358c5bffe4b6d/,
    );
    assert.match(envExample, /ONMARU_AI_MEMORY_LIMIT=512m/);
    assert.match(envExample, /ONMARU_SECRET_INTERNAL_AI_SERVICE_TOKEN_CURRENT=REPLACE_WITH_UNIQUE_RANDOM_SECRET/);

    assert.match(compose, /^  staging-ai-engine:\n/m);
    assert.match(compose, /image: \$\{ONMARU_AI_IMAGE:\?Set an immutable staging AI image digest\}/);
    assert.match(compose, /mem_limit: \$\{ONMARU_AI_MEMORY_LIMIT:-512m\}/);
    assert.match(compose, /http:\/\/127\.0\.0\.1:8000\/ready/);
    assert.match(compose, /ONMARU_AI_BASE_URL: http:\/\/staging-ai-engine:8000/);
    assert.match(compose, /ONMARU_SPRING_BASE_URL: http:\/\/staging-api:8080/);
    assert.match(compose, /ONMARU_SECRET_INTERNAL_AI_SERVICE_TOKEN_CURRENT: \$\{ONMARU_SECRET_INTERNAL_AI_SERVICE_TOKEN_CURRENT:\?Set the staging internal AI service token\}/);

    const aiService = compose.slice(
      compose.indexOf('  staging-ai-engine:'),
      compose.indexOf('\n  spring-api:'),
    );
    assert.match(aiService, /expose:\n\s+- ["']8000["']/);
    assert.doesNotMatch(aiService, /\n\s+ports:/);

    assert.match(start, /onmaru-lightsail-ai-engine-1/);
    assert.match(start, /compose up -d staging-ai-engine/);
    assert.match(start, /compose ps staging-ai-engine --format '\{\{\.Health\}\}'/);
    assert.match(start, /compose stop spring-api staging-ai-engine postgres/);
    assert.match(stop, /stop spring-api staging-ai-engine postgres/);
    assert.match(status, /staging-ai-engine/);

    const requiredVariables = [...compose.matchAll(/\$\{([A-Z0-9_]+):\?/g)].map((match) => match[1]);
    const exampleVariables = new Set(
      [...envExample.matchAll(/^([A-Z0-9_]+)=/gm)].map((match) => match[1]),
    );
    assert.deepEqual(
      [...new Set(requiredVariables)].filter((name) => !exampleVariables.has(name)),
      [],
      'staging .env.example must define every required Compose variable',
    );
  });

  it('documents pagination-sized staging fixtures for FE verification', () => {
    const readme = read('infra/lightsail/staging/README.md');
    const feGuide = read('docs/operations/staging-fe-guide.md');

    for (const document of [readme, feGuide]) {
      assert.match(document, /지도 장소 300건/);
      assert.match(document, /공개 온기 후기 150건/);
      assert.match(document, /Odii story 65건/);
      assert.match(document, /limit=30/);
      assert.match(document, /모든 행은 합성/);
      assert.match(document, /운영 회원·세션 데이터를 복사하지 않/);
      assert.match(document, /반복 기동.*fixture.*늘어나지 않/);
    }
    assert.match(feGuide, /onmaru-staging-operator@13\.125\.191\.16 start/);
  });

  it('publishes a manual staging workflow with image build scan smoke and rollback jobs', () => {
    const workflow = read('.github/workflows/deploy.yml');

    assert.match(workflow, /workflow_dispatch:/);
    assert.match(workflow, /branches:\n\s+- master/);
    assert.doesNotMatch(workflow, /branches:\n(?:\s+- .+\n)*\s+- main/);
    assert.match(workflow, /permissions:\n\s+contents: read\n\s+packages: write\n\s+security-events: write/);
    assert.match(workflow, /concurrency:/);
    assert.match(workflow, /docker\/build-push-action@[0-9a-f]{40} # v6/);
    assert.match(workflow, /aquasecurity\/trivy-action@[0-9a-f]{40} # v0\.36\.0/);
    assert.equal(
      workflow.match(/limit-severities-for-sarif:\s+true/g)?.length,
      2,
      'both SARIF scans must limit the deploy gate to configured severities',
    );
    assert.match(workflow, /Dockerfile/);
    assert.match(workflow, /ai\/Dockerfile/);
    assert.match(workflow, /migration-gate:/);
    const migrationGate = workflow.slice(
      workflow.indexOf('  migration-gate:'),
      workflow.indexOf('  staging-smoke:'),
    );
    assert.match(migrationGate, /actions\/setup-python@[0-9a-f]{40} # v5/);
    assert.match(migrationGate, /python-version:\s*["']?3\.12["']?/);
    assert.match(migrationGate, /pip install -r scripts\/test\/requirements-contract\.txt/);
    assert.ok(
      migrationGate.indexOf('pip install -r scripts/test/requirements-contract.txt')
        < migrationGate.indexOf('bash scripts/verify-contracts'),
      'migration gate must install contract dependencies before validation',
    );
    assert.match(workflow, /staging-smoke:/);
    assert.match(workflow, /STAGING_SPRING_URL/);
    assert.match(workflow, /AI staging service is not provisioned yet/);
    assert.doesNotMatch(workflow, /\$\{\{ vars\.STAGING_AI_URL \}\}/);
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

  it('applies the verified Gradle profile to Spring image and migration builds without dropping outer caches', () => {
    const dockerfile = read('Dockerfile');
    const workflow = read('.github/workflows/deploy.yml');
    const migrationGate = workflow.slice(
      workflow.indexOf('  migration-gate:'),
      workflow.indexOf('  staging-deploy:'),
    );

    assert.match(dockerfile, /RUN --mount=type=cache,target=\/root\/\.gradle/);
    assert.match(dockerfile, /--init-script build-logic\/ci-performance\.gradle\.kts/);
    assert.match(dockerfile, /:apps:spring-api:bootJar -x test/);
    assert.match(dockerfile, /-Ponmaru\.ci\.performance\.enabled=true/);
    assert.match(workflow, /cache-from: type=gha,scope=spring-api/);
    assert.match(workflow, /cache-to: type=gha,mode=max,scope=spring-api/);

    assert.match(migrationGate, /cache: gradle/);
    assert.match(migrationGate, /--init-script build-logic\/ci-performance\.gradle\.kts/);
    assert.match(migrationGate, /:apps:spring-api:test --tests '\*Migration\*'/);
    assert.match(migrationGate, /-Ponmaru\.ci\.performance\.enabled=true/);
  });
});
