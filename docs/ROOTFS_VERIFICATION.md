# Rootfs security implementation and verification record

Recorded on 2026-10-11 against baseline
`6bef35117a089f96598d3764e3041e59f141d178`.

Status: implementation and independent review complete. Full APK assembly,
Android runtime acceptance, and phone acceptance remain pending. This record
describes the reviewed source snapshot; branch history records its commit and
push status. No APK release or phone installation has been performed.

Design contract: [Security proposal](ROOTFS_SECURITY_PROPOSAL.md).
Work instructions: [Implementation and review sheet](ROOTFS_IMPLEMENTATION.md).
Final behavior: [Architecture](ARCHITECTURE.md#installing-the-rootfs).

## Implemented behavior

- Network, cached, and manually delivered rootfs archives all require a detached
  signature by the pinned Arch Linux ARM Build System key.
- Signature format, algorithm, and supported signer are checked before a large
  download or external-file copy. Full cryptographic verification still checks
  the exact compressed bytes before extraction.
- Public-key fingerprint:
  `68B3537F39A313B3E574D06777193F152BDBE6A6`.
- Bundled public-key asset SHA-256:
  `d114c6e8610068d8972c0cd4b46bbc3d60617b5a96e95b59f86fcb5a9d0d6d1a`.
- Host-side verification uses Bouncy Castle's lightweight OpenPGP API, version
  1.86, without registering a global Android security provider.
- Downloads use configured official HTTPS mirrors with normal certificate
  validation. Redirects, responses, retries, and archive sizes are bounded.
- Range continuation validates response offsets and validators. Interrupted
  resumable transfers preserve progress; unusable or persistently mismatched
  mirrors can give way to other mirrors. A try has a two-download budget.
- External archives are copied privately before verification and extraction.
  Manually supplied originals are preserved. The adjacent `.sig` is required.
- Extraction happens in a fresh private staging directory. Member names, path
  components, link targets used for copies, types, permissions, sizes, gzip
  integrity, tar checksums, and end-of-stream behavior are checked.
- Files and directories are extracted without following guest symlinks.
  Hard links are copied from internal targets; symlinks are created last with
  their guest link text preserved, including legitimate absolute guest links.
- GNU/PAX metadata size and recursion limits are applied before body parsing.
  The retained name/link metadata, entry count, expanded bytes, and file sizes
  also have explicit limits.
- Activation uses a checked staging rename and a private activation receipt.
  Process-interruption recovery preserves completed activation and never
  replaces a completed or legacy-ready setup.
- Cleanup and installation are serialized. Removal invalidates activation and
  setup records before deleting trees. A failure that kept the Linux files
  is shown as a failed removal; Linux resumes instead of reporting success.
- A valid but incompatible archive stays cached so retry does not download the
  same signed bytes unnecessarily. Changed bytes remain subject to verification.
- Existing PRoot, bubblewrap, pacman, graphics, and clipboard behavior are
  preserved. No additional isolation guarantee is claimed.

## Code and tests

The four new implementation helpers are `linux/RootfsSignature.kt`,
`RootfsDownload.kt`, `RootfsExtractor.kt`, and `RootfsInstaller.kt`.
Integration changes are in Bootstrap, Proot preparation, Runtime, the Linux
settings screen, and StepJournal reset ordering. Build dependencies, licenses,
README manual-delivery instructions, architecture, and changelog are updated.

The app has 126 rootfs tests including the optional upstream-image test,
plus one new StepJournal test. The rootfs fixtures use invented archives,
local HTTP servers, and test-only signing keys. Production private keys and
private device content are not test fixtures.

## Independent review rounds

Implementation and both remediation passes used Claude Opus 5.5 with High
reasoning. Three separate Opus 5.5 High review tasks inspected the changes
from fresh contexts. All five first-round findings and all six second-round
findings were closed in the third review.

Closed first-round findings:

1. Partial downloads discarded on mirror failover.
2. Archive metadata allocation before meaningful memory limits.
3. Repeat downloads of valid but incompatible signed archives.
4. Remote-response and unsupported-signer error classification.
5. Activation-receipt invalidation ordering during cleanup.

Closed second-round findings:

1. A range-ignoring mirror repeatedly trapping download retries.
2. Failed removal reporting success while retaining the old Linux home.
3. A persistently mismatched archive/signature pair preventing healthy failover.
4. Startup cleanup overlapping a new installation.
5. Discarding partial bytes before replacement-response validation.
6. Discovering an unsupported signature only after a full archive transfer.

The third review independently reran mirror failover, mismatch, metadata
allocation, and removal probes, and the real upstream-image test. It reported
no required remaining changes and recommended local/device validation.
This conclusion covers the rootfs installer and its lifecycle wiring, not the
whole application's security or device acceptance.

After that review, the parent corrected one removal message to say "Your Linux
files were kept" because setup metadata can already have been removed. The
parent reran app tests and release R8 shrinking after this wording change.

## Final repository checks

The parent captured exit 0 for the final repository command:

```sh
./gradlew \
  -Porg.gradle.java.installations.paths=<existing-jdk17-directory> \
  -Porg.gradle.java.installations.auto-download=false \
  -Pandroid.builder.sdkDownload=false \
  :owl:test :app:testDebugUnitTest :owl:owlJar
```

The existing Android SDK was selected through `ANDROID_HOME`.
The final result XML contains:

- OWL: 73 tests, 73 passed, zero failed/errors/skipped. This task was up to date
  in the parent run; prior Opus full runs captured its execution successfully.
- App: 192 tests, 191 passed, zero failed/errors, one skipped. The skip is the
  opt-in real-image test; app tests executed again in the parent run.
- OWL CLI jar: successful/up to date.

The parent also captured exit 0 for `:app:minifyReleaseWithR8`, using the same
existing SDK/JDK and disabled toolchain auto-download flags. Release Kotlin
compilation and R8 ran after the final message correction.

`git diff --check` passed. Reported counts were read from XML rather than copied
from narrative handoffs. Filtered test runs replace result XML, so the final
parent full invocation restored the complete app-suite result set.

The implementation passes ran lint and reported six existing RestrictedApi
errors in MainActivity and LinuxWindowActivity, with no finding in the changed
rootfs/lifecycle files. Lint was not rerun by the parent final pass; the lint
result remains a reported failure, not a successful gate.

## Real upstream image evidence

The cached official image was 829,367,415 bytes. Its detached signature was
verified with the bundled public key using GnuPG/gpgv and the app verifier.
The independent reviews ran the optional `RootfsUpstreamTest` against the
cached archive/signature pair and captured successful verification/extraction.

The third review's last upstream run passed one test with zero skips, verified
the archive in approximately 1.96 seconds, and extracted 36,765 kept entries.
The parent preserved that independent test receipt before running the full
suite, in which the opt-in test was skipped as intended.

The second review independently compared extraction with the archive and found
zero missing/extra entries and zero type, content, link-text, or permission
mismatches. Its verified counts were:

- 26,814 regular files, including 1,833 copied hard-link members.
- 8,297 symlinks: 28 absolute guest targets and 8,269 relative targets.
- 1,808 executable files preserved their executable permissions.
- 21 setuid, setgid, or sticky permissions were dropped intentionally.

No binary or script from the downloaded Linux image was executed. Workstation
verification and filesystem comparisons do not prove Android runtime behavior.

## Remaining acceptance and limitations

- Full `:app:assembleDebug` was not completed. Pinned native binaries are absent
  from `jniLibs`; the required native build tooling is also incomplete. R8 and
  unit tests do not substitute for assembly of a complete APK.
- No phone installation, ART execution, API 29–33 runtime/TLS check, or Gate E
  device acceptance was performed. Android no-follow permission operations,
  exact symlink behavior, Bouncy Castle on ART, and service/audio/widget recovery
  still require a compatible signed test build and device testing.
- Bootstrap/Runtime lifecycle mapping and locking were inspected in review.
  JVM tests exercise installer boundaries and stand-in state; no Android test
  runner exercised the full lifecycle integration.
- Valid signatures establish publisher authentication, not freshness. Older
  properly signed images remain acceptable. Signing-key rotation is a reviewed
  source/APK change; runtime revocation refresh is not implemented.
- Process-interruption recovery was tested; sudden power-loss durability is
  weaker because the extracted tree is not synced file by file.
- The extractor relies on protected Commons Compress behavior. Dependency
  upgrades must rerun its metadata, completion, and compatibility regressions.
- A mirror that repeatedly resumes correctly but makes only a little progress
  can continue receiving priority. Small continuations consume download-budget
  slots; some pathological combinations can require another manual retry.
- Existing queued update work can recreate removed-rootfs leftovers. That
  behavior predates this patch; the new installation path clears leftovers
  before activation. Background update lifecycle redesign is outside this phase.
- Removal/setup errors can show app-private filesystem paths on the phone.
  Do not publish raw test reports or error screenshots containing private data.

## Local build artifacts and rollout boundary

One initial implementation run caused Gradle to auto-download NDK
28.2.13676358 into the existing Android SDK, approximately 2.2 GB. Subsequent
remediation, review, and final parent checks explicitly disabled auto-downloads.
A temporary checksum-verified JDK 17 and the cached upstream image remain in
the task's build cache for reproducibility. They were not removed automatically.

No signing configuration or application ID was changed. The installed phone
app has not received these fixes. A compatible APK and authorized device test
are the next acceptance gate; a debug APK must not be assumed capable of
updating a Play-signed installation without changing or losing application data.

## Upstream integration, 26 October 2026

The authenticated-rootfs commit `828c06c6debce28276c7a042d481450ee99a2cd1`
was integrated locally with upstream
`57fe9af991bc2d29ff7c885f4996055d4eaed691`. This preserves both histories;
the earlier results above remain historical evidence for their original snapshot.

The integration keeps pinned signature verification, bounded acquisition,
contained staging, atomic activation and recovery, serialized removal and
legacy-ready adoption. It retains upstream's guest fork check and streamed,
SHA-256-checked cliamp installation. Explicit cancellation now reaches the
authenticated path as well as coroutine cancellation, including large file and
hard-link copies and the boundary before activation. HTTP disconnects are
requested off the UI thread; existing connection/read timeouts bound a blocked
platform operation. Space checks precede private copying, downloading and
extraction. ENOSPC stops network retries. Journal reset still saves before
changing memory and now uses `Versioned.writeFile`.

Current verification used a separate plain Kotlin/JVM harness on Linux with
Kotlin 2.4.21 and a checksum-verified, task-local JDK 25. It compiled the actual
Bootstrap, rootfs, journal/versioning, managed-file/provisioning, HTTP and stream
sources. Android Context/Log/Os and Proot/agent/pacman dependencies were explicit
host adapters; the no-follow deletion function was copied unchanged from the
production source. This is host wiring evidence, **not Android acceptance**.

The final XML records **139 tests: 138 passed, zero failed/errors, one skipped**:

- 133 repository rootfs tests, including seven new cancellation/storage
  regressions. The optional real-image test was skipped.
- The repository's StepJournal reset-order regression.
- Five host-only Bootstrap wiring checks: completed/legacy cancellation guards,
  legacy adoption, disk-space preflight, ENOSPC stopping retries, and cancellation
  stopping the next step.

Four negative controls removed the activation cancellation check, ENOSPC retry
guard, journal save-before-reset ordering and large-file cancellation check in
the isolated harness only. Each corresponding test failed; all changes were
restored before the final passing run. The integration files pass the scoped
Git whitespace check. The whole merge reports whitespace embedded in unchanged
upstream PRoot/Mesa `.patch` files; those patches are preserved byte for byte.
The pinned signing-key asset hash is unchanged.

The full repository command, with Android SDK/toolchain auto-download disabled,
was attempted and stopped at `:app` configuration because the Android SDK was
absent. No Android license was accepted, SDK installed, APK assembled, release
shrunk, app deployed or phone tested. Current ART, TLS, NIO/permission, lifecycle,
device cancellation/recovery, native-binary and full-project lint acceptance
remain open. No real upstream rootfs image was downloaded or executed for this
integration. Publication remains a separate owner decision.

## Resume point

Work is paused at the owner's request after saving the source, tests, and
documents on `fix/authenticated-rootfs`. The original upstream baseline is
recorded at the top of this document. No additional delegated work is running.

Resume with full APK assembly using the existing project versions and pinned
native binaries, followed by an explicitly authorized device test with a
compatible signing strategy. The required native tooling and phone acceptance
are still pending; do not treat a saved branch as an installed fix. Retain the
recorded JVM, upstream-image, and R8 results as separate evidence from those
remaining checks.
