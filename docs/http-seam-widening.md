# Widening the `HttpCall` seam so credentials can be zeroed

Status: **scope record only — no code changed.** Written by Agent05 (cloud & offline) while the
three affected seams were fresh, at the lead's request. The change itself lands **after** the
current cloud lane, not before.

Anchored at `main` @ `a831027`. Citations name files and symbols rather than line numbers,
because line numbers drift between commits and symbols do not.

## The problem

The project's rule for secrets is that every plaintext copy is bounded: hold it in a
`CharArray`, use it, zero it in a `finally`. `CredentialStore` is built on exactly that, and so
are `TokenResponse` and `PkceChallenge`.

`HttpCall` breaks the rule, not by accident but by signature. It speaks `String` in three
places, and a `String` is immutable — it cannot be zeroed, and it stays in the heap until the
memory is reused. That is precisely what a heap dump captures.

This is already written down in `CloudTokenEndpoint`'s KDoc and in #73's and #81's descriptions.
This file exists so the *scope* is not rediscovered halfway through the work.

## The three seams

Each is a place a live credential becomes text that nothing can erase.

### 1. The request body — `HttpCall.request(method, url, headers, body: String?)`

Carries the **PKCE verifier** and the **refresh token** on every token exchange. In
`CloudTokenEndpoint.post` the secret exists as at least three separate `String`s before it
reaches the wire: `CharArray.concatToString()`, the percent-encoded copy from `URLEncoder`, and
the joined form body — plus the writer's internal buffers inside `HttpUrlConnectionCall`.

### 2. The response body — `HttpResponse.body: String`

The larger of the three, and the one most easily missed. On a **successful** token exchange this
string is the provider's JSON carrying **both the access token and the refresh token** in
plaintext. `TokenResponse` lifts them into `CharArray`s and zeroes those on `close()` — but the
`String` they were parsed out of is never zeroed, and nothing in `:remote:cloud` can zero it.

A test named `closing zeroes both tokens` is therefore a true statement about `TokenResponse`
and **not** a guarantee that the token has left the process. Worth stating plainly, because the
name invites the stronger reading.

### 3. The headers — `Map<String, String>`

Carries the **bearer token on every authorized request**, which makes it the highest-frequency
of the three by a wide margin: one copy per API call, not one per sign-in. `CloudSession
.authorizationHeaders` zeroes the `CharArray` it loaded, then immediately places the same secret
into a `String` it cannot touch.

This seam was found last, while wiring M3. A widening designed against the first two alone would
discover it midway.

## What has to change, counted rather than remembered

"Six implementors" circulated for a while and is **wrong**. Verified at `a831027`:

| | count | where |
|---|---|---|
| production implementations | **3** | `HttpUrlConnectionCall` (`Http.kt`), `CleartextHttpCall`, `ClockHttpCall` |
| test fakes and decorators | **8** | `:feature:remote`, `:remote:sync` ×4, `:remote:core`, `:remote:cloud` ×2 |
| **total declaration sites** | **11** | `grep -rn ": HttpCall {\|: HttpCall by "` |

Two more test fakes arrive with #81 and #83, making **13** once those land.

The shape of the work is worth noting: the production change is three classes, and the bulk of
the churn is fakes. Both decorators (`CleartextHttpCall`, `ClockHttpCall`) must forward whatever
is added — the same trap that made `requestStream` deliberately *not* have a default
implementation, since a default silently lets a decorator skip the new method.

## What "fixed" means

Not "use `CharArray` everywhere". The goal is narrower: **no credential should exist in a form
that cannot be erased.** That suggests, rather than mandates:

- a body that accepts `ByteArray`/`CharArray` so the caller can zero it after the call returns;
- a response read that yields bytes, letting the JSON parse happen over a buffer the caller
  owns and clears;
- headers that carry values the caller can zero, which is the awkward one — `Map<String, String>`
  is deeply idiomatic and every call site assumes it.

The headers seam may well be the one to leave last, or to solve differently. It should be a
deliberate decision, not an omission.

## Traps

- **Do not let a new method have a default implementation.** A default means a decorator that
  forgets to forward still compiles, and the bug is invisible until a token takes the
  unforwarded path. This was settled once already for `requestStream`.
- **`URLEncoder.encode` returns a new `String`.** Encoding a secret makes a second copy even
  when the content is unchanged; a base64url verifier has nothing to escape and still gets
  copied.
- **Eligible for GC is not gone.** None of these copies is retained in a field, so all are
  collectable immediately — and the bytes stay in the heap until that memory is reused, with no
  upper bound on the interval.
- **Do not widen and refactor at once.** Eleven declaration sites is already a large diff to
  review; behaviour changes hidden inside it will not get the attention they need.

## Where this should land

The cloud lane (Agent05) owns `HttpCall`'s current shape and found all three seams, but the
change touches `:remote:sync`'s clients and probes as much as `:remote:cloud`. It is a
cross-lane change and wants the lead's sequencing, after M3 at the earliest — widening the seam
while provider work is still landing on it would put a large mechanical diff directly in the
path of the feature work.
