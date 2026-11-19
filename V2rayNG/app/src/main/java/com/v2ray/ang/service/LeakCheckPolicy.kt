package com.v2ray.ang.service

/**
 * Pure decisions of the IP leak tester.
 *
 * A check collects a [Snapshot] of what the device looks like right now (which VPN is up, what
 * public address traffic leaves with through the tunnel and outside it, which tethering
 * interfaces are up) and [evaluate] turns it into findings. Everything that touches the network
 * or Android lives in the caller, so every rule here is covered by JVM tests.
 */
object LeakCheckPolicy {

    /** Interval value meaning "a few checks per hour at random times" instead of fixed minutes. */
    const val RANDOM_SCHEDULE = -1

    /** Checks per hour in the random schedule. */
    const val RANDOM_CHECKS_PER_HOUR = 3

    /** Length of one random-schedule slot; each slot gets exactly one check. */
    const val RANDOM_SLOT_MS = 60 * 60 * 1000L / RANDOM_CHECKS_PER_HOUR

    /** Keeps checks of neighbouring slots at least twice this far apart. */
    private const val RANDOM_SLOT_MARGIN_MS = 60 * 1000L

    /**
     * Picks the moment of the check in one slot of the random schedule.
     *
     * The hour is cut into [RANDOM_CHECKS_PER_HOUR] equal slots and each gets one check at a
     * uniformly random moment, so there are exactly that many checks per hour, yet nothing on the
     * network side can predict them. A margin at both ends of a slot keeps two checks from
     * running back to back across a slot boundary.
     *
     * @param slotStartMs When the slot starts.
     * @param random The random source, injectable for tests.
     * @param slotLengthMs The slot length.
     * @return When the check runs, inside the slot.
     */
    fun randomTimeInSlot(slotStartMs: Long, random: kotlin.random.Random, slotLengthMs: Long = RANDOM_SLOT_MS): Long {
        val margin = minOf(RANDOM_SLOT_MARGIN_MS, slotLengthMs / 4)
        return slotStartMs + margin + random.nextLong(slotLengthMs - 2 * margin + 1)
    }

    /** What started a check; written to the log so a leak can be tied to its moment. */
    enum class Trigger {
        /** The tunnel just came up. */
        TUNNEL_UP,

        /** The periodic check inside the running VPN service. */
        PERIODIC,

        /** The background watcher that also runs when the VPN service is not running. */
        WATCHER,

        /** The user asked for a check. */
        MANUAL,
    }

    /** One observed problem. */
    enum class Finding(val severity: Verdict) {
        /** The user left the VPN on, but this app's VPN is not up: traffic leaves directly. */
        VPN_DOWN_WHILE_EXPECTED(Verdict.LEAK),

        /** Another app's VPN holds the VPN slot while this one is expected to be up. */
        OTHER_VPN_ACTIVE(Verdict.LEAK),

        /** Traffic sent through the tunnel reaches the internet with the real public address. */
        EXIT_IP_IS_REAL_IP(Verdict.LEAK),

        /** Hotspot or USB tethering is up without root LAN sharing: its clients skip the VPN. */
        TETHERING_BYPASSES_VPN(Verdict.LEAK),

        /** The tunnel could not reach the test address, so its exit could not be checked. */
        TUNNEL_PROBE_FAILED(Verdict.WARNING),

        /** The physical network has a global IPv6 address while the tunnel carries no IPv6. */
        IPV6_OUTSIDE_TUNNEL(Verdict.WARNING),
    }

    /** Overall result of a check, ordered from good to bad. */
    enum class Verdict {
        /** Nothing could be checked, for example outside VPN mode. */
        SKIPPED,
        OK,
        WARNING,
        LEAK,
    }

    /**
     * Public address seen by the test service.
     *
     * @property ip The address, or null when the request failed.
     * @property country The two-letter country the service placed the address in, if it said.
     * @property error A short reason when the request failed.
     */
    data class ProbeResult(
        val ip: String?,
        val country: String?,
        val error: String?,
    )

    /**
     * What a check saw.
     *
     * @property vpnMode True in VPN mode; the other modes have no tunnel to check.
     * @property vpnExpected True while the user has the VPN switched on.
     * @property ourVpnUp True when this app's VPN network exists, null when it cannot be told.
     * @property otherVpnUp True when a VPN network owned by another app exists.
     * @property killSwitchBlocking True while the kill switch holds the tunnel with no core.
     * @property tunnel What the test service saw through the tunnel, null when not probed.
     * @property direct What it saw outside the tunnel, null when not probed.
     * @property ipv6Tunneled True when the tunnel carries IPv6.
     * @property underlyingGlobalIpv6 True when a physical network has a global IPv6 address.
     * @property tetheringInterfaces Names of tethering interfaces that are up.
     * @property rootLanSharing True when root LAN sharing routes tethered clients into the tunnel.
     */
    data class Snapshot(
        val vpnMode: Boolean,
        val vpnExpected: Boolean,
        val ourVpnUp: Boolean?,
        val otherVpnUp: Boolean,
        val killSwitchBlocking: Boolean,
        val tunnel: ProbeResult?,
        val direct: ProbeResult?,
        val ipv6Tunneled: Boolean,
        val underlyingGlobalIpv6: Boolean,
        val tetheringInterfaces: List<String>,
        val rootLanSharing: Boolean,
    )

    /**
     * The findings of a check and the verdict they add up to.
     *
     * @property verdict The worst severity among [findings], or OK / SKIPPED.
     * @property findings The problems, in [Finding] declaration order.
     */
    data class Evaluation(
        val verdict: Verdict,
        val findings: List<Finding>,
    )

