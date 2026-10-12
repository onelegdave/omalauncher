# Authenticated rootfs downloads and contained extraction

Status: approved scope for local implementation and review; this document is a
design and acceptance contract, not evidence that the changes have passed.

Baseline: `6bef35117a089f96598d3764e3041e59f141d178` on upstream `main`.
Prepared on 2026-10-11. Implementation must recheck the working tree and current
source before relying on the baseline's line numbers or assumptions.

Companion: [Implementation and review instructions](ROOTFS_IMPLEMENTATION.md).

Captured outcome: [Implementation and verification record](ROOTFS_VERIFICATION.md).

## 1. Outcome

A first Linux installation must authenticate the exact compressed Arch Linux
ARM archive before reading it as a filesystem archive. Extraction must write
only within a fresh private staging directory, preserve the filesystem behavior
needed by Arch under PRoot, and activate the result only after complete success.

The ordinary result stays the same: optional Arch Linux ARM, the existing guest
paths, terminal tools, graphical applications, packages, widgets, themes, and
optional coding agents. Network progress, interrupted-download recovery, and
manual archive delivery remain supported with authentication added.

An invalid download produces an actionable installation failure. It must not
produce an apparently usable Linux system, overwrite launcher data, or cause a
retry to replace a completed installation.

## 2. Scope and exclusions

Implement these together because authentication and safe extraction defend
different boundaries:

1. A bundled, explicitly pinned trust anchor for the upstream rootfs signer.
2. Host-side detached-signature verification of the compressed archive.
3. Correct acquisition, caching, and pairing of archives and signatures.
4. Extraction with path, link, type, permission, and resource validation.
5. Staging, activation, interrupted-install recovery, and journal integration.
6. Focused adversarial tests, compatible-archive tests, and documentation.

This phase does not redesign PRoot isolation, remove the bubblewrap shim,
reenable unsupported pacman sandboxing, change clipboard behavior, add shared
storage, change DNS/VPN handling, harden every executable downloader, add agent
permissions, or change GPU behavior. Authentication of cliamp, mise installers,
theme downloads, and package metadata is separate work. Avoid broad networking,
filesystem, and UI refactors.

Local source changes, tests, and a candidate build are within scope. Installing
on a phone, replacing a Play-signed app, using release signing secrets, pushing,
opening a public PR, reporting vulnerabilities publicly, publishing, and
releasing are separate actions. Do not claim this phase secures existing guest
contents retroactively or turns PRoot into a security sandbox.

## 3. Current behavior and documented compatibility reasons

The baseline `linux/Bootstrap.kt` downloads
`http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`. It accepts a
manually supplied external-file archive or a cached archive by existence alone.
It resumes `.part` files with a validator and HTTP range request, but authenticates
neither the completed bytes nor the archive publisher.

The extractor deletes `files/arch`, resets rootfs state, and writes tar entries
using their names. It creates raw symbolic links and follows ordinary host file
operations for later entries and hard-link copying. There is no root containment
check. Hard links with missing targets are silently skipped. Device nodes and
FIFOs are omitted; boot, firmware, modules, and device contents are filtered.

The journal records completed bootstrap steps and protects a completed setup
from replacement. This is valuable behavior to preserve, not a reason to bump
the rootfs step ID and reinstall everyone. Launcher widgets and themes are
outside the rootfs and bound into the guest; they must survive every failure.

Compatibility workarounds have explicit explanations in the source. The
bubblewrap shim exists because user namespaces are unavailable under PRoot.
Pacman's privilege-dropping and Landlock download sandbox are disabled because
they do not work there; its low-speed timeout is disabled for slow phone links.
Those mechanisms are outside this patch's security claim and must stay intact.

Relevant baseline code:

