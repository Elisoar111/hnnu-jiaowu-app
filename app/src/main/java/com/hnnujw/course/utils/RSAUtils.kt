package com.hnnujw.course.utils

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object RSAUtils {

    fun encrypt(modulus: String, exponent: String, data: String): String {
        val mod = BigInteger(1, Base64.decode(modulus, Base64.DEFAULT))
        val exp = BigInteger(1, Base64.decode(exponent, Base64.DEFAULT))
        val spec = RSAPublicKeySpec(mod, exp)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * 用 **X.509 SubjectPublicKeyInfo** 形态的 Base64 公钥加密（PEM 去掉头尾与换行后的那段）。
     *
     * 为什么要单独一个入口：学工系统（xg.hnnu.edu.cn）的 H5 用 JSEncrypt 加密密码，
     * 站点把公钥写成了 `MIGfMA0GCSqGSIb3…` 这种 SPKI 包装（自带算法标识），
     * 而不是 [encrypt] 要的裸模数/指数两段。两者填充方式一致（PKCS#1 v1.5），
     * 只是密钥的装载方式不同 —— 这里交给系统 `KeyFactory` 直接解析 DER，
     * 不去手工剥 TLV（那种写法一旦公钥换个位数就会静默算错，且很难查）。
     *
     * 服务端解不出来时表现为"账号或密码错误"，所以填充方式必须与 H5 严格一致。
     */
    fun encryptWithPublicKey(spkiBase64: String, data: String): String {
        val spec = X509EncodedKeySpec(Base64.decode(spkiBase64, Base64.DEFAULT))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}