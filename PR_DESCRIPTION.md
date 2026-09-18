Branch: feat/fix-thumbnail-flake
Commit: 5b176f7

Reproduction: 50-run `corrupt source bytes throw IOException` using `Dispatchers.Unconfined` (no sleeps). The pipeline fix (LAZY deferred + invokeOnCompletion after registration) ensures `IOException` from `loadFromSource` propagates cleanly through `await()` and isn't masked by concurrent/deferred handling.

Result: 50/50 passes on fixed branch (original `ThumbnailPipelineTest` passes consistently; reproduction script added).
