package com.dynast.airtether.culling

import com.dynast.airtether.domain.port.AndroidStorageContextHolder
import com.dynast.airtether.domain.port.CullingRepository
import java.io.File

actual fun createPlatformCullingRepository(sessionName: String): CullingRepository {
    val root = AndroidStorageContextHolder.appContext?.filesDir
        ?: File(System.getProperty("java.io.tmpdir").orEmpty().ifBlank { "." })
    val dir = File(root, "AirTether/$sessionName/culling")
    return LocalCullingRepository(dir.absolutePath)
}
