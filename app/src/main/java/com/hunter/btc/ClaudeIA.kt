package com.hunter.btc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Informe escrito del modo IA con la API de Claude (SDK oficial de Java), con la
 * clave de API del propio usuario. Lo que se envía es solo el resumen del análisis
 * (velas recientes, indicadores, niveles, factores, titulares): nada de la cartera
 * ni de la cuenta. Va por Tor si la red privada está activa. La clave se guarda
 * cifrada con una clave del Android Keystore (AES-GCM) y nunca se muestra.
 */
object ClaudeIA {
    private const val MODELO = "claude-opus-5-5"
    private const val ALIAS = "dex_claude_api_key"
    private const val PREFS = "dex_ia"

    private fun claveKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun guardarClave(ctx: Context, clave: String?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (clave.isNullOrBlank()) { p.edit().remove("k").apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, claveKeystore()) }
        val datos = c.iv + c.doFinal(clave.trim().toByteArray())
        p.edit().putString("k", android.util.Base64.encodeToString(datos, android.util.Base64.NO_WRAP)).apply()
    }

    fun leerClave(ctx: Context): String? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("k", null)?.let { b64 ->
            val d = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, claveKeystore(), GCMParameterSpec(128, d, 0, 12)) }
            String(c.doFinal(d, 12, d.size - 12))
        }
    } catch (e: Exception) { null }

    fun tieneClave(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("k")

    private const val SISTEMA = """You are a careful crypto and stock market analyst inside a paper-trading app (virtual money, no real orders).
You receive JSON computed on the user's phone for one pair: recent OHLCV candles, indicators, support/resistance levels, scored factors
(market, chart, social), candlestick patterns, the local rule engine's verdict and plan, and recent Reddit headlines.
Use only this data; do not invent prices, news or events that are not in it. If data is missing or contradictory, say so.
Write a concise analysis (about 250-400 words) with these headings: Summary, Bias (Long / Short / Spot / Wait, with a confidence from 0 to 100%),
Key levels, Bull and bear scenarios, Trade plan (entry, stop, targets, max leverage, or why to wait), What would invalidate it.
Say where you agree or disagree with the local engine and why. End with one line saying this is educational and not financial advice.
Write in the language given in the user message."""

    /** Llamada bloqueante (hilo de fondo). Devuelve el texto o lanza un mensaje de error legible. */
    fun analizar(ctx: Context, datosJson: String, idioma: String): String {
        val clave = leerClave(ctx) ?: throw IllegalStateException("Add your Claude API key first")
        val cliente = AnthropicOkHttpClient.builder()
            .apiKey(clave)
            .proxy(RedPrivada.proxy())
            .timeout(java.time.Duration.ofSeconds(180))
            .maxRetries(2)
            .build()
        try {
            val params = MessageCreateParams.builder()
                .model(MODELO)
                .maxTokens(16000L)
                .system(SISTEMA)
                .addUserMessage("Language for the answer: $idioma\n\nData:\n$datosJson")
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                // si el modelo rechaza la petición, la API la reintenta sola con otro modelo
                .fallbacksDefault()
                .addBeta("server-side-fallback-2026-07-01")
                .build()
            val r = cliente.beta().messages().create(params)
            if (r.stopReason().map { it == BetaStopReason.REFUSAL }.orElse(false))
                throw IllegalStateException("Claude declined to analyze this request")
            val texto = r.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
            if (texto.isEmpty()) throw IllegalStateException("Empty answer from Claude")
            val truncado = r.stopReason().map { it == BetaStopReason.MAX_TOKENS }.orElse(false)
            return if (truncado) "$texto\n\n(The answer was cut off at the length limit.)" else texto
        } catch (e: UnauthorizedException) { throw IllegalStateException("Invalid Claude API key")
        } catch (e: PermissionDeniedException) { throw IllegalStateException("This API key has no access to the model")
        } catch (e: RateLimitException) { throw IllegalStateException("Too many requests to the Claude API, try again in a minute")
        } catch (e: AnthropicServiceException) { throw IllegalStateException("Claude API error (${e.statusCode()})")
        } catch (e: AnthropicIoException) { throw IllegalStateException("Could not reach the Claude API (network or Tor)")
        } finally { try { cliente.close() } catch (e: Exception) { } }
    }
}
