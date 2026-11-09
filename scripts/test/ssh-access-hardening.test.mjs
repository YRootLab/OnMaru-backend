import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(path) {
  return readFileSync(join(root, path), 'utf8');
}

describe('Lightsail SSH access hardening', () => {
  it('keeps the firewall script scoped to TCP/22 with guarded mutations', () => {
    const script = read('infra/lightsail/ssh-firewall/apply-ssh-firewall.sh');

    assert.match(script, /set -eu/);
    for (const subcommand of ['status', 'restrict', 'open-ssh', 'close-ssh']) {
      assert.match(script, new RegExp(subcommand));
    }
    assert.match(
      script,
      /select\(\.fromPort != 22\)/,
      'other ports must keep their current rules untouched',
    );
    assert.match(script, /lightsail put-instance-public-ports/);
    assert.match(script, /lightsail get-instance-public-ports/);
    assert.match(
      script,
      /must be a single host \/32 per Issue #586/,
      'admin restriction must stay on single-host /32 CIDRs',
    );
    assert.match(script, /0\.0\.0\.0\/0/);
    assert.match(
      script,
      /Re-run with --yes to apply/,
      'mutating runs must stay dry-run until --yes is passed',
    );
    assert.doesNotMatch(script, /13\.125\.191\.16/, 'the script must not hardcode the server IP');
    assert.doesNotMatch(
      script,
      /ssh-rsa AAAAB3|ssh-ed25519 AAAAC3/,
      'the script must not embed SSH keys',
    );
  });

  it('documents the CI constraint, tunnel options, and the out-of-band emergency path', () => {
    const runbook = read('docs/operations/runbooks/ssh-access-hardening.md');
    const operationsIndex = read('docs/operations/README.md');

    assert.match(runbook, /Issue #586/);
    assert.match(runbook, /GitHub Actions hosted runner/);
    assert.match(
      runbook,
      /아웃바운드 전용 터널/,
      'the runbook must present the outbound-only tunnel design first',
    );
    assert.match(runbook, /Tailscale|Cloudflare Tunnel/);
    assert.match(runbook, /Lightsail 콘솔의 브라우저 SSH/, 'the emergency path must be documented');
    assert.match(runbook, /apply-ssh-firewall\.sh/);
    assert.match(runbook, /Issue #819/);
    assert.match(operationsIndex, /runbooks\/ssh-access-hardening\.md/);
  });
});
