package com.dynast.airtether.presentation

sealed interface TetheringEffect {
    data class ScrollFilmstripTo(val photoId: String) : TetheringEffect
    data class ShowMessage(val message: String) : TetheringEffect
    data class ShowError(val message: String) : TetheringEffect
}
