# Rootfs implementation and review instruction sheet

Read [the proposal](ROOTFS_SECURITY_PROPOSAL.md) first. Its scope, trust
requirements, compatibility contract, and acceptance gates govern this task.
This sheet turns that proposal into concrete work and review instructions.

Status: instructions prepared; implementation and acceptance evidence must be
recorded after work. Do not describe unchecked boxes as completed.

Captured outcome: [Implementation and verification record](ROOTFS_VERIFICATION.md).

## 1. Assignment and boundaries

The requested coding model is Claude Opus 5.5 with High reasoning. Implementation
and a fresh independent review are separate delegated tasks. Work is local to
the existing project workspace. Preserve unrelated work and upstream attribution.
Do not create additional delegated tasks unless the parent authorizes them.

Implement authenticated rootfs downloads and compatible contained extraction.
The current launcher experience and PRoot compatibility must keep working.
Do not install a build, use release keys, push, publish, file a public issue,
open a PR, or change the app ID/signing configuration. Do not inspect secrets.
Leave changes reviewable in the working tree; do not rewrite history.

Read the current account-wide/project instructions and inspect Git status and
recent history before editing. Read the README, architecture, build settings,
Bootstrap, Proot, StepJournal, managed-file provisioner, and no-follow deletion
helper. The prepared docs are expected changes, not another tool's unfinished
code to overwrite. Preserve every other preexisting change.

## 2. Preflight checklist

- [ ] Record current commit and initial changed-file list.
- [ ] Identify supported Android API, Kotlin, Gradle, AGP, SDK/NDK prerequisites.
- [ ] Trace network, cache, manual-input, retry, setup, legacy setup, removal,
      guest preparation, and journal paths.
- [ ] Confirm the current official archive URL, detached signature URL, signer,
      and public-key source. Recheck live endpoint behavior where relevant.
- [ ] Inspect a real upstream signature's signer/algorithm and the bundled key's
      full fingerprint, binding, expiry, and revocation information.
- [ ] Select host-side verifier/library from existing dependencies or a narrowly
      justified maintained dependency. Record version/source/API support.
- [ ] Identify how to exercise pure logic using local fixtures without Android
      device state, app secrets, or cloud-provider calls.

Do not delay code work for a broad audit of unrelated issues. If an unexpected
prerequisite blocks one check, keep making progress on independent work and
report the precise boundary to the parent.

## 3. Recommended work sequence

1. Add the pinned public trust anchor and a streaming detached-signature verifier.
2. Add deterministic positive/negative crypto fixtures and tests.
3. Refactor acquisition just enough to route every input through verification.
4. Preserve and test bounded download resumption and rollover recovery.
5. Add a contained extractor with compatible link and permission handling.
6. Add staging/activation logic and integrate it with existing journal behavior.
7. Add security, compatibility, failure, and crash-window tests.
8. Update README/architecture/changelog/third-party and AI-assistance prose as
   appropriate; do not add AI author tags or commit trailers.
9. Run focused checks, then repository checks and available build checks.
10. Inspect the final diff and return a concise evidence-backed handoff.

It is acceptable to reorder tightly dependent steps. It is not acceptable to
leave an input path unauthenticated while reporting the feature implemented.

## 4. Authentication implementation requirements

Use the exact pinned signer fingerprint documented in the proposal, after
rechecking the actual key. Bundle public data only. Verify compressed archive
bytes before tar parsing. Make success difficult to represent accidentally:
an internal authenticated-archive handle or similarly explicit gate should
separate untrusted files from extraction inputs.

Streaming verification must reject wrong keys and malformed signatures. A
signature issuer ID can select a candidate but cannot establish trust. Validate
the full fingerprint and actual cryptographic verification. Do not call guest
GnuPG, accept a user-imported key, use TOFU, or rely on a same-server MD5/SHA file.

Pin the selected library in the version catalog. Avoid globally registering
or replacing Android crypto providers unless a documented necessity exists.
Inspect dependency licensing and release shrinking. Test the verifier on the
project JVM; report Android runtime verification separately.

Specify error categories internally so recovery can distinguish temporary
network failures, missing manual signatures, invalid signatures, mismatched
latest pairs, format errors, and I/O failures. Do not dump binary signatures,
archive content, credentials, or private paths into logs. Do not claim a bad
signature establishes an attack rather than corruption or rollover.

## 5. Download and cache requirements

The current file name is `ArchLinuxARM-aarch64-latest.tar.gz`; the adjacent
signature is conventionally its `.sig` file. Confirm upstream spelling.
Manual delivery must provide both, and both must pass the same verification
gate as network input. Preserve the optional manual workflow without an unsafe
unsigned exception. Document the changed requirement in the build instructions.

Use app-private stable snapshots for verification and extraction. Protect the
gap between verifying an external file and later reopening it. Reverify cache
hits; existence, length, ETag, and a stale success marker are not sufficient.
Track archive/signature ownership so retries never delete user input files.

