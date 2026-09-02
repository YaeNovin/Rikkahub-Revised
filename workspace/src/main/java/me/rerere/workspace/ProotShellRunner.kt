package me.rerere.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class WorkspaceBindMount(
    val source: File,
    val target: String,
    val access: WorkspaceBindMountAccess = WorkspaceBindMountAccess.SHELL_READ_WRITE,
) {
    init {
        val normalized = target.replace('\\', '/').trim().trimEnd('/').ifBlank { "/" }
        require(normalized.startsWith("/")) { "Bind mount target must be absolute: $target" }
        require(!normalized.contains('\u0000')) { "Bind mount target contains an invalid character" }
        val segments = normalized.removePrefix("/")
        require(
            segments.isBlank() ||
                segments.split('/').none { it.isBlank() || it == "." || it == ".." }
        ) {
            "Bind mount target must not contain empty or traversal segments: $target"
        }
    }
}

enum class WorkspaceBindMountAccess {
    FILE_TOOLS_ONLY,
    SHELL_READ_ONLY_COPY,
    SHELL_READ_WRITE,
}

data class PreparedWorkspaceBindMounts(
    val mounts: List<WorkspaceBindMount>,
    private val mirrorRoot: File?,
) : AutoCloseable {
    override fun close() {
        mirrorRoot?.deleteRecursively()
    }
}

fun prepareWorkspaceBindMounts(
    bindMounts: List<WorkspaceBindMount>,
    tempDir: File,
    scope: String = UUID.randomUUID().toString(),
): PreparedWorkspaceBindMounts {
    val mirrorRoot = File(tempDir, "bind-mirrors/$scope")
    mirrorRoot.deleteRecursively()
    val prepared = bindMounts.mapNotNull { mount ->
        when (mount.access) {
            WorkspaceBindMountAccess.FILE_TOOLS_ONLY -> null
            WorkspaceBindMountAccess.SHELL_READ_WRITE -> mount.takeIf { it.source.exists() }
            WorkspaceBindMountAccess.SHELL_READ_ONLY_COPY -> {
                if (!mount.source.exists()) return@mapNotNull null
                val name = mount.target.trim('/').replace('/', '_').ifBlank { "root" }
                val mirror = File(mirrorRoot, name)
                copyTreeWithoutLinks(mount.source, mirror)
                WorkspaceBindMount(
                    source = mirror,
                    target = mount.target,
                    access = WorkspaceBindMountAccess.SHELL_READ_WRITE,
                )
            }
        }
    }
    return PreparedWorkspaceBindMounts(prepared, mirrorRoot.takeIf { it.exists() })
}

private fun copyTreeWithoutLinks(source: File, target: File) {
    var entries = 0
    var bytes = 0L
    Files.walk(source.toPath()).use { stream ->
        stream.forEach { path ->
            entries++
            require(entries <= MAX_MIRROR_ENTRIES) { "Shared Shell mount contains too many entries" }
            if (Files.isSymbolicLink(path)) return@forEach
            val relative = source.toPath().relativize(path)
            val destination = target.toPath().resolve(relative)
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination)
            } else if (Files.isRegularFile(path)) {
                val size = Files.size(path)
                require(bytes <= MAX_MIRROR_BYTES - size) { "Shared Shell mount is too large" }
                bytes += size
                destination.parent?.let(Files::createDirectories)
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val rootfsShell = context.linuxDir.rootfsShellPath()
            ?: return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs does not contain /bin/bash or /bin/sh",
            )
        val preparedMounts = prepareWorkspaceBindMounts(context.bindMounts, context.tempDir)
        try {
            val process = ProcessBuilder(buildCommand(context, proot, preparedMounts.mounts))
                .directory(context.filesDir)
                .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
                environment()["HOME"] = "/root"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["TERM"] = "xterm-256color"
                environment()["LANG"] = "C.UTF-8"
                environment()["LC_ALL"] = "C.UTF-8"
                environment()["USER"] = "root"
                environment()["LOGNAME"] = "root"
                environment()["SHELL"] = rootfsShell
            }
            .start()

            return process.readResult(context.timeoutMillis, context.stdin)
        } finally {
            preparedMounts.close()
        }
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
        bindMounts: List<WorkspaceBindMount>,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        val env = when {
            File(context.linuxDir, "usr/bin/env").isFile -> "/usr/bin/env"
            File(context.linuxDir, "bin/env").isFile -> "/bin/env"
            else -> null
        }
        val rootfsShell = context.linuxDir.rootfsShellPath()
            ?: throw IllegalArgumentException("Rootfs does not contain /bin/bash or /bin/sh")
        if (env != null) {
            command += listOf(
                env,
                "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "SHELL=$rootfsShell",
                "USER=root",
                "LOGNAME=root",
                rootfsShell,
            )
        } else {
            // Minimal Rootfs images may omit coreutils/env. ProcessBuilder
            // supplies the same deterministic environment in that case.
            command += rootfsShell
        }
        if (rootfsShell.endsWith("/bash")) command += "-l"
        command += listOf(
            "-c",
            // 命令通过位置参数传入, 避免路径转义; POSIX sh 和 Bash 都支持这段启动器
            "cd \"\$1\" && eval \"\$2\"",
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && rootfsShellPath() != null

    private fun File.rootfsShellPath(): String? = listOf(
        "bin/bash" to "/bin/bash",
        "usr/bin/bash" to "/usr/bin/bash",
        "bin/sh" to "/bin/sh",
        "usr/bin/sh" to "/usr/bin/sh",
    ).firstOrNull { (relative, _) -> File(this, relative).isFile }?.second

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}

private const val MAX_MIRROR_ENTRIES = 20_000
private const val MAX_MIRROR_BYTES = 256L * 1024 * 1024
