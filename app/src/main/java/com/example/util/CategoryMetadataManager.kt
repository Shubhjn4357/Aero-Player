package com.example.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.data.database.MediaEntity
import java.io.File

/**
 * Data structures and utilities for category-wise media format metadata,
 * hardware/software codec detection, and direct video/audio intent opening.
 */
enum class MediaFormatCategory(
    val title: String,
    val description: String,
    val iconName: String
) {
    VIDEO_CONTAINER(
        "Video Containers",
        "High-definition video formats, multiplexers, and raw streams",
        "video"
    ),
    AUDIO_CODEC(
        "Audio Codecs",
        "Lossless Hi-Res audio, surround sound, and high-efficiency streams",
        "audio"
    ),
    SUBTITLE_FORMAT(
        "Subtitles & Captions",
        "Styled vector subtitles, timed text, and bitmap overlays",
        "subtitles"
    ),
    STREAM_PROTOCOL(
        "Network & Streaming",
        "Live HTTP/HTTPS, adaptive bitrates, and local network shares",
        "stream"
    ),
    HARDWARE_DECODER(
        "Decoders & Acceleration",
        "Android MediaCodec Hardware & LibVLC native pipelines",
        "hardware"
    )
}

data class FormatSpec(
    val name: String,
    val extension: String,
    val mimeType: String,
    val category: MediaFormatCategory,
    val engine: String, // "Media3 / LibVLC", "LibVLC Native", etc.
    val maxResolutionOrQuality: String,
    val description: String,
    val isHardwareAccelerated: Boolean = true,
    val features: List<String> = emptyList()
)

data class MediaCategoryMetadata(
    val category: MediaFormatCategory,
    val containerFormat: String,
    val codecFamily: String,
    val qualityTier: String,
    val audioLayout: String,
    val sampleRateOrFps: String,
    val bitrateTier: String,
    val recommendedEngine: String,
    val badges: List<String>
)

object CategoryMetadataManager {

