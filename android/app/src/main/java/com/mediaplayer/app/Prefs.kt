/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app

/**
 * SharedPreferences 常量，统一管理偏好设置的文件名和键名。
 */
object Prefs {
    const val FILE = "mediaplayer_prefs"

    // 通用设置
    const val DEFAULT_SERVER_URL = "http://0.0.0.0:9527"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_GESTURE_HINT_SHOWN = "gesture_hint_shown"
    const val KEY_SHOW_CHANNEL_LOGO = "show_channel_logo"
    const val KEY_GESTURE_BRIGHTNESS = "gesture_brightness_enabled"
    const val KEY_GESTURE_VOLUME = "gesture_volume_enabled"
    
    // 全局细线进度条：0=关闭, 1=顶部, 2=底部
    const val KEY_GLOBAL_PROGRESS_BAR = "global_progress_bar"
    const val GLOBAL_PROGRESS_OFF = 0
    const val GLOBAL_PROGRESS_TOP = 1
    const val GLOBAL_PROGRESS_BOTTOM = 2
    
    // 播放器设置
    const val KEY_NETWORK_CACHE = "network_cache_ms"
    const val DEFAULT_NETWORK_CACHE = 0
    
    // 解码模式：0=自动, 1=强制硬解, 2=强制软解
    const val KEY_DECODER_MODE = "decoder_mode"
    const val DECODER_MODE_AUTO = 0
    const val DECODER_MODE_HARDWARE = 1
    const val DECODER_MODE_SOFTWARE = 2
    
    // 音频直通开关
    const val KEY_AUDIO_PASSTHROUGH = "audio_passthrough"
    
    // 播放内核
    const val KEY_PLAYER_CORE = "player_core"
    const val PLAYER_CORE_AUTO = 0
    const val PLAYER_CORE_VLC = 1
    const val PLAYER_CORE_EXO = 2
    const val PLAYER_CORE_IJK = 3
    
    // 渲染引擎 (为未来做准备)
    const val KEY_RENDER_VIEW = "render_view_mode"
    const val RENDER_VIEW_SURFACE = 0
    const val RENDER_VIEW_TEXTURE = 1
    
    // 开机自启
    const val KEY_AUTO_START = "auto_start"

    // 画中画
    const val KEY_ENABLE_PIP = "enable_pip"

    // 画面比例
    const val KEY_SCALE_MODE = "scale_mode"
    const val SCALE_MODE_DEFAULT = 0 // 自适应 (Fit)
    const val SCALE_MODE_STRETCH = 1 // 16:9 强行拉伸
    const val SCALE_MODE_CROP = 2    // 裁剪放大 (Crop)
    const val SCALE_MODE_4_3 = 3     // 强制 4:3
    const val SCALE_MODE_16_10 = 4   // 强制 16:10
    const val SCALE_MODE_FILL = 5    // 铺满全屏

    // 认证信息
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_CLIENT_ID = "client_id"
    const val KEY_CLIENT_STATUS = "client_status"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_SERVER_URLS = "server_urls"      // JSON 数组，所有服务器地址
    const val KEY_SERVER_PORT = "config_server_port" // 配置 Web 服务器实际端口
    const val KEY_ENABLE_LOG = "enable_log"
    const val KEY_REVERSE_CHANNEL_KEYS = "reverse_channel_keys"
    const val KEY_PLAN_NAME = "plan_name"
    const val KEY_EXPIRES_AT = "expires_at"
    const val KEY_SERVER_NAME = "server_name"

    // 时间显示模式：0=隐藏, 1=常显, 2=整点, 3=半点
    const val KEY_TIME_SHOW_MODE = "time_show_mode"
    const val TIME_SHOW_MODE_HIDDEN = 0
    const val TIME_SHOW_MODE_ALWAYS = 1
    const val TIME_SHOW_MODE_EVERY_HOUR = 2
    const val TIME_SHOW_MODE_HALF_HOUR = 3
}
