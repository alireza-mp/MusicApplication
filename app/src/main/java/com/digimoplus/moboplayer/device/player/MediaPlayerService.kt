package com.digimoplus.moboplayer.device.player

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import androidx.compose.material.ExperimentalMaterialApi
import androidx.core.app.NotificationCompat
import com.digimoplus.moboplayer.R
import com.digimoplus.moboplayer.domain.useCase.SaveLastMusicDataUseCase
import com.digimoplus.moboplayer.presentation.ui.mainactivity.MainActivity
import com.digimoplus.moboplayer.util.Constans
import com.digimoplus.moboplayer.util.stopForeground
import com.digimoplus.moboplayer.util.toBitmap
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject


@OptIn(ExperimentalMaterialApi::class)
@AndroidEntryPoint
class MediaPlayerService : Service(), ServiceUiChangeListener {

    @Inject
    lateinit var exoPlayer: ExoPlayer

    @Inject
    lateinit var saveLastMusicDataUseCase: SaveLastMusicDataUseCase

    var serviceMediaListener: ServiceMediaListener? = null
    private var isViewExist = false
    private val chanelId = "Music Chanel"
    private val notificationId = 1111111
    private lateinit var mediaDescriptionAdapter: PlayerNotificationManager.MediaDescriptionAdapter
    private lateinit var notificationListener: PlayerNotificationManager.NotificationListener
    private val serviceBinder: IBinder = ServiceBinder()
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var removeNotificationJob: Job? = null


    inner class ServiceBinder : Binder() {
        fun getMediaPlayerService(): MediaPlayerService {
            return this@MediaPlayerService
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return serviceBinder
    }

    override fun onCreate() {
        super.onCreate()
        // initial media description adapter and notification listener
        initialMediaDescription()
        initialNotificationListener()
        setPlayerAudioAttributes()
        initialPlayerNotificationManager()
        initialPlayerListener()
    }

    private fun setPlayerAudioAttributes() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, true)

        if (exoPlayer.repeatMode == Player.REPEAT_MODE_OFF)
            exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
    }

    // view exist listener
    override fun onViewExist(isExist: Boolean) {
        isViewExist = isExist
        removeNotification(exoPlayer.isPlaying, isExist)
    }

    private fun initialMediaDescription() {
        mediaDescriptionAdapter = object : PlayerNotificationManager.MediaDescriptionAdapter {

            //set notification title
            override fun getCurrentContentTitle(player: Player): CharSequence {
                return player.currentMediaItem?.mediaMetadata?.title ?: "null"
            }

            // set notification onClicked intent
            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                // intent to open app
                val intent = Intent(this@MediaPlayerService, MainActivity::class.java)

                return PendingIntent.getActivity(
                    /* context = */ applicationContext,
                    /* requestCode = */ 0,
                    /* intent = */ intent,
                    /* flags = */ PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            // set notification description
            override fun getCurrentContentText(player: Player): CharSequence {
                return player.currentMediaItem?.mediaMetadata?.artist ?: ""
            }

            // set notification image
            override fun getCurrentLargeIcon(
                player: Player,
                callback: PlayerNotificationManager.BitmapCallback,
            ): Bitmap? {
                val uri = player.currentMediaItem?.mediaMetadata?.artworkUri
                return if (uri == null) {
                    // set default image
                    BitmapFactory.decodeResource(
                        applicationContext.resources,
                        R.drawable.default_image
                    )
                } else {
                    try {
                        uri.toBitmap(applicationContext)
                    } catch (e: Exception) {
                        // set default image
                        BitmapFactory.decodeResource(
                            applicationContext.resources,
                            R.drawable.default_image
                        )
                    }
                }
            }
        }
    }

    private fun initialNotificationListener() {
        notificationListener = object : PlayerNotificationManager.NotificationListener {


            override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                super.onNotificationCancelled(notificationId, dismissedByUser)
                stopForeground()
                stopSelf()
            }

            override fun onNotificationPosted(
                notificationId: Int,
                notification: Notification,
                ongoing: Boolean,
            ) {
                if (ongoing) {
                    startForeground(notificationId, notification)
                }
            }
        }
    }

    private fun initialPlayerNotificationManager() {
        playerNotificationManager =
            PlayerNotificationManager.Builder(applicationContext, notificationId, chanelId)
                .setNotificationListener(notificationListener)
                .setMediaDescriptionAdapter(mediaDescriptionAdapter)
                .setChannelImportance(NotificationManager.IMPORTANCE_HIGH)
                .setSmallIconResourceId(R.drawable.ic_logo)
                .setNextActionIconResourceId(R.drawable.ic_notification_next)
                .setPreviousActionIconResourceId(R.drawable.ic_notification_previous)
                .setPlayActionIconResourceId(R.drawable.ic_play)
                .setPauseActionIconResourceId(R.drawable.ic_pause)
                .setChannelNameResourceId(R.string.app_name)
                .build()

        playerNotificationManager?.setPlayer(exoPlayer)
        playerNotificationManager?.setPriority(NotificationCompat.PRIORITY_MAX)
        playerNotificationManager?.setUseRewindAction(false)
        playerNotificationManager?.setUseFastForwardAction(false)

    }

    private fun initialPlayerListener() {
        exoPlayer.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                serviceMediaListener?.onIsPlayingChanged(isPlaying)
                removeNotification(isPlaying, isViewExist)

            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                serviceMediaListener?.onMediaItemTransition(mediaItem)
            }

        })
    }

    private fun removeNotification(isPlaying: Boolean, isViewExist: Boolean) {
        removeNotificationJob = if (!isPlaying && !isViewExist) {
            val s = SupervisorJob()
            CoroutineScope(Dispatchers.IO + s).launch {
                delay(Constans.NOTIFICATION_REMOVE_TIME) // remove notification after 5 minute
                saveData()
                stopForeground()
            }
        } else {
            removeNotificationJob?.cancel()
            null
        }
    }

    override fun onDestroy() {
        if (exoPlayer.isPlaying) exoPlayer.stop()
        // save last music info
        saveData()
        stopForeground()
        super.onDestroy()

    }

    // save last music ingo into datastore when service is destroy
    private fun saveData() {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main + job)
        scope.launch {
            saveLastMusicDataUseCase.invoke(
                duration = exoPlayer.duration,
                currentPosition = exoPlayer.currentPosition,
                musicTitle = exoPlayer.currentMediaItem?.mediaMetadata?.title?.toString() ?: "/*/"
            )
            job.cancel()
        }
    }
}


