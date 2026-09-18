<!-- What this changes, and why. Link the spec section. -->

## Before you asked for review

- [ ] `git fetch origin && git merge origin/main` — the branch is not stale
- [ ] `git diff origin/main...HEAD --stat` lists **only** my files (three dots, not two)
- [ ] `git grep -nE '^(<<<<<<<|=======|>>>>>>>) '` prints nothing
- [ ] `./gradlew detekt lintRelease test testDebugUnitTest :app:assembleRelease` on the **pushed** commit
- [ ] `python3 tools/check-apk-size.py --apk app/build/outputs/apk/release/app-release-unsigned.apk`
- [ ] Every fix here has a test that fails without it — verified by reverting the fix and watching it go red

The third and sixth are the ones that keep costing days. A branch that is missing four modules
still compiles and still passes a local gate; a test that asserts on a string the test itself
wrote still passes with the bug restored.

## Notes for the reviewer

<!-- What you are least sure about. What you could not test. What you deliberately left out. -->
