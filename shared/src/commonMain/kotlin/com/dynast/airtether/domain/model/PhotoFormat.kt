package com.dynast.airtether.domain.model

enum class PhotoFormat(val isRaw: Boolean) {
    JPEG(isRaw = false),
    CR3(isRaw = true),
    CR2(isRaw = true),
    ARW(isRaw = true),
    NEF(isRaw = true),
    RAF(isRaw = true),
    DNG(isRaw = true),
    UNKNOWN(isRaw = false);

    companion object {
        fun fromFileName(fileName: String): PhotoFormat {
            val ext = fileName.substringAfterLast('.', "").uppercase()
            return when (ext) {
                "JPG", "JPEG" -> JPEG
                "CR3" -> CR3
                "CR2" -> CR2
                "ARW" -> ARW
                "NEF" -> NEF
                "RAF" -> RAF
                "DNG" -> DNG
                else -> UNKNOWN
            }
        }
    }
}
