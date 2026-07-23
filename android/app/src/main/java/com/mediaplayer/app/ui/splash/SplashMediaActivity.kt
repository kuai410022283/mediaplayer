/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.ui.splash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import com.mediaplayer.app.Prefs
import com.mediaplayer.app.R

class SplashMediaActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_DURATION = "extra_duration"
        const val EXTRA_SKIP_AFTER = "extra_skip_after"
    }

    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var skipText: TextView

    private var exoPlayer: ExoPlayer? = null
    private var countDownTimer: CountDownTimer? = null

    private var mediaUrl: String = ""
    private var mediaType: String = "auto"
    private var maxDuration: Int = 5
    private var skipAfter: Int = 0

    private var canSkip = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 安全地请求横屏方向，规避 Android 8.0 透明主题请求固定方向时的崩溃Bug
        try {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } catch (e: Exception) {
            // 忽略 Android 8.0 上的 IllegalStateException
        }

        setContentView(R.layout.activity_splash_media)

        playerView = findViewById(R.id.player_view)
        imageView = findViewById(R.id.image_view)
        skipText = findViewById(R.id.skip_text)

        val prefs = getSharedPreferences(Prefs.FILE, android.content.Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(Prefs.KEY_SERVER_URL, Prefs.DEFAULT_SERVER_URL) ?: Prefs.DEFAULT_SERVER_URL
        mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL) ?: ""
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "auto"
        maxDuration = intent.getIntExtra(EXTRA_DURATION, 5)
        skipAfter = intent.getIntExtra(EXTRA_SKIP_AFTER, 0)

        // 拼接相对路径
        if (mediaUrl.startsWith("/")) {
            val base = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
            mediaUrl = "$base$mediaUrl"
        }

        if (mediaUrl.isBlank()) {
            finishWithResult()
            return
        }

        setupSkipLogic()
        setupBackPressedInterceptor()

        val lowerUrl = mediaUrl.lowercase()
        // 允许通过在 URL 末尾附加 #video 或 format=video 等方式，或者后台显式指定的 mediaType 来强制指定类型
        val forceVideo = mediaType == "video" || lowerUrl.contains("#video") || lowerUrl.contains("type=video") || lowerUrl.contains("format=video")
        val forceImage = mediaType == "image" || lowerUrl.contains("#image") || lowerUrl.contains("type=image") || lowerUrl.contains("format=image")

        // 去除 URL 参数和锚点，以准确判断后缀
        val urlWithoutParams = mediaUrl.substringBefore('?').substringBefore('#')
        val ext = urlWithoutParams.substringAfterLast('.', "").lowercase()
        // 明确的视频后缀走播放器，其余（包括无后缀的动态 API）默认走图片解析
        val isVideoExt = ext == "mp4" || ext == "mkv" || ext == "ts" || ext == "m3u8" || ext == "flv" || ext == "avi" || ext == "webm"

        val isVideo = forceVideo || (!forceImage && isVideoExt)

        if (isVideo) {
            showVideo()
        } else {
            showImage()
        }

        // 启动最大展示时长兜底防护
        startMaxDurationTimer()
    }

    private fun setupSkipLogic() {
        skipText.visibility = View.VISIBLE
        if (skipAfter <= 0) {
            canSkip = true
            skipText.text = "按返回键跳过"
        } else {
            canSkip = false
            skipText.text = "${skipAfter}秒后可跳过"
            object : CountDownTimer(skipAfter * 1000L, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val sec = millisUntilFinished / 1000
                    skipText.text = "${sec}秒后可跳过"
                }

                override fun onFinish() {
                    canSkip = true
                    skipText.text = "按返回键跳过"
                }
            }.also {
                countDownTimer = it
                it.start()
            }
        }

        skipText.setOnClickListener {
            if (canSkip) finishWithResult()
            else Toast.makeText(this, "请看完广告", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBackPressedInterceptor() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canSkip) {
                    finishWithResult()
                } else {
                    Toast.makeText(this@SplashMediaActivity, "请看完广告", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun startMaxDurationTimer() {
        skipText.postDelayed({
            if (!finished) {
                finishWithResult()
            }
        }, maxDuration * 1000L)
    }

    private fun showImage() {
        imageView.visibility = View.VISIBLE
        playerView.visibility = View.GONE
        imageView.load(mediaUrl) {
            crossfade(true)
            listener(
                onError = { _, _ -> finishWithResult() }
            )
        }
    }

    private fun showVideo() {
        imageView.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    finishWithResult()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                finishWithResult()
            }
        })

        val mediaItem = MediaItem.fromUri(mediaUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (canSkip) finishWithResult()
            else Toast.makeText(this, "请看完广告", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun finishWithResult() {
        if (finished) return
        finished = true
        countDownTimer?.cancel()
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