    val supportedVideoFormats = listOf(
        FormatSpec(
            name = "Matroska Video",
            extension = ".mkv",
            mimeType = "video/x-matroska",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "Media3 ExoPlayer & LibVLC",
            maxResolutionOrQuality = "Up to 8K Ultra HD (HDR10+ / Dolby Vision)",
            description = "Universal open-standard container supporting unlimited video, audio, and ASS/SSA styled subtitle tracks.",
            isHardwareAccelerated = true,
            features = listOf("Multiple Audio Tracks", "Stylized ASS Subtitles", "Chapter Markers", "10-bit / HDR")
        ),
        FormatSpec(
            name = "MPEG-4 Part 14",
            extension = ".mp4 / .m4v",
            mimeType = "video/mp4",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "Media3 ExoPlayer & LibVLC",
            maxResolutionOrQuality = "Up to 8K UHD",
            description = "Industry standard video container with universal hardware acceleration across all Android chipsets.",
            isHardwareAccelerated = true,
            features = listOf("AVC / HEVC / AV1", "Gapless Playback", "Fast Streaming Seek")
        ),
        FormatSpec(
            name = "Google WebM",
            extension = ".webm",
            mimeType = "video/webm",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "Media3 ExoPlayer & LibVLC",
            maxResolutionOrQuality = "Up to 4K UHD",
            description = "Royalty-free HTML5 video container optimized for VP8, VP9, and AV1 video streams.",
            isHardwareAccelerated = true,
            features = listOf("VP9 Profile 2", "Opus Audio", "Efficient Web Streaming")
        ),
        FormatSpec(
            name = "Audio Video Interleave",
            extension = ".avi",
            mimeType = "video/x-msvideo",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "LibVLC Native Engine",
            maxResolutionOrQuality = "Full HD 1080p",
            description = "Classic Windows multimedia container format with full legacy codec support.",
            isHardwareAccelerated = true,
            features = listOf("Xvid / DivX", "PCM / MP3 Audio", "Legacy Compatibility")
        ),
        FormatSpec(
            name = "Apple QuickTime",
            extension = ".mov / .qt",
            mimeType = "video/quicktime",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "Up to 4K ProRes / H.264",
            description = "High-fidelity video format used by Apple iOS and macOS recording devices.",
            isHardwareAccelerated = true,
            features = listOf("ProRes Decoding", "Multi-channel AAC", "Spatial Metadata")
        ),
        FormatSpec(
            name = "MPEG Transport Stream",
            extension = ".ts / .m2ts",
            mimeType = "video/mp2t",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "Broadcast 1080i / 4K",
            description = "Digital television broadcast and Blu-ray disc stream multiplexer.",
            isHardwareAccelerated = true,
            features = listOf("DVB / ATSC Broadcast", "Seamless Segment Stitching", "AC-3 Passthrough")
        ),
        FormatSpec(
            name = "Flash Video",
            extension = ".flv",
            mimeType = "video/x-flv",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "LibVLC Native Engine",
            maxResolutionOrQuality = "1080p Full HD",
            description = "Web streaming container with Sorenson Spark, VP6, and H.264 video decoding.",
            isHardwareAccelerated = false,
            features = listOf("RTMP Live Streams", "Nellymoser Audio", "Fast Startup")
        ),
        FormatSpec(
            name = "3GPP Mobile Media",
            extension = ".3gp / .3g2",
            mimeType = "video/3gpp",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "Media3 ExoPlayer & LibVLC",
            maxResolutionOrQuality = "HD 720p",
            description = "Mobile multimedia container designed for telecommunications and low-bandwidth capture.",
            isHardwareAccelerated = true,
            features = listOf("H.263 / MPEG-4", "AMR Audio", "Ultra Compact")
        ),
        FormatSpec(
            name = "DVD Video Object",
            extension = ".vob",
            mimeType = "video/dvd",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "LibVLC Native Engine",
            maxResolutionOrQuality = "DVD NTSC/PAL 480p/576p",
            description = "MPEG-2 Program Stream container containing multiplexed video, AC-3 audio, and subtitle streams.",
            isHardwareAccelerated = true,
            features = listOf("MPEG-2 Video", "Dolby AC3 5.1", "DVD Subtitles")
        ),
        FormatSpec(
            name = "Ogg Theora Video",
            extension = ".ogv / .ogg",
            mimeType = "video/ogg",
            category = MediaFormatCategory.VIDEO_CONTAINER,
            engine = "LibVLC Native Engine",
            maxResolutionOrQuality = "1080p HD",
            description = "Open container encapsulating Theora video and Vorbis audio streams.",
            isHardwareAccelerated = false,
            features = listOf("Open Format", "Vorbis Stereo", "Lossless Framing")
        )
    )

