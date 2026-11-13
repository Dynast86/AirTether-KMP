package com.dynast.airtether.culling

import com.dynast.airtether.domain.port.CullingRepository
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

actual fun createPlatformCullingRepository(sessionName: String): CullingRepository {
    val docs = NSSearchPathForDirectoriesInDomains(
        directory = NSDocumentDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String
    val root = (docs ?: NSTemporaryDirectory()).trimEnd('/')
    return LocalCullingRepository("$root/AirTether/$sessionName/culling")
}
