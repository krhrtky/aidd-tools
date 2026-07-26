package dev.aidd.cli

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object SafePaths {
    fun output(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        rejectSymlinkComponents(normalized)
        normalized.parent?.let {
            Files.createDirectories(it)
            rejectSymlinkComponents(it)
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized)) {
            error("Refusing to write through symbolic link: $normalized")
        }
        return normalized
    }

    fun directory(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        rejectSymlinkComponents(normalized)
        Files.createDirectories(normalized)
        rejectSymlinkComponents(normalized)
        return normalized
    }

    fun writeText(path: Path, text: String) {
        val target = output(path)
        val temporary = Files.createTempFile(target.parent, ".aidd-", ".tmp")
        try {
            Files.writeString(temporary, text)
            move(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun copy(source: Path, target: Path) {
        val safeTarget = output(target)
        val temporary = Files.createTempFile(safeTarget.parent, ".aidd-", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            move(temporary, safeTarget)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun resetGeneratedDirectory(path: Path) {
        val directory = directory(path)
        Files.list(directory).use { entries ->
            entries.forEach { entry ->
                require(!Files.isSymbolicLink(entry) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    "Unexpected entry in generated artifact directory: $entry"
                }
                Files.delete(entry)
            }
        }
    }

    private fun move(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun rejectSymlinkComponents(path: Path) {
        var current = path.root
        var depth = 0
        path.forEach { component ->
            depth += 1
            current = current.resolve(component)
            if (depth > 1 && Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                error("Symbolic links are not allowed in output paths: $current")
            }
        }
    }
}