    val supportedAudioFormats = listOf(
        FormatSpec(
            name = "Free Lossless Audio Codec",
            extension = ".flac",
            mimeType = "audio/flac",
            category = MediaFormatCategory.AUDIO_CODEC,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "24-bit / 192 kHz Hi-Res Lossless",
            description = "Bit-perfect lossless compression preserving studio audio fidelity with zero quality degradation.",
            isHardwareAccelerated = true,
            features = listOf("24-bit Studio Master", "Vorbis Comment Metadata", "Embedded Album Art")
        ),
        FormatSpec(
            name = "MPEG-1/2 Audio Layer III",
            extension = ".mp3",
            mimeType = "audio/mpeg",
            category = MediaFormatCategory.AUDIO_CODEC,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "320 kbps CBR / VBR",
            description = "Universally compatible audio format with full ID3v1/ID3v2 metadata and gapless playback.",
            isHardwareAccelerated = true,
            features = listOf("Universal Compatibility", "ID3v2.4 Tags", "Low Battery Draw")
        ),
        FormatSpec(
            name = "Advanced Audio Coding",
            extension = ".aac / .m4a",
            mimeType = "audio/mp4",
            category = MediaFormatCategory.AUDIO_CODEC,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "Up to 512 kbps / 7.1 Multi-channel",
            description = "High-efficiency perceptual audio codec used across Apple Music, YouTube, and digital broadcast.",
            isHardwareAccelerated = true,
            features = listOf("AAC-LC / HE-AAC", "5.1 Surround", "iTunes Metadata")
        ),
        FormatSpec(
            name = "Waveform Audio File Format",
            extension = ".wav",
            mimeType = "audio/wav",
            category = MediaFormatCategory.AUDIO_CODEC,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "32-bit Float / 384 kHz PCM",
            description = "Uncompressed Linear PCM audio format used for professional recording and zero-latency playback.",
            isHardwareAccelerated = true,
            features = listOf("Uncompressed PCM", "Zero Latency", "Floating Point Depth")
        ),
        FormatSpec(
            name = "Opus Interactive Audio",
            extension = ".opus",
            mimeType = "audio/opus",
            category = MediaFormatCategory.AUDIO_CODEC,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "Fullband 48 kHz / 510 kbps",
            description = "Modern IETF audio codec outperforming MP3 and AAC at all bitrates for speech and music.",
            isHardwareAccelerated = true,
            features = listOf("Ultra-low Delay", "Adaptive Bitrate", "Seamless Dynamic Bandwidth")
        ),
        FormatSpec(
            name = "Apple Lossless (ALAC)",
            extension = ".m4a / .alac",
            mimeType = "audio/x-m4a",
            category = MediaFormatCategory.AUDIO_CODEC,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "24-bit / 192 kHz Lossless",
            description = "Apple bit-perfect lossless compression algorithm for high-resolution iOS & macOS audio collections.",
            isHardwareAccelerated = true,
            features = listOf("Bit-perfect Accuracy", "M4A Container", "Quick Seeking")
        ),
        FormatSpec(
            name = "Ogg Vorbis",
            extension = ".ogg",
            mimeType = "audio/ogg",
            category = MediaFormatCategory.AUDIO_CODEC,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "500 kbps Variable Bitrate",
            description = "Open source perceptual audio format offering superior acoustic transparency to MP3.",
            isHardwareAccelerated = true,
            features = listOf("Ogg Framing", "ReplayGain Support", "Streaming Capable")
        ),
        FormatSpec(
            name = "Dolby Digital & Plus (AC-3 / E-AC-3)",
            extension = ".ac3 / .eac3",
            mimeType = "audio/ac3",
            category = MediaFormatCategory.AUDIO_CODEC,
            engine = "Media3 & LibVLC (Bitstream Passthrough)",
            maxResolutionOrQuality = "7.1 Surround (6.144 Mbps)",
            description = "Cinema multichannel surround sound standard with S/PDIF and HDMI digital passthrough.",
            isHardwareAccelerated = true,
            features = listOf("5.1 & 7.1 Channels", "Digital Passthrough", "Cinema Dynamic Range")
        )
    )

