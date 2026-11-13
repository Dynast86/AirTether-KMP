package com.dynast.airtether.culling

import com.dynast.airtether.model.ExifMetadata
import com.dynast.airtether.model.TetherMediaItem
import com.dynast.airtether.model.TransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmpSidecarGeneratorTest {

    @Test
    fun generatesLightroomCompatibleXmpWithRatingAndExif() {
        val item = TetherMediaItem(
            id = "shot-1",
            fileName = "DSC_0001.NEF",
            fileSize = 48_000_000L,
            transferStatus = TransferStatus.Completed,
            localUri = "/tmp/DSC_0001.NEF",
            exifMetadata = ExifMetadata(
                shutterSpeed = "1/640s",
                aperture = "f/2.8",
                iso = 64,
                focalLength = "70mm",
                cameraModel = "Nikon Z8 & Grip",
            ),
            rating = 5,
        )

        val xml = XmpSidecarGenerator.generate(item)

        assertTrue(xml.contains("""<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="AirTether-KMP">"""))
        assertTrue(xml.contains("""xmp:Rating="5""""))
        assertTrue(xml.contains("""tiff:Model="Nikon Z8 &amp; Grip""""))
        assertTrue(xml.contains("""exif:ExposureTime="1/640s""""))
        assertTrue(xml.contains("""exif:FNumber="2.8""""))
        assertTrue(xml.contains("""exif:FocalLength="70""""))
        assertTrue(xml.contains("""<rdf:li>64</rdf:li>"""))
    }

    @Test
    fun resolvesSidecarFileNameByReplacingExtension() {
        assertEquals("DSC_0001.xmp", XmpSidecarGenerator.resolveSidecarFileName("DSC_0001.NEF"))
        assertEquals("IMG_9999.xmp", XmpSidecarGenerator.resolveSidecarFileName("IMG_9999.CR3"))
    }
}
