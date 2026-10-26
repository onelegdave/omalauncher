package dev.omalauncher.linux

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.omalauncher.LauncherData
import dev.omalauncher.hex
import dev.omalauncher.openHttp
import dev.omalauncher.upgrade.Provisioner
import dev.omalauncher.upgrade.StepJournal
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "bootstrap"

/** Attempts per network step before setup (or an update) reports a failure. */
private const val NETWORK_TRIES = 3

/** How long `echo | cat` may take in the guest before setup calls the phone's kernel broken; it takes well under a second. */
private const val FORK_CHECK_SECONDS = 30L

/** Room for the download (~830 MB). */
private const val TARBALL_SPACE = 900_000_000L

/** Room for the unpacked system and what setup installs into it (~1.9 GB with an agent), with some to spare. */
private const val SYSTEM_SPACE = 2_100_000_000L

/** A failure that trying again can't fix, like a full disk: a network step stops at once rather than downloading again. */
private class NoRetry(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private fun Throwable.outOfSpace() =
    generateSequence(this) { it.cause }.any {
        (it is ErrnoException && it.errno == OsConstants.ENOSPC) || it.message?.contains("ENOSPC") == true
    }

private fun gigabytes(bytes: Long) = "%.1f GB".format(bytes / 1e9)

/**
 * Omarchy's classic terminal apps. The Nerd Font glyphs come from the
 * symbols-only font: the full JetBrainsMono Nerd family is ~200 MB.
 * Changing this list needs a new id for the packages step.
 */
private val PACKAGES =
    listOf(
        "foot", "ttf-dejavu", "btop", "fastfetch", "ttf-jetbrains-mono", "ttf-nerd-fonts-symbols-mono",
        "neovim", "starship", "eza", "bat", "fd", "ripgrep", "fzf", "zoxide", "tmux", "gum", "jq", "tealdeer",
        "man-db", "less", "dua-cli", "inxi", "whois", "git", "bash-completion", "pulseaudio", "pulseaudio-alsa", "micro",
    )

/** Omarchy's Winamp-style player; not in Arch's ARM repos, but upstream ships an arm64 build. */
private const val CLIAMP_VERSION = "v2.3.0"
private const val CLIAMP_URL = "https://github.com/bjarneo/cliamp/releases/download/$CLIAMP_VERSION/cliamp-linux-arm64"
private const val CLIAMP_SHA256 = "827b4aa879feecab33ea621dc1460c60ffa765c210b5046d679ce5a4073d2b32"

private val MIRRORS =
    listOf(
        "de.mirror.archlinuxarm.org",
        "fl.us.mirror.archlinuxarm.org",
        "eu.mirror.archlinuxarm.org",
        "dk.mirror.archlinuxarm.org",
        "mirror.archlinuxarm.org",
    )

/** Firmware and kernel modules are useless under proot; pacman never unpacks them. */
private const val NO_EXTRACT = "NoExtract = usr/lib/firmware/* usr/lib/modules/*"

/** What the bootstrap installs; the packages screen doesn't offer to remove these. */
val CORE_PACKAGES = PACKAGES.toSet()

/** The phases of a fresh install, in order, as the setup screen lists them. */
enum class Stage(
    val label: String,
) {
    Download("download arch linux"),
    Extract("unpack the system"),
    Configure("configure pacman"),
    Packages("install omarchy's apps"),
    Agents("install coding agents"),
}

/** Where the optional Linux system stands. The launcher works in every state; Linux features only when [Installed]. */
sealed interface LinuxState {
    data object Checking : LinuxState

    /** Never installed, or removed: nothing of it is on disk. */
    data object NotInstalled : LinuxState

    data class Installing(
        val stage: Stage,
        val step: String,
        val progress: Float?,
        val log: List<String>,
    ) : LinuxState

    /** An install, or an update that must finish before Linux runs, stopped; [Bootstrap.run] retries it. */
    data class Failed(
        val stage: Stage,
        val message: String,
        val log: List<String>,
    ) : LinuxState

    data object Installed : LinuxState

    data object Removing : LinuxState
}

/**
 * One idempotent change to the rootfs. Once it succeeds its [id] is recorded
 * and it doesn't run again; to bring a changed step to existing installs,
 * give it a new id. [network] steps of an update run in the background, so
 * an offline phone still reaches the home screen. [isDone], when set, reads
 * the step's state from disk instead of the journal.
 */
private class Step(
    val id: String,
    val stage: Stage,
    val label: String,
    val network: Boolean,
    /** A failure doesn't stop a fresh install: the system works without it, and it's retried later. */
    val optional: Boolean = false,
    val isDone: (() -> Boolean)? = null,
    val run: suspend () -> Unit,
)

/**
 * Sets up the Arch Linux ARM rootfs and keeps it current. A fresh install
 * runs every [steps] entry behind the setup screen; after an app update only
 * the new ones run, see [Step].
 */
class Bootstrap(
    private val context: Context,
    private val proot: Proot,
    private val agents: AgentStore,
    private val pacman: Pacman,
    /** The guest's files; see [Provisioner]. */
    private val provisioner: Provisioner,
) {
    private val _state = MutableStateFlow<LinuxState>(LinuxState.Checking)
    val state = _state.asStateFlow()

    val isInstalled get() = _state.value == LinuxState.Installed

    /** An install was started (and may still need to finish), or one finished. */
    val wanted get() = journal.setupComplete || choiceFile.exists() || File(proot.filesDir, "rootfs-ready").exists()

    /** Update steps running after the home screen is up; Idle when there are none. */
    private val _updates = MutableStateFlow<JobState>(JobState.Idle)
    val updates = _updates.asStateFlow()

    /**
     * Why removing Linux, or deleting what an earlier removal left, didn't fully work; null when
     * it did. The Linux screen shows it; it clears when the next removal or install starts.
     */
    private val _removalProblem = MutableStateFlow<String?>(null)
    val removalProblem = _removalProblem.asStateFlow()

    private val log = OutputTail(200)
    private val journal = StepJournal(File(proot.filesDir, "rootfs-steps.json"))

    /** A finished setup owns the rootfs: the journal says so, or an older launcher's rootfs-ready. */
    private val setUp get() = journal.setupComplete || File(proot.filesDir, "rootfs-ready").exists()

    /** One of [run], [install] and [remove] at a time: none may see another's half-done state. */
    private val lifecycle = Mutex()

    @Volatile private var cancelled = false
    @Volatile private var rootfsDownload: RootfsDownloader? = null
    @Volatile private var connection: HttpURLConnection? = null

    /** Only an unfinished install may be cancelled; updates must preserve the installed system. */
    val cancellable get() = !setUp && !cancelled

    /** The caller also cancels/joins its coroutine and stops guest commands before [remove]. */
    fun cancel(): Boolean {
        if (!cancellable) return false
        cancelled = true
        rootfsDownload?.cancel()
        // Some HttpURLConnection implementations wait for a read lock in disconnect; never block the UI thread.
        connection?.let { conn -> thread(name = "bootstrap-cancel", isDaemon = true) { runCatching { conn.disconnect() } } }
        return true
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("cancelled")
    }

    private val rootfsInstaller =
        RootfsInstaller(
            proot.filesDir,
            setUp = { setUp },
            forgetOldRootfs = {
                journal.reset()
                provisioner.forgetAll()
            },
            deleteTree = LauncherData::deleteTree,
            beforeExtract = { requireSpace(SYSTEM_SPACE) },
            report = ::report,
        )

    /** The agents picked when installing Linux, one bin per line; its existence means an install was started. */
    private val choiceFile get() = File(proot.filesDir, "setup-agents")

    /** The coding agents setup installs, in the order they were listed. */
    val chosenAgents: List<Agent>
        get() = runCatching { choiceFile.readLines() }.getOrDefault(emptyList()).mapNotNull { bin -> AGENTS.find { it.bin == bin } }

    fun choose(picked: List<Agent>) {
        proot.filesDir.mkdirs()
        choiceFile.writeText(picked.joinToString("") { it.bin + "\n" })
    }

    private var stage = Stage.Download
    private var step = ""

    private val steps =
        listOf(
            Step("rootfs", Stage.Download, "Downloading Arch Linux ARM", network = true) { installRootfs() },
            Step("etc-2", Stage.Configure, "Configuring the system", network = false) { configure() },
            // Only before the first setup: a phone where Linux already runs can fork.
            Step("forks-1", Stage.Configure, "Checking that programs can start", network = false, isDone = { journal.setupComplete }) {
                checkForks()
            },
            Step("keyring-1", Stage.Configure, "Initializing pacman keyring", network = false) {
                guest("pacman-key --init && pacman-key --populate archlinuxarm")
            },
            // The ALARM image ships a kernel and firmware (~1 GB, split into sub-packages) we can't use.
            Step("no-kernel-1", Stage.Configure, "Removing kernel packages", network = false) {
                guest("pacman -Rdd --noconfirm linux-aarch64 \$(pacman -Qq | grep ^linux-firmware); true")
            },
            Step("packages-1", Stage.Packages, "Installing packages", network = true) { guest(pacmanInstall(PACKAGES, refresh = true)) },
            Step("keymap-1", Stage.Packages, "Compiling keymap", network = false, isDone = { proot.keymap.exists() }) { compileKeymap() },
            Step("cliamp-$CLIAMP_VERSION", Stage.Packages, "Installing cliamp", network = true) { installCliamp() },
            // Omarchy's themes were a step here ("omarchy-<ref>") until Linux became optional; ThemeStore fetches them now.
        )

    private fun Step.done() = isDone?.invoke() ?: (id in journal)

    /**
     * Runs the step; one that needs the network gets a few tries, since phone connections drop.
     * Retrying is cheap: pacman keeps what it already downloaded, and downloads resume. A
     * failure another try can't fix (a tarball that doesn't match its signature) ends it.
     */
    private suspend fun Step.attempt() {
        repeat(if (network) NETWORK_TRIES - 1 else 0) { n ->
            try {
                checkCancelled()
                return run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is RootfsException && !e.kind.retryable) throw e
                if (e is NoRetry) throw e
                if (e.outOfSpace()) throw outOfSpace(e)
                log.add("${e.message}; trying again (${n + 2} of $NETWORK_TRIES)")
                kotlinx.coroutines.delay(5_000L * (n + 1))
            }
        }
        checkCancelled()
        run()
    }

    /** Adopt the pre-journal ready marker without ever replacing that completed system. */
    private fun adoptLegacySetup() {
        val ready = File(proot.filesDir, "rootfs-ready")
        if (journal.setupComplete || !ready.exists()) return
        for (id in listOf("rootfs", "etc-1", "keyring-1", "no-kernel-1", "packages-1")) journal.mark(id)
        if (File(proot.rootfs, "usr/local/bin/cliamp").exists()) journal.mark("cliamp-$CLIAMP_VERSION")
        journal.completeSetup()
        proot.filesDir.listFiles { f -> f.name == "rootfs-ready" || f.name.startsWith("apps-v") }?.forEach { it.delete() }
    }

    /**
     * Brings Linux to [LinuxState.Installed] if it's wanted: updates an
     * installed system, or continues an install that was started (also
     * after the app was killed during it). Otherwise reports NotInstalled.
     */
    suspend fun run() = withContext(Dispatchers.IO) { lifecycle.withLock { runLocked() } }

    private suspend fun runLocked() {
        try {
            adoptLegacySetup()
            when {
                journal.setupComplete -> update()
                !choiceFile.exists() -> {
                    // Before NotInstalled offers an install: nothing may still be deleting when one starts.
                    clearLeftovers()
                    _state.value = LinuxState.NotInstalled
                    return
                }
                else -> setup()
            }
            _state.value = LinuxState.Installed
        } catch (e: Throwable) {
            // Runtime waits for this work, then removes the unfinished install under the same lifecycle lock.
            if (cancelled) {
                Log.i(TAG, "setup cancelled")
                return
            }
            if (e is CancellationException) throw e
            Log.e(TAG, "setup failed", e)
            _state.value = LinuxState.Failed(stage, e.message ?: e.toString(), log.toList())
        }
    }

    /**
     * Starts installing Linux with [picked] coding agents; see [run]. A fresh install first
     * deletes what a removal left; if that fails it doesn't start, and [removalProblem] says why.
     */
    suspend fun install(picked: List<Agent>) =
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                if (!setUp && !choiceFile.exists() && !clearLeftovers()) return@withLock
                choose(picked)
                runLocked()
            }
        }

    /**
     * Deletes what a removal that didn't finish left (the app was killed, or a file resisted);
     * true once nothing is left. Only while nothing is set up: [run] and [install] check.
     */
    private fun clearLeftovers(): Boolean {
        val cause =
            when (val removal = rootfsInstaller.remove()) {
                RootfsInstaller.Removal.Done -> {
                    _removalProblem.value = null
                    return true
                }
                is RootfsInstaller.Removal.Kept -> removal.cause
                is RootfsInstaller.Removal.Leftovers -> removal.cause
            }
        Log.e(TAG, "leftover rootfs", cause)
        _removalProblem.value = "Files of a removed Linux couldn't be deleted (${cause.message}). Linux can be installed again once they are; try again, or restart the launcher."
        return false
    }

    /**
     * Deletes the rootfs and what we recorded about it. Callers stop guest
     * processes first. Launcher data (themes, widgets) lives outside it and stays.
     */
    suspend fun remove() =
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                _removalProblem.value = null
                _state.value = LinuxState.Removing
                val removal =
                    pacman.lock.withLock {
                        rootfsInstaller.remove(
                            forget = {
                                // The record that an install was started, then the journal: once that's reset nothing
                                // will run or update this system, and a start in between still finds it set up.
                                check(!choiceFile.exists() || choiceFile.delete()) { "can't delete ${choiceFile.name}" }
                                journal.reset()
                            },
                        )
                    }
                if (removal is RootfsInstaller.Removal.Kept) {
                    // Nothing was deleted: Linux stays installed, and the Linux screen says why.
                    Log.e(TAG, "Linux not removed", removal.cause)
                    _removalProblem.value = "Linux wasn't removed: ${removal.cause.message}. Your Linux files were kept; try again."
                    _state.value = LinuxState.Installed
                    return@withLock
                }
                backlog = emptyList()
                try {
                    provisioner.forgetAll()
                } catch (e: Exception) {
                    // Forgotten again before the next install's rootfs is switched in.
                    Log.e(TAG, "provisioned files not forgotten", e)
                }
                proot.keymap.delete()
                // Downloads and the private copy of a pushed tarball; a tarball the user pushed stays.
                context.cacheDir.listFiles { f -> f.name.startsWith(ROOTFS_TARBALL) }?.forEach { it.delete() }
                log.clear()
                if (removal is RootfsInstaller.Removal.Leftovers) {
                    // Nothing left can run or pass for a rootfs; deleting goes on before the next install, or at the next start.
                    Log.e(TAG, "rootfs not fully deleted", removal.cause)
                    _removalProblem.value =
                        "Linux is removed, but some of its files couldn't be deleted (${removal.cause.message}). " +
                        "They're deleted when the launcher next starts, or before Linux is installed again."
                }
                _state.value = LinuxState.NotInstalled
                cancelled = false
            }
        }

    /**
     * A fresh install: every step, in order. A failure stops here and a
     * retry picks up at the failed step. Agents are extras: the system
     * works without them, and settings can retry.
     */
    private suspend fun setup() {
        for (step in steps) {
            if (step.done()) continue
            checkCancelled()
            report(step.stage, step.label)
            try {
                step.attempt()
                checkCancelled()
                journal.mark(step.id)
            } catch (e: Exception) {
                if (!step.optional || e is CancellationException) throw e
                log.add("${step.label} failed: ${e.message}; retry in settings")
            }
        }
        installAgents()
        checkCancelled()
        journal.completeSetup()
    }

    /**
     * After an app update: offline steps run before the home screen, so it
     * never shows a half-configured system; from the first step that needs
     * the network on, the rest continue in the background (see [updates]).
     */
    private suspend fun update() {
        val pending = steps.filterNot { it.done() }
        val blocking = pending.takeWhile { !it.network }
        for (step in blocking) {
            report(step.stage, step.label)
            step.run()
            journal.mark(step.id)
        }
        backlog = pending.drop(blocking.size)
    }

    private var backlog: List<Step> = emptyList()

    private val backlogRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Runs update steps that need the network; call once the home screen is up. Safe to call again to retry. */
    suspend fun runBacklog() {
        if (!backlogRunning.compareAndSet(false, true)) return
        try {
            runSteps()
        } finally {
            backlogRunning.set(false)
        }
    }

    private suspend fun runSteps() =
        withContext(Dispatchers.IO) {
            val todo = backlog.filterNot { it.done() }
            if (todo.isEmpty()) return@withContext
            for (step in todo) {
                _updates.value = JobState.Working(step.label, emptyList())
                try {
                    // Package steps run pacman; nothing else may hold its lock meanwhile.
                    pacman.lock.withLock { step.attempt() }
                    journal.mark(step.id)
                } catch (e: Throwable) {
                    Log.w(TAG, "update step ${step.id} failed", e)
                    _updates.value = JobState.Failed(log.toList().takeLast(12) + (e.message ?: e.toString()))
                    return@withContext
                }
            }
            _updates.value = JobState.Idle
        }

    /**
     * Installs the agents picked on the welcome screen. A failed agent doesn't
     * fail setup: it can be installed again from settings.
     */
    private suspend fun installAgents() {
        val chosen = chosenAgents
        for (agent in chosen) {
            if (agents.isInstalled(agent)) continue
            checkCancelled()
            try {
                report(Stage.Agents, "Installing ${agent.name}")
                guest(agents.installScript(agent))
                agents.onInstalled(agent)
            } catch (e: IllegalStateException) {
                log.add("${agent.name} didn't install; try again in settings › coding agents")
            }
        }
        chosen.firstOrNull { agents.isInstalled(it) }?.let(agents::choose)
    }

    private fun report(
        stage: Stage,
        step: String,
        progress: Float? = null,
    ) {
        this.stage = stage
        this.step = step
        if (journal.setupComplete && _state.value == LinuxState.Installed) return
        _state.value = LinuxState.Installing(stage, step, progress, log.toList())
    }

    /**
     * Gets Arch Linux ARM's tarball, checks its signature against the pinned build key, and
     * unpacks it beside the rootfs, which it then replaces in one rename; see [RootfsInstaller].
     * This step only runs until one completed, so a rootfs here is a half-made one. A set-up
     * system is never replaced: it holds the user's home, packages and agents.
     */
    private suspend fun installRootfs() {
        val job = currentCoroutineContext()[Job]
        val ensureActive = {
            checkCancelled()
            job?.ensureActive() ?: Unit
        }
        val downloader =
            RootfsDownloader(context.cacheDir, ensureActive = ensureActive) { done, total ->
                report(
                    Stage.Download,
                    if (total > 0) "${done shr 20} of ${total shr 20} MB" else "${done shr 20} MB",
                    if (total > 0) done.toFloat() / total else null,
                )
            }
        rootfsDownload = downloader
        try {
            ensureActive()
            val verifier = RootfsVerifier(context.assets.open(ROOTFS_SIGNER_ASSET).use { it.readBytes() })
            val source =
                RootfsSource(
                    context.cacheDir, context.getExternalFilesDir(null), verifier, downloader,
                    ensureActive = ensureActive,
                    beforeDownload = { requireSpace((TARBALL_SPACE - downloader.partialBytes).coerceAtLeast(0L) + SYSTEM_SPACE) },
                    beforeCopy = { bytes -> requireSpace(bytes + SYSTEM_SPACE) },
                ) { step, progress ->
                    report(Stage.Download, step, progress)
                }
            val extractor =
                RootfsExtractor(
                    symlink = { target, path -> Os.symlink(target, path) },
                    ensureActive = ensureActive,
                    progress = { count, fraction -> report(Stage.Extract, "$count files", fraction) },
                )
            rootfsInstaller.install(source, extractor, ensureActive)
            // Proot.prepare() ran before there was a rootfs; give the new one its mount points and font stand-in.
            proot.prepareRootfs()
        } catch (e: Exception) {
            throw if (e.outOfSpace()) outOfSpace(e) else e
        } finally {
            rootfsDownload = null
        }
    }

    private fun requireSpace(bytes: Long) {
        val free = proot.filesDir.usableSpace
        if (free < bytes) {
            throw NoRetry("not enough space: Linux needs ${gigabytes(bytes)} free, the phone has ${gigabytes(free)}; free some up, then try again")
        }
    }

    private fun outOfSpace(e: Exception) =
        NoRetry("the phone ran out of space; Linux needs about ${gigabytes(TARBALL_SPACE + SYSTEM_SPACE)} free; free some up, then try again", e)

    private fun configure() {
        val root = proot.rootfs
        File(root, "etc/resolv.conf").apply {
            delete()
            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/locale.conf").writeText("LANG=C.UTF-8\n")
        // The geo-balanced default mirror often lags behind; pacman falls through this list on 404s.
        File(root, "etc/pacman.d/mirrorlist").writeText(MIRRORS.joinToString("") { "Server = http://$it/\$arch/\$repo\n" })
        // pacman drops privileges and uses Landlock for downloads; neither works under proot.
        // Its low-speed timeout aborts big packages on slow phone connections, every retry.
        val conf = File(root, "etc/pacman.conf")
        conf.writeText(
            conf
                .readText()
                .replace(Regex("(?m)^(DownloadUser|CheckSpace|DisableSandbox|DisableDownloadTimeout|NoExtract)\\b.*\n"), "")
                .replaceFirst("[options]", "[options]\nDisableSandbox\nDisableDownloadTimeout\n$NO_EXTRACT"),
        )
    }

    private fun compileKeymap() {
        guest(
            "xkbcli compile-keymap --layout us > /tmp/keymap.xkb || " +
                "/usr/lib/xkbcommon/xkbcli-compile-keymap --layout us > /tmp/keymap.xkb",
        )
        // Clients mmap the keymap and parse it as a C string.
        writeAtomically(proot.keymap, proot.hostFile("/tmp/keymap.xkb").readBytes() + 0)
    }

    /**
     * Streamed to disk and hashed on the way: the binary is ~36 MB, and holding it while a buffer
     * doubles needs more heap than small phones give an app (48 MB on a 2 GB Android 10 device).
     */
    private fun installCliamp() {
        val target = File(proot.rootfs, "usr/local/bin/cliamp")
        val tmp = File(target.parentFile, ".cliamp.download")
        val digest = MessageDigest.getInstance("SHA-256")
        // Runs under pacman's lock, so a stalled download must time out rather than hold it.
        val conn = openHttp(CLIAMP_URL, connectTimeout = 10_000, readTimeout = 20_000)
        connection = conn
        try {
            checkCancelled()
            conn.useCaches = false
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $CLIAMP_URL")
            target.parentFile?.mkdirs()
            DigestInputStream(conn.inputStream, digest).use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    out.fd.sync()
                }
            }
        } finally {
            connection = null
            conn.disconnect()
        }
        checkCancelled()
        if (digest.digest().hex() != CLIAMP_SHA256) {
            tmp.delete()
            error("cliamp download doesn't match $CLIAMP_VERSION")
        }
        tmp.setExecutable(true, false)
        // One rename, so a running cliamp is never replaced by half a file.
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("can't replace $target")
        }
    }

    /**
     * Runs a pipeline, the smallest guest command that forks. Some custom ROM kernels (4.14, with Android's cgroup v2
     * freezer backported but not its follow-up, upstream 05b289263772) make every fork of a traced process restart
     * forever (#105), and the next step, pacman-key, would hang without a word. Such a phone gets an error naming the cause.
     */
    private fun checkForks() {
        val process = proot.process("echo forks | cat").start()
        val output = StringBuffer()
        val reader = thread(isDaemon = true) { runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } } }
        if (process.waitFor(FORK_CHECK_SECONDS, TimeUnit.SECONDS)) {
            reader.join(1_000)
            output.lines().forEach(log::add)
            check(process.exitValue() == 0 && "forks" in output) { "Linux can't run commands here (exit ${process.exitValue()})" }
            return
        }
        process.destroyForcibly()
        // The guest shell is proot's tracee and outlives a killed proot.
        proot.killAll()
        log.add("a guest command that starts another one didn't finish in $FORK_CHECK_SECONDS s (kernel ${System.getProperty("os.version")})")
        error(
            "this phone's kernel stops Linux programs from starting other programs, so setup can't go on. " +
                "custom ROMs with a 4.14 kernel need kernel commit 05b289263772 from their maintainer; see github issue #105",
        )
    }

    private fun guest(script: String) {
        log.add("$ $script")
        val code = proot.run(script) { line -> if (log.add(line)) report(stage, step) }
        check(code == 0) { "`${script.take(60)}` failed (exit $code)" }
    }
}
