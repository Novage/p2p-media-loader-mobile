# Specifications

Design documentation for P2P Media Loader Mobile. These specs describe **the
system as designed**: the shape the code is meant to have, and the reasons
for it. They are not a changelog, a roadmap or a migration guide.

Read them when you need to know _why_ something is built the way it is, or
before a change that crosses a module boundary. The peer protocol, segment
identity and the core's behaviour are specified in the web repository,
[P2P Media Loader](https://github.com/Novage/p2p-media-loader), in its
`specs/` folder.

| Spec                   | Covers                                                                                   |
| ---------------------- | ---------------------------------------------------------------------------------------- |
| [scope.md](scope.md)   | Platforms, players and streams, and which requests get P2P                               |
| [engine.md](engine.md) | The JS engine that runs the core: its thread, loop, closing, globals and uncaught errors |
| [api.md](api.md)       | The public API: the web library's, and where Kotlin differs                              |

## Proposals

[`proposals/`](proposals/) holds designs that are **not** part of the system:
candidate designs recorded with the measurement that would justify building
them. When a proposal is adopted, it is folded into the spec it extends and
removed from the folder. When it is rejected, it is deleted.

| Proposal                                                       | Would decide                                                   |
| -------------------------------------------------------------- | -------------------------------------------------------------- |
| [js-core-host.md](proposals/js-core-host.md)                   | Whether the library runs the web core in an embedded JS engine |
| [javascriptcore-on-ios.md](proposals/javascriptcore-on-ios.md) | The engine's size and memory in iOS apps                       |

## Keeping these current

Specs are kept current with the code, in the same change. A spec that
describes behaviour the code no longer has is worse than no spec, because
people trust it. See [`AGENTS.md`](../AGENTS.md) for the rules that apply to
every change, by a person or an AI agent.
