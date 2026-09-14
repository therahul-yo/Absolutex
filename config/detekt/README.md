# detekt configuration

detekt is applied **once, at the root project** (see `build.gradle.kts`), not per module.
Per-module application creates a task per module per build variant, each re-parsing the same
shared sources, and pulls in detekt's Android integration — which needs the SDK. The root-only
setup is far less work and lets CI run detekt without an Android SDK or a device.

The tradeoff: no type resolution, so rules that need the compiler's type information are
inactive. That is fine for the rules we care about (naming, complexity, style).

## Files

| File | What it is |
|---|---|
| `detekt.yml` | **Deltas only.** `buildUponDefaultConfig = true`, so anything not named here keeps detekt's default. |
| `baseline.xml` | Pre-existing findings, frozen. |

Regenerate the full default config for reference with `./gradlew detektGenerateConfig` — it
lands in `config/detekt/detekt.yml` and will **overwrite** the delta file, so do it in a
scratch checkout.

## The baseline is a burn-down list, not a suppression list

Every ID in `baseline.xml` is a real finding. They are frozen so that CI gates *new* code
without first demanding a repo-wide cleanup. Deleting an entry is how you close one out.

**Do not regenerate the baseline to make a new failure go away.** That silently launders a
fresh finding into the accepted set. Either fix the finding, or make the case that the rule
is wrong for this codebase and change `detekt.yml`.

Regenerate (only when genuinely closing entries out) with:

```sh
./gradlew detektBaseline
```

Note: detekt's baseline reader rejects multi-line XML comments — it fails with
`Error on position <line>:<col> while reading the baseline xml file!`. Keep the header to
a single line.

## Current entries

All six are in files owned by other lanes at the time this landed:

| Finding | File | Verdict |
|---|---|---|
| `FunctionParameterNaming` | `source/api/NaturalOrder.kt` | `as_` dodges the `as` keyword; `aStart` would read better. |
| `LoopWithTooManyJumpStatements` | `source/api/NaturalOrder.kt` | Hand-written comparator loop. Arguably fine as-is. |
| `MagicNumber` | `core/decode/MemoryBudget.kt` | The `4L` in `bytesForPage` wants to be `BYTES_PER_ARGB_8888_PIXEL`. |
| `MatchingDeclarationName` | `core/decode/Dispatchers.kt` | File holds only `DecodeDispatchers`; rename one or the other. |
| `ReturnCount` ×2 | `source/api/NaturalOrder.kt` | Mid-loop comparator exits, not guard clauses. |

## Why only two rule overrides

`detekt.yml` overrides exactly two rules, each because the default is wrong *for this
codebase* rather than because the code is wrong:

- **`FunctionNaming.ignoreAnnotated: ['Composable']`** — `@Composable` functions are
  PascalCase by Compose convention. Without this, every composable is a finding.
- **`ReturnCount.excludeGuardClauses: true`** — guard clauses are the house style for the
  hot predicates. This exempts guard clauses *specifically* rather than raising `max`, so a
  genuinely sprawling function is still caught: `NaturalOrder.compare` still trips the rule
  and sits in the baseline.

## Version

`dev.detekt` `2.0.0-alpha.6`. It is an alpha, deliberately — see the note in
`gradle/libs.versions.toml`. The last stable 1.x (`io.gitlab.arturbosch.detekt` 1.23.8,
February 2025) was published by Gradle 8.12.1 and predates the Gradle 9 API removals this
repo's toolchain depends on. 2.0.0-alpha.6 was published by Gradle 9.6.1 and declares
configuration-cache support. Re-check for a stable 2.0.0 before the first release build.
