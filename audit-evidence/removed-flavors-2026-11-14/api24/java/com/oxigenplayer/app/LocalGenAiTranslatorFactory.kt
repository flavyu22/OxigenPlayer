// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

internal object LocalGenAiTranslatorFactory {
    fun create(): LocalGenAiTranslator = UnavailableLocalGenAiTranslator
}
