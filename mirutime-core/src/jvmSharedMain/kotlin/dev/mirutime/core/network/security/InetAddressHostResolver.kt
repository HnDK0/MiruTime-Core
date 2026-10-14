package dev.mirutime.core.network.security

import java.net.InetAddress
import java.net.UnknownHostException

// Default DNS resolution for SsrfGuard: a blocking InetAddress lookup.
// Lives in jvmSharedMain so both targets get it; the call site is the
// session's IO worker (PluginHttpClient.get runs under Dispatchers.IO via
// LuaPluginSource.locked), so blocking there is the intended trade-off.
// Unknown hosts resolve to an empty list — SsrfGuard reports DnsFailure.
class InetAddressHostResolver : HostResolver {
    override fun resolve(host: String): List<String> = try {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
    } catch (e: UnknownHostException) {
        emptyList()
    }
}
