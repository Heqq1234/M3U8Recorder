package com.dl.m3u8recorder.llhls

/**
 * 平台取流结果的共享契约。
 *
 * ChaturbateApi.StreamResult 与 StripchatApi.StreamResult 结构相同但属于不同 data 类，
 * 直接放进同一个 when 表达式会被推断成 Any，丢失成员。让二者都实现本接口，
 * 编译器即可统一推断到 StreamFetchResult。
 */
interface StreamFetchResult {
    val m3u8Url: String
    val errorMessage: String?
    val isSuccess: Boolean
}

/**
 * 平台 Master Playlist 解析结果的共享契约。
 *
 * 用途同 [StreamFetchResult]：统一 ChaturbateApi.ResolvedStream 与 StripchatApi.ResolvedStream。
 */
interface StreamResolveResult {
    val videoPlaylistUrl: String
    val audioPlaylistUrl: String?
    val errorMessage: String?
    val isSuccess: Boolean
}