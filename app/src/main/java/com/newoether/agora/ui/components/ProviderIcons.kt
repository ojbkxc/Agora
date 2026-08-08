package com.newoether.agora.ui.components

import com.newoether.agora.R

/**
 * Single source of truth mapping a built-in provider name to its brand icon drawable.
 * Returns 0 for unknown / custom providers (callers fall back to a generic Cloud icon).
 */
fun providerIcon(name: String): Int = when (name.lowercase()) {
    "openai" -> R.drawable.provider_openai
    "anthropic" -> R.drawable.provider_anthropic
    else -> 0
}
