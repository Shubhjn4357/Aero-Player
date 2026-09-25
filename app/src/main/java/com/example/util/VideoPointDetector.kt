package com.example.util

import java.util.Locale

enum class VideoPointType(val label: String, val badgeColorHex: Long) {
    PROLOGUE("Prologue / Intro", 0xFF6366F1),          // Indigo
    RECAP("Recap / Cold Open", 0xFF8B5CF6),             // Violet
    TITLE_CARD("Title Sequence", 0xFF3B82F6),           // Blue
    CHAPTER("Main Feature", 0xFF10B981),                // Emerald Green
    INTERMISSION("Midpoint / Intermission", 0xFFF59E0B),// Amber
    CLIMAX("Climax & Finale", 0xFFEF4444),              // Rose / Red
    CREDITS("Rolling Credits", 0xFF64748B),             // Slate
    POST_CREDIT("Post-Credit Scene", 0xFFEC4899)        // Pink
}

data class VideoPoint(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: VideoPointType,
    val startMs: Long,
    val endMs: Long,
    val isSkippable: Boolean = false,
    val skipTargetMs: Long = endMs,
    val isNativeChapter: Boolean = false
) {
    fun formatRange(): String {
        return "${formatTime(startMs)} - ${formatTime(endMs)}"
    }

    fun formatDuration(): String {
        val durationSec = ((endMs - startMs) / 1000).coerceAtLeast(0)
        val m = durationSec / 60
        val s = durationSec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

object VideoPointDetector {

    /**
     * Maps native media chapters parsed directly from MKV/MP4 files (LibVLC or Media3)
     * into VideoPoints, automatically recognizing prologue/intro, recap, opening/ending,
     * credits, and post-credit tags.
     */
    fun mapNativeChapters(
        nativeChapters: List<NativeChapterRaw>,
        durationMs: Long
    ): List<VideoPoint> {
        if (nativeChapters.isEmpty()) return emptyList()

        // Sort by start offset
        val sorted = nativeChapters.sortedBy { it.startTimeMs }
        val points = mutableListOf<VideoPoint>()

        for (i in sorted.indices) {
            val curr = sorted[i]
            val nextStart = if (i < sorted.size - 1) sorted[i + 1].startTimeMs else durationMs
            val effectiveEnd = if (curr.durationMs > 0) {
                (curr.startTimeMs + curr.durationMs).coerceAtMost(durationMs)
            } else {
                nextStart.coerceAtMost(durationMs)
            }

            if (effectiveEnd <= curr.startTimeMs) continue

            val lowerName = curr.name.trim().lowercase(Locale.US)

            // Categorize based on native chapter title heuristics
            val (type, isSkippable, skipTarget) = when {
                lowerName.contains("prologue") || lowerName.contains("cold open") || lowerName.contains("recap") -> {
                    Triple(VideoPointType.PROLOGUE, true, effectiveEnd)
                }
                lowerName.contains("intro") || lowerName.contains("opening") || lowerName.contains("op") -> {
                    Triple(VideoPointType.PROLOGUE, true, effectiveEnd)
                }
                lowerName.contains("title") -> {
                    Triple(VideoPointType.TITLE_CARD, false, effectiveEnd)
                }
                lowerName.contains("post-credit") || lowerName.contains("post credit") || lowerName.contains("stinger") || lowerName.contains("after credit") -> {
                    Triple(VideoPointType.POST_CREDIT, true, effectiveEnd)
                }
                lowerName.contains("credit") || lowerName.contains("ending") || lowerName.contains("ed") || lowerName.contains("outro") -> {
                    Triple(VideoPointType.CREDITS, true, effectiveEnd)
                }
                lowerName.contains("intermission") || lowerName.contains("break") -> {
                    Triple(VideoPointType.INTERMISSION, false, effectiveEnd)
                }
                lowerName.contains("climax") || lowerName.contains("finale") -> {
                    Triple(VideoPointType.CLIMAX, false, effectiveEnd)
                }
                else -> {
                    // Check if it's the very first chapter under 3 minutes, or the very last chapter
                    if (i == 0 && (effectiveEnd - curr.startTimeMs) in 10_000L..180_000L && lowerName.contains("start")) {
                        Triple(VideoPointType.PROLOGUE, true, effectiveEnd)
                    } else if (i == sorted.size - 1 && (durationMs - curr.startTimeMs) in 15_000L..300_000L && (lowerName.contains("end") || lowerName.contains("credit"))) {
                        Triple(VideoPointType.CREDITS, true, effectiveEnd)
                    } else {
                        Triple(VideoPointType.CHAPTER, false, effectiveEnd)
                    }
                }
            }

            points.add(
                VideoPoint(
                    id = "native_ch_${i + 1}",
                    title = curr.name.ifBlank { "Chapter ${i + 1}" },
                    subtitle = "Native Media Chapter ${i + 1} (${type.label})",
                    type = type,
                    startMs = curr.startTimeMs,
                    endMs = effectiveEnd,
                    isSkippable = isSkippable,
                    skipTargetMs = skipTarget,
                    isNativeChapter = true
                )
            )
        }

        return points
    }

    /**
     * Generic data holder for raw chapters extracted from video files.
     */
    data class NativeChapterRaw(
        val name: String,
        val startTimeMs: Long,
        val durationMs: Long = 0L
    )

    /**
     * Intelligently analyzes video duration and divides the timeline into designated
     * cinematic skip points, acts, prologue, credits, and post-credit scenes.
     */
    fun detectPoints(
        durationMs: Long,
        customPrologueSec: Int? = null,
        customPostCreditSec: Int? = null,
        nativeChapters: List<NativeChapterRaw> = emptyList()
    ): List<VideoPoint> {
        // If native chapters exist in the file, use them directly!
        if (nativeChapters.isNotEmpty()) {
            val mapped = mapNativeChapters(nativeChapters, durationMs)
            if (mapped.isNotEmpty()) {
                return mapped
            }
        }

        if (durationMs <= 10_000L) return emptyList()

        val points = mutableListOf<VideoPoint>()
        val totalSec = durationMs / 1000

        when {
            // Short clip (< 1 minute)
            totalSec < 60 -> {
                points.add(
                    VideoPoint(
                        id = "main_feature",
                        title = "Main Video",
                        subtitle = "Full clip playback",
                        type = VideoPointType.CHAPTER,
                        startMs = 0L,
                        endMs = durationMs
                    )
                )
            }

            // Quick clip (1 to 5 minutes)
            totalSec <= 300 -> {
                val prologueDuration = (customPrologueSec?.toLong()?.times(1000L)) ?: (15_000L.coerceAtMost(durationMs / 6))
                val outroDuration = (customPostCreditSec?.toLong()?.times(1000L)) ?: 12_000L

                // 1. Intro
                points.add(
                    VideoPoint(
                        id = "intro",
                        title = "Starting Prologue",
                        subtitle = "Opening intro hook",
                        type = VideoPointType.PROLOGUE,
                        startMs = 0L,
                        endMs = prologueDuration,
                        isSkippable = true,
                        skipTargetMs = prologueDuration
                    )
                )

                // 2. Main Feature
                val mainEnd = (durationMs - outroDuration).coerceAtLeast(prologueDuration)
                points.add(
                    VideoPoint(
                        id = "main",
                        title = "Main Feature",
                        subtitle = "Primary content",
                        type = VideoPointType.CHAPTER,
                        startMs = prologueDuration,
                        endMs = mainEnd
                    )
                )

                // 3. Post-Credit / Outro
                if (outroDuration > 0 && mainEnd < durationMs) {
                    points.add(
                        VideoPoint(
                            id = "outro",
                            title = "Post-Credit Outro",
                            subtitle = "End cards & conclusion",
                            type = VideoPointType.POST_CREDIT,
                            startMs = mainEnd,
                            endMs = durationMs
                        )
                    )
                }
            }

            // Medium Episode / TV Show (5 to 25 minutes, e.g. anime standard 24 min)
            totalSec <= 1500 -> {
                val prologueSec = customPrologueSec ?: 90 // Standard 90s opening
                val prologueMs = (prologueSec * 1000L).coerceAtMost(durationMs / 4)

                val postCreditSec = customPostCreditSec ?: 30
                val creditsLengthMs = 90_000L.coerceAtMost(durationMs / 5)
                val postCreditMs = (postCreditSec * 1000L).coerceAtMost(durationMs / 10)

                val creditsStart = (durationMs - creditsLengthMs - postCreditMs).coerceAtLeast(prologueMs)
                val postCreditStart = (durationMs - postCreditMs).coerceAtLeast(creditsStart)

                // 1. Prologue / OP
                points.add(
                    VideoPoint(
                        id = "prologue",
                        title = "Starting Prologue",
                        subtitle = "Cold open & opening theme",
                        type = VideoPointType.PROLOGUE,
                        startMs = 0L,
                        endMs = prologueMs,
                        isSkippable = true,
                        skipTargetMs = prologueMs
                    )
                )

                // 2. Act 1: The Inciting Incident
                val midPoint = prologueMs + (creditsStart - prologueMs) / 2
                points.add(
                    VideoPoint(
                        id = "act1",
                        title = "Act 1: Development",
                        subtitle = "Narrative setup & dialogue",
                        type = VideoPointType.CHAPTER,
                        startMs = prologueMs,
                        endMs = midPoint
                    )
                )

                // 3. Act 2: Climax & Resolution
                points.add(
                    VideoPoint(
                        id = "climax",
                        title = "Climax & Resolution",
                        subtitle = "High tension confrontation",
                        type = VideoPointType.CLIMAX,
                        startMs = midPoint,
                        endMs = creditsStart
                    )
                )

                // 4. Rolling Credits / Ending Theme
                points.add(
                    VideoPoint(
                        id = "credits",
                        title = "Ending Theme & Credits",
                        subtitle = "Staff roll & outro song",
                        type = VideoPointType.CREDITS,
                        startMs = creditsStart,
                        endMs = postCreditStart,
                        isSkippable = true,
                        skipTargetMs = postCreditStart
                    )
                )

                // 5. Post-Credit Scene (Preview or Stinger)
                if (postCreditMs > 0 && postCreditStart < durationMs) {
                    points.add(
                        VideoPoint(
                            id = "post_credit",
                            title = "Post-Credit Scene",
                            subtitle = "Epilogue & next episode teaser",
                            type = VideoPointType.POST_CREDIT,
                            startMs = postCreditStart,
                            endMs = durationMs
                        )
                    )
                }
            }

            // Long TV Episode / Mini-series (25 to 60 minutes)
            totalSec <= 3600 -> {
                val prologueSec = customPrologueSec ?: 110 // Recap + Title sequence
                val prologueMs = (prologueSec * 1000L).coerceAtMost(durationMs / 5)

                val postCreditSec = customPostCreditSec ?: 45
                val creditsLengthMs = 120_000L
                val postCreditMs = (postCreditSec * 1000L)

                val creditsStart = (durationMs - creditsLengthMs - postCreditMs).coerceAtLeast(prologueMs)
                val postCreditStart = (durationMs - postCreditMs).coerceAtLeast(creditsStart)

                val storyDuration = creditsStart - prologueMs
                val act1End = prologueMs + (storyDuration * 0.35).toLong()
                val act2End = prologueMs + (storyDuration * 0.70).toLong()

                // 1. Prologue / Intro
                points.add(
                    VideoPoint(
                        id = "prologue",
                        title = "Starting Prologue",
                        subtitle = "Recap & opening title sequence",
                        type = VideoPointType.PROLOGUE,
                        startMs = 0L,
                        endMs = prologueMs,
                        isSkippable = true,
                        skipTargetMs = prologueMs
                    )
                )

                // 2. Act 1
                points.add(
                    VideoPoint(
                        id = "act1",
                        title = "Act 1: The Setup",
                        subtitle = "Opening storyline",
                        type = VideoPointType.CHAPTER,
                        startMs = prologueMs,
                        endMs = act1End
                    )
                )

                // 3. Act 2: Intermission & Pivot
                points.add(
                    VideoPoint(
                        id = "act2",
                        title = "Act 2: The Escalation",
                        subtitle = "Conflict & central turning point",
                        type = VideoPointType.INTERMISSION,
                        startMs = act1End,
                        endMs = act2End
                    )
                )

                // 4. Climax
                points.add(
                    VideoPoint(
                        id = "climax",
                        title = "Act 3: The Climax",
                        subtitle = "Episode peak and resolution",
                        type = VideoPointType.CLIMAX,
                        startMs = act2End,
                        endMs = creditsStart
                    )
                )

                // 5. Ending Credits
                points.add(
                    VideoPoint(
                        id = "credits",
                        title = "Rolling Credits",
                        subtitle = "Production cast & credits",
                        type = VideoPointType.CREDITS,
                        startMs = creditsStart,
                        endMs = postCreditStart,
                        isSkippable = true,
                        skipTargetMs = postCreditStart
                    )
                )

                // 6. Post-Credit Scene
                if (postCreditMs > 0 && postCreditStart < durationMs) {
                    points.add(
                        VideoPoint(
                            id = "post_credit",
                            title = "Post-Credit Scene",
                            subtitle = "Teaser / bonus scene",
                            type = VideoPointType.POST_CREDIT,
                            startMs = postCreditStart,
                            endMs = durationMs
                        )
                    )
                }
            }

            // Full Feature Film / Long Video (> 60 minutes)
            else -> {
                val prologueSec = customPrologueSec ?: 150 // 2m 30s prologue
                val prologueMs = (prologueSec * 1000L).coerceAtMost(durationMs / 8)

                val postCreditSec = customPostCreditSec ?: 90
                val creditsLengthMs = 300_000L // 5 mins credits
                val postCreditMs = (postCreditSec * 1000L)

                val creditsStart = (durationMs - creditsLengthMs - postCreditMs).coerceAtLeast(prologueMs)
                val postCreditStart = (durationMs - postCreditMs).coerceAtLeast(creditsStart)

                val storyDuration = creditsStart - prologueMs
                val act1End = prologueMs + (storyDuration * 0.28).toLong()
                val midpoint = prologueMs + (storyDuration * 0.60).toLong()

                // 1. Prologue
                points.add(
                    VideoPoint(
                        id = "prologue",
                        title = "Starting Prologue",
                        subtitle = "Studio intro & cold open sequence",
                        type = VideoPointType.PROLOGUE,
                        startMs = 0L,
                        endMs = prologueMs,
                        isSkippable = true,
                        skipTargetMs = prologueMs
                    )
                )

                // 2. Act 1
                points.add(
                    VideoPoint(
                        id = "act1",
                        title = "Act 1: The Inciting Incident",
                        subtitle = "Character introduction & premise",
                        type = VideoPointType.CHAPTER,
                        startMs = prologueMs,
                        endMs = act1End
                    )
                )

                // 3. Act 2: Rising Action & Midpoint
                points.add(
                    VideoPoint(
                        id = "act2",
                        title = "Act 2: The Rising Confrontation",
                        subtitle = "Plot escalation & midpoint twist",
                        type = VideoPointType.INTERMISSION,
                        startMs = act1End,
                        endMs = midpoint
                    )
                )

                // 4. Act 3: Climax & Finale
                points.add(
                    VideoPoint(
                        id = "climax",
                        title = "Act 3: The Grand Climax",
                        subtitle = "Final resolution & culmination",
                        type = VideoPointType.CLIMAX,
                        startMs = midpoint,
                        endMs = creditsStart
                    )
                )

                // 5. Credits
                points.add(
                    VideoPoint(
                        id = "credits",
                        title = "Rolling Credits",
                        subtitle = "Original soundtrack & staff scroll",
                        type = VideoPointType.CREDITS,
                        startMs = creditsStart,
                        endMs = postCreditStart,
                        isSkippable = true,
                        skipTargetMs = postCreditStart
                    )
                )

                // 6. Post-Credit Stinger
                if (postCreditMs > 0 && postCreditStart < durationMs) {
                    points.add(
                        VideoPoint(
                            id = "post_credit",
                            title = "Post-Credit Scene",
                            subtitle = "Mid/post-credits bonus stinger",
                            type = VideoPointType.POST_CREDIT,
                            startMs = postCreditStart,
                            endMs = durationMs
                        )
                    )
                }
            }
        }

        return points
    }

    /**
     * Determines which point is currently active based on playback position.
     */
    fun getActivePoint(points: List<VideoPoint>, positionMs: Long): VideoPoint? {
        return points.firstOrNull { positionMs in it.startMs until it.endMs }
            ?: points.lastOrNull { positionMs >= it.startMs }
    }

    /**
     * Detects if the current playback position is in a skippable designated time window
     * (e.g. Prologue or Rolling Credits before post-credit scene).
     */
    fun getActiveSkippablePoint(points: List<VideoPoint>, positionMs: Long): VideoPoint? {
        return points.firstOrNull { it.isSkippable && positionMs in it.startMs until it.endMs }
    }
}
