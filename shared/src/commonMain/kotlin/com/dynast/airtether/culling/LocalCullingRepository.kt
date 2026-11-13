package com.dynast.airtether.culling

import com.dynast.airtether.domain.port.CullingRepository
import com.dynast.airtether.model.ExifMetadata
import com.dynast.airtether.model.TetherMediaItem
import com.dynast.airtether.model.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Multiplatform disk-backed [CullingRepository] using `kotlinx-io` [SystemFileSystem].
 * Persists culled items & ratings across app restarts and writes `.xmp` sidecars.
 */
class LocalCullingRepository(
    private val storageDirPath: String,
) : CullingRepository {

    private val storageDir = Path(storageDirPath)
    private val indexPath = Path("$storageDirPath/culling_index.tsv")
    private val _items = MutableStateFlow<List<TetherMediaItem>>(emptyList())

    init {
        SystemFileSystem.createDirectories(storageDir)
        _items.value = loadFromDisk()
    }

    override fun observeItems(): Flow<List<TetherMediaItem>> = _items.asStateFlow()

    override suspend fun upsertItem(item: TetherMediaItem) {
        _items.update { current ->
            val filtered = current.filterNot { it.id == item.id }
            val next = listOf(item) + filtered
            saveToDisk(next)
            next
        }
    }

    override suspend fun updateRating(itemId: String, rating: Int) {
        val clamped = rating.coerceIn(0, 5)
        var updatedTarget: TetherMediaItem? = null
        _items.update { current ->
            val next = current.map { item ->
                if (item.id == itemId) {
                    item.copy(rating = clamped).also { updatedTarget = it }
                } else {
                    item
                }
            }
            saveToDisk(next)
            next
        }
        updatedTarget?.let { target ->
            if (!target.localUri.isNullOrBlank() && !target.localUri.startsWith("file:///storage/emulated/0/")) {
                exportXmpSidecar(target)
            }
        }
    }

    override suspend fun exportXmpSidecar(item: TetherMediaItem): Result<String> {
        return runCatching {
            val xmpContent = XmpSidecarGenerator.generate(item)
            val sidecarPath = resolveTargetXmpPath(item)
            sidecarPath.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(sidecarPath).buffered().use { sink ->
                sink.writeString(xmpContent)
            }
            sidecarPath.toString()
        }
    }

    private fun resolveTargetXmpPath(item: TetherMediaItem): Path {
        val local = item.localUri?.removePrefix("file://")
        if (!local.isNullOrBlank() && !local.startsWith("content://")) {
            val parent = local.substringBeforeLast('/', "").ifBlank {
                local.substringBeforeLast('\\', "")
            }
            if (parent.isNotEmpty()) {
                val sep = if (local.contains('\\') && !local.contains('/')) '\\' else '/'
                val xmpName = XmpSidecarGenerator.resolveSidecarFileName(item.fileName)
                return Path("$parent$sep$xmpName")
            }
        }
        val xmpDir = Path("$storageDirPath/xmp").also { SystemFileSystem.createDirectories(it) }
        return Path("$xmpDir/${XmpSidecarGenerator.resolveSidecarFileName(item.fileName)}")
    }

    private fun saveToDisk(items: List<TetherMediaItem>) {
        val serialized = items.joinToString("\n") { item ->
            val exif = item.exifMetadata
            listOf(
                encodeField(item.id),
                encodeField(item.fileName),
                item.fileSize.toString(),
                encodeField(item.localUri ?: ""),
                encodeField(item.previewUri ?: ""),
                item.rating.toString(),
                encodeField(item.capturedAt),
                item.previewAccentHex.toString(),
                encodeField(exif?.shutterSpeed ?: ""),
                encodeField(exif?.aperture ?: ""),
                (exif?.iso ?: -1).toString(),
                encodeField(exif?.focalLength ?: ""),
                encodeField(exif?.cameraModel ?: ""),
            ).joinToString("\t")
        }
        SystemFileSystem.sink(indexPath).buffered().use { sink ->
            sink.writeString(serialized)
        }
    }

    private fun loadFromDisk(): List<TetherMediaItem> {
        if (!SystemFileSystem.exists(indexPath)) return emptyList()
        val raw = runCatching {
            SystemFileSystem.source(indexPath).buffered().use { it.readString() }
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val cols = line.split('\t')
                if (cols.size < 13) return@mapNotNull null
                val iso = cols[10].toIntOrNull() ?: -1
                val hasExif = cols[8].isNotEmpty() || cols[9].isNotEmpty() || iso >= 0 ||
                    cols[11].isNotEmpty() || cols[12].isNotEmpty()
                val exif = if (hasExif) {
                    ExifMetadata(
                        shutterSpeed = decodeField(cols[8]),
                        aperture = decodeField(cols[9]),
                        iso = iso.coerceAtLeast(0),
                        focalLength = decodeField(cols[11]),
                        cameraModel = decodeField(cols[12]),
                    )
                } else null

                TetherMediaItem(
                    id = decodeField(cols[0]),
                    fileName = decodeField(cols[1]),
                    fileSize = cols[2].toLongOrNull() ?: 0L,
                    transferStatus = TransferStatus.Completed,
                    localUri = decodeField(cols[3]).ifBlank { null },
                    previewUri = decodeField(cols[4]).ifBlank { null },
                    rating = cols[5].toIntOrNull()?.coerceIn(0, 5) ?: 0,
                    capturedAt = decodeField(cols[6]),
                    previewAccentHex = cols[7].toLongOrNull() ?: 0xFF38BDF8,
                    exifMetadata = exif,
                )
            }
            .toList()
    }

    private fun encodeField(value: String): String =
        value.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")

    private fun decodeField(value: String): String =
        value.replace("%0D", "\r").replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")
}

expect fun createPlatformCullingRepository(sessionName: String = "AirTether"): CullingRepository
