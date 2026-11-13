package com.dynast.airtether.domain.port

import com.dynast.airtether.domain.model.CullingMetadata
import com.dynast.airtether.domain.model.PhotoItem
import kotlinx.coroutines.flow.Flow

interface CullingRepository {
    fun observePhotos(): Flow<List<PhotoItem>>
    suspend fun upsertPhoto(photo: PhotoItem)
    suspend fun updateCulling(photoId: String, culling: CullingMetadata)
    suspend fun exportXmpSidecar(photo: PhotoItem): Result<String>
}
