package com.dl.m3u8recorder.llhls

/**
 * @deprecated 改用 ChaturbateApi.fetchStreamUrl() (OkHttp + 代理方案)
 * 保留仅做错误码引用参考
 */
object ChaturbateStreamFetcher {
    val ERROR_MAP = mapOf(
        "offline" to "房间当前不在直播",
        "private" to "房间正在进行私密秀",
        "away" to "主播暂时离开",
        "password protected" to "房间需要密码",
        "hidden" to "隐藏会话进行中"
    )
}