package com.itvs.connect.ble

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent

class PhoneMediaController(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val telecomManager = context.getSystemService(TelecomManager::class.java)

    fun execute(action: ButtonAction, speedDial: String = "") {
        when (action) {
            ButtonAction.TOGGLE_PLAYBACK -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ButtonAction.NEXT_TRACK -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            ButtonAction.PREVIOUS_TRACK -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ButtonAction.VOLUME_UP -> audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            ButtonAction.VOLUME_DOWN -> audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            ButtonAction.GOOGLE_ASSISTANT -> {
                val intent = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
            ButtonAction.SPEED_DIAL -> {
                if (speedDial.isBlank()) return
                val uri = Uri.fromParts("tel", speedDial, null)
                runCatching {
                    telecomManager?.placeCall(uri, null)
                }.onFailure {
                    val dial = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(dial) }
                }
            }
            ButtonAction.ROTATE_RIDE_STATS,
            ButtonAction.DO_NOTHING -> Unit
        }
    }

    fun executeMusicCommand(command: MusicCommand) {
        when (command) {
            MusicCommand.PLAY -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            MusicCommand.PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            MusicCommand.TOGGLE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            MusicCommand.NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            MusicCommand.PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            MusicCommand.VOLUME_UP -> execute(ButtonAction.VOLUME_UP)
            MusicCommand.VOLUME_DOWN -> execute(ButtonAction.VOLUME_DOWN)
        }
    }

    fun answerCall() {
        runCatching { telecomManager?.acceptRingingCall() }
            .onFailure { Log.w(TAG, "acceptRingingCall failed", it) }
    }

    fun declineOrEndCall() {
        runCatching { telecomManager?.endCall() }
            .onFailure { Log.w(TAG, "endCall failed", it) }
    }

    fun currentTrackLabel(): String? {
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return null
        return runCatching {
            msm.getActiveSessions(null)
                .mapNotNull { controller: MediaController ->
                    val meta = controller.metadata ?: return@mapNotNull null
                    val title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    val artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    listOfNotNull(title, artist).joinToString(" - ").ifBlank { null }
                }
                .firstOrNull()
        }.getOrNull()
    }

    fun headphonesConnected(): Boolean {
        return audioManager?.isWiredHeadsetOn == true ||
            audioManager?.isBluetoothA2dpOn == true
    }

    private fun sendMediaKey(keyCode: Int) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager?.dispatchMediaKeyEvent(down)
        audioManager?.dispatchMediaKeyEvent(up)
    }

    companion object {
        private const val TAG = "PhoneMediaController"
    }
}
