#!/bin/sh
set -eu
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: sudo ./install-deployer.sh /path/to/ci-public-key.pub" >&2
    exit 2
fi
DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
if [ "$DIR" != /opt/onmaru/repo/infra/lightsail/production ]; then
    echo "Install from /opt/onmaru/repo/infra/lightsail/production." >&2
    exit 1
fi
PUBLIC_KEY="$(python3 - "$1" <<'PY'
from pathlib import Path
import sys

lines = Path(sys.argv[1]).read_text().splitlines()
if len(lines) != 1 or not lines[0].startswith("ssh-ed25519 "):
    raise SystemExit("Expected exactly one Ed25519 public key line")
if any(ord(character) < 32 for character in lines[0]):
    raise SystemExit("Public key contains control characters")
print(lines[0])
PY
)"
USER_NAME=onmaru-production-deployer
if ! id "$USER_NAME" >/dev/null 2>&1; then
    useradd --system --create-home --home-dir "/home/$USER_NAME" --shell /bin/sh "$USER_NAME"
fi
install -o root -g root -m 0755 "$DIR/deployer-command.sh" /usr/local/bin/onmaru-production-deploy-command
install -o root -g root -m 0755 "$DIR/deploy-blue-green.sh" /usr/local/sbin/onmaru-production-deploy
install -o root -g root -m 0755 "$DIR/status-blue-green.sh" /usr/local/sbin/onmaru-production-deploy-status
install -o root -g root -m 0755 "$DIR/rollback-blue-green.sh" /usr/local/sbin/onmaru-production-rollback
install -d -o root -g root -m 0755 "/home/$USER_NAME/.ssh"
printf 'restrict,command="/usr/local/bin/onmaru-production-deploy-command" %s\n' "$PUBLIC_KEY" > "/home/$USER_NAME/.ssh/authorized_keys"
chown root:root "/home/$USER_NAME/.ssh/authorized_keys"
chmod 0644 "/home/$USER_NAME/.ssh/authorized_keys"
cat > "/etc/sudoers.d/$USER_NAME" <<'SUDOERS'
onmaru-production-deployer ALL=(root) NOPASSWD: /usr/local/sbin/onmaru-production-deploy *
onmaru-production-deployer ALL=(root) NOPASSWD: /usr/local/sbin/onmaru-production-deploy-status *
onmaru-production-deployer ALL=(root) NOPASSWD: /usr/local/sbin/onmaru-production-rollback *
SUDOERS
chmod 0440 "/etc/sudoers.d/$USER_NAME"
visudo -cf "/etc/sudoers.d/$USER_NAME" >/dev/null
printf 'Restricted production deployer installed.\n'
