package com.dl.m3u8recorder.parser

import android.util.Base64
import java.security.MessageDigest

/**
 * Stripchat / Doppio CDN #EXT-X-MOUFLON v2 (PSCH) 分片 URI token 解密。
 *
 * 背景：带 pkey 请求 media playlist 时，#EXT-X-MOUFLON:URI: 给出的分片 URI 中
 * token 段是密文，直接请求必 404（nginx 10 字节 "Not Found"）。真实地址需要用
 * pkey 对应的 pdkey 解密 token 后替换得到。
 *
 * 已端到端验证的算法（与 StreaMonitor / kesamom 社区实现一致，2026-08 实测 8/8 分片下载 200）：
 *   enc  = uri.split('_')[-3]  (带 _partN 的 part URI)
 *        | uri.split('_')[-2]  (整段 URI，形如 {room}_{seq}_{token}_{ts}.mp4)
 *   data = Base64Decode(enc.reversed() + "==")
 *   ks   = SHA-256(pdkey)  (32 字节循环)
 *   dec  = UTF8(data[i] XOR ks[i % 32])
 *   real = uri.replace(enc, dec)
 *
 * 密钥来源说明：MMP 播放器 chunk 内嵌的 _knownKeys(oe 对象) 是无效诱饵；
 * 真实密钥为运行时闭包持有。此处使用 StreaMonitor 用户社区维护的密钥表
 * (kesamom/stripchat_mouflon 的 stripchat_mouflon_keys.json)。
 *
 * 仅 Stripchat/Doppio 走到本类（Chaturbate 的 playlist 无 #EXT-X-MOUFLON 标签，
 * M3U8ParserImpl 不会调用这里），对 Chaturbate 零影响。
 */
object MouflonCrypto {

    private const val TAG = "MouflonCrypto"

    /** pkey -> pdkey 密钥表（StreaMonitor 社区维护版）。 */
    private val KEYS: Map<String, String> = mapOf(
        "Zokee2OhPh9kugh4" to "Quean4cai9boJa5a",
        "Zeechoej4aleeshi" to "ubahjae7goPoodi6",
        "Ook7quaiNgiyuhai" to "EQueeGh2kaewa3ch",
        "Fq6m2TO2ZeBkRPm9" to "xb6di1NF9EFXHUwb",
        "GrRncsoByZmsiT6L" to "NigHYyOD9l4rvAEb",
        "1Dzcc6OjP73LKbtI" to "Y64UVwX5RrIWnOLp",
        "N2oLovTIXb0o28Uj" to "ABE7Sj8jh3oPM2ae",
        "NTK9aqcLmNFMWrpQ" to "tOcYOap4Ty1l9Jzb",
        "7uUnbD0jMCB9GH32" to "lzCQ6QBTnLpB0zMF",
        "Ohi7eTRBpkAuML0l" to "kExe29N2sLFrHGqu",
        "OLzu7QlySkG2fVRn" to "CsovScFH9VirSJ4Z"
    )

    /** part URI 尾部特征：_partN（可带可不带 .mp4 后缀，本类在归一化之前调用）。 */
    private val PART_SUFFIX = Regex("""_part\d+(?:\.mp4)?$""")

    /** 解密后的真实 token 字符集校验：真实 token 为 8~32 位字母数字（实测 16 位）。 */
    private val PLAIN_TOKEN = Regex("""[A-Za-z0-9]{8,32}""")

    /**
     * 解密 MOUFLON 分片 URI 中的 token 段。
     *
     * @param uri     MOUFLON 给出的分片 URI（已 resolveUrl 成绝对地址，仍含加密 token）
     * @param baseUrl media playlist 请求 URL（含 pkey 查询参数，用于查密钥表）
     * @return 解密成功返回真实可下载 URI；无 pkey / 密钥表未命中 / 解密失败时原样返回 [uri]
     *         （回退为现状行为，不破坏已有链路）。
     */
    fun decryptUri(uri: String, baseUrl: String?): String {
        if (uri.isEmpty()) return uri
        val pkey = baseUrl?.let { extractQueryParam(it, "pkey") }
        val pdkey = pkey?.let { KEYS[it] }
        return when {
            pdkey != null -> decryptWith(uri, pdkey) ?: run {
                // pkey 命中但解密失败：密钥可能已轮换，仍尝试其余密钥
                tryAllKeys(uri) ?: uri
            }
            else -> tryAllKeys(uri) ?: uri
        }
    }

    /** 轮询全部密钥解密；真实 token 校验(纯字母数字)保证误命中概率可忽略。 */
    private fun tryAllKeys(uri: String): String? {
        for (pdkey in KEYS.values) {
            decryptWith(uri, pdkey)?.let { return it }
        }
        return null
    }

    private fun decryptWith(uri: String, pdkey: String): String? {
        return try {
            val isPart = PART_SUFFIX.containsMatchIn(uri)
            val parts = uri.split('_')
            val idx = if (isPart) parts.size - 3 else parts.size - 2
            if (idx < 1) return null
            val enc = parts[idx]
            if (enc.isEmpty()) return null
            val dec = decryptToken(enc, pdkey) ?: return null
            uri.replace(enc, dec)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 单 token 解密：反转 + 补 "=" + Base64 解码 + XOR SHA-256(pdkey) 循环密钥流。
     * 解出的明文必须通过 PLAIN_TOKEN 校验（错误密钥解出乱码字节，UTF-8 重建后几乎不可能
     * 恰好是纯字母数字串），否则视为密钥不匹配返回 null。
     */
    private fun decryptToken(enc: String, pdkey: String): String? {
        return try {
            val b64 = enc.reversed() + "=="
            val data = Base64.decode(b64, Base64.DEFAULT)
            val ks = MessageDigest.getInstance("SHA-256").digest(pdkey.toByteArray(Charsets.UTF_8))
            val plain = ByteArray(data.size) { i ->
                (data[i].toInt() xor ks[i % ks.size].toInt()).toByte()
            }
            val text = String(plain, Charsets.UTF_8)
            if (PLAIN_TOKEN.matches(text)) text else null
        } catch (e: Exception) {
            null
        }
    }

    /** 手写查询参数提取（避免依赖 android.net.Uri，保持纯逻辑可移植）。 */
    private fun extractQueryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            if (pair.substringBefore('=') == name) {
                return pair.substringAfter('=').takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