Validate range responses and validators before appending. Check Content-Range
start and any advertised totals. A server returning 200 to a range request
must trigger a restart, not an append. HTTP 416 needs deliberate recovery.
Unknown Content-Length is allowed only with bounded streaming and final
cryptographic verification. Check rename/atomic-promotion errors.

Keep network resources, redirects, retries, signature bytes, archive bytes,
and progress emissions bounded. A latest-pair mismatch must have a bounded
fresh-pair retry strategy, not a permanent loop over the same bad cache.
Do not swallow cancellation. Do not disable TLS validation. Do not globally
ban cleartext traffic without tracing other existing uses.

## 6. Extraction requirements

Use a fresh internal staging directory. Validate its exact path and that it is
not a symlink. Use no-follow cleanup of only this known staging tree.

Normalize tar member names carefully and reject absolute/traversing/malformed
paths before writes, including hard-link source paths. Enforce path-component
containment as well as final path containment. Do not silently rewrite unsafe
members into harmless-looking names.

Separate symlink payload validation from member-path validation. Valid guest
absolute links are compatible requirements. Store them without following them
on the host; do not let a later member, chmod, hard-link copy, or cleanup follow
them. If links are deferred, confirm this works on the actual official archive.
If aliases require virtual guest resolution, bound cycles and resolve absolute
targets relative to the guest root rather than Android's root.

Hard-link copies must support targets occurring later, resolve internally, and
fail clearly on unresolved targets rather than silently skipping them. Define
duplicate/type-conflict rules. Omit unsupported special files under an explicit
policy. Preserve regular/executable modes and apply directory permissions in a
safe order without following links or restoring elevated host privileges.

Bound entry count, total expanded bytes, per-entry sizes, path length, and
recursive link resolution. Handle malformed sparse entries deliberately.
Fully validate gzip completion and tar data, not just file presence. Any failure
must leave the active rootfs and launcher-owned data untouched.

## 7. Activation and state requirements

Do not bump the existing rootfs step ID to force replacement of completed
installs. Existing users' data and credentials must remain intact. Preserve
legacy-ready adoption and the existing refusal to replace a set-up system.

Only successful authenticated extraction may activate staging. Immediately
before activation recheck complete/legacy state and validate the destination.
Replace only an incomplete prior tree and use a checked same-filesystem rename.
Do not reset the journal or managed-file records before extraction succeeds.

Specify how each interruption window recovers:

1. Partial archive/signature acquisition.
2. Completed archive, not yet verified.
3. Verified archive, no staging directory yet.
4. Partial staging contents.
5. Complete staging, activation not yet attempted.
6. Incomplete old root removed, staging not yet renamed.
7. Root renamed, rootfs journal step not yet marked.
8. Rootfs step marked, later bootstrap steps incomplete.
9. Setup completed or legacy-ready found.

Recovery may repeat safe work, but must never start a partial rootfs or replace
a completed setup. A receipt/digest design may help; keep it small, versioned,
and tied to verified private bytes. Be honest if power-loss durability is
weaker than process-interruption recovery.

Trace `Proot.prepare()` and post-activation initialization, particularly binds,
CJK font placeholders, keymap compilation, provisioning, and terminal font
repair. The staged tree needs the same successful startup path as the current
tree. Do not change process-launch sandbox workarounds to solve installer bugs.

## 8. Required test matrix

Use tiny invented fixtures and local network servers. Production private keys,
credentials, device files, and downloaded code execution do not belong in tests.
Test keys may be generated fixtures or test-only key material, clearly labeled
and never packaged as production trust anchors.

### Authentication

- Authorized key, valid signature, exact original compressed bytes: succeeds.
- Change one payload byte: fails before extraction.
- Truncated/empty payload: fails.
- Missing, empty, truncated, malformed, or modified signature: fails.
- Valid signature from a different key: fails.
- Forged/incorrect signer metadata: does not confer trust.
- Supported primary/subkey case matches the documented trust policy.
- Manual, cached, and network input all reach the verifier.
- Cache content changes after an earlier successful verification: cannot bypass.
- External input replacement between snapshot/verification/extraction: cannot
  change the extracted bytes.
- Failure never invokes guest commands or marks rootfs success.

### Acquisition

- Normal full response and expected signature pair.
- Correct 206 resume with matching offset/validator.
- Ignored range returning 200: restart/truncate.
- Incorrect Content-Range start or total: reject/recover without appending.
- Changed validator/version: no mixed file.
- Stale partial/validator combination and HTTP 416: bounded recovery.
- Unknown Content-Length, short read, oversized response/signature.
- Temporary signature fetch failure after archive completion.
- Latest rollover creates mismatch: bounded fresh-pair recovery.
- Repeated mismatch: actionable terminal failure, no unsigned fallback.
- HTTP errors, redirect limit, insecure/downgrade policy, disconnect/cleanup.
- Interrupted read: retains only safe resumable state.

### Archive containment

