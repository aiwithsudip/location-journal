package app.locationjournal.data

data class LegacyData(
    val points: List<PointEntity> = emptyList(),
    val photos: List<PhotoEntity> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val reelStates: List<ReelStateEntity> = emptyList(),
    val reelComments: List<ReelCommentEntity> = emptyList(),
    val tracking: Boolean = false,
)
