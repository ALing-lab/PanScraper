package com.tamu.pan123.data.drive

import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 阿里云 OSS V1 签名（光鸭 / 115 上传共用）。 */
object OssV1 {
    fun sign(
        secret: String, verb: String, contentMd5: String, contentType: String, date: String,
        ossHeaders: Map<String, String>, resource: String,
    ): String {
        val canonHeaders = ossHeaders.entries
            .map { it.key.lowercase() to it.value.trim() }
            .sortedBy { it.first }
            .joinToString("") { "${it.first}:${it.second}\n" }
        val sts = "$verb\n$contentMd5\n$contentType\n$date\n$canonHeaders$resource"
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
        return Base64.getEncoder().encodeToString(mac.doFinal(sts.toByteArray()))
    }

    fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(java.util.Date())
}
