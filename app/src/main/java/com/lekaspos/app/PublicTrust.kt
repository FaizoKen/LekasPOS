package com.lekaspos.app

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.google.android.gms.security.ProviderInstaller
import com.lekaspos.R
import com.lekaspos.util.Log
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS on old phones to the app's own services: the error-report relay (D-057) and GitHub, where
 * updates come from (D-059). Their certificates chain to roots Android 5 to 7 do not have; Android 5
 * could not reach the relay in CI (2026-10-02). The phone's own trust store is asked first; only when
 * it refuses must the chain end at one of these public roots (res/raw/public_roots.pem, SHA-256):
 * - the relay (Cloudflare): GTS Root R1 `d947432a…a7f4cf`, GTS Root R4 `349dfa40…1b3c7d`,
 *   ISRG Root X1 `96bcec06…df08c6`, ISRG Root X2 `69729b8e…cb1470`;
 * - GitHub (api.github.com, github.com — Sectigo): USERTrust ECC `4ff460d5…a9ad7a`, USERTrust RSA
 *   `e793c9b0…d4cbd2`, Sectigo Public Server Authentication Root E46 `c90f26f0…0c5383` and R46
 *   `7bb647a6…f25a06`; its download host (release-assets.githubusercontent.com) uses Let's Encrypt
 *   (ISRG Root X1).
 * Only these connections use it; hostnames are checked as usual.
 */
internal object PublicTrust {

    /** Why the phone's own trust store refused a connection, the last time it did (tests and diagnosis). */
    @Volatile
    var systemRefusal: String? = null

    @Volatile
    private var made = false

    @Volatile
    private var factory: SSLSocketFactory? = null

    /**
     * The socket factory for these connections, made once; null: the phone's own (it could not be
     * made). First brings TLS up to date where Play services can (as for Drive). Blocking.
     */
    fun sockets(ctx: Context): SSLSocketFactory? {
        if (!made) {
            synchronized(this) {
                if (!made) {
                    securityProvider(ctx)
                    factory = try {
                        socketFactory(ctx)
                    } catch (e: Exception) {
                        // Not Log.w/e: the error-report path uses this (no report about reports).
                        android.util.Log.w(Log.TAG, "The bundled TLS roots could not be set up", e)
                        null
                    }
                    made = true
                }
            }
        }
        return factory
    }

    private fun securityProvider(ctx: Context) {
        try {
            ProviderInstaller.installIfNeeded(ctx)
        } catch (e: Exception) {
            // No Play services: the phone's own TLS is tried.
        } catch (e: LinkageError) {
            // the same
        }
    }

    /** Throws when the phone's TLS cannot be set up this way. */
    private fun socketFactory(ctx: Context): SSLSocketFactory {
        val system = trustManager(null)
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val certs = CertificateFactory.getInstance("X.509")
        val pem = ctx.resources.openRawResource(R.raw.public_roots).use { String(it.readBytes(), Charsets.US_ASCII) }
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
