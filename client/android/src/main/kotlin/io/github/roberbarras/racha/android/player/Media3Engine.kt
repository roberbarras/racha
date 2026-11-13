package io.github.roberbarras.racha.android.player

import android.content.Context
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import io.github.roberbarras.racha.application.playback.AudioTrack
import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.application.playback.MediaSource
import io.github.roberbarras.racha.application.playback.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * [PlayerEngine] on Media3/ExoPlayer (ADR-0002): hardware decoding through MediaCodec, presentation on a SurfaceView
 * (hardware overlay, the cheapest path for battery), audio focus and "becoming noisy" handled by the player, and a
 * MediaSession so headset/Bluetooth/system media controls work. Subtitle cues are published as text because a
 * SurfaceView does not draw them; the shared UI renders them.
 */
@OptIn(UnstableApi::class)
class Media3Engine(context: Context) : PlayerEngine {
    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        // If a hardware decoder fails to start (buggy vendor codec), try the next one, ending in the software decoder.
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    private val session = MediaSession.Builder(context, player).setId("racha-${UUID.randomUUID()}").build()
    private val state = MutableStateFlow(EngineSnapshot())
    private var cueText: String? = null
    private var pendingSubtitle: String? = null
    private var subtitleIds: List<String> = emptyList()

    override val snapshot: StateFlow<EngineSnapshot> = state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()

            override fun onTracksChanged(tracks: Tracks) {
                pendingSubtitle?.let { applySubtitle(it) }
                publish()
            }

            override fun onCues(cueGroup: CueGroup) {
                cueText = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").ifEmpty { null }
                publish()
            }
        })
    }

    fun attach(view: SurfaceView) = player.setVideoSurfaceView(view)

    fun detach(view: SurfaceView) = player.clearVideoSurfaceView(view)

    override fun positionMs(): Long = player.currentPosition

    override fun load(source: MediaSource, startMs: Long, playWhenReady: Boolean) {
        subtitleIds = source.subtitles.map { it.id }
        pendingSubtitle = null
        cueText = null
        val item = MediaItem.Builder()
            .setUri(source.uri)
            .setSubtitleConfigurations(
                source.subtitles.map { s ->
                    MediaItem.SubtitleConfiguration.Builder(s.uri.toUri())
                        .setId(s.id)
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(s.language)
                        .setLabel(s.label)
                        .build()
                },
            )
            .build()
        // Subtitles start disabled (spec §4.2); selectSubtitle enables one explicitly.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        player.setMediaItem(item, startMs)
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    override fun selectSubtitle(id: String?) {
        if (id == null) {
            pendingSubtitle = null
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            cueText = null
            publish()
            return
        }
        pendingSubtitle = id
        applySubtitle(id)
    }

    /** Text tracks only exist once the media is prepared: keep the wish until they appear. */
    private fun applySubtitle(id: String) {
        val group = player.currentTracks.groups.firstOrNull { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).id?.endsWith(id) == true }
        } ?: return
        pendingSubtitle = null
        val index = (0 until group.length).first { group.getTrackFormat(it).id?.endsWith(id) == true }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    override fun selectAudio(id: String?) {
        val (groupIndex, trackIndex) = id?.split(':')?.map(String::toInt) ?: return
        val group = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    override fun release() {
        session.release()
        player.release()
    }

    private fun publish() {
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val audio = audioGroups.flatMapIndexed { g, group ->
            (0 until group.length).map { t ->
                val format = group.getTrackFormat(t)
                AudioTrack("$g:$t", format.language, format.label)
            }
        }
        val selectedAudio = audioGroups.withIndex().firstNotNullOfOrNull { (g, group) ->
            (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { "$g:$it" }
        }
        val selectedSubtitle = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).id } }
            ?.let { formatId -> subtitleIds.firstOrNull { formatId.endsWith(it) } }
        val error: PlaybackException? = player.playerError
        state.value = EngineSnapshot(
            state = when {
                error != null -> EngineState.ERROR
                player.playbackState == Player.STATE_BUFFERING -> EngineState.BUFFERING
                player.playbackState == Player.STATE_READY -> EngineState.READY
                player.playbackState == Player.STATE_ENDED -> EngineState.ENDED
                else -> EngineState.IDLE
            },
            playing = player.isPlaying,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET },
            speed = player.playbackParameters.speed,
            audioTracks = audio,
            selectedAudio = selectedAudio,
            selectedSubtitle = selectedSubtitle,
            // The rendered size arrives with the first frame on a surface; the track format knows it before that.
            videoWidth = player.videoSize.width.takeIf { it > 0 } ?: player.videoFormat?.width?.coerceAtLeast(0) ?: 0,
            videoHeight = player.videoSize.height.takeIf { it > 0 } ?: player.videoFormat?.height?.coerceAtLeast(0) ?: 0,
            subtitleText = if (selectedSubtitle != null) cueText else null,
            error = error?.errorCodeName,
        )
    }
}
