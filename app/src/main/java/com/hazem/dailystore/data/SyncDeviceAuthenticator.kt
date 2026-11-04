package com.hazem.dailystore.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.MessageDigest

/**
 * Per-installation asymmetric identity for synchronization.
 *
 * The private key is generated and retained by Android Keystore; only the
 * X.509-encoded public key may leave the device. Signatures bind the complete
 * peer/operation context as well as the encrypted frame bytes.
 */
object SyncDeviceAuthenticator {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS_PREFIX = "daily_shop_sync_identity_v1_"
    private const val CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val DOMAIN = "THE_DAILY_SHOP_SYNC_DEVICE_SIGNATURE_V1"

    data class SigningContext(
        val storeId: String,
        val senderDeviceId: String,
        val recipientDeviceId: String,
        val operationId: String
    ) {
        init {
            require(storeId.isNotBlank() && senderDeviceId.isNotBlank())
            require(recipientDeviceId.isNotBlank() && operationId.isNotBlank())
        }
    }

    @Volatile
    private var testKeyPairProvider: ((Context, String) -> KeyPair)? = null

    /**
     * Test-only seam. Release builds never consult this provider; production keys
     * are always generated and retained by Android Keystore.
     */
    internal fun installTestKeyPairProvider(provider: (Context, String) -> KeyPair) {
        check(com.hazem.dailystore.BuildConfig.DEBUG) {
            "Test key providers are disabled in release builds"
        }
        testKeyPairProvider = provider
    }

    /** Generate once; the private key is never exported from Android Keystore. */
    fun getOrCreate(context: Context, deviceId: String): KeyPair {
        require(deviceId.isNotBlank())
        if (com.hazem.dailystore.BuildConfig.DEBUG) {
            testKeyPairProvider?.let { return it(context, deviceId) }
        }
        val alias = aliasFor(deviceId)
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getCertificate(alias)
        if (existing != null) {
            val privateKey = keyStore.getKey(alias, null) as? PrivateKey
                ?: throw IllegalStateException("مفتاح هوية الجهاز غير صالح")
            return KeyPair(existing.publicKey, privateKey)
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKeyPair()
    }

    fun sign(
        privateKey: PrivateKey,
        context: SigningContext,
        frame: ByteArray
    ): ByteArray {
        require(frame.isNotEmpty())
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(canonicalSignedBytes(context, frame))
        return signer.sign()
    }

    fun verify(
        encodedPublicKey: ByteArray,
        context: SigningContext,
        frame: ByteArray,
        signatureBytes: ByteArray
    ): Boolean {
        if (encodedPublicKey.isEmpty() || frame.isEmpty() || signatureBytes.isEmpty()) return false
        return runCatching {
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(encodedPublicKey))
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(canonicalSignedBytes(context, frame))
            verifier.verify(signatureBytes)
        }.getOrDefault(false)
    }

    /** Signs protocol payload bytes with SHA-256/ECDSA for relay certificates and challenges. */
    fun signBytes(privateKey: PrivateKey, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty())
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    /** Verifies a root-signed certificate or a protocol-level signed statement. */
    fun verifyBytes(encodedPublicKey: ByteArray, payload: ByteArray, signatureBytes: ByteArray): Boolean {
        if (encodedPublicKey.isEmpty() || payload.isEmpty() || signatureBytes.isEmpty()) return false
        return runCatching {
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(encodedPublicKey))
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(payload)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }

    fun publicKeyBytes(keyPair: KeyPair): ByteArray = keyPair.public.encoded.copyOf()

    fun fingerprint(encodedPublicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(encodedPublicKey)
            .joinToString("") { "%02x".format(it) }

    private fun aliasFor(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(Charsets.UTF_8))
        return KEY_ALIAS_PREFIX + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun canonicalSignedBytes(context: SigningContext, frame: ByteArray): ByteArray {
        val fields = listOf(
            DOMAIN, context.storeId, context.senderDeviceId,
            context.recipientDeviceId, context.operationId
        )
        val buffer = ByteBuffer.allocate(
            fields.sumOf { 4 + it.toByteArray(Charsets.UTF_8).size } + 4 + frame.size
        )
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
        }
        buffer.putInt(frame.size)
        buffer.put(frame)
        return buffer.array()
    }
}
