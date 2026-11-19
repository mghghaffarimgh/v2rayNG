package com.v2ray.ang.service

import com.v2ray.ang.service.LeakCheckPolicy.Evaluation
import com.v2ray.ang.service.LeakCheckPolicy.Finding
import com.v2ray.ang.service.LeakCheckPolicy.ProbeResult
import com.v2ray.ang.service.LeakCheckPolicy.Snapshot
import com.v2ray.ang.service.LeakCheckPolicy.Trigger
import com.v2ray.ang.service.LeakCheckPolicy.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeakCheckPolicyTest {

    private val healthy = Snapshot(
        vpnMode = true,
        vpnExpected = true,
        ourVpnUp = true,
        otherVpnUp = false,
        killSwitchBlocking = false,
        tunnel = ProbeResult("203.0.113.7", "DE", null),
        direct = ProbeResult("198.51.100.20", "IR", null),
        ipv6Tunneled = false,
        underlyingGlobalIpv6 = false,
        tetheringInterfaces = emptyList(),
        rootLanSharing = false,
    )

    @Test
    fun healthyTunnelIsOk() {
        assertEquals(Evaluation(Verdict.OK, emptyList()), LeakCheckPolicy.evaluate(healthy))
    }

    @Test
    fun nonVpnModeIsSkipped() {
        assertEquals(Verdict.SKIPPED, LeakCheckPolicy.evaluate(healthy.copy(vpnMode = false)).verdict)
    }

    @Test
    fun vpnDownWhileExpectedIsALeak() {
        val down = healthy.copy(ourVpnUp = false, tunnel = null)
        val result = LeakCheckPolicy.evaluate(down)
        assertEquals(Verdict.LEAK, result.verdict)
        assertEquals(listOf(Finding.VPN_DOWN_WHILE_EXPECTED), result.findings)
    }

    @Test
    fun vpnDownAfterUserStopIsNotALeak() {
        val stopped = healthy.copy(vpnExpected = false, ourVpnUp = false, tunnel = null)
        assertEquals(Verdict.OK, LeakCheckPolicy.evaluate(stopped).verdict)
    }

    @Test
    fun unknownVpnStateIsNotReportedAsDown() {
        assertEquals(Verdict.OK, LeakCheckPolicy.evaluate(healthy.copy(ourVpnUp = null)).verdict)
    }

    @Test
    fun anotherVpnTakingTheSlotIsNamed() {
        val takenOver = healthy.copy(ourVpnUp = false, otherVpnUp = true, tunnel = null)
        assertEquals(
            listOf(Finding.VPN_DOWN_WHILE_EXPECTED, Finding.OTHER_VPN_ACTIVE),
            LeakCheckPolicy.evaluate(takenOver).findings,
        )
    }

    @Test
    fun sameExitAndRealAddressIsALeak() {
        val leaking = healthy.copy(tunnel = ProbeResult("198.51.100.20", "IR", null))
        val result = LeakCheckPolicy.evaluate(leaking)
        assertEquals(Verdict.LEAK, result.verdict)
        assertEquals(listOf(Finding.EXIT_IP_IS_REAL_IP), result.findings)
    }

    @Test
    fun tetheringNeedsRootSharingToBeSafe() {
        val tethering = healthy.copy(tetheringInterfaces = listOf("ap0"))
        assertEquals(listOf(Finding.TETHERING_BYPASSES_VPN), LeakCheckPolicy.evaluate(tethering).findings)
        assertEquals(Verdict.OK, LeakCheckPolicy.evaluate(tethering.copy(rootLanSharing = true)).verdict)
        assertEquals(
            Verdict.OK,
            LeakCheckPolicy.evaluate(tethering.copy(vpnExpected = false, ourVpnUp = false, tunnel = null)).verdict,
        )
    }

    @Test
    fun failedTunnelProbeWarnsUnlessTheKillSwitchIsBlocking() {
        val failed = healthy.copy(tunnel = ProbeResult(null, null, "timeout"))
        val result = LeakCheckPolicy.evaluate(failed)
        assertEquals(Verdict.WARNING, result.verdict)
        assertEquals(listOf(Finding.TUNNEL_PROBE_FAILED), result.findings)
        assertEquals(Verdict.OK, LeakCheckPolicy.evaluate(failed.copy(killSwitchBlocking = true)).verdict)
    }

    @Test
    fun globalIpv6WithoutTunnelIpv6Warns() {
        val ipv6 = healthy.copy(underlyingGlobalIpv6 = true)
        assertEquals(listOf(Finding.IPV6_OUTSIDE_TUNNEL), LeakCheckPolicy.evaluate(ipv6).findings)
        assertEquals(Verdict.OK, LeakCheckPolicy.evaluate(ipv6.copy(ipv6Tunneled = true)).verdict)
    }

    @Test
    fun leakOutranksWarning() {
        val both = healthy.copy(tetheringInterfaces = listOf("rndis0"), underlyingGlobalIpv6 = true)
        val result = LeakCheckPolicy.evaluate(both)
        assertEquals(Verdict.LEAK, result.verdict)
        assertEquals(listOf(Finding.TETHERING_BYPASSES_VPN, Finding.IPV6_OUTSIDE_TUNNEL), result.findings)
    }

    @Test
    fun notifiesOncePerDistinctLeak() {
        val leak = LeakCheckPolicy.evaluate(healthy.copy(ourVpnUp = false, tunnel = null))
        assertTrue(LeakCheckPolicy.shouldNotify(leak, null))
        assertFalse(LeakCheckPolicy.shouldNotify(leak, LeakCheckPolicy.signature(leak)))
        assertTrue(LeakCheckPolicy.shouldNotify(leak, "EXIT_IP_IS_REAL_IP"))
        assertFalse(LeakCheckPolicy.shouldNotify(LeakCheckPolicy.evaluate(healthy), null))
    }

    @Test
    fun logKeepsProblemsAndTransitionsOnly() {
        val ok = LeakCheckPolicy.evaluate(healthy)
        val leak = LeakCheckPolicy.evaluate(healthy.copy(ourVpnUp = false, tunnel = null))
        val skipped = LeakCheckPolicy.evaluate(healthy.copy(vpnMode = false))
        assertTrue(LeakCheckPolicy.shouldLog(leak, Verdict.LEAK, Trigger.PERIODIC))
        assertTrue(LeakCheckPolicy.shouldLog(ok, Verdict.LEAK, Trigger.PERIODIC))
        assertTrue(LeakCheckPolicy.shouldLog(ok, null, Trigger.WATCHER))
        assertFalse(LeakCheckPolicy.shouldLog(ok, Verdict.OK, Trigger.PERIODIC))
        assertFalse(LeakCheckPolicy.shouldLog(skipped, Verdict.OK, Trigger.WATCHER))
        assertTrue(LeakCheckPolicy.shouldLog(ok, Verdict.OK, Trigger.MANUAL))
        assertTrue(LeakCheckPolicy.shouldLog(ok, Verdict.OK, Trigger.TUNNEL_UP))
    }

    @Test
    fun traceBodyIsParsed() {
        val body = "fl=12f\nh=www.cloudflare.com\nip=203.0.113.7\nts=1.2\nloc=de\nwarp=off\n"
        assertEquals(ProbeResult("203.0.113.7", "DE", null), LeakCheckPolicy.parseTrace(body))
        assertEquals(ProbeResult("2001:db8::1", null, null), LeakCheckPolicy.parseTrace("2001:db8::1\n"))
        assertNull(LeakCheckPolicy.parseTrace("<html>blocked</html>").ip)
        assertNull(LeakCheckPolicy.parseTrace("").ip)
        assertNull(LeakCheckPolicy.parseTrace(null).ip)
    }

    @Test
    fun addressesAreMaskedBeforeLogging() {
        assertEquals("203.0.x.x", LeakCheckPolicy.maskIp("203.0.113.7"))
        assertEquals("2001:db8:x:x", LeakCheckPolicy.maskIp("2001:db8:85a3::8a2e:370:7334"))
        assertEquals("x", LeakCheckPolicy.maskIp("garbage"))
        assertNull(LeakCheckPolicy.maskIp(null))
    }

    @Test
    fun randomScheduleRunsThreeTimesPerHourInsideTheirSlots() {
        val random = kotlin.random.Random(7)
        val hour = 60 * 60 * 1000L
        assertEquals(3, LeakCheckPolicy.RANDOM_CHECKS_PER_HOUR)
        assertEquals(hour / 3, LeakCheckPolicy.RANDOM_SLOT_MS)
        var previous = Long.MIN_VALUE
        val distinct = mutableSetOf<Long>()
        repeat(300) { slot ->
            val start = slot * LeakCheckPolicy.RANDOM_SLOT_MS
            val at = LeakCheckPolicy.randomTimeInSlot(start, random)
            assertTrue(at >= start + 60_000L && at <= start + LeakCheckPolicy.RANDOM_SLOT_MS - 60_000L)
            if (previous != Long.MIN_VALUE) assertTrue(at - previous >= 120_000L)
            previous = at
            distinct += at - start
        }
        // Moments vary from slot to slot instead of repeating a fixed offset.
        assertTrue(distinct.size > 250)
    }

    @Test
    fun addressComparisonNeedsBothSides() {
        assertTrue(LeakCheckPolicy.isSameAddress("2001:DB8::1", "2001:db8::1"))
        assertFalse(LeakCheckPolicy.isSameAddress(null, "203.0.113.7"))
        assertFalse(LeakCheckPolicy.isSameAddress("203.0.113.7", "203.0.113.8"))
    }
}
