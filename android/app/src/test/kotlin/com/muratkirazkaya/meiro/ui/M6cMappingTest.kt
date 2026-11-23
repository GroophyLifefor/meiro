package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.PlaybackEngine
import com.muratkirazkaya.meiro.playback.PlayerUi
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.ui.components.toMini
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M6c: the player's error surface and what the media session (notification, lock screen) is given of a track. */
@RunWith(RobolectricTestRunner::class)
class M6cMappingTest {
    private val yolla = QItem(Source.YTM, "yolla1", "Yolla", "Tarkan", "4:05", thumbnail = "https://i.ytimg.com/vi/yolla1/hqdefault.jpg")

    @Test fun aFailedTrackShowsItsReasonInTheMiniAndFullPlayer() {
        val p = PlayerUi(yolla, playing = false, positionMs = 0, durationMs = 245_000, loading = false, error = "Bu şarkı artık yok.")
        val mini = p.toMini()
        assertEquals("Bu şarkı artık yok.", mini.error)
        assertEquals(false, mini.playing)
        assertEquals(0f, mini.progress)
        assertEquals("Bu şarkı artık yok.", NowPlayingMapping.player(p, null).artist)
        // Asked again (play): while it is found again the preparing state shows, not the old reason.
        val again = p.copy(loading = true)
        assertEquals("Tarkan", NowPlayingMapping.player(again, null).artist)
        assertNull(PlayerUi(yolla, true, 1000, 245_000, false).toMini().error)
    }

    @Test fun theLockScreenGetsTitleArtistAndPicture() {
        val m = PlaybackEngine.metadata(yolla)
        assertEquals("Yolla", m.title.toString())
        assertEquals("Tarkan", m.artist.toString())
        assertEquals("https://i.ytimg.com/vi/yolla1/hqdefault.jpg", m.artworkUri.toString())
        val bare = PlaybackEngine.metadata(QItem(Source.SOUNDCLOUD, "https://soundcloud.com/x/y", "", ""))
        assertEquals("https://soundcloud.com/x/y", bare.title.toString())
        assertNull(bare.artist); assertNull(bare.artworkUri)
    }
}
