# node-peer

The P2P Media Loader core in Node, with libdatachannel for WebRTC. It is a
peer like a browser, without a browser and without a player.

It runs the first check of
[the JS core proposal](../../specs/proposals/js-core-host.md): does the
unchanged web core meet browser peers in one swarm when its WebRTC is
libdatachannel? Later it is a headless peer for tests with mixed swarms.

## Setup

Node 24 or later, and pnpm. The core comes from a checkout of
[P2P Media Loader](https://github.com/Novage/p2p-media-loader), by default
`../p2p-media-loader` next to this repository, on the branch to test.

```bash
pnpm pack-core
pnpm install --frozen-lockfile
pnpm start
```

`pnpm start` runs `src/main.ts` directly; Node strips the types. Check the
types with `pnpm type-check`.

## The core

`pnpm pack-core` installs the web checkout's dependencies from its lockfile,
builds the core, and packs it into `.core/`, as `pnpm publish` packs it. It
refuses a checkout with uncommitted changes, and it writes the branch and the
commit it packed to `.core/SOURCE`. Another checkout: `pnpm pack-core <path>`,
or set `P2PML_WEB`.

The same commit gives the same tarball, so the integrity hash in
`pnpm-lock.yaml` pins the core: `pnpm install --frozen-lockfile` stops when
the packed core is not the one the lockfile names. To use another core: check
out the branch or commit in the web repo, then run `pnpm pack-core` and
`pnpm install`, and commit the changed lockfile.
