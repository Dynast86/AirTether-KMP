package com.dynast.airtether.culling

import com.dynast.airtether.domain.port.CullingRepository
import platform.Foundation.NSTemporaryDirectory

actual fun createPlatformCullingRepository(sessionName: String): CullingRepository {
    val root = NSTemporaryDirectory().trimEnd('/')
    return LocalCullingRepository("$root/AirTether/$sessionName/culling")
}
