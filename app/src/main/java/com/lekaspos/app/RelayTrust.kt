package com.lekaspos.app

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.lekaspos.R
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS to the error-report relay on old phones (D-057). Cloudflare's certificate for it chains to
 * Google Trust Services (today) or Let's Encrypt, whose roots Android 5 to 7 do not have; Android 5
 * could not reach the relay in CI (2026-10-02). The phone's own trust store is asked first; only when
 * it refuses must the chain end at one of four public roots (res/raw/relay_roots.pem, SHA-256):
 * GTS Root R1 `d947432a…a7f4cf`, GTS Root R4 `349dfa40…1b3c7d`, ISRG Root X1 `96bcec06…df08c6`,
 * ISRG Root X2 `69729b8e…cb1470`. Only the relay's connections use this; hostnames are checked as usual.
 */
internal object RelayTrust {

    /** Why the phone's own trust store refused the relay, the last time it did (tests and diagnosis). */
    @Volatile
    var systemRefusal: String? = null

    /** Blocking: reads the roots. Throws when the phone's TLS cannot be set up this way. */
    fun socketFactory(ctx: Context): SSLSocketFactory {
        val system = trustManager(null)
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val certs = CertificateFactory.getInstance("X.509")
        val pem = ctx.resources.openRawResource(R.raw.relay_roots).use { String(it.readBytes(), Charsets.US_ASCII) }
        for ((i, block) in pem.split(END).withIndex()) {
            val body = block.substringAfter(BEGIN, "").trim()
            if (body.isEmpty()) continue
            val der = Base64.decode(body, Base64.DEFAULT)
            roots.setCertificateEntry("root$i", certs.generateCertificate(der.inputStream()))
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(SystemThenRoots(system, trustManager(roots))), null)
        return ssl.socketFactory
    }

    private fun trustManager(store: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(store)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** The phone's trust store, then the bundled roots: both check the whole chain, nothing is waved through. */
    @SuppressLint("CustomX509TrustManager")
    private class SystemThenRoots(private val system: X509TrustManager, private val roots: X509TrustManager) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                systemRefusal = e.toString()
                roots.checkServerTrusted(chain, authType)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + roots.acceptedIssuers
    }

    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"
}
