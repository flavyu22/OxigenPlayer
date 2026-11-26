// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/**
 * Gemini Nano (ML Kit GenAI Prompt) was removed on 14 November 2026: it needs Android AICore,
 * which televisions do not ship, so it could never run on the devices this player targets and
 * its client crashed the app at launch there. Translation uses ML Kit and the configured
 * providers. The [LocalGenAiTranslator] seam is kept so the engine can be reintroduced later.
 */
internal object LocalGenAiTranslatorFactory {
    fun create(): LocalGenAiTranslator = UnavailableLocalGenAiTranslator
}
