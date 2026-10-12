# Architecture

How Omalauncher is put together, for anyone working on it.

An Android home-screen launcher that runs Arch Linux ARM under proot and acts
as its Wayland compositor. The home screen (widgets, pinned apps, the bar
and its app list) is Jetpack Compose; every Linux window opens as its own Android task,
so it gets a Recents card and behaves like a separate app.

Linux is optional. Without it the launcher still has Omarchy's themes and
wallpapers, Android apps from the bar and the widgets that don't run commands.
Everything else is offered as an install from settings › linux and wherever
it appears: Linux apps, terminal widgets, coding agents, pacman and Files.
Widgets that need Linux (`Program.needsLinux`: cmd/stream sources, `Terminal`,
run/terminal/app actions, `requires`) show as placeholders in their cells until
it's installed. Removing Linux deletes `files/arch` and its leftovers (a
staging directory, the download cache) only.

Launcher data that the guest also uses lives outside the rootfs and is bound
into it by proot (`LauncherData.kt`), so installing or removing Linux never
touches it: `files/omarchy` is `~/.local/share/omarchy` and `files/widgets` is
`~/.config/omalauncher/widgets`.

## Source layout

- `app/src/main/cpp/wlbridge.c`: JNI for unix sockets with fd passing, shm
  mapping, per-surface pixel stores, and compositing them into a window's
  `ANativeWindow`
- `wayland/`: the Wayland server in Kotlin (wl_compositor, wl_shm,
  wl_subcompositor, wl_seat, wl_output, xdg_wm_base, xdg-decoration) and
  `WindowRenderer`, which draws one window into its activity's SurfaceView
  (or, for a widget's `Terminal`, into a TextureView in the tile)
- `linux/`: proot, the rootfs setup and its update steps, pacman, coding
  agents, and the bridges to Android (audio, URLs, /proc)
- `ui/`: the Compose home screen, the bar and its app list, settings and Files
- `widgets/`: the widget engine; the language itself is in the `owl` module
- `theme/`: Omarchy themes, applied to the launcher and the guest
- `upgrade/`: how installs follow app updates (see below)

## Rendering

A client's commit copies only the damaged rectangles of its shm buffer into
the surface's pixel store, outside the server lock, and hands the buffer back
right away. Each window's `WindowRenderer` runs on its own thread: at vsync it
snapshots the surface tree, redraws only the dirty region of the SurfaceView
(everything, if a surface moved or appeared), posts, and then sends the frame
callbacks. Hidden windows get no frame callbacks, so their clients stop
drawing.

## GPU acceleration

Opt-in, in settings › linux, on Mali GPUs (v10+: Pixel 7 and newer, recent
MediaTek). Turning it on installs Mesa and the Vulkan loader with pacman and
the pinned PanVK-kbase driver (a Mesa Vulkan driver for Android's
`/dev/mali0`, from zenithblue-oss/panvk-kbase-android, checked against its
sha256) into `/usr/local`, where pacman never touches it. OpenGL is Arch's
stock Mesa on top of it, via zink. Frames still arrive as wl_shm buffers
(`MESA_VK_WSI_DEBUG=sw`), so the compositor is the same either way.

`linux/Gpu.kt` only switches guest apps to the GPU (`Proot.graphicsEnv`)
after a check passes (vulkaninfo finds PanVK, eglinfo gets zink), and checks
again after every system update; if that fails, apps render in software
(llvmpipe) and settings offers a retry. One app can opt out with
`GALLIUM_DRIVER=llvmpipe app`. Terminal apps (foot) draw on the CPU and
don't change.

## Installing the rootfs

The first setup step (`rootfs` in `linux/Bootstrap.kt`) gets Arch Linux ARM's
generic aarch64 tarball, checks it, and unpacks it. Only Android-side code
runs until it's in place; no guest command runs before.

- **Trust anchor.** The tarball's detached signature must be by the Arch Linux
  ARM Build System key, pinned by its full fingerprint
  `68B3537F39A313B3E574D06777193F152BDBE6A6` (`RootfsSignature.kt`). The
  public key is `app/src/main/assets/archlinuxarm-build-system.gpg` (SHA-256
  `d114c6e8610068d8972c0cd4b46bbc3d60617b5a96e95b59f86fcb5a9d0d6d1a`):
  `gpg --export --export-options export-minimal 68B3537F39A313B3E574D06777193F152BDBE6A6!`
  from `archlinuxarm.gpg` in archlinuxarm/archlinuxarm-keyring at
  `91e6b11698f8df66042d56aaa56fbe9c9263847d` (that repository's newest commit
  in October 2026; the keyring file's SHA-256 is
  `50a08f82ce3cd524a552da4bfa37f3e04b2c9da468e2fce5783474b58a34c518`). The
  fingerprint matches archlinuxarm.org/about/package-signing and the downloads
  page. Only the primary key, its user ID and self-signature are bundled: no
  encryption subkey, no third-party certifications, no other keys. The key
  has no expiry and, in that keyring and on keyserver.ubuntu.com in October
  2026, no revocation; the app can't learn of a later revocation by itself.
- **Verification** (`RootfsVerifier`) streams the compressed bytes through
  Bouncy Castle's OpenPGP code (its lightweight API; no security provider is
  registered) before anything reads them as an archive. It accepts exactly
  what upstream publishes: one binary v4 signature made by the pinned primary
  key, RSA with SHA-256 or stronger (the 2026-08-05 release is RSA 4096 with
  SHA-512). A wrong key, a malformed or armored signature, more than one
  signature, or a forged issuer fails. Unreadable signatures (damaged, cut off,
  a captive portal's page) and readable ones it refuses (another key, an
  unsupported kind) are told apart: the first is a download problem, the second
  means the app may need an update. The result is a handle on the open file
  (`VerifiedRootfs`), the only thing the extractor accepts, which also rechecks
  the bytes' SHA-256 as it unpacks them.
