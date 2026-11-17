# Working in this repository

Instructions for AI coding agents (Claude Code, Codex, Cursor, Copilot, and
others) and a summary for humans. `CLAUDE.md` and any other tool-specific file
point here; this file is the source of truth.

P2P Media Loader Mobile brings the P2P delivery of
[P2P Media Loader](https://github.com/Novage/p2p-media-loader) to native
players on Android and iOS. Mobile peers and web peers share one swarm, so the
peer protocol and segment identity are those of the web project, not a copy of
them. Start at [`specs/README.md`](specs/README.md).

## Writing

Write specs, comments, commit messages and pull request descriptions in clear,
simple English, close to ASD-STE100 (Simplified Technical English): short
sentences, one instruction per sentence, the active voice, and one word for one
meaning. This does not apply to code or to quoted text.

## Specs are part of the code

`specs/` documents the system as designed: the shape the code is meant to have
and the reasons for it. It is design documentation, not a roadmap. It
describes the end state, never development phases or how the code got to
where it is. Compare a decision with an alternative when this explains _why_,
but write the alternative as a hypothetical, never as something this
repository did before.

**Read before changing.** Before a change that crosses a module boundary,
touches the peer protocol, changes how requests reach the core, or adds a
player integration, read the related spec in `specs/`.

**Update in the same change.** A change that alters designed behaviour updates
the spec that describes it, in the same commit or pull request. A spec that
describes behaviour the code no longer has is worse than no spec, because
people trust it.

**Write the spec first when the design is new.** If a change adds a concept
that `specs/` does not cover, add the spec with it.

**Never weaken a spec to match the code.** If the code and the spec disagree,
the code is wrong, or the design changed on purpose and the spec is rewritten
to state the new design.

**No phases, dates or status in specs.** Write what the system does, in the
present tense. Where the code does not yet match a spec, record the gap in
[`KNOWN-ISSUES.md`](KNOWN-ISSUES.md) or the issue tracker.

**`specs/proposals/` is the one exception.** A proposal describes a design the
system does not have, with the measurement that would justify building it. It
is not a spec: nothing in it may be cited as how the system behaves. When a
proposal is adopted, fold it into the spec it extends and delete it. When it
is rejected, delete it.

## The peer protocol belongs to the web project

Swarm IDs, segment identity and the messages between peers are defined in the
web repository: `specs/segment-identity.md`, its golden vectors, and
`PEER_PROTOCOL_VERSION`. This repository never derives identity in a different
way and never changes the wire format. A mobile peer that cannot meet a web
peer on the same stream is a defect, however well it plays.

The core version this repository uses is pinned. Updating it is a change of
its own, tested with web peers in the same swarm.

## Conventions

- **Do not commit or push unless asked.** Leave changes in the working tree
  for review.
- **Keep the git state.** Do not stash, reset or rebuild the index to get a
  baseline. For a baseline, use a worktree of the base commit in a scratch
  folder.
- **Temporary edits are always removed** before a change is reported done.
- **Verify before reporting done:**
  - `./gradlew spotlessApply`, then `./gradlew build`, with `JAVA_HOME` set
    as the README says. The build fails on a warning from the Kotlin
    compiler, Android lint, detekt (with type resolution), the C compiler and
    clang-tidy, on a failed test (the JVM tests and the C tests with
    AddressSanitizer and UndefinedBehaviorSanitizer), and on a ktfmt,
    clang-format or whitespace finding; Gradle deprecations do not fail it
    yet (see KNOWN-ISSUES.md). Fix a finding; suppress one only where the
    code is deliberate, with the reason next to it.
  - CI (`.github/workflows/build.yml`) runs the same `./gradlew build` on
    Linux for each push and pull request to `next`; it does not run the
    device test or the `tools/node-peer` checks.
  - For a change to `native/` or to the engine: also
    `./gradlew :library:connectedAndroidDeviceTest` on a connected device.
  - For a change in `tools/node-peer`: `npm test` and `npm run type-check`
    there.
  - Prettier on the changed files, with the version `tools/node-peer` pins
    (after `npm ci` there):
    `tools/node-peer/node_modules/.bin/prettier --check --ignore-unknown <files>`.
- **Known issues go in [`KNOWN-ISSUES.md`](KNOWN-ISSUES.md)**: an issue found
  and not fixed gets an entry; a fix removes its entry.
- Match the surrounding code's style, comment density and naming. Comments
  explain _why_; they do not restate the code.
- **C in `native/`:** C11 with GNU extensions (gnu11, for POSIX and platform
  functions). No C++ in our code: Ada (`native/third_party/ada/`) is C++20,
  compiled as released, and our C calls only its C API. Each function that
  takes or returns a `JSValue` says who frees it; QuickJS's leak check in the
  C tests catches a value not freed. Only the engine thread touches a runtime (see
  [`specs/engine.md`](specs/engine.md)). `native/third_party/` holds code as
  released: never edit it, update it as its README says.
- **JS in `native/`:** the polyfills (`native/src/polyfills/`) reach C only
  through the natives object they get, and the JS tests are in
  `native/test/js/`, run by the C tests. Prettier formats both.

## Skills are procedures

`specs/` says what the system does and why. A skill says how to do a task in
this repository: the steps, the tools and the traps. Each one is a folder in
[`.agents/skills/`](.agents/skills/) with a `SKILL.md` whose front matter names
the skill and says when it applies. `.claude/skills` is a symlink to that
folder, so Claude Code loads the same files; an agent that loads no skills
reads the `SKILL.md` of a task before it starts that task. On Windows the
symlink needs `core.symlinks=true`.

| Skill                                                              | Use it when                                                                                      |
| ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| [update-dependencies](.agents/skills/update-dependencies/SKILL.md) | Updating any dependency: Gradle, the catalog, the NDK, CI, QuickJS, Ada, the web core, node-peer |

## Things that are deliberate, not oversights

- **The local server gives P2P to relative URLs only.** Through the server, a
  resource that a manifest names with a relative or root-relative URL reaches
  the core; one named with an absolute URL plays without P2P, and the library
  does not rewrite manifests. A data source sees every URL. See
  [`specs/scope.md`](specs/scope.md).
- **A request the core does not recognise goes to the network.** This is
  normal operation, not an error path. A disagreement between the core's parse
  and the player's must cost P2P for that request, never the playback.
- **Player integrations do not describe streams to the core.** They pass
  manifest bytes, segment requests and the playback state, as in the web
  project's `specs/player-adapters.md`.
