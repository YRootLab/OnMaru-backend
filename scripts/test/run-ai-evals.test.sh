#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT

real_python="$(uv python find 3.12)"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'exec "${REAL_PYTHON}" -c '\''import os, runpy, sys; major, minor = map(int, os.environ["SIMULATED_VERSION"].split(".")); sys.version_info = (major, minor, 0, "final", 0); target = sys.argv[1]; sys.argv = sys.argv[1:]; runpy.run_path(target, run_name="__main__")'\'' "$@"' \
  >"${temporary_directory}/python3"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf '\''%s\n'\'' "$@" >"${UV_CAPTURE}"' \
  >"${temporary_directory}/uv"

chmod +x "${temporary_directory}/python3" "${temporary_directory}/uv"

for simulated_version in 3.11 3.13; do
  capture="${temporary_directory}/uv-${simulated_version}.txt"
  stderr="${temporary_directory}/stderr-${simulated_version}.txt"

  PATH="${temporary_directory}:${PATH}" \
    REAL_PYTHON="${real_python}" \
    SIMULATED_VERSION="${simulated_version}" \
    UV_CAPTURE="${capture}" \
    "${repository_root}/scripts/run-ai-evals" \
      --input fixture.json --output report.json 2>"${stderr}"

  grep -Fxq 'run' "${capture}"
  grep -Fxq -- '--project' "${capture}"
  grep -Fxq "${repository_root}/ai" "${capture}"
  grep -Fxq 'python' "${capture}"
  grep -Fxq -- '--input' "${capture}"
  grep -Fxq 'fixture.json' "${capture}"
  grep -Fxq -- '--output' "${capture}"
  grep -Fxq 'report.json' "${capture}"
  if grep -Fq 'Traceback' "${stderr}"; then
    printf 'unexpected traceback for Python %s\n' "${simulated_version}" >&2
    exit 1
  fi
done

printf 'run-ai-evals re-exec version tests passed\n'
