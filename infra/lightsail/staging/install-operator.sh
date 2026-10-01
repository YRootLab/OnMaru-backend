#!/bin/sh
set -eu
if [ "$(id -u)" -ne 0 ] || [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: sudo ./install-operator.sh /path/to/fe-developer-public-key.pub" >&2
    exit 2
fi
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ "$DIR" != /opt/onmaru/staging-repo/infra/lightsail/staging ]; then
    echo "Install from /opt/onmaru/staging-repo/infra/lightsail/staging." >&2
    exit 1
fi
USER_NAME=onmaru-staging-operator
PUBLIC_KEY="$(python3 - "$1" <<'PY'
from pathlib import Path
import sys
lines = Path(sys.argv[1]).read_text().splitlines()
if len(lines) != 1 or not lines[0].startswith(('ssh-ed25519 ', 'ecdsa-sha2-nistp256 ')):
    raise SystemExit('Expected exactly one Ed25519 or ECDSA public key line')
if any(ord(ch) < 32 for ch in lines[0]):
    raise SystemExit('Public key contains control characters')
print(lines[0])
PY
)"
if ! id "$USER_NAME" >/dev/null 2>&1; then
    useradd --system --create-home --home-dir "/home/$USER_NAME" --shell /bin/sh "$USER_NAME"
fi
install -o root -g root -m 0755 "$DIR/operator-command.sh" /usr/local/bin/onmaru-staging-command
install -d -o root -g root -m 0755 "/home/$USER_NAME"
install -d -o root -g root -m 0755 "/home/$USER_NAME/.ssh"
printf 'restrict,command="/usr/local/bin/onmaru-staging-command" %s\n' "$PUBLIC_KEY" > "/home/$USER_NAME/.ssh/authorized_keys"
chown root:root "/home/$USER_NAME/.ssh/authorized_keys"
chmod 0644 "/home/$USER_NAME/.ssh/authorized_keys"
cat > "/etc/sudoers.d/$USER_NAME" <<'SUDOERS'
onmaru-staging-operator ALL=(root) NOPASSWD: /opt/onmaru/staging-repo/infra/lightsail/staging/start.sh, /opt/onmaru/staging-repo/infra/lightsail/staging/stop.sh, /opt/onmaru/staging-repo/infra/lightsail/staging/status.sh
SUDOERS
chmod 0440 "/etc/sudoers.d/$USER_NAME"
visudo -cf "/etc/sudoers.d/$USER_NAME" >/dev/null
printf 'Restricted staging operator installed. No general shell or production command is granted.\n'
