package com.carcast.service

import android.os.Build
import com.carcast.BuildConfig
import com.carcast.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dumps the phone's policy routing so "the laptop cannot reach 100.99.9.9" can be diagnosed
 * from a single text: ip rules (VPN uid ranges, prohibit rules), every route table, addresses,
 * and the two sysctls that decide how replies to hotspot clients are routed.
 * Everything is read through the public `ip` binary or plain files; no root, no adb.
 * On Android 16 (One UI 8) SELinux denies both to apps (netlink bind, /proc/sys), so the dump
 * says so and lists the adb commands that give the same picture from the shell uid.
 */
object NetDiag {
    fun dump(): String = buildString {
        appendLine("CarCast build ${BuildConfig.GIT_SHA} / Android ${Build.VERSION.RELEASE} (${Build.VERSION.INCREMENTAL}) ${Build.MODEL}")
        appendLine("tun ${Config.TUN_ADDRESS}/${Config.TUN_PREFIX} state=${com.carcast.vpn.CarVpnService.state} running=${StreamService.running} useVpn=${StreamService.useVpn}")
        for (f in listOf("/proc/sys/net/ipv4/tcp_fwmark_accept", "/proc/sys/net/ipv4/fwmark_reflect", "/proc/sys/net/ipv4/ip_forward")) {
            appendLine("$f = ${runCatching { File(f).readText().trim() }.getOrElse { "read failed: ${it.javaClass.simpleName}" }}")
        }
        section("ip rule", "ip", "rule")
        section("ip route show table all", "ip", "route", "show", "table", "all")
        section("ip -4 addr", "ip", "-4", "addr")
        section("ip -6 route show table all (short)", "sh", "-c", "ip -6 route show table all | head -60")
        appendLine()
        appendLine("### if the sections above are 'Permission denied', run from a PC with adb:")
        appendLine("adb shell ip rule")
        appendLine("adb shell ip route show table all")
        appendLine("adb shell cat /proc/sys/net/ipv4/tcp_fwmark_accept /proc/sys/net/ipv4/fwmark_reflect")
        appendLine("adb shell ss -tan   # while the laptop connects: SYN-RECV on :${Config.HTTP_PORT} = SYN arrived, reply lost")
    }

    /** The lines of `ip rule` worth putting in the on-screen log: VPN, prohibit and local-network rules. */
    fun summary(): List<String> {
        val rules = run("ip", "rule").lines()
        val interesting = rules.filter { l ->
            l.contains("tun") || l.contains("prohibit") || l.contains("unreachable") ||
                l.contains("uidrange") || l.contains("local_network") || l.contains("swlan") || l.contains("ap0")
        }
        val sysctl = listOf("tcp_fwmark_accept", "fwmark_reflect").map { n ->
            "$n=${runCatching { File("/proc/sys/net/ipv4/$n").readText().trim() }.getOrElse { "?" }}"
        }
        return listOf("ip rule ${rules.size}줄, ${sysctl.joinToString(" ")}") + interesting.take(25)
    }

    private fun StringBuilder.section(title: String, vararg cmd: String) {
        appendLine().appendLine("### $title")
        appendLine(run(*cmd))
    }

    private fun run(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
        out.ifBlank { "(no output, exit ${p.exitValue()})" }
    } catch (e: Exception) {
        "failed: $e"
    }
}
