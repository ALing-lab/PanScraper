package com.tamu.pan123.data.p115

import net.jpountz.lz4.LZ4Factory
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.zip.CRC32
import org.bouncycastle.jce.provider.BouncyCastleProvider
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 115 上传 initupload 用的 ECDH(P-224) + AES 握手。
 * 移植自 SheltonZhu/115driver pkg/crypto/ec115（与 aead/ecdh Generic(P224) 行为一致）。
 */
class Ec115Cipher private constructor(
    private val key: ByteArray,
    private val iv: ByteArray,
    private val pubKey: ByteArray,
) {
    fun encrypt(plain: ByteArray): ByteArray {
        val padded = pkcs7Pad(plain, 16)
        val block = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        val out = ByteArray(padded.size)
        var xorKey = iv.copyOf()
        var i = 0
        while (i < padded.size) {
            val tmp = ByteArray(16)
            for (j in 0 until 16) tmp[j] = (padded[i + j].toInt() xor xorKey[j].toInt()).toByte()
            block.doFinal(tmp, 0, 16, xorKey, 0)
            System.arraycopy(xorKey, 0, out, i, 16)
            i += 16
        }
        return out
    }

    fun decrypt(cipherText: ByteArray): ByteArray {
        val aligned = cipherText.copyOf(cipherText.size - cipherText.size % 16)
        val aes = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val lz4Block = aes.doFinal(aligned)
        if (lz4Block.size < 2) throw java.io.IOException("115 返回的数据太短，无法解密")
        val length = (lz4Block[0].toInt() and 0xff) + ((lz4Block[1].toInt() and 0xff) shl 8)
        if (length > lz4Block.size - 2) throw java.io.IOException("115 返回的数据长度不对，无法解密")
        return lz4Decompress(lz4Block, 2, length)
    }

    /**
     * LZ4 块解压：输出大小事先不知道。从压缩数据的 4 倍（至少 8 KB）开始，放不下就翻倍，
     * 上限是 LZ4 理论最大压缩比（255 倍）和 [MAX_PLAIN] 中较小的那个，超出就报错，不会截断数据。
     */
    internal fun lz4Decompress(src: ByteArray, off: Int, len: Int): ByteArray {
        val d = LZ4Factory.fastestInstance().safeDecompressor()
        val limit = minOf(MAX_PLAIN.toLong(), len.toLong() * 255 + 16).toInt()
        var size = minOf(limit, maxOf(0x2000, len * 4))
        while (true) {
            val dest = ByteArray(size)
            try {
                val n = d.decompress(src, off, len, dest, 0)
                return dest.copyOf(n)
            } catch (e: net.jpountz.lz4.LZ4Exception) {
                if (size >= limit) throw java.io.IOException("115 返回的数据解压失败", e)
                size = minOf(limit, size * 2)
            }
        }
    }

    /** k_ec 查询参数：时间戳（毫秒）编码后的令牌。 */
    fun encodeToken(timestampMs: Long): String {
        val r = SecureRandom()
        val r1 = r.nextInt(256).toByte()
        val r2 = r.nextInt(256).toByte()
        val time = ByteBuffer.allocate(4).putInt((timestampMs and 0xffffffffL).toInt()).array()
        val tmp = ArrayList<Byte>(48)
        for (i in 0 until 15) tmp += (pubKey[i].toInt() xor (r1.toInt() and 0xff)).toByte()
        tmp += r1
        tmp += (0x73 xor (r1.toInt() and 0xff)).toByte()
        repeat(3) { tmp += r1 }
        for (i in 0 until 4) tmp += (r1.toInt() xor (time[3 - i].toInt() and 0xff)).toByte()
        for (i in 15 until pubKey.size) tmp += (pubKey[i].toInt() xor (r2.toInt() and 0xff)).toByte()
        tmp += r2
        tmp += (0x01 xor (r2.toInt() and 0xff)).toByte()
        repeat(3) { tmp += r2 }
        val crc = CRC32()
        crc.update(CRC_SALT.toByteArray())
        crc.update(tmp.toByteArray())
        val crcBytes = ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()
        for (i in 0 until 4) tmp += crcBytes[3 - i]
        return Base64.getEncoder().encodeToString(tmp.toByteArray())
    }

    companion object {
        /** 解压后最大 16 MB（115 的接口响应远小于这个）。 */
        const val MAX_PLAIN = 16 * 1024 * 1024
        private const val CRC_SALT = "^j>WD3Kr?J2gLFjD4W2y@"
        private const val P224_BASE = 28
        /** 115 服务端固定 P-224 公钥（X||Y，各 28 字节）。 */
        private val REMOTE_PUB = byteArrayOf(
            0x57, 0xA2.toByte(), 0x92.toByte(), 0x57, 0xCD.toByte(), 0x23, 0x20, 0xE5.toByte(),
            0xD6.toByte(), 0xD1.toByte(), 0x43, 0x32, 0x2F, 0xA4.toByte(), 0xBB.toByte(), 0x8A.toByte(),
            0x3C, 0xF9.toByte(), 0xD3.toByte(), 0xCC.toByte(), 0x62, 0x3E, 0xF5.toByte(), 0xED.toByte(),
            0xAC.toByte(), 0x62, 0xB7.toByte(), 0x67, 0x8A.toByte(), 0x89.toByte(), 0xC9.toByte(), 0x1A,
            0x83.toByte(), 0xBA.toByte(), 0x80.toByte(), 0x0D, 0x61, 0x29, 0xF5.toByte(), 0x22,
            0xD0.toByte(), 0x34, 0xC8.toByte(), 0x95.toByte(), 0xDD.toByte(), 0x24, 0x65, 0x24,
            0x3A, 0xDD.toByte(), 0xC2.toByte(), 0x50, 0x95.toByte(), 0x3B, 0xEE.toByte(), 0xBA.toByte(),
        )

        /** 测试用：固定密钥，不走真实 ECDH。 */
        fun forTest(key: ByteArray, iv: ByteArray, pubKey: ByteArray = ByteArray(30) { it.toByte() }) =
            Ec115Cipher(key.copyOf(16), iv.copyOf(16), pubKey)

        fun create(): Ec115Cipher {
            if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                java.security.Security.addProvider(BouncyCastleProvider())
            }
            val kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
                .apply { initialize(ECGenParameterSpec("secp224r1")) }
            val kp = kpg.generateKeyPair()
            val params = (kp.public as ECPublicKey).params
            val remote = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME).generatePublic(
                ECPublicKeySpec(
                    ECPoint(
                        BigInteger(1, REMOTE_PUB.copyOfRange(0, P224_BASE)),
                        BigInteger(1, REMOTE_PUB.copyOfRange(P224_BASE, REMOTE_PUB.size)),
                    ),
                    params,
                ),
            )
            val ka = KeyAgreement.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME).apply {
                init(kp.private)
                doPhase(remote, true)
            }
            val secret = ka.generateSecret() // P-224 X 坐标，28 字节
            val key = secret.copyOfRange(0, 16)
            val iv = secret.copyOfRange(secret.size - 16, secret.size)
            val point = (kp.public as ECPublicKey).w
            val x = point.affineX.toByteArray().let { b ->
                when {
                    b.size == P224_BASE -> b
                    b.size > P224_BASE -> b.copyOfRange(b.size - P224_BASE, b.size)
                    else -> ByteArray(P224_BASE - b.size) + b
                }
            }
            val odd = point.affineY.testBit(0)
            val pub = byteArrayOf((P224_BASE + 1).toByte(), if (odd) 0x03 else 0x02) + x
            return Ec115Cipher(key, iv, pub)
        }

        private fun pkcs7Pad(data: ByteArray, block: Int): ByteArray {
            val pad = block - data.size % block
            return data + ByteArray(pad) { pad.toByte() }
        }

        fun sha1HexUpper(data: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02X".format(it) }

        fun md5Hex(data: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
    }
}