- [Bootstrap acquisition and extraction](https://github.com/SimonSchubert/omalauncher/blob/6bef35117a089f96598d3764e3041e59f141d178/app/src/main/kotlin/dev/omalauncher/linux/Bootstrap.kt).
- [Guest process setup and binds](https://github.com/SimonSchubert/omalauncher/blob/6bef35117a089f96598d3764e3041e59f141d178/app/src/main/kotlin/dev/omalauncher/linux/Proot.kt).
- [Bubblewrap compatibility shim](https://github.com/SimonSchubert/omalauncher/blob/6bef35117a089f96598d3764e3041e59f141d178/app/src/main/assets/bwrap-shim.sh).
- [Journal](https://github.com/SimonSchubert/omalauncher/blob/6bef35117a089f96598d3764e3041e59f141d178/app/src/main/kotlin/dev/omalauncher/upgrade/StepJournal.kt).
- [Deletion without following symlinks](https://github.com/SimonSchubert/omalauncher/blob/6bef35117a089f96598d3764e3041e59f141d178/app/src/main/kotlin/dev/omalauncher/LauncherData.kt).

## 4. Threat model

Assume the archive, signature, HTTP response headers, redirect destinations,
download validators, and preexisting cache files are untrusted until checked.
An attacker could replace traffic, cause partial transfers, replay old signed
images, supply malformed tar metadata, or create archives designed to escape
their destination. Local manual delivery is a convenience, not a trust grant.

Authentication must defeat modification or substitution by an entity that
does not possess the trusted upstream signing key. Containment must defeat
archive-directed writes outside the extraction root, even if the bytes came
from a trusted signer. Resource checks limit obvious disk and memory exhaustion.

This does not defend against a compromised upstream signer, a compromised APK,
malware already executing with the launcher's UID, or all malicious guest code
after installation. Android's application boundary remains relevant; guest
root is simulated and is not phone root. A signed image proves publisher
authentication, not that every program in it is harmless.

Replay is a separate property. A valid signature alone does not establish that
an image is the newest available. Do not invent a trusted release timestamp from
HTTP headers or the phone's clock. Unless an authenticated version source is
added within the same focused design, document that older valid signed images
can be accepted, including manually supplied ones.

## 5. Trust anchor and cryptographic design

### 5.1 Upstream identity

Arch Linux ARM's [download page](https://archlinuxarm.org/about/downloads)
publishes detached signatures for its root filesystem releases. Its
[package-signing page](https://archlinuxarm.org/about/package-signing) identifies
the Arch Linux ARM Build System signing key. The fingerprint observed in both
sources and independently printed from the official public-key material is:

`68B3537F39A313B3E574D06777193F152BDBE6A6`

Treat this as a full fingerprint, not a short key ID. The official historical
keyring at commit `91e6b11698f8df66042d56aaa56fbe9c9263847d` of
`archlinuxarm/archlinuxarm-keyring` was fetched over validated HTTPS during
proposal preparation. GnuPG printed that fingerprint for the Build System key.
The full downloaded keyring's SHA-256 was
`50a08f82ce3cd524a552da4bfa37f3e04b2c9da468e2fce5783474b58a34c518`.

This historical keyring is provenance evidence, not proof of current revocation
status. The implementer must inspect the actual signing key, signature, and
current upstream key information, document the selected public-key resource,
and make any expiry or revocation limitations explicit. Do not silently trust
every key in that keyring. Prefer bundling only the required public key and any
valid, bound signing subkey needed by the actual rootfs signature.

### 5.2 Verification location

Verification runs inside the Android host app before extraction and before any
guest command. Running GnuPG from the downloaded image would be circular trust.
Do not shell out to an unavailable host binary, depend on Termux being installed,
or ask a coding agent to decide whether a signature looks trustworthy.

Reuse installed libraries if they provide a correct OpenPGP verifier. Otherwise
a small maintained OpenPGP library dependency is justified. Select a concrete
version from official documentation and record why it is needed. Do not
implement OpenPGP or cryptographic primitives by hand. Avoid changing Android's
global security-provider order; use explicit verifier implementations/providers
where supported. Check minimum API compatibility and release shrinker behavior.

The verifier must stream the compressed bytes, not decompress before checking
the signature. It must bind the result to the exact bytes later extracted,
require an authorized signer, and reject malformed, missing, unsupported,
incorrect, or wrong-key signatures. Short key ID matching is insufficient.
If supporting a signing subkey, validate its binding to the pinned primary key
and its relevant key flags, expiry, and revocation policy. Prefer the simplest
policy that matches the real upstream artifact.

Do not accept an arbitrary user-supplied public key, trust on first use, an
automatically imported keyserver result, or a checksum fetched alongside an
untrusted archive as the authority. No "continue anyway" path belongs here.

### 5.3 Transport policy

Prefer HTTPS endpoints with ordinary certificate validation and bounded
redirects. Before choosing one, test the exact archive and signature URLs.
The existing `os.archlinuxarm.org` host failed certificate hostname validation
when addressed as HTTPS during this review. Merely changing its scheme is not
a working solution. Never disable TLS checks or add a permissive trust manager.

If a working official HTTPS mirror is found, use it with detached signatures.
If upstream delivery remains HTTP-only, authenticated payload delivery may use
the existing endpoint provided a signature is verified against the bundled key
before any extraction. A forged signature or missing signature must fail closed.
The trust anchor must not be acquired from that unauthenticated connection.
Document that signatures protect authenticity while HTTP remains susceptible to
availability interference, replay, and observation of transfer metadata.

Validate redirects rather than assuming the initial URL's scheme constrains
the final connection. Keep the transport change focused on the rootfs path;
global cleartext policy changes could break unrelated existing code.

## 6. Archive and signature acquisition

### 6.1 Three entry paths, one verification gate

Network downloads, completed cache files, and manually supplied archives all
converge on the same host-side authentication gate. None may return directly to
extraction because a file exists, has a familiar filename, or has a prior marker.

For manual delivery, accept the archive plus its adjacent `.sig` file using the
existing external-files workflow. Document both filenames and commands. A
missing signature must explain what the user needs to provide. Preserve
user-supplied input on failure; do not delete it as automatic cache cleanup.
Snapshot external input into app-private storage before verification/extraction
if necessary to prevent replacement between those operations.

### 6.2 Mutable latest and signature pairing

The `latest` URL can change between requests. An archive and a separately fetched
signature may belong to different upstream generations. Verification detects
that mismatch, but recovery must avoid retaining the same mismatched pair
forever or retrying without authentication.

Fetch a bounded signature, obtain or resume the archive, and verify the pair.
Handle rollover with a bounded attempt policy consistent with the existing
bootstrap retries. Preserve a completed archive when the failure is a temporary
signature fetch failure if it can be reverified later; discard or quarantine
app-owned mismatched/corrupt bytes when needed to make progress. Explain errors
without asserting whether a mismatch was corruption, rollover, or an attack.

An ETag or Last-Modified validator only supports resumption consistency. It does
not establish origin authenticity. Signature verification is always the final
gate, even after a server claims the download is complete.

### 6.3 Resumption invariants

Preserve range resumption while validating response status and Content-Range.
Append only when the returned start offset equals the local partial length and
the validator is appropriate. On a full response, truncate/restart rather than
append. Handle invalid ranges, changed validators, servers ignoring range
requests, unknown lengths, and HTTP 416 without promoting bogus completion.

Do not splice different archive generations. Ensure stale validator files cannot
authorize an unrelated partial file. Check final lengths when known, verify the
signature even when the length is unknown, and check file promotion/rename
results. A `.part` file or partial signature must never be a completed cache hit.

### 6.4 Bounds, cleanup, and progress

Use connection/read timeouts and always disconnect/close network resources.
Bound signature bytes, redirect count, compressed archive bytes, and retries.
Define limits from the observed official archive with enough documented margin
for normal growth. Stream; do not hold the archive or the extracted system in
memory. Extraction must have independent expanded-byte and entry-count limits.

Display verification as part of setup progress. On failure retain a useful
message and existing retry behavior. Do not broaden this into a new UI workflow.
Honor coroutine cancellation rather than swallowing it in generic retry catches.

## 7. Contained extraction and filesystem compatibility

### 7.1 Fresh staging directory

Create a dedicated sibling of the rootfs inside app-private internal storage.
It must be a real directory with no preexisting archive-controlled children.
Cleanup must be restricted to the known staging name and must not follow
symlinks. Check every destructive operation's exact target.

No guest process may run from staging. Do not call provisioners or reset the
journal before authentication and successful extraction. Staging paths and
rootfs paths must be validated as expected application-controlled locations;
an unexpected symlink at the staging/root boundary must fail safely.

### 7.2 Member paths

Validate every tar entry before processing it, including entries that might
otherwise be skipped. Define supported normalization for leading `./` and
trailing slash directory names. Reject absolute member paths, NULs, parent
traversal, malformed names, and unsupported encodings/types that cannot be
interpreted safely. Do not use string-prefix containment without a separator.

Resolve writes as root-relative paths. A normalized destination must remain
beneath staging. Check path components without following archive-created host
symlinks. Canonicalization alone is insufficient if a later operation follows
a replaced path component. Fresh staging and deferred link creation can avoid
many races and simplify the proof; document the remaining concurrency assumption.

Do not silently sanitize an escaping path into a different valid filename.
Reject the archive with a clear error. Define behavior for duplicate entries,
file/directory conflicts, links overwritten by later members, and excessive
path length. Prefer rejecting ambiguous conflicts unless the official archive
requires a specific safe rule demonstrated by a test.

### 7.3 Symbolic links

Linux depends on symbolic links, including links using absolute guest paths.
An absolute guest target such as `/usr/...` names a location inside the guest;
it must not be interpreted as an Android-host destination during extraction.
Dangling links into virtual `/proc`, `/dev`, or `/run` can be legitimate.

Preserve valid link text and PRoot semantics. The safety requirement is that no
extraction write, chmod, copy, mkdir, or cleanup follows a link outside staging.
Do not blanket-ban absolute guest links, all `..` within link text, or all
symlinks. Distinguish archive member paths from symbolic-link payloads.

One acceptable approach extracts directories/files without symlink parents and
creates validated links after content extraction. If the real upstream archive
contains files addressed through symlink aliases, implement a bounded virtual
guest-root resolver or another demonstrated-safe strategy. Never let host
`File` canonicalization resolve an absolute guest link into Android `/usr`.
Check link cycles, type collisions, alias writes, and normalized target semantics.

### 7.4 Hard links

Android app storage does not support the current guest hard-link approach, so
materializing file copies remains acceptable. Defer unresolved targets until
they can be handled; a missing target must not silently drop a required file.
Resolve targets entirely within the guest/staging model and never copy through
an escaping host symlink. Test links to later entries and chains, cycle limits,
directory targets, missing targets, and type conflicts. Do not read arbitrary
host files to satisfy an archive link.

### 7.5 Types and permissions

Retain the existing policy to omit unusable device nodes and FIFOs, boot files,
firmware, and kernel modules. Confirm whether top-level `dev`, `boot`, `proc`,
and runtime directories need safe placeholders; test exact exclusions.

Preserve executable bits for shells, tools, and loaders, along with regular
read/write permissions needed by the guest. Do not restore real ownership,
setuid/setgid, special device metadata, ACLs, or archive-specified capabilities.
Do not chmod through a symbolic link. Apply restrictive directory modes only
after children are written if early application could break extraction.
Keep any intentional user-write permission adjustment compatible with cleanup.

### 7.6 Complete archive validation

A partial or malformed tar must fail even if some expected files appeared.
Validate entry sizes, unsupported sparse semantics, counts, expanded bytes, and
gzip corruption/truncation. Ensure gzip integrity/trailer checks are actually
reached: tar's end marker alone may stop consumption before gzip validation.
Define handling of concatenated/trailing streams rather than accidentally
accepting unauthenticated trailing interpretations.

Before activation confirm a minimal expected rootfs shape, such as the shell,
pacman, configuration paths, and required symlink layout. Shape validation is
an additional compatibility check, never a substitute for the signature.

## 8. Activation, journal, and recovery

The intended state sequence is acquisition -> authenticated private archive ->
validated staging -> activated rootfs -> rootfs journal mark -> existing
configuration/package steps -> completed setup.

Preserve the existing `rootfs` step ID for completed installations. A security
change to future extraction must not cause all users' roots to be overwritten.
Adopt legacy-complete setup exactly as before. Never set `setupComplete` early.

Before activation recheck that setup is not complete and there is no legacy
ready marker. Replace only a provably incomplete previous rootfs. Validate
paths and use the existing no-follow deletion behavior. Activate with a checked
same-filesystem rename or an equally explicit operation. Do not copy staging
piecemeal into an active rootfs.

Filesystem activation and journal writes are separate operations. Design for
process death before and after each. A small versioned receipt bound to the
verified archive's digest and contained in the completed staged image is one
possible way to recognize a successful activation before the journal mark.
Do not trust an unverified external receipt. If choosing a simpler recovery
strategy, prove it cannot replace a completed setup, start partial contents, or
erase launcher data. Document any re-download/re-extraction cost after crashes.

There is an existing preparation subtlety: `Proot.prepare()` can run before
rootfs extraction and creates placeholders only when the rootfs exists. Trace
font, keymap, bind-mount, and managed-file behavior after activation; do not
assume a staging rename automatically redoes preparation. Preserve the
launcher's configuration and font repair paths.

Delete app-owned archive/signature caches only after successful activation and
state recording, or deliberately after an authentication failure that needs
fresh bytes. Do not delete manually delivered files as failed-download cleanup.
Removing Linux should clean new app-owned rootfs staging/cache artifacts as
appropriate without touching themes, widgets, or user inputs.

No new cancel button is required in this phase. Nevertheless interruption and
existing cancellation must leave a safe, retryable state. Never run recursive
cleanup concurrently with a still-writing download/extraction worker.

## 9. Expected implementation shape

Keep orchestration in `Bootstrap.kt`. Isolate pure, testable authentication,
acquisition, extraction, and activation logic where this makes security checks
observable without Android services. Small helpers are preferable to a new
installation framework. The exact file split is the implementer's choice.

Likely touched areas are `linux/Bootstrap.kt`, focused `linux/` helpers, a
public-key asset, narrowly required Gradle dependencies, tests, README build
instructions, architecture notes, third-party notices if a dependency needs
them, and an Unreleased changelog entry. Changes to global process launch,
Wayland, widgets, theme extraction, or unrelated UI require a direct reason.

The project currently uses Apache Commons Compress for tar reading. Reuse it.
Prefer Java/NIO no-follow operations supported by Android 10+ and thin Android
permission wrappers that can be verified separately. Do not assume JVM
filesystem tests prove every Android storage behavior.

## 10. Acceptance gates

### Gate A: trust and provenance

- Bundled key and exact fingerprint agree with documented upstream evidence.
- A real upstream signature is inspected, and supported signing behavior is
  recorded. A complete real-image verification is reported separately if run.
- No runtime trust bootstrap from the downloaded filesystem or arbitrary keys.
- The selected crypto library version and Android compatibility are documented.

### Gate B: deterministic security tests

- Valid authorized signatures pass; modified archives, signatures, and wrong
  keys fail; all three input paths require verification.
- Resumption and latest-generation mismatch cannot bypass verification.
- Traversal, link escape, hard-link abuse, conflicts, corrupt/truncated archives,
  and limit violations cannot write outside staging or activate partial data.
- Successful fixture extraction preserves valid links and executable modes.
- Failure and interruption leave journal and launcher data in correct states.

### Gate C: project verification

Run the repository's CI checks: `:owl:test`, `:app:testDebugUnitTest`, and
`:owl:owlJar`. Build `:app:assembleDebug` when the existing Android SDK, NDK,
CMake, and pinned native binaries allow it. Explain missing prerequisites;
never silently change SDK/AGP/Gradle versions to manufacture a green build.
Build/shrinker verification is particularly relevant to a new crypto dependency.

### Gate D: independent review

A fresh Claude Opus 5.5 High task reviews the full brief and final diff, including
trust bootstrapping, real archive compatibility, external input races, archive
link semantics, memory/disk bounds, activation and journal crash windows,
Android compatibility, and release risks. Resolve blocking findings and repeat
review with the previous objections included when changes require it.

### Gate E: device acceptance, reported separately

A compatible signed test build on an authorized device must eventually verify
fresh setup, terminal execution, one graphical window, keymap/font preparation,
ordinary package installation, and retry after interruption. Test with and
without chosen agents. This phase does not authorize device installation.
Until captured, label the device gate pending rather than inferring it from
JVM tests or compilation.

## 11. Rollout and maintenance

Existing installed Linux systems must continue to start and update without a
new rootfs download. Failed/incomplete first installs should adopt the new
authenticated path on retry. Existing unverified cache files must be verified
or safely replaced, not grandfathered in.

Manual-install documentation adds the detached signature and explains missing
or mismatched-pair errors. User-facing text should explain the action needed;
cryptographic internals belong in developer docs. Signature/key rotation is a
maintainer-reviewed APK/source change, with documented provenance and tests.
Do not silently widen signer trust on a network error.

For any future release, use the normal signing and release process. A debug APK
cannot be assumed to replace a Play-signed installation. Never uninstall the
installed launcher as an automatic workaround, since that would lose app data.

## 12. Completion report

The implementer must report changed files, design choices, key/library
provenance, captured command results, added test coverage, behavior of manual
inputs, cache/resume policy, compatibility evidence, and residual limitations.
The independent reviewer must report severity, exact locations, reproducible
triggers, impacts, and minimal fixes for findings, plus checks not performed.

Keep implementation complete, review complete, candidate built, upstream
artifact verified, and device accepted as distinct statuses. The proposal
and an agent's statement are not themselves verification evidence.
