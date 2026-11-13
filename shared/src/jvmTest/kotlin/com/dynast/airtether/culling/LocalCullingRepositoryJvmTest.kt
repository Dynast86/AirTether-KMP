package com.dynast.airtether.culling

import com.dynast.airtether.model.ExifMetadata
import com.dynast.airtether.model.TetherMediaItem
import com.dynast.airtether.model.TransferStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalCullingRepositoryJvmTest {

    @Test
    fun persistsRatingsAcrossRestartsAndExportsXmpSidecarBesidePhoto() = runBlocking {
        val tempDir = Files.createTempDirectory("airtether-culling-test").toFile()
        val photoFile = File(tempDir, "DSC_1001.NEF").apply { writeText("dummy-raw") }
        val dbDir = File(tempDir, "db").apply { mkdirs() }

        try {
            val repo1 = LocalCullingRepository(dbDir.absolutePath)
            val item = TetherMediaItem(
                id = "item-1001",
                fileName = "DSC_1001.NEF",
                fileSize = 42_000_000L,
                transferStatus = TransferStatus.Completed,
                localUri = photoFile.absolutePath,
                exifMetadata = ExifMetadata("1/500s", "f/2.8", 100, "50mm", "Nikon Z8"),
                rating = 0,
            )

            repo1.upsertItem(item)
            repo1.updateRating("item-1001", 4)

            // Verify sidecar .xmp created beside original photo
            val xmpFile = File(tempDir, "DSC_1001.xmp")
            assertTrue(xmpFile.exists())
            assertTrue(xmpFile.readText().contains("""xmp:Rating="4""""))

            // Simulate app restart by creating a new repository instance
            val repo2 = LocalCullingRepository(dbDir.absolutePath)
            val restored = repo2.observeItems().first()
            assertEquals(1, restored.size)
            assertEquals(4, restored.first().rating)
            assertEquals("Nikon Z8", restored.first().exifMetadata?.cameraModel)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
