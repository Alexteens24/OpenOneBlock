# Contributing to OpenOneBlock

OpenOneBlock is currently establishing its domain and execution semantics. Changes should preserve
the module boundaries and safety invariants described by the project documentation.

## Development setup

1. Install JDK 21.
2. Clone the repository.
3. Run `./gradlew spotlessCheck build` before submitting changes.

Use the Gradle Wrapper committed to this repository; a separate Gradle installation is not needed.

## Change guidelines

- Keep `openoneblock-api` free of Paper, WorldEdit, economy, and custom-content implementation types.
- Keep `openoneblock-core` platform-independent.
- Route platform scheduling and external plugins through explicit ports and adapters.
- Add tests for domain invariants and failure recovery behavior.
- Do not introduce public API solely for a single implementation detail.

Create focused branches using `agent/<description>` or another short descriptive name. Keep commit
messages terse and imperative, and explain behavior and validation in the pull request description.
