package com.dynast.airtether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TetheringModelsTest {

    @Test
    fun rawAndJpgFormatDetectionWorksCorrectly() {
        val sonyRawItem = TetherMediaItem(
            id = "raw_1",
            fileName = "_DSC1001.ARW",
            fileSize = 62_914_560L, // 60.0 MB
            transferStatus = TransferStatus.Completed,
            localUri = "file:///mock/_DSC1001.ARW",
            exifMetadata = ExifMetadata(
                shutterSpeed = "1/1000s",
                aperture = "f/1.4",
                iso = 100,
                focalLength = "50mm",
                cameraModel = "Sony ILCE-7RM5"
            ),
            rating = 5
        )

        val jpgItem = TetherMediaItem(
            id = "jpg_1",
            fileName = "_DSC1002.JPG",
            fileSize = 15_728_640L, // 15.0 MB
            transferStatus = TransferStatus.Completed,
            localUri = "file:///mock/_DSC1002.JPG",
            exifMetadata = null,
            rating = 3
        )

        assertTrue(sonyRawItem.isRaw)
        assertEquals("ARW", sonyRawItem.extension)
        assertEquals("RAW", sonyRawItem.formatBadge)
        assertEquals("60.0 MB", sonyRawItem.formattedFileSize)

        assertFalse(jpgItem.isRaw)
        assertEquals("JPG", jpgItem.extension)
        assertEquals("JPG", jpgItem.formatBadge)
        assertEquals("15.0 MB", jpgItem.formattedFileSize)
    }

    @Test
    fun transferProgressPercentClampsAndRoundsAccurately() {
        assertEquals(0, TransferStatus.Transferring(-0.2f).progressPercent)
        assertEquals(46, TransferStatus.Transferring(0.456f).progressPercent)
        assertEquals(100, TransferStatus.Transferring(1.25f).progressPercent)
    }

    @Test
    fun serverStatusEndpointLabelFormatsFtpUrl() {
        val status = ServerStatus.Running(ipAddress = "192.168.43.1", port = 2121)
        assertEquals("ftp://192.168.43.1:2121", status.endpointLabel)
    }

    @Test
    fun uiStateComputedPropertiesResolveSelectedItemAndCounts() {
        val item1 = TetherMediaItem(
            id = "1",
            fileName = "A.ARW",
            fileSize = 1024L,
            transferStatus = TransferStatus.Transferring(0.5f),
            localUri = null,
            exifMetadata = null
        )
        val item2 = TetherMediaItem(
            id = "2",
            fileName = "B.ARW",
            fileSize = 1024L,
            transferStatus = TransferStatus.Completed,
            localUri = "file:///B.ARW",
            exifMetadata = null
        )

        val state = TetheringUiState(
            items = listOf(item1, item2),
            selectedItemId = "2"
        )

        assertEquals(item2, state.selectedItem)
        assertEquals(1, state.completedCount)
        assertEquals(1, state.transferringCount)
    }
}