    /**
     * Turns a [Snapshot] into findings.
     *
     * @param snapshot What the check saw.
     * @return The evaluation.
     */
    fun evaluate(snapshot: Snapshot): Evaluation {
        if (!snapshot.vpnMode) return Evaluation(Verdict.SKIPPED, emptyList())

        val findings = mutableListOf<Finding>()
        val vpnDown = snapshot.ourVpnUp == false
        if (snapshot.vpnExpected && vpnDown) findings += Finding.VPN_DOWN_WHILE_EXPECTED
        if (snapshot.vpnExpected && vpnDown && snapshot.otherVpnUp) findings += Finding.OTHER_VPN_ACTIVE

        if (isSameAddress(snapshot.tunnel?.ip, snapshot.direct?.ip)) findings += Finding.EXIT_IP_IS_REAL_IP

        val tunnelInUse = snapshot.vpnExpected || snapshot.ourVpnUp == true
        if (tunnelInUse && snapshot.tetheringInterfaces.isNotEmpty() && !snapshot.rootLanSharing) {
            findings += Finding.TETHERING_BYPASSES_VPN
        }

        // A blocked tunnel cannot reach anything by design; that is the kill switch working.
        val tunnel = snapshot.tunnel
        if (tunnel != null && tunnel.ip == null && !snapshot.killSwitchBlocking && snapshot.ourVpnUp != false) {
            findings += Finding.TUNNEL_PROBE_FAILED
        }

        if (snapshot.ourVpnUp == true && !snapshot.ipv6Tunneled && snapshot.underlyingGlobalIpv6) {
            findings += Finding.IPV6_OUTSIDE_TUNNEL
        }

        val ordered = Finding.entries.filter { it in findings }
        val verdict = ordered.maxOfOrNull { it.severity } ?: Verdict.OK
        return Evaluation(verdict, ordered)
    }

    /**
     * Decides whether a check result is worth a notification.
     *
     * Only a leak notifies, and only when its findings differ from the last notified ones, so a
     * condition that lasts for hours produces one notification rather than one per check.
     *
     * @param evaluation The current result.
     * @param lastNotifiedSignature The [signature] of the last notified result, or null.
     * @return True when the user must be told.
     */
    fun shouldNotify(evaluation: Evaluation, lastNotifiedSignature: String?): Boolean {
        if (evaluation.verdict != Verdict.LEAK) return false
        return signature(evaluation) != lastNotifiedSignature
    }

    /**
     * A stable text for a result's findings, used to notify once per distinct leak.
     *
     * @param evaluation The result.
     * @return The signature; empty for a result without findings.
     */
    fun signature(evaluation: Evaluation): String = evaluation.findings.joinToString(",") { it.name }

    /**
     * Decides whether a check result is written to the leak log.
     *
     * Problems are always written; a clean result only when the previous one was not clean, so
     * the log keeps the moment a leak ended without filling up with identical clean entries.
     *
     * @param evaluation The current result.
     * @param previousVerdict The verdict of the last check, or null when there was none.
     * @param trigger What started the check; manual checks are always written.
     * @return True when the entry must be written.
     */
    fun shouldLog(evaluation: Evaluation, previousVerdict: Verdict?, trigger: Trigger): Boolean {
        if (trigger == Trigger.MANUAL || trigger == Trigger.TUNNEL_UP) return true
        if (evaluation.verdict == Verdict.SKIPPED) return false
        if (evaluation.verdict != Verdict.OK) return true
        return previousVerdict != Verdict.OK
    }

    /**
     * Reads the answer of a Cloudflare-style trace endpoint (`key=value` per line).
     *
     * @param body The response body.
     * @return The address and country, with an error when no address was found.
     */
    fun parseTrace(body: String?): ProbeResult {
        if (body.isNullOrBlank()) return ProbeResult(null, null, "empty response")
        val fields = body.lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
            .toMap()
        val ip = fields["ip"]?.takeIf { looksLikeIp(it) }
        val country = fields["loc"]?.takeIf { it.length == 2 }?.uppercase()
        if (ip != null) return ProbeResult(ip, country, null)

        // Plain-text endpoints answer with the address alone.
        val plain = body.trim()
        return if (looksLikeIp(plain)) ProbeResult(plain, null, null) else ProbeResult(null, null, "no address in response")
    }

    /**
     * Hides the host part of an address before it is written anywhere.
     *
     * The log must be safe to share, yet show whether two addresses match and roughly whose
     * network they belong to: the first two IPv4 octets or the first two IPv6 groups are kept.
     *
     * @param ip The address.
     * @return The masked address, or null for null.
     */
    fun maskIp(ip: String?): String? {
        if (ip == null) return null
        if (ip.contains(':')) {
            val groups = ip.split(':').filter { it.isNotEmpty() }
            return groups.take(2).joinToString(":") + ":x:x"
        }
        val octets = ip.split('.')
        if (octets.size != 4) return "x"
        return "${octets[0]}.${octets[1]}.x.x"
    }

    /**
     * Compares two addresses as the test service printed them, ignoring case and surrounding
     * spaces. Both probes use the same service, so its formatting is consistent between them.
     *
     * @return True when both are present and equal.
     */
    fun isSameAddress(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    private fun looksLikeIp(text: String): Boolean {
        if (text.length > 45 || text.isEmpty()) return false
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        val ipv6 = Regex("""^[0-9a-fA-F:]+$""")
        return ipv4.matches(text) || (text.contains(':') && ipv6.matches(text))
    }
}
