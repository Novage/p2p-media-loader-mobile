# Known issues

Issues found and not fixed, and gaps between the code and a spec. A fix
removes its entry. A decision to keep a behaviour by design goes in the spec
that describes the behaviour, not here.

## Open

- **The library's Kotlin metadata is for Kotlin 2.4.** The build sets no
  `apiVersion` or `languageVersion`, so a published build could not be used
  by an app that compiles with Kotlin 2.2 or older (a Kotlin compiler reads
  metadata one version ahead). The oldest Kotlin the library supports is
  decided when publishing is set up, and these two options follow from it.
