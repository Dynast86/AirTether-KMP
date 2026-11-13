package com.dynast.airtether.domain.model

enum class CullingFlag {
    NONE,
    PICK,
    REJECT,
}

enum class ColorLabel {
    NONE,
    RED,
    YELLOW,
    GREEN,
    BLUE,
    PURPLE,
}

data class CullingMetadata(
    val rating: Int = 0,
    val flag: CullingFlag = CullingFlag.NONE,
    val colorLabel: ColorLabel = ColorLabel.NONE,
) {
    init {
        require(rating in 0..5) { "Rating must be between 0 and 5" }
    }
}