- **Download** (`RootfsDownload.kt`). `os.archlinuxarm.org` redirects over
  plain HTTP and has no certificate for its name, so the app downloads from
  official mirrors that serve HTTPS with valid certificates (`ROOTFS_MIRRORS`),
  following at most 5 redirects and only to HTTPS. The signature is what
  authenticates; HTTPS adds privacy and turns tampering on the way into an
  early failure. The signature comes first, and is parsed and checked against
  the pinned key's policy (the same code `RootfsVerifier` uses) before any
  tarball is downloaded; only the full check of the bytes accepts anything.
  Then the tarball: a finished one from an earlier try, or a download that
  resumes an interrupted one with If-Range and a 206 whose Content-Range
  starts exactly where the partial file ends (anything else asks for the whole
  file, at most once per try). A transfer that breaks off after progress ends
  the try, and the step's retry continues it at the same mirror, which goes
  first then; but only if that mirror can continue it: a 206, or a first
  download from a mirror that sends `Accept-Ranges: bytes` and a validator. A
  mirror that answered a range request with the whole file, or that can't be
  reached, sends nothing, a damaged or refused signature, or too much data,
  gives way to the next one within the try. The partial file stays until a
  server's whole-file reply checks out (not re-encoded, not over the limit),
  and another mirror continues it only with the same Last-Modified and length
  (the mirrors copy the file with its time; ETags differ or are missing).
  "latest" can move between the two requests: a pair that doesn't match gets
  a fresh signature, and bytes that were kept or continued get one fresh copy
  from that mirror. A whole copy that its own mirror's signature doesn't fit
  sends the try on to the next mirror for a fresh pair, and that mirror goes
  last in later tries (`.mismatched`, cleared when an install succeeds or
  Linux is removed). At most two tarballs are downloaded per try; then it
  fails without an unsigned fallback, and the step doesn't retry it by
  itself. A signature by another key is tried at the other mirrors too, and
  then fails saying the app may need an update; no other key is ever
  accepted. A tarball over 2 GB or a signature over 16 KB is refused.
  Download files live in the cache dir as `ArchLinuxARM-aarch64-latest.tar.gz`
  (finished, still to be verified), `.part` and `.part.json` (URL, validator,
  Last-Modified, length).
- **Pushed tarballs.** A tarball pushed to
  `Android/data/dev.omalauncher/files/` is used instead of a download, but only
  with its `.sig` next to it. It's copied into the cache dir first and
  verified and unpacked from that copy, so changing the pushed file meanwhile
  can't matter. A signature the app would refuse is reported before the copy.
  The pushed files are never deleted.
