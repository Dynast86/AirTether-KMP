package com.dynast.airtether.domain.port

import com.dynast.airtether.model.TetherMediaItem
import kotlinx.coroutines.flow.Flow

interface CullingRepository {
    fun observeItems(): Flow<List<TetherMediaItem>>
    suspend fun upsertItem(item: TetherMediaItem)
    suspend fun updateRating(itemId: String, rating: Int)
    suspend fun exportXmpSidecar(item: TetherMediaItem): Result<String>
}
