package com.dynast.airtether.culling

import com.dynast.airtether.domain.port.CullingRepository
import java.io.File

actual fun createPlatformCullingRepository(sessionName: String): CullingRepository {
    val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
    val dir = File(home, "Pictures/AirTether/$sessionName/.culling")
    return LocalCullingRepository(dir.absolutePath)
}
