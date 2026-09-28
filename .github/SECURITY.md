# Security policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Use GitHub's private reporting instead:

> **Security** tab → **Report a vulnerability**

That opens a private advisory that only the maintainer can see. You do not need to
guess an email address, and nothing you write is public until a fix ships.

If that form is unavailable to you, open a minimal public issue *asking for a private
channel* — and put no details of the vulnerability in it.

### What to include

- What the problem is, and where it happens (screen, file type, or archive)
- Steps to reproduce, or the smallest file that triggers it
- The version you are running (Settings → About) and your Android version
- Any crash log or stack trace

A file that crashes or hangs the reader is a security report, not just a bug — please
send it privately rather than attaching it to a public issue.

## What to expect

- **Acknowledgement** within 7 days
- **Assessment and a fix plan** within 30 days for a confirmed issue
- **Credit** in the advisory, if you would like it

Absolutex is maintained by one person. These are honest best-effort targets, not a
commercial SLA.

## Supported versions

Only the most recent release on the [releases page](../../releases) receives security
fixes. The app is distributed as a signed release APK and is not auto-updated, so if you
are on an older version, updating is the fix.

## Scope

**In scope**

- The app: reader, library, archive and format handling, import path
- The CI and release pipeline in this repository
- This repository itself (leaked credentials, unsafe workflow configuration)

**Out of scope**

- Vulnerabilities in third-party dependencies — please report those upstream.
  Dependabot watches them here, so they are already tracked.
- Anything requiring a rooted device, an unlocked device in someone else's hands,
  or a malicious file the user chose to open *and* an OS-level flaw in doing so.

## What this repository already does

- Secret scanning and push protection are enabled, so a committed credential is
  blocked at push time rather than discovered later
- Dependabot alerts and security updates are enabled for dependencies
- No analytics, telemetry, or crash reporting — nothing leaves your device