- **Unpacking** (`RootfsExtractor`) writes only into `files/arch.staging`, a
  fresh directory. Member names must be relative, without `.`/`..` or empty
  components, at most 4095 bytes; a name under a symlink, a repeated
  non-directory, a type change or a reserved name rejects the archive instead
  of being rewritten. Files and directories come first; hard links become
  copies (app storage forbids hard links) of their target inside the archive,
  in any order, with chains bounded; symlinks are created last with exactly
  their text, so absolute ones like `/usr/lib/...` keep meaning guest paths and
  nothing ever writes through one. Device nodes, FIFOs, `/boot`, `/dev`'s
  contents, firmware and kernel modules are skipped as before. Permission bits
  are kept with owner read/write (and enter, for directories) always set;
  setuid/setgid/sticky bits and owners aren't. Header checksums, tar's end
  marker, the gzip trailer, and that nothing follows (no second gzip member,
  no data after tar's end) are all checked, with limits on entries (250,000),
  bytes of tar (8 GB) and written (4 GB), single files (1 GB) and link text.
  Memory is bounded too. Commons Compress reads a GNU long name or a PAX
  header whole and nests a call for each before the entry they describe, so
  their raw headers are checked first: at most 64 KB each (global ones 64 KB
  together), at most 4 in a row, and at most about 330 KB of the stream read
  to produce one entry (which also bounds a PAX sparse map). Names and link
  texts kept until the end are limited to 16 million characters. The
  2026-08-05 image has seven PAX headers of at most 205 bytes, never two in a
  row, and 1.7 million characters of kept names.
  Last, the tree must have bash, env, pacman, its config and the loader.
- **Activation** (`RootfsInstaller`). Only after all of that: a random token
  goes into the tree (`.omalauncher-rootfs`) and into
  `files/rootfs-activation.json`, the journal and the guest's provisioned-file
  records are reset, an incomplete earlier `files/arch` is deleted (without
  following links), and staging is renamed to `files/arch`. A set-up system
  (journal or a pre-journal `rootfs-ready`) is refused before and right before
  the switch. Then the journal marks `rootfs` and setup continues. If the app
  dies before the rename, the next try deletes staging and unpacks again from
  the still-cached tarball; after the rename and before the journal mark, the
  matching token tells the next try the rootfs is complete, so it's kept. That
  holds when the process is killed; files aren't synced one by one, so a power
  cut right after unpacking can still lose recent writes. Completed installs
  never see any of this: their journal already has `rootfs`. A verified
  tarball that can't be unpacked stays cached: the same bytes would fail the
  same way, and each retry checks it against a fresh signature, so a new
  release replaces it. Only bytes that changed while being unpacked are
  dropped and fetched again.
- **Removal** (`RootfsInstaller.remove`, from removing Linux and from the
  leftover check at start) deletes `rootfs-activation.json` first, then (when
  removing Linux) `setup-agents` and the journal's setup record; if any of
  that fails, nothing is deleted, Linux stays installed and running, and the
  Linux screen says removal failed. Only then are staging and `files/arch`
  deleted, without following links. However far deleting gets, what's left
  can't pass for an activated or set-up rootfs, and the app dying meanwhile
  leaves leftovers, not a half-deleted system. Files that resist are reported
  on the Linux screen and deleted at the next start or before the next
  install, which doesn't start until they're gone. Removing, installing and
  the leftover check run one at a time (`Bootstrap` and `RootfsInstaller`
  each hold a lock), and the leftover check finishes before Linux shows as
  not installed, so an install never starts while a deletion is running.

What it doesn't do: a valid signature says who published an image, not that
it's the newest, so an older signed tarball (downloaded or pushed) would be
accepted; it doesn't make proot a sandbox; and it doesn't cover the other
downloads (packages are pacman's business, cliamp, mise and themes are pinned
or fetched separately). To change the signing key, replace the asset and the
fingerprint together in a reviewed change, with the new key's provenance.

## Updates

The app writes into the rootfs and keeps state of its own, so an update must
reach existing installs without breaking them. There are four mechanisms:

- **Rootfs steps** (`linux/Bootstrap.kt`): an ordered list of idempotent steps
  (`/etc` configuration, packages, the keymap, cliamp).
  Each one's id is recorded once it succeeds (`files/rootfs-steps.json`). To
  change what a step does on existing installs, give it a new id, for example
  `packages-1` → `packages-2` after changing the package list. A fresh install
  runs every step in the background, with progress in settings › linux and
  the notification. After an update, new offline steps run before Linux
  starts, and steps that need the network run in the background. Their progress and failures show in settings, and they retry on
  the next start.
- **Launcher-provided files** (`upgrade/ManagedFiles.kt`): shims, desktop
  entries, default configs, default widgets. Every file written is recorded
  with its hash: the launcher's own (default widgets) in
  `files/launcher-managed.json`, the guest's in `files/managed.json`, which
  only syncs while Linux is installed and is forgotten with it.
  - `Owned` files (plumbing) always match the APK.
  - `Seeded` files are defaults the user may edit. They are updated only while
    unchanged since we wrote them, and never brought back once deleted.
  - Files dropped from the APK are deleted if they're unchanged.
  - The sync runs on every start and only hashes files when nothing changed.
- **Saved data** (`upgrade/Versioned.kt`): preferences and JSON files are
  stored as `{"v": N, "data": …}`. A format change bumps `N` and adds a
  migration step to the reader. Entries are parsed one at a time, so a bad
  one is skipped instead of losing the rest.
- **Widgets**: `Compiler.LEVEL` in the owl module is the language version.
  Widgets that need a newer one declare `meta { owl: N }`, and store entries
  carry it. An older launcher then shows "needs a newer launcher" instead of
  failing. Bump the level with every addition a widget could depend on. The
  store's `index.json` format number only changes for incompatible changes.

**System updates** (`linux/SystemUpdates.kt`) are the user's own: pacman
packages and the tools mise installed (the coding agents). The launcher
checks at most every 6 hours while it's on screen: pacman against a private
copy of the databases in /tmp, so the real ones and pacman's lock are
untouched, plus `mise outdated`. When updates are pending, a dot shows on the
app list's settings gear. "update all" in settings runs `pacman -Syyu` and then
`mise upgrade` as one job. Nothing updates automatically.

Pinned versions: `ThemeStore.OMARCHY_REF` (the Omarchy commit) and
`CLIAMP_VERSION` in `Bootstrap.kt`. Changing the first makes every install
download the new themes on its next start (`ThemeStore.ensureRelease`, with or
without Linux); rerun `scripts/fetch-theme-colors.sh` with it, since first run
fetches only the picked theme's colors and first background by the names that
script bundles. Changing the second updates Linux installs through the steps
above.

Guest processes die with the app (`--kill-on-exit`), so the Wayland protocol,
the socket and the guest environment carry no state across an update.
