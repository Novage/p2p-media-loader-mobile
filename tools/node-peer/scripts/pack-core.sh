#!/usr/bin/env bash
# Builds p2p-media-loader-core in a checkout of the web repo and packs it into
# .core/, to try a core that is not released: `pnpm add` it from there (see the
# README). The tarball holds the published entry points (lib/*.js), not the
# sources the web repo develops against.
#
# Usage: scripts/pack-core.sh [path to the web repo]
# The path defaults to $P2PML_WEB, then to p2p-media-loader next to this repo.
set -euo pipefail

tool_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
web_dir="${1:-${P2PML_WEB:-$tool_dir/../../../p2p-media-loader}}"
core_dir="$web_dir/packages/p2p-media-loader-core"
out_dir="$tool_dir/.core"

if [[ ! -f "$core_dir/package.json" ]]; then
  echo "pack-core: no p2p-media-loader-core in $web_dir" >&2
  exit 1
fi
web_dir="$(cd "$web_dir" && pwd)"

# A core built from uncommitted changes could not be told apart from the
# commit SOURCE names.
if [[ -n "$(git -C "$web_dir" status --porcelain)" ]]; then
  echo "pack-core: $web_dir has uncommitted changes; commit or remove them first" >&2
  exit 1
fi

# The build tools and the core's dependencies must be the ones the commit
# names, not the ones an earlier commit installed.
(cd "$web_dir" && pnpm install --frozen-lockfile)
(cd "$core_dir" && pnpm run build)

rm -rf "$out_dir"
mkdir -p "$out_dir"
(cd "$core_dir" && pnpm pack --pack-destination "$out_dir")

# A fixed name keeps package.json the same when the core's version changes.
tarballs=("$out_dir"/p2p-media-loader-core-*.tgz)
mv "${tarballs[0]}" "$out_dir/p2p-media-loader-core.tgz"

{
  echo "repository: $web_dir"
  branch="$(git -C "$web_dir" branch --show-current)"
  echo "branch: ${branch:-(detached)}"
  echo "commit: $(git -C "$web_dir" rev-parse HEAD)"
  echo "version: $(node -p "require('$core_dir/package.json').version")"
} >"$out_dir/SOURCE"

cat "$out_dir/SOURCE"
