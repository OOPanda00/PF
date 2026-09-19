package com.panda.finance.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {
    private const val PREFS = "panda_security"
    private const val SALT = "pin_salt"
    private const val HASH = "pin_hash"
    fun hasPin(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(HASH)
    fun setPin(context: Context, pin: String) { require(pin.length in 4..8 && pin.all(Char::isDigit)); val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}; val hash=hash(pin,salt); context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(SALT,Base64.encodeToString(salt,Base64.NO_WRAP)).putString(HASH,Base64.encodeToString(hash,Base64.NO_WRAP)).apply() }
    fun verifyPin(context: Context, pin: String): Boolean { val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE); val salt=p.getString(SALT,null)?:return false; val expected=p.getString(HASH,null)?:return false; return MessageDigest.isEqual(hash(pin,Base64.decode(salt,Base64.NO_WRAP)),Base64.decode(expected,Base64.NO_WRAP)) }
    fun clearPin(context: Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(SALT).remove(HASH).apply()
    private fun hash(pin:String,salt:ByteArray):ByteArray{var value=salt+pin.toByteArray(Charsets.UTF_8);repeat(120_000){value=MessageDigest.getInstance("SHA-256").digest(value)};return value}
    private fun backupKey(pin:String,salt:ByteArray):SecretKeySpec{val spec=PBEKeySpec(pin.toCharArray(),salt,120_000,256);val bytes=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded;return SecretKeySpec(bytes,"AES")}
    fun encryptBackup(plain:String,pin:String):String{require(pin.length in 4..8);val salt=ByteArray(16).also{SecureRandom().nextBytes(it)};val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,backupKey(pin,salt));return "PANDAFINANCE1:"+Base64.encodeToString(salt+cipher.iv+cipher.doFinal(plain.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)}
    fun decryptBackup(encoded:String,pin:String):String{require(encoded.startsWith("PANDAFINANCE1:"));val payload=Base64.decode(encoded.removePrefix("PANDAFINANCE1:"),Base64.NO_WRAP);val salt=payload.copyOfRange(0,16);val iv=payload.copyOfRange(16,28);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,backupKey(pin,salt),GCMParameterSpec(128,iv));return String(cipher.doFinal(payload.copyOfRange(28,payload.size)),Charsets.UTF_8)}
}
