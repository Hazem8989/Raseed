package com.hazem.dailystore.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncRelayRegistryClientTest {
    @Test fun fetchesPinnedRootAndUsesCertificateAndRevocationControlEndpointsOverTls() {
        val storeId = UUID.randomUUID().toString()
        val rootId = UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID().toString()
        val rootPublic = "MIIB-test-public-key"
        val certificateJson = """{"store_id":"$storeId","device_id":"$deviceId","signature":"root-signature"}"""
        val revocationJson = """{"store_id":"$storeId","device_id":"$deviceId","signature":"root-revocation"}"""

        val tlsCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(tlsCertificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(tlsCertificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(
            """{"store_id":"$storeId","device_id":"$rootId","public_key":"$rootPublic","algorithm":"EC_P256_SHA256"}"""
        ))
        server.enqueue(MockResponse().setBody("""{"registered":true}"""))
        server.enqueue(MockResponse().setBody("""{"revoked":true}"""))
        server.start()
        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .build()
        val registry = SyncRelayRegistryClient(server.url("/").toString(), httpClient)
        try {
            val trust = registry.fetchTrustRoot(storeId)
            assertEquals(rootId, trust.deviceId)
            assertEquals(rootPublic, trust.publicKeyBase64)
            assertTrue(registry.registerCertificate(storeId, deviceId, certificateJson))
            assertTrue(registry.revokeCertificate(storeId, deviceId, revocationJson))

            val trustRequest = server.takeRequest()
            assertEquals("GET", trustRequest.method)
            assertEquals("/v1/trust/$storeId", trustRequest.path)
            val registerRequest = server.takeRequest()
            assertEquals("POST", registerRequest.method)
            assertEquals("/v1/devices/$storeId/$deviceId/certificate", registerRequest.path)
            val revokeRequest = server.takeRequest()
            assertEquals("POST", revokeRequest.method)
            assertEquals("/v1/devices/$storeId/$deviceId/revoke", revokeRequest.path)
        } finally { server.shutdown() }
    }

    @Test fun rejectsUnencryptedRegistryEndpoints() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SyncRelayRegistryClient("http://localhost:8787")
        }
    }
}