    val supportedSubtitleFormats = listOf(
        FormatSpec(
            name = "Advanced SubStation Alpha",
            extension = ".ass / .ssa",
            mimeType = "text/x-ssa",
            category = MediaFormatCategory.SUBTITLE_FORMAT,
            engine = "LibVLC & Embedded Renderer",
            maxResolutionOrQuality = "Vector Scalable (Any PPI)",
            description = "Full typography, positioning, colors, karaoke effects, and anime typesetting styling.",
            isHardwareAccelerated = false,
            features = listOf("Custom Fonts", "Keyframe Animations", "Vector Graphics", "Precise Positioning")
        ),
        FormatSpec(
            name = "SubRip Text Subtitles",
            extension = ".srt",
            mimeType = "application/x-subrip",
            category = MediaFormatCategory.SUBTITLE_FORMAT,
            engine = "Media3 & LibVLC",
            maxResolutionOrQuality = "Universal Text",
            description = "World standard timed text subtitle format with HTML bold, italic, and color tag styling.",
            isHardwareAccelerated = true,
            features = listOf("Universal Support", "Custom Font Sizing", "Subtitle Offset Delay Control")
        ),
        FormatSpec(
            name = "WebVTT (Web Video Text Tracks)",
            extension = ".vtt",
            mimeType = "text/vtt",
            category = MediaFormatCategory.SUBTITLE_FORMAT,
            engine = "Media3 ExoPlayer & LibVLC",
            maxResolutionOrQuality = "HTML5 CSS Compliant",
            description = "Standard subtitle format for HTML5 and modern adaptive HLS / DASH video streams.",
            isHardwareAccelerated = true,
            features = listOf("CSS Cue Styling", "Line Alignments", "Voice Tags")
        ),
        FormatSpec(
            name = "VobSub / DVD Subtitles",
            extension = ".idx / .sub",
            mimeType = "application/x-vobsub",
            category = MediaFormatCategory.SUBTITLE_FORMAT,
            engine = "LibVLC Native Engine",
            maxResolutionOrQuality = "Bitmap Overlay",
            description = "Direct extraction of physical DVD graphical bitmap subtitles with original typography.",
            isHardwareAccelerated = false,
            features = listOf("Exact DVD Rendering", "Embedded Color Palettes", "Multi-language Packs")
        ),
        FormatSpec(
            name = "SAMI / MicroDVD",
            extension = ".smi / .sub",
            mimeType = "application/smil",
            category = MediaFormatCategory.SUBTITLE_FORMAT,
            engine = "LibVLC & Media3",
            maxResolutionOrQuality = "Frame/Time Indexed",
            description = "Legacy multimedia captioning formats used in educational and international media.",
            isHardwareAccelerated = false,
            features = listOf("Frame Synchronization", "CSS Stylesheets", "Dual Language Display")
        )
    )

    val supportedStreamingProtocols = listOf(
        FormatSpec(
            name = "HTTP Live Streaming (HLS)",
            extension = ".m3u8 / .ts",
            mimeType = "application/x-mpegURL",
            category = MediaFormatCategory.STREAM_PROTOCOL,
            engine = "Media3 ExoPlayer & LibVLC",
            maxResolutionOrQuality = "Adaptive Bitrate up to 4K 60fps",
            description = "Apple adaptive bitrate streaming protocol with master playlist multi-audio and closed captions.",
            isHardwareAccelerated = true,
            features = listOf("Adaptive Quality Switching", "Live DVR Scrubbing", "Multi-audio Track Selection")
        ),
        FormatSpec(
            name = "Dynamic Adaptive Streaming over HTTP (DASH)",
            extension = ".mpd",
            mimeType = "application/dash+xml",
            category = MediaFormatCategory.STREAM_PROTOCOL,
            engine = "Media3 ExoPlayer",
            maxResolutionOrQuality = "Adaptive Bitrate up to 8K HDR",
            description = "International standard adaptive streaming format with multi-representation XML manifest.",
            isHardwareAccelerated = true,
            features = listOf("MPD Manifest Parsing", "Widevine Modular DRM", "Segment Timeline")
        ),
        FormatSpec(
            name = "Real-Time Streaming Protocol (RTSP)",
            extension = "rtsp://",
            mimeType = "application/x-rtsp",
            category = MediaFormatCategory.STREAM_PROTOCOL,
            engine = "LibVLC Native Engine & Media3",
            maxResolutionOrQuality = "Low-latency Security & IP Cams",
            description = "Low-latency network control protocol designed for IP cameras, drone feeds, and security monitors.",
            isHardwareAccelerated = true,
            features = listOf("RTP / UDP / TCP Interleaved", "Low Latency Buffer", "H.264/H.265 Stream")
        ),
        FormatSpec(
            name = "Real-Time Messaging Protocol (RTMP)",
            extension = "rtmp://",
            mimeType = "video/x-rtmp",
            category = MediaFormatCategory.STREAM_PROTOCOL,
            engine = "LibVLC Native Engine",
            maxResolutionOrQuality = "Live Broadcast Feed",
            description = "High-performance live streaming protocol used by Twitch, YouTube Live, and broadcast servers.",
            isHardwareAccelerated = true,
            features = listOf("Live Push/Pull", "Low Jitter", "Realtime Frame Decoding")
        ),
        FormatSpec(
            name = "Local Network Shares (SMB / FTP / UPnP)",
            extension = "smb:// / ftp://",
            mimeType = "application/octet-stream",
            category = MediaFormatCategory.STREAM_PROTOCOL,
            engine = "LibVLC & Network Client",
            maxResolutionOrQuality = "Full Bitrate Direct Stream",
            description = "Direct playback from NAS (Network Attached Storage), Samba shares, FTP servers, and DLNA media renderers.",
            isHardwareAccelerated = true,
            features = listOf("Zero Local Storage Required", "Direct Folder Browsing", "High Speed Caching")
        )
    )

