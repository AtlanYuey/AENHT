package com.atlanyuey.aenht.core

import com.atlanyuey.aenht.shared.translate.LocalTranslator
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.nl.translate.Translation as MlTranslation

/**
 * The device's own translator (Google ML Kit, on device): the last
 * fallback, free and without an account. The source language is
 * recognized; a language model (about 30 MB) is downloaded once per
 * language.
 */
object DeviceTranslator : LocalTranslator {
    override val name: String get() = L10n.string("On-Device Translation")

    private val identifier by lazy { LanguageIdentification.getClient() }

    private fun mlCode(tag: String): String? = when {
        tag.startsWith("zh") -> TranslateLanguage.CHINESE
        tag.startsWith("pt") -> TranslateLanguage.PORTUGUESE
        else -> TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
    }

    override suspend fun translate(text: String, target: String): String? {
        val to = mlCode(target) ?: throw NHException(L10n.string("The device can't translate into this language."))
        val detected = identifier.identifyLanguage(text).await()
        if (detected == "und") throw NHException(L10n.string("The device couldn't recognize the language."))
        val from = mlCode(detected) ?: throw NHException(L10n.string("The device can't translate from this language."))
        if (from == to) return text
        val options = TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
        val client = MlTranslation.getClient(options)
        try {
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            return client.translate(text).await()
        } finally {
            client.close()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
