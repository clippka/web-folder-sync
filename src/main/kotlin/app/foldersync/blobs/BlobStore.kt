package app.foldersync.blobs

import app.foldersync.config.FolderSyncProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Stream

/**
 * Immutable content files outside the web root, named only by generated identifiers (never by user paths).
 * Layout: `<data>/blobs/<id[0..2]>/<id[2..4]>/<id>`. A file here is final: it is written to staging first and moved
 * in atomically, so a blob file is either complete or absent.
 */
@Component
class BlobStore(private val props: FolderSyncProperties) {
    private val idPattern = Regex("^[0-9a-f]{32}$")

    fun path(id: String): Path {
        require(idPattern.matches(id)) { "invalid blob id" }
        return props.blobDir.resolve(id.substring(0, 2)).resolve(id.substring(2, 4)).resolve(id)
    }

    /** Moves a fully written, verified file into place. The source must be on the same volume (it is: both under /data). */
    fun publish(source: Path, id: String): Path {
        val target = path(id)
        Files.createDirectories(target.parent)
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            throw IOException("Blob store requires atomic moves within the data volume", e)
        }
        return target
    }

    fun exists(id: String) = Files.isRegularFile(path(id))

    fun delete(id: String): Boolean = Files.deleteIfExists(path(id))

    /** Every regular file under the blob directory, for orphan detection. Caller must close the stream. */
    fun walk(): Stream<Path> = if (Files.isDirectory(props.blobDir)) Files.walk(props.blobDir).filter { Files.isRegularFile(it) } else Stream.empty()
}