    fun getAllSupportedCategories(): Map<MediaFormatCategory, List<FormatSpec>> {
        return mapOf(
            MediaFormatCategory.VIDEO_CONTAINER to supportedVideoFormats,
            MediaFormatCategory.AUDIO_CODEC to supportedAudioFormats,
            MediaFormatCategory.SUBTITLE_FORMAT to supportedSubtitleFormats,
            MediaFormatCategory.STREAM_PROTOCOL to supportedStreamingProtocols
        )
    }

    /**
     * Inspects a MediaEntity and produces category-wise metadata.
     */
    fun extractCategoryMetadata(media: MediaEntity): MediaCategoryMetadata {
        val pathLower = media.path.lowercase()
        val uriLower = media.uriString.lowercase()
        val mimeLower = (media.mimeType ?: "").lowercase()

        val isStream = uriLower.startsWith("http") || uriLower.startsWith("rtsp") ||
                uriLower.startsWith("rtmp") || uriLower.startsWith("mms") ||
                media.genre == "Live Stream" || media.genre == "Playlist Stream Channel"

        val category = when {
            isStream -> MediaFormatCategory.STREAM_PROTOCOL
            media.isVideo -> MediaFormatCategory.VIDEO_CONTAINER
            else -> MediaFormatCategory.AUDIO_CODEC
        }

        val container = when {
            pathLower.endsWith(".mkv") || mimeLower.contains("matroska") -> "Matroska (MKV)"
            pathLower.endsWith(".mp4") || mimeLower.contains("mp4") -> "MPEG-4 Part 14 (MP4)"
            pathLower.endsWith(".webm") || mimeLower.contains("webm") -> "WebM Media"
            pathLower.endsWith(".avi") || mimeLower.contains("avi") -> "Audio Video Interleave (AVI)"
            pathLower.endsWith(".mov") || mimeLower.contains("quicktime") -> "Apple QuickTime (MOV)"
            pathLower.endsWith(".flv") || mimeLower.contains("flv") -> "Flash Video (FLV)"
            pathLower.endsWith(".ts") || pathLower.endsWith(".m2ts") -> "MPEG Transport Stream (TS)"
            pathLower.endsWith(".m3u8") || uriLower.contains(".m3u8") -> "HTTP Live Streaming (HLS)"
            pathLower.endsWith(".mpd") || uriLower.contains(".mpd") -> "Dynamic Adaptive Stream (DASH)"
            pathLower.endsWith(".flac") || mimeLower.contains("flac") -> "Free Lossless Audio (FLAC)"
            pathLower.endsWith(".mp3") || mimeLower.contains("mpeg") -> "MPEG-1 Layer III (MP3)"
            pathLower.endsWith(".m4a") || pathLower.endsWith(".aac") -> "Advanced Audio Coding (M4A)"
            pathLower.endsWith(".wav") || mimeLower.contains("wav") -> "Waveform PCM (WAV)"
            pathLower.endsWith(".opus") || mimeLower.contains("opus") -> "Opus Interactive Audio"
            pathLower.endsWith(".ogg") || mimeLower.contains("ogg") -> "Ogg Vorbis Audio"
            isStream -> "Network Live Stream"
            media.isVideo -> "Universal Video"
            else -> "Digital Audio"
        }

        val codec = when {
            pathLower.endsWith(".flac") || mimeLower.contains("flac") -> "FLAC 24-bit Lossless"
            pathLower.endsWith(".opus") || mimeLower.contains("opus") -> "Opus Low-Latency"
            pathLower.endsWith(".mp3") || mimeLower.contains("mp3") -> "MP3 Perceptual"
            pathLower.endsWith(".m4a") || pathLower.endsWith(".aac") -> "AAC-LC Audio"
            pathLower.endsWith(".wav") -> "PCM Uncompressed"
            mimeLower.contains("hevc") || mimeLower.contains("h265") -> "H.265 / HEVC Main 10"
            mimeLower.contains("av01") || mimeLower.contains("av1") -> "AOMedia AV1 Video"
            mimeLower.contains("vp9") -> "Google VP9 Profile 0/2"
            pathLower.endsWith(".mkv") -> "AVC / HEVC Matroska"
            pathLower.endsWith(".mp4") -> "AVC / H.264 High Profile"
            media.isVideo -> "MPEG-4 AVC / Hardware Video"
            else -> "Digital Audio Stream"
        }

        val quality = when {
            media.size > 2_000_000_000L -> "4K UHD / Blu-Ray Grade"
            media.size > 800_000_000L -> "Full HD 1080p Master"
            media.size > 200_000_000L -> "HD 720p Crisp"
            media.isVideo -> "Standard Definition Video"
            pathLower.endsWith(".flac") || pathLower.endsWith(".wav") -> "Studio Hi-Res Lossless"
            else -> "High Fidelity Audio"
        }

        val audioLayout = if (media.isVideo) "Stereo 2.0 / 5.1 Surround Capable" else "Stereo 2.0 Hi-Fi"
        val sampleOrFps = if (media.isVideo) "60 fps / 24p Cinema" else "44.1 kHz / 48 kHz / 96 kHz"

        val bitrateEst = if (media.duration > 0 && media.size > 0) {
            val kbps = (media.size * 8) / (media.duration.coerceAtLeast(1000L) / 1000L) / 1000L
            "$kbps kbps"
        } else {
            "Variable Bitrate (VBR)"
        }

        val recEngine = when {
            pathLower.endsWith(".avi") || pathLower.endsWith(".flv") || pathLower.endsWith(".vob") || pathLower.endsWith(".ass") -> "LibVLC Native Engine"
            isStream -> "Media3 ExoPlayer Adaptive"
            else -> "Media3 ExoPlayer (Hardware Accelerated)"
        }

        val badges = mutableListOf<String>()
        if (media.isVideo) {
            badges.add("VIDEO")
            if (media.size > 1_500_000_000L) badges.add("4K UHD") else badges.add("HD")
            badges.add(if (pathLower.endsWith(".mkv")) "MKV" else if (pathLower.endsWith(".webm")) "WEBM" else "MP4")
            badges.add("HARDWARE ACCELERATED")
        } else {
            badges.add("AUDIO")
            if (pathLower.endsWith(".flac") || pathLower.endsWith(".wav")) {
                badges.add("HI-RES LOSSLESS")
            } else {
                badges.add("HI-FI STEREO")
            }
            badges.add(if (pathLower.endsWith(".flac")) "FLAC" else if (pathLower.endsWith(".m4a")) "AAC" else "MP3")
        }
        if (isStream) badges.add("NETWORK STREAM")

        return MediaCategoryMetadata(
            category = category,
            containerFormat = container,
            codecFamily = codec,
            qualityTier = quality,
            audioLayout = audioLayout,
            sampleRateOrFps = sampleOrFps,
            bitrateTier = bitrateEst,
            recommendedEngine = recEngine,
            badges = badges
        )
    }

