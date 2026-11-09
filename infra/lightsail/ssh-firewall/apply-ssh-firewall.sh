#!/bin/sh
set -eu
#
# Manages the Lightsail instance firewall rules for SSH hardening (Issue #586).
# Only TCP/22 rules are ever touched; every other port keeps its current rules.
#
# Subcommands:
#   status                          print the current Lightsail firewall rules
#   restrict --admin-cidr CIDR ...  allow TCP/22 only from the given admin
#                                   /32 CIDRs (repeatable)
#   open-ssh                        emergency: allow TCP/22 from 0.0.0.0/0
#   close-ssh                       remove TCP/22 entirely; only valid once the
#                                   CI deploy path no longer needs inbound SSH
#                                   (outbound-only tunnel such as Tailscale or
#                                   Cloudflare Tunnel)
# Common flags: --instance NAME, --region REGION, --profile PROFILE, --yes
#
# Mutating subcommands print the computed rule plan and require --yes to apply.
# Requires: aws cli (lightsail) and jq on the operator machine.
#
# Emergency access path that does not depend on these rules: the Lightsail
# console browser SSH (AWS out-of-band), documented in
# docs/operations/runbooks/ssh-access-hardening.md.

instance=
region=
profile=
assume_yes=0
subcommand=
admin_cidrs=

usage() {
  echo "usage: $0 [--instance NAME] [--region REGION] [--profile PROFILE] status" >&2
  echo "       $0 [--instance NAME] [--region REGION] [--profile PROFILE] restrict --admin-cidr A.B.C.D/32 [--admin-cidr ...] [--yes]" >&2
  echo "       $0 [--instance NAME] [--region REGION] [--profile PROFILE] open-ssh [--yes]" >&2
  echo "       $0 [--instance NAME] [--region REGION] [--profile PROFILE] close-ssh [--yes]" >&2
  exit 2
}

validate_cidr() {
  cidr=$1
  ip=${cidr%/32}
  if [ "$ip" = "$cidr" ]; then
    echo "admin CIDR must be a single host /32 per Issue #586: $cidr" >&2
    exit 2
  fi
  oldIFS=$IFS
  IFS=.
  # shellcheck disable=SC2086
  set -- $ip
  IFS=$oldIFS
  if [ "$#" -ne 4 ]; then
    echo "admin CIDR must be an IPv4 /32: $cidr" >&2
    exit 2
  fi
  for octet in "$@"; do
    case "$octet" in
      '' | *[!0-9]*)
        echo "admin CIDR must be an IPv4 /32: $cidr" >&2
        exit 2
        ;;
    esac
    if [ "$octet" -gt 255 ]; then
      echo "admin CIDR octet out of range: $cidr" >&2
      exit 2
    fi
  done
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --instance)
      [ "$#" -ge 2 ] || usage
      instance=$2
      shift 2
      ;;
    --region)
      [ "$#" -ge 2 ] || usage
      region=$2
      shift 2
      ;;
    --profile)
      [ "$#" -ge 2 ] || usage
      profile=$2
      shift 2
      ;;
    --yes)
      assume_yes=1
      shift
      ;;
    --admin-cidr)
      [ "$#" -ge 2 ] || usage
      validate_cidr "$2"
      admin_cidrs="$admin_cidrs $2"
      shift 2
      ;;
    status|restrict|open-ssh|close-ssh)
      if [ -n "$subcommand" ]; then
        usage
      fi
      subcommand=$1
      shift
      ;;
    *)
      usage
      ;;
  esac
done

[ -n "$subcommand" ] || usage
if [ -z "$instance" ]; then
  echo "--instance NAME is required (or wire a default in the operator profile)" >&2
  exit 2
fi
if [ "$subcommand" = "restrict" ] && [ -z "$admin_cidrs" ]; then
  echo "restrict requires at least one --admin-cidr A.B.C.D/32" >&2
  exit 2
fi

aws_flags=""
if [ -n "$region" ]; then
  aws_flags="$aws_flags --region $region"
fi
if [ -n "$profile" ]; then
  aws_flags="$aws_flags --profile $profile"
fi

fetch_ports() {
  # shellcheck disable=SC2086
  aws $aws_flags lightsail get-instance-public-ports --instance-name "$instance" --output json
}

keep_non_ssh_rules() {
  jq -c '[.publicPorts[]
    | select(.fromPort != 22)
    | {protocol: .protocol, fromPort: .fromPort, toPort: .toPort,
       cidrs: (.cidrs // []), ipv6Cidrs: (.ipv6Cidrs // [])}]
    | sort_by(.fromPort, .protocol)'
}

case "$subcommand" in
  status)
    fetch_ports
    exit 0
    ;;
esac

current=$(fetch_ports)
new_ports=$(printf '%s' "$current" | keep_non_ssh_rules)

case "$subcommand" in
  restrict)
    if [ -z "$admin_cidrs" ]; then
      echo "restrict requires at least one --admin-cidr A.B.C.D/32" >&2
      exit 2
    fi
    cidrs_json=$(printf '%s' "$admin_cidrs" | tr ' ' '\n' | sed '/^$/d' | jq -R . | jq -s .)
    new_ports=$(printf '%s' "$new_ports" | jq -c --argjson cidrs "$cidrs_json" '
      . + [{protocol: "tcp", fromPort: 22, toPort: 22, cidrs: $cidrs, ipv6Cidrs: []}]
      | sort_by(.fromPort, .protocol)')
    ;;
  open-ssh)
    new_ports=$(printf '%s' "$new_ports" | jq -c '
      . + [{protocol: "tcp", fromPort: 22, toPort: 22, cidrs: ["0.0.0.0/0"], ipv6Cidrs: []}]
      | sort_by(.fromPort, .protocol)')
    echo "WARNING: this reopens TCP/22 to the whole internet. Re-run restrict as soon as the emergency window closes." >&2
    ;;
  close-ssh)
    echo "TCP/22 will be removed entirely. CI deploy must already use an outbound-only tunnel." >&2
    ;;
esac

ssh_rules=$(printf '%s' "$new_ports" | jq -c '[.[] | select(.fromPort == 22)]')
rule_count=$(printf '%s' "$new_ports" | jq '. | length')
echo "Planned rule count: $rule_count"
echo "Planned TCP/22 rules: $ssh_rules"
if [ "$assume_yes" -ne 1 ]; then
  echo "Dry run only. Re-run with --yes to apply." >&2
  exit 0
fi

ports_file=$(mktemp)
trap 'rm -f "$ports_file"' EXIT HUP INT TERM
printf '%s' "$new_ports" > "$ports_file"
# shellcheck disable=SC2086
aws $aws_flags lightsail put-instance-public-ports \
  --instance-name "$instance" \
  --port-infos "file://$ports_file" >/dev/null

echo "Applied. Verify from the admin host and a CI deploy path before closing this session."
echo "Rollback: Lightsail console > Networking > IPv4 Firewall, or re-run this script."