- Regular file `../outside`, nested traversal, absolute member path, malformed
  name, and sibling-prefix trick: no outside modification.
- Relative symlink pointing outside staging followed by a child member.
- Absolute guest symlink followed by a child member: never writes to host root.
- Nested symlink chains, cycles, aliases, and link/type replacement conflicts.
- Hard link with escaping target, symlink target, later target, missing target,
  chain, cycle, and directory target.
- Preexisting symlink at staging boundary or a parent component.
- Normal cleanup with symlinks: target contents outside staging survive.
- Device nodes, FIFOs, and exact exclusion boundaries.
- Huge declared sizes, excessive entry count/expansion/path length, bad sparse
  metadata, duplicate/conflicting entries, truncated tar, corrupt gzip/trailer.
- Every failure leaves sentinels outside staging unchanged.

### Archive compatibility

- Normal `./` names, directories, files, permissions, and long supported names.
- Executable shell/tool, safe relative links, legitimate absolute guest links,
  dangling virtual-directory links, and copied hard links.
- A fixture representing the actual upstream link layout and entry ordering.
- Minimal rootfs shape succeeds only after complete extraction.
- Directory permission ordering allows creation and later cleanup.
- Actual signed upstream archive inspection/verification/extraction, if run,
  is documented separately from synthetic fixtures. Do not execute it on the
  workstation merely to call extraction successful.

### State and recovery

- Verification/extraction failure preserves journal, managed state, themes,
  widgets, external inputs, and an existing completed rootfs.
- Successful activation precedes rootfs step marking and later bootstrap steps.
- Failed activation/rename is reported; no success state is recorded.
- Every interruption window listed above has a safe retry outcome.
- Completed setup and legacy-ready markers refuse replacement.
- Failed/incomplete old installs move to the authenticated path on retry.
- Removal cleans only expected app-owned staging/cache artifacts.
- Guest preparation/provisioning assumptions are preserved after activation.

## 9. Commands and evidence

Start with focused new tests. Then run the current CI command:

```sh
./gradlew :owl:test :app:testDebugUnitTest :owl:owlJar
```

When existing prerequisites allow:

```sh
./gradlew :app:assembleDebug
```

The repo's `scripts/fetch-proot.sh` is the documented way to obtain pinned native
binaries if a candidate build needs them. It is allowed to fetch public build
artifacts with the script's existing hash checks. Do not install system packages,
change system configuration, or use sudo as an automatic build workaround.
Do not read signing files. Report absent SDK/NDK/CMake/native assets precisely.

Capture each command's exit status and relevant summary. Verify reported test
counts from current XML/output rather than old PR prose. Inspect `git diff
--check`, the final diff, and the changed-file list. Do not rerun an unchanged
full suite indefinitely after success; rerun when further edits justify it.

## 10. Independent review brief

The parent starts a fresh Claude Opus 5.5 High review task after implementation,
with both documents, the complete implementation handoff, captured checks, and
all known limitations. The reviewer begins with read-only work; do not overlap
reviewer edits with the implementer.

Review the final code, not just the proposal. Try to invalidate each security
invariant. Trace every acquisition path through trust verification and every
filesystem operation through containment. Check the pin and public-key provenance,
subkey policy, signature parser, Android providers, dependency/API/shrinker
compatibility, mutable latest pairing, cache/external races, range handling,
limits, cancellation, symlink/hard-link semantics, chmod/cleanup, gzip completion,
activation, and journal/legacy setup behavior.

Assess compatibility explicitly. Refusing all guest absolute symlinks, dropping
hard links, stripping executable modes, resetting completed installations,
removing PRoot workarounds, or breaking the manual archive workflow are material
regressions. Tests should exercise real boundaries rather than mirror code.

Report findings with severity, `file:line`, exact trigger, impact, and minimal
fix. Include unrun checks and a clear recommendation: ready for local/device
validation, requires fixes, or incomplete. "No findings" must state the review's
scope and exclusions; it is not a guarantee of security.

If the parent requests another review round, use a new delegated task with the
original brief, all previous findings, implementer responses, actual fixes, and
unresolved objections. Do not silently discard dissent or treat the first review
as approval of subsequent changes.

## 11. Required implementation handoff

Return:

1. A brief outcome and exact files changed.
2. Selected verifier, dependency version, trusted fingerprint, public-key
   source/pin/hash, and actual upstream signature observations.
3. Transport choice and any remaining HTTP/freshness/revocation limitations.
4. Manual archive/signature workflow and cache/resume/rollover policy.
5. Extraction strategy, legitimate-link compatibility, hard-link handling,
   permissions, bounds, staging, activation, and recovery design.
6. Captured tests/build results, new tests/counts where verified, and failures.
7. Actual upstream image checks performed versus fixture-only evidence.
8. Remaining Android/device/shrinker acceptance and known risks.
9. Diff review notes and any concern requiring parent resolution.

Stop after handing off the local implementation. Do not publish or install.
