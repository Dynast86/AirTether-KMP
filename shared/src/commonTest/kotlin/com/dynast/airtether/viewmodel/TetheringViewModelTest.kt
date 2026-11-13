package com.dynast.airtether.viewmodel

import com.dynast.airtether.model.ServerStatus
import com.dynast.airtether.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TetheringViewModelTest {

    private fun createTestViewModel(): TetheringViewModel {
        return TetheringViewModel(
            autoSimulateInitialTransfer = false,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    @Test
    fun initialStateHasMockItemsAndRunningFtpServer() {
        val viewModel = createTestViewModel()
        val state = viewModel.uiState.value

        val runningStatus = assertIs<ServerStatus.Running>(state.serverStatus)
        assertEquals("192.168.43.1", runningStatus.ipAddress)
        assertEquals(2121, runningStatus.port)
        assertTrue(state.isLiveFollowEnabled)
        assertTrue(state.isExifOverlayVisible)
        assertTrue(state.items.isNotEmpty())
        assertNotNull(state.selectedItem)
    }

    @Test
    fun viewerUserGesturePausesLiveFollow() {
        val viewModel = createTestViewModel()
        assertTrue(viewModel.uiState.value.isLiveFollowEnabled)

        // 메인 뷰어에서 핀치 줌 또는 더블탭 제스처 발생 시 Live Follow가 일시 중지되어야 함
        viewModel.onIntent(TetheringIntent.PauseLiveFollowOnGesture)
        assertFalse(viewModel.uiState.value.isLiveFollowEnabled)
    }

    @Test
    fun selectingOlderItemInFilmstripPausesLiveFollowAndToggleRestoresLatest() {
        val viewModel = createTestViewModel()

        // 필름스트립에서 이전(과거) 사진 수동 선택 시 Live Follow 자동 일시 중지
        val olderItem = viewModel.uiState.value.items.last()
        viewModel.onIntent(TetheringIntent.SelectItem(olderItem.id, isUserGesture = true))
        assertEquals(olderItem.id, viewModel.uiState.value.selectedItemId)
        assertFalse(viewModel.uiState.value.isLiveFollowEnabled)

        // 상단 Live Follow 토글 칩 클릭 시 다시 활성화되면서 최신 사진(index 0)으로 즉시 포커스 이동
        viewModel.onIntent(TetheringIntent.ToggleLiveFollow)
        assertTrue(viewModel.uiState.value.isLiveFollowEnabled)
        assertEquals(viewModel.uiState.value.items.first().id, viewModel.uiState.value.selectedItemId)
    }

    @Test
    fun simulateIncomingPhotoAutoSelectsWhenLiveFollowEnabled() {
        val viewModel = createTestViewModel()
        val initialCount = viewModel.uiState.value.items.size

        // Live Follow가 켜진 상태에서 새 사진 수신 시뮬레이션 실행
        viewModel.onIntent(TetheringIntent.SimulateIncomingPhoto)

        val updatedState = viewModel.uiState.value
        assertEquals(initialCount + 1, updatedState.items.size)
        val newestItem = updatedState.items.first()
        assertEquals(newestItem.id, updatedState.selectedItemId)
        assertIs<TransferStatus.Transferring>(newestItem.transferStatus)
    }

    @Test
    fun simulateIncomingPhotoKeepsCurrentSelectionWhenLiveFollowPaused() {
        val viewModel = createTestViewModel()
        val lockedSelectionId = viewModel.uiState.value.items.last().id

        // 이전 사진을 선택하여 Live Follow 일시 중지
        viewModel.onIntent(TetheringIntent.SelectItem(lockedSelectionId, isUserGesture = true))
        assertFalse(viewModel.uiState.value.isLiveFollowEnabled)

        // 새 사진이 들어와도 사용자가 보고 있던 사진 선택(lockedSelectionId)이 유지되어야 함
        viewModel.onIntent(TetheringIntent.SimulateIncomingPhoto)
        assertEquals(lockedSelectionId, viewModel.uiState.value.selectedItemId)
    }

    @Test
    fun updateRatingAndToggleSameRatingResetsToZero() {
        val viewModel = createTestViewModel()
        val targetId = viewModel.uiState.value.items.first().id

        // 별점 4점 부여
        viewModel.onIntent(TetheringIntent.UpdateRating(targetId, 4))
        assertEquals(4, viewModel.uiState.value.items.first { it.id == targetId }.rating)

        // 동일한 별점(4점)을 다시 클릭하면 0점으로 해제
        viewModel.onIntent(TetheringIntent.UpdateRating(targetId, 4))
        assertEquals(0, viewModel.uiState.value.items.first { it.id == targetId }.rating)
    }

    @Test
    fun toggleExifOverlayAndServerStatus() {
        val viewModel = createTestViewModel()
        assertTrue(viewModel.uiState.value.isExifOverlayVisible)

        viewModel.onIntent(TetheringIntent.ToggleExifOverlay)
        assertFalse(viewModel.uiState.value.isExifOverlayVisible)

        viewModel.onIntent(TetheringIntent.ToggleServerStatus)
        assertIs<ServerStatus.Stopped>(viewModel.uiState.value.serverStatus)

        viewModel.onIntent(TetheringIntent.ToggleServerStatus)
        assertIs<ServerStatus.Running>(viewModel.uiState.value.serverStatus)
    }

    @Test
    fun ratingFilterAndSessionNameUpdate() {
        val viewModel = createTestViewModel()
        viewModel.onIntent(TetheringIntent.UpdateSessionName("Wedding_Outdoor_02"))
        assertEquals("Wedding_Outdoor_02", viewModel.uiState.value.sessionName)

        viewModel.onIntent(TetheringIntent.SetRatingFilter(4))
        assertTrue(viewModel.uiState.value.visibleItems.all { it.rating >= 4 })

        viewModel.onIntent(TetheringIntent.SetRatingFilter(0))
        assertEquals(viewModel.uiState.value.items.size, viewModel.uiState.value.visibleItems.size)
    }

    @Test
    fun clearMockItemsRemovesInitialAndSimulatedMockItems() {
        val viewModel = createTestViewModel()
        assertTrue(viewModel.uiState.value.hasMockItems)

        viewModel.onIntent(TetheringIntent.SimulateIncomingPhoto)
        assertTrue(viewModel.uiState.value.items.isNotEmpty())

        viewModel.onIntent(TetheringIntent.ClearMockItems)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertFalse(viewModel.uiState.value.hasMockItems)
    }

    @Test
    fun ptpSessionLiveViewAndShutterIntentsUpdateState() {
        val fakePtp = FakePtpSessionManager()
        val viewModel = TetheringViewModel(
            autoSimulateInitialTransfer = false,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ptpSessionManager = fakePtp,
        )

        assertFalse(viewModel.uiState.value.isPtpConnected)
        assertFalse(viewModel.uiState.value.isLiveViewActive)

        viewModel.onIntent(TetheringIntent.ToggleLiveView)
        assertTrue(viewModel.uiState.value.isPtpConnected)
        assertTrue(viewModel.uiState.value.isLiveViewActive)

        viewModel.onIntent(TetheringIntent.TriggerPtpShutter)
        assertEquals(1, fakePtp.shutterCount)

        viewModel.onIntent(TetheringIntent.ToggleLiveView)
        assertFalse(viewModel.uiState.value.isLiveViewActive)

        viewModel.onIntent(TetheringIntent.TogglePtpSession)
        assertFalse(viewModel.uiState.value.isPtpConnected)
    }
}

private class FakePtpSessionManager : com.dynast.airtether.domain.port.PtpSessionManager {
    private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val isConnected = _isConnected

    private val _isLiveViewActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val isLiveViewActive = _isLiveViewActive

    private val frames = kotlinx.coroutines.flow.MutableSharedFlow<com.dynast.airtether.domain.port.LiveViewFramePacket>()
    var shutterCount = 0

    override suspend fun openSession(): Result<Unit> {
        _isConnected.value = true
        return Result.success(Unit)
    }

    override suspend fun closeSession() {
        _isLiveViewActive.value = false
        _isConnected.value = false
    }

    override fun observeLiveViewFrames() = frames

    override suspend fun startLiveView(): Result<Unit> {
        _isLiveViewActive.value = true
        return Result.success(Unit)
    }

    override suspend fun stopLiveView() {
        _isLiveViewActive.value = false
    }

    override suspend fun triggerShutter(): Result<Unit> {
        shutterCount++
        return Result.success(Unit)
    }
}
