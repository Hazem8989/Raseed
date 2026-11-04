package com.hazem.dailystore.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** HTTPS control-plane client for root-pinned device certificate registration and revocation. */
class SyncRelayRegistryClient(
    relayBaseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    data class TrustRoot(
        val storeId: String,
        val deviceId: String,
        val publicKeyBase64: String,
        val algorithm: String
    )

    private val base: HttpUrl = parseBase(relayBaseUrl)

    fun fetchTrustRoot(storeId: String): TrustRoot {
        requireUuid(storeId)
        val request = Request.Builder().url(base.newBuilder()
            .addPathSegments("v1/trust/$storeId").build()).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "تعذر جلب جذر الثقة من وسيط المزامنة (${response.code})" }
            val json = JSONObject(response.body?.string() ?: error("استجابة جذر الثقة فارغة"))
            require(json.getString("store_id") == storeId)
            val deviceId = json.getString("device_id")
            requireUuid(deviceId)
            val algorithm = json.getString("algorithm")
            require(algorithm == "EC_P256_SHA256")
            val publicKey = json.getString("public_key")
            require(publicKey.isNotBlank())
            return TrustRoot(storeId, deviceId, publicKey, algorithm)
        }
    }

    fun registerCertificate(storeId: String, deviceId: String, certificateJson: String): Boolean {
        requireUuid(storeId); requireUuid(deviceId)
        val certificate = JSONObject(certificateJson)
        require(certificate.getString("store_id") == storeId &&
            certificate.getString("device_id") == deviceId &&
            certificate.getString("signature").isNotBlank())
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/devices/$storeId/$deviceId/certificate").build())
            .post(certificateJson.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "رفض وسيط المزامنة شهادة الجهاز (${response.code})" }
            return JSONObject(response.body?.string() ?: "{}").optBoolean("registered")
        }
    }

    fun revokeCertificate(storeId: String, deviceId: String, signedRevocationJson: String): Boolean {
        requireUuid(storeId); requireUuid(deviceId)
        val revocation = JSONObject(signedRevocationJson)
        require(revocation.getString("store_id") == storeId &&
            revocation.getString("device_id") == deviceId &&
            revocation.getString("signature").isNotBlank())
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/devices/$storeId/$deviceId/revoke").build())
            .post(signedRevocationJson.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "رفض وسيط المزامنة إلغاء الجهاز (${response.code})" }
            return JSONObject(response.body?.string() ?: "{}").optBoolean("revoked")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun parseBase(value: String): HttpUrl {
            require(value.startsWith("https://", ignoreCase = true)) {
                "يجب استخدام HTTPS مشفر لإدارة شهادات الأجهزة"
            }
            val parsed = value.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("عنوان وسيط المزامنة غير صالح")
            require(parsed.scheme == "https" && parsed.username.isEmpty() &&
                parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null &&
                (parsed.encodedPath == "/" || parsed.encodedPath.isEmpty())) {
                "عنوان وسيط المزامنة يجب أن يكون أساس HTTPS دون مسارات أو بيانات إضافية"
            }
            return parsed
        }

        private fun requireUuid(value: String) {
            require(runCatching { UUID.fromString(value) }.isSuccess) { "معرّف المتجر أو الجهاز غير صالح" }
        }
    }
}