    /**
     * Resolves an incoming Android Intent (ACTION_VIEW, ACTION_SEND) into a fully functional MediaEntity
     * with category metadata, title, duration, and dimensions.
     */
    fun createMediaEntityFromIntent(context: Context, uri: Uri, intentMimeType: String? = null): MediaEntity {
        var displayName = uri.lastPathSegment ?: "Media File"
        var sizeBytes = 0L
        var durationMs = 0L
        var isVideo = true
        var resolvedMime = intentMimeType
        var artist = "Direct Media"
        var album = "Opened File"

        // 1. Query ContentResolver if content://
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) displayName = name
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0) {
                            sizeBytes = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore cursor query errors
            }

            if (resolvedMime.isNullOrBlank()) {
                try {
                    resolvedMime = context.contentResolver.getType(uri)
                } catch (e: Exception) {}
            }
        } else if (uri.scheme == "file") {
            try {
                val file = File(uri.path ?: "")
                if (file.exists()) {
                    displayName = file.name
                    sizeBytes = file.length()
                }
            } catch (e: Exception) {}
        }

        // 2. Infer media type from MIME or Extension
        val lowerName = displayName.lowercase()
        val lowerUri = uri.toString().lowercase()
        val lowerMime = (resolvedMime ?: "").lowercase()

        val isAudioExtension = lowerName.endsWith(".mp3") || lowerName.endsWith(".flac") ||
                lowerName.endsWith(".wav") || lowerName.endsWith(".m4a") ||
                lowerName.endsWith(".aac") || lowerName.endsWith(".ogg") ||
                lowerName.endsWith(".opus") || lowerName.endsWith(".wma") ||
                lowerName.endsWith(".alac") || lowerName.endsWith(".aiff")

        val isAudioMime = lowerMime.startsWith("audio/") || lowerMime.contains("audio")

        isVideo = if (isAudioExtension || isAudioMime) {
            false
        } else {
            true
        }

        if (resolvedMime.isNullOrBlank()) {
            resolvedMime = if (isVideo) "video/mp4" else "audio/mpeg"
        }

        // 3. Extract duration & metadata using MediaMetadataRetriever
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durStr?.toLongOrNull() ?: 0L

                val artStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                if (!artStr.isNullOrBlank()) artist = artStr

                val albStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                if (!albStr.isNullOrBlank()) album = albStr

                val hasVideoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                if (hasVideoStr == "yes") {
                    isVideo = true
                }
            } catch (e: Exception) {
                // Ignore retriever failure for external/stream URIs
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        } catch (e: Exception) {}

        // Clean up displayName from URL encoding or paths
        try {
            if (displayName.contains("%20") || displayName.contains("%2F")) {
                displayName = Uri.decode(displayName)
            }
            if (displayName.contains("/")) {
                displayName = displayName.substringAfterLast("/")
            }
        } catch (e: Exception) {}

        val id = (uri.toString().hashCode().toLong() and 0x7FFFFFFF)

        return MediaEntity(
            uriString = uri.toString(),
            title = displayName,
            artist = artist,
            album = album,
            duration = durationMs,
            size = sizeBytes,
            dateAdded = System.currentTimeMillis() / 1000,
            isVideo = isVideo,
            path = uri.path ?: uri.toString(),
            mimeType = resolvedMime,
            genre = if (uri.scheme == "http" || uri.scheme == "https") "Live Stream" else "Direct Media"
        )
    }
}
