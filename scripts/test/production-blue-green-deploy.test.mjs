import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(path) {
  return readFileSync(join(root, path), 'utf8');
}

describe('Lightsail production blue-green deployment', () => {
  it('defines two bounded Spring slots behind a runtime Nginx upstream', () => {
    const compose = read('infra/lightsail/compose.yaml');
    const site = read('infra/lightsail/nginx/site.conf');
    const runtimeKeepPath = 'infra/lightsail/nginx/runtime/.gitkeep';

    assert.match(compose, /spring-blue:/);
    assert.match(compose, /spring-green:/);
    assert.equal(compose.match(/mem_limit:\s+384m/g)?.length, 2);
    assert.equal(compose.match(/ONMARU_TOURAPI_SYNC_ON_STARTUP:\s+"false"/g)?.length, 2);
    assert.equal(compose.match(/ONMARU_ODII_SYNC_ON_STARTUP:\s+"false"/g)?.length, 2);
    assert.match(compose, /\.\/nginx\/runtime:\/etc\/nginx\/onmaru:ro/);
    assert.match(site, /include \/etc\/nginx\/onmaru\/upstream\.conf;/);
    assert.match(site, /proxy_pass http:\/\/spring_backend;/);
    assert.ok(existsSync(join(root, runtimeKeepPath)), `${runtimeKeepPath} missing`);
    assert.match(read('infra/lightsail/.gitignore'), /nginx\/runtime\/upstream\.conf/);
  });

  it('fails closed around memory pressure, staging overlap, health, and rollback', () => {
    const path = 'infra/lightsail/production/deploy-blue-green.sh';
    assert.ok(existsSync(join(root, path)), `${path} missing`);
    const script = read(path);

    assert.match(script, /flock -n/);
    assert.match(script, /onmaru-staging/);
    assert.match(script, /MIN_AVAILABLE_KB=131072/);
    assert.match(script, /MAX_SWAP_DELTA_MB=192/);
    assert.match(script, /spring-(blue|green)/);
    assert.match(script, /actuator\/health/);
    assert.match(script, /OOMKilled/);
    assert.match(script, /nginx -t/);
    assert.match(script, /nginx -s reload/);
    assert.match(script, /production-active-slot/);
    assert.match(script, /docker update --memory 448m --memory-swap 896m/);
    assert.match(script, /rollback/);
    assert.match(script, /trap .*HUP INT TERM/);
    assert.match(script, /trap on_exit EXIT/);
    assert.match(script, /docker start "\$active_id"/);
    assert.match(script, /SUCCESS=1/);
    assert.match(script, /Active production Spring slot is not running/);

    const writeIndex = script.indexOf('write_upstream "$TARGET"');
    const switchedIndex = script.indexOf('SWITCHED=1', writeIndex);
    const nginxTestIndex = script.indexOf('nginx -t', writeIndex);
    assert.ok(writeIndex >= 0 && switchedIndex > writeIndex && switchedIndex < nginxTestIndex,
      'rollback must become active before validating the replacement Nginx upstream');
  });

  it('restricts the production SSH principal to the deploy command', () => {
    const command = read('infra/lightsail/production/deployer-command.sh');
    const installer = read('infra/lightsail/production/install-deployer.sh');

    assert.match(command, /SSH_ORIGINAL_COMMAND/);
    assert.match(command, /deploy <master-sha> <spring-image-digest> <github-actor>/);
    assert.match(command, /sudo -n \/usr\/local\/sbin\/onmaru-production-deploy/);
    assert.match(installer, /onmaru-production-deployer/);
    assert.match(installer, /restrict,command=/);
    assert.match(installer, /visudo -cf/);
    assert.doesNotMatch(installer, /NOPASSWD:\s+ALL/);
  });

  it('checks production state before rebuilding an already deployed master', () => {
    const workflow = read('.github/workflows/deploy.yml');
    const statusPath = 'infra/lightsail/production/status-blue-green.sh';
    const command = read('infra/lightsail/production/deployer-command.sh');
    const installer = read('infra/lightsail/production/install-deployer.sh');

    assert.ok(existsSync(join(root, statusPath)), `${statusPath} missing`);
    const status = read(statusPath);
    assert.match(status, /production-deploy-hold/);
    assert.match(status, /printf 'held\\n'/);
    assert.match(status, /printf 'deployed\\n'/);
    assert.match(status, /printf 'deploy\\n'/);
    assert.match(status, /branch --show-current/);
    assert.match(status, /status --porcelain/);
    assert.match(command, /status <master-sha>/);
    assert.match(installer, /onmaru-production-deploy-status/);

    const preflightIndex = workflow.indexOf('  production-preflight:');
    const buildIndex = workflow.indexOf('  build-and-scan-images:');
    assert.ok(preflightIndex >= 0 && preflightIndex < buildIndex,
      'production preflight must run before an optional production rebuild');
    assert.match(workflow, /production-preflight:[\s\S]*?outputs:[\s\S]*?deploy-required:/);
    assert.match(workflow, /build-and-scan-images:[\s\S]*?needs:\s*production-preflight/);
    assert.match(workflow, /needs\.production-preflight\.outputs\.deploy-required == 'true'/);
  });

  it('deploys only from the nightly master schedule or an explicit master dispatch', () => {
    const workflow = read('.github/workflows/deploy.yml');
    const preflight = workflow.slice(
      workflow.indexOf('  production-preflight:'),
      workflow.indexOf('  build-and-scan-images:'),
    );
    const production = workflow.slice(workflow.indexOf('  production-deploy:'));
    const aiBuild = workflow.slice(
      workflow.indexOf('      - name: Build and push FastAPI AI image'),
      workflow.indexOf('  migration-gate:'),
    );

    assert.match(workflow, /schedule:[\s\S]*?- cron: "17 18 \* \* \*"/);
    assert.match(workflow, /deploy_production:\s*\n\s+description: Deploy the latest master to production now/);
    assert.match(preflight, /github\.ref == 'refs\/heads\/master'/);
    assert.match(preflight, /github\.event_name == 'schedule'/);
    assert.match(preflight, /github\.event_name == 'workflow_dispatch'[\s\S]{0,100}inputs\.deploy_production/);
    assert.match(preflight, /vars\.PRODUCTION_DEPLOY_ENABLED == 'true'/);
    assert.match(production, /github\.ref == 'refs\/heads\/master'/);
    assert.match(production, /needs\.production-preflight\.outputs\.deploy-required == 'true'/);
    assert.match(production, /needs:\s*\n\s+- production-preflight\s*\n\s+- build-and-scan-images\s*\n\s+- migration-gate/);
    assert.match(production, /environment:\s+production/);
    assert.match(production, /checks:\s+read/);
    assert.match(production, /Require successful CI verify for this master commit/);
    assert.match(production, /select\(\.name == "verify" and \.conclusion == "success"/);
    assert.match(production, /PRODUCTION_DEPLOY_SSH_KEY/);
    assert.match(production, /PRODUCTION_SSH_KNOWN_HOSTS/);
    assert.match(production, /onmaru-production-deployer@13\.125\.191\.16/);
    assert.match(production, /deploy \$GITHUB_SHA \$SPRING_DIGEST \$GITHUB_ACTOR/);
    assert.doesNotMatch(production, /github\.event_name == 'push'/);
    assert.equal(aiBuild.match(/if: github\.ref == 'refs\/heads\/develop'/g)?.length, 3,
      'FastAPI image steps must stay out of the Spring-only master production path');
  });

  it('supports an explicit bounded rollback and blocks the rejected SHA from the next schedule', () => {
    const workflow = read('.github/workflows/deploy.yml');
    const deploy = read('infra/lightsail/production/deploy-blue-green.sh');
    const rollbackPath = 'infra/lightsail/production/rollback-blue-green.sh';
    const command = read('infra/lightsail/production/deployer-command.sh');
    const installer = read('infra/lightsail/production/install-deployer.sh');

    assert.ok(existsSync(join(root, rollbackPath)), `${rollbackPath} missing`);
    const rollback = read(rollbackPath);
    assert.match(workflow, /rollback_production:\s*\n\s+description:/);
    assert.match(workflow, /production-rollback:/);
    assert.match(workflow, /github\.event_name == 'workflow_dispatch'/);
    assert.match(workflow, /inputs\.rollback_production/);
    assert.match(workflow, /rollback \$GITHUB_ACTOR/);
    assert.match(command, /rollback <github-actor>/);
    assert.match(installer, /onmaru-production-rollback/);
    assert.match(deploy, /production-previous-sha/);
    assert.match(deploy, /production-previous-slot/);
    assert.match(deploy, /production-deploy-hold/);
    assert.match(rollback, /production-previous-sha/);
    assert.match(rollback, /production-previous-slot/);
    assert.match(rollback, /production-deploy-hold/);
    assert.match(rollback, /--profile legacy ps -aq spring-api/);
    assert.match(rollback, /legacy\) backend=spring-api/);
    assert.match(rollback, /docker start "\$target_id"/);
    assert.match(rollback, /nginx -s reload/);
    assert.match(rollback, /auth\/csrf/);
  });

  it('pins third-party actions and reports every production outcome', () => {
    const workflow = read('.github/workflows/deploy.yml');
    const actionLines = [...workflow.matchAll(/^\s*uses:\s+([^@\s]+)@([^\s#]+)(?:\s+#\s+(.+))?$/gm)];

    assert.ok(actionLines.length > 0);
    for (const [, action, ref, comment] of actionLines) {
      assert.match(ref, /^[0-9a-f]{40}$/, `${action} must use an immutable commit SHA`);
      assert.ok(comment, `${action} must retain a readable version comment`);
    }
    assert.match(workflow, /notify-production:/);
    assert.match(workflow, /if:\s+always\(\)/);
    assert.match(workflow, /PRODUCTION_DEPLOY_WEBHOOK_URL/);
    assert.match(workflow, /needs\.production-preflight\.outputs\.status/);
  });

  it('prunes only old dangling images after successful production state changes', () => {
    const deploy = read('infra/lightsail/production/deploy-blue-green.sh');
    const rollbackPath = 'infra/lightsail/production/rollback-blue-green.sh';

    assert.ok(existsSync(join(root, rollbackPath)), `${rollbackPath} missing`);
    const rollback = read(rollbackPath);
    assert.match(deploy, /docker image prune -f --filter until=168h/);
    assert.match(rollback, /docker image prune -f --filter until=168h/);
  });

  it('records a deployed master SHA and makes an identical scheduled deployment a no-op', () => {
    const script = read('infra/lightsail/production/deploy-blue-green.sh');

    assert.match(script, /production-deployed-sha/);
    assert.match(script, /Already deployed master/);
    assert.match(script, /DEPLOYED_SHA_TEMP/);
    assert.match(script, /printf '%s\\n' "\$SHA"/);
  });

  it('documents one-time bootstrap, swap boundaries, schema compatibility, and GitHub configuration', () => {
    const guide = read('infra/lightsail/README.md');

    assert.match(guide, /Blue-Green 최초 전환/);
    assert.match(guide, /PRODUCTION_DEPLOY_ENABLED/);
    assert.match(guide, /PRODUCTION_DEPLOY_SSH_KEY/);
    assert.match(guide, /PRODUCTION_SSH_KNOWN_HOSTS/);
    assert.match(guide, /expand.*contract/is);
    assert.match(guide, /스테이징.*중지/is);
    assert.match(guide, /swap.*RAM.*아니/is);
    assert.match(guide, /FastAPI.*별도 Lightsail/is);
    assert.match(guide, /03:17 KST/);
    assert.match(guide, /workflow_dispatch/);
    assert.match(guide, /Repository Variable[\s\S]*PRODUCTION_DEPLOY_ENABLED/);
    assert.match(guide, /PRODUCTION_DEPLOY_WEBHOOK_URL/);
    assert.match(guide, /rollback_production/);
  });
});
