package com.example.nfctransit.data.route

import java.net.URLEncoder
import java.security.MessageDigest

/** 腾讯地图 WebService GET 请求的 SN 签名与 URL 构造。 */
internal object TencentWebServiceSigner {

    fun sign(
        requestPath: String,
        parameters: Map<String, String>,
        secretKey: String
    ): String {
        require(requestPath.startsWith('/')) { "请求路径必须以 / 开头" }
        require(secretKey.isNotBlank()) { "腾讯地图 SecretKey 不能为空" }
        require("sig" !in parameters) { "sig 不能参与签名" }

        val canonicalParameters = sortedEntries(parameters).joinToString("&") { (name, value) ->
            "$name=$value"
        }
        val source = "$requestPath?$canonicalParameters$secretKey"
        val digest = MessageDigest.getInstance("MD5").digest(source.toByteArray(Charsets.UTF_8))
        return digest.toLowerHex()
    }

    fun buildGetUrl(
        baseUrl: String,
        requestPath: String,
        parameters: Map<String, String>,
        secretKey: String
    ): String {
        require(!baseUrl.endsWith('/')) { "baseUrl 不能以 / 结尾" }
        val sortedParameters = sortedEntries(parameters)
        val encodedParameters = sortedParameters.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        val signature = sign(requestPath, parameters, secretKey)
        return "$baseUrl$requestPath?$encodedParameters&sig=$signature"
    }

    private fun sortedEntries(parameters: Map<String, String>): List<Map.Entry<String, String>> =
        parameters.entries.sortedBy(Map.Entry<String, String>::key)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun ByteArray.toLowerHex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            for (byte in this@toLowerHex) {
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }
}
