package dev.mirutime.core.api

sealed class PluginError {
    data class UserMessage(
        val title: String,
        val message: String,
        val authUrl: String? = null,
    ) : PluginError()

    data class Network(val cause: String) : PluginError()
    data class DnsFailure(val host: String) : PluginError()
    data class CfChallengeRequired(val host: String) : PluginError()
    data class ResponseTooLarge(val limit: Long) : PluginError()
    data class UnsupportedApi(val required: Int, val actual: Int) : PluginError()
    data class ScriptError(val detail: String) : PluginError()
}
