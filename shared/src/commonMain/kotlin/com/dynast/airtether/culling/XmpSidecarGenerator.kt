package com.dynast.airtether.culling

import com.dynast.airtether.model.TetherMediaItem

/**
 * Generates Adobe Lightroom / Capture One compatible XMP sidecar XML (`*.xmp`)
 * containing star ratings (`xmp:Rating`) and basic EXIF metadata.
 */
object XmpSidecarGenerator {

    fun generate(item: TetherMediaItem): String {
        val rating = item.rating.coerceIn(0, 5)
        val exif = item.exifMetadata
        val cameraModel = escapeXml(exif?.cameraModel ?: "Unknown Camera")
        val shutter = escapeXml(exif?.shutterSpeed ?: "")
        val aperture = escapeXml(exif?.aperture?.removePrefix("f/") ?: "")
        val focal = escapeXml(exif?.focalLength?.removeSuffix("mm") ?: "")
        val iso = exif?.iso ?: 0
        val fileName = escapeXml(item.fileName)

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="AirTether-KMP">""")
            appendLine(""" <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""")
            appendLine("""  <rdf:Description rdf:about="$fileName"""")
            appendLine("""    xmlns:xmp="http://ns.adobe.com/xap/1.0/"""")
            appendLine("""    xmlns:tiff="http://ns.adobe.com/tiff/1.0/"""")
            appendLine("""    xmlns:exif="http://ns.adobe.com/exif/1.0/"""")
            appendLine("""    xmp:CreatorTool="AirTether-KMP"""")
            appendLine("""    xmp:Rating="$rating"""")
            if (exif != null) {
                appendLine("""    tiff:Model="$cameraModel"""")
                if (shutter.isNotEmpty()) appendLine("""    exif:ExposureTime="$shutter"""")
                if (aperture.isNotEmpty()) appendLine("""    exif:FNumber="$aperture"""")
                if (focal.isNotEmpty()) appendLine("""    exif:FocalLength="$focal"""")
                if (iso > 0) {
                    appendLine(""">""")
                    appendLine("""   <exif:ISOSpeedRatings>""")
                    appendLine("""    <rdf:Seq>""")
                    appendLine("""     <rdf:li>$iso</rdf:li>""")
                    appendLine("""    </rdf:Seq>""")
                    appendLine("""   </exif:ISOSpeedRatings>""")
                    appendLine("""  </rdf:Description>""")
                } else {
                    appendLine("""  />""")
                }
            } else {
                appendLine("""  />""")
            }
            appendLine(""" </rdf:RDF>""")
            appendLine("""</x:xmpmeta>""")
        }
    }

    fun resolveSidecarFileName(originalFileName: String): String {
        val base = originalFileName.substringBeforeLast('.', originalFileName)
        return "$base.xmp"
    }

    private fun escapeXml(input: String): String {
        return buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }
    }
}
