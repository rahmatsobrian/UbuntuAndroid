package dev.ubuntu4a.core.proot.setup

import dev.ubuntu4a.core.data.model.DesktopEnv
import java.io.File

/**
 * Port of Desktop/de-xfce.sh, de-lxqt.sh, de-lxde.sh and de-kde.sh from
 * github.com/wahasa/Ubuntu — package lists, the udisks2 postinst hack,
 * xstartup and the vnc start/stop helpers.
 */
object DEScripts {

    fun aptSteps(de: DesktopEnv): List<List<String>> = when (de) {
        DesktopEnv.NONE -> emptyList()
        DesktopEnv.XFCE -> listOf(
            listOf("udisks2"),
            listOf(
                "software-properties-common", "elementary-xfce-icon-theme", "sudo", "tzdata",
                "xfce4", "xfce4-goodies", "xfce4-appmenu-plugin", "parole",
            ),
            listOf("tigervnc-standalone-server", "dbus-x11"),
        )
        DesktopEnv.LXQT -> listOf(
            listOf("udisks2"),
            listOf("sudo", "tzdata", "lxqt", "qterminal"),
            listOf("tigervnc-standalone-server", "dbus-x11"),
        )
        DesktopEnv.LXDE -> listOf(
            listOf("udisks2"),
            listOf("sudo", "tzdata", "lxde", "lxterminal"),
            listOf("tigervnc-standalone-server", "dbus-x11"),
        )
        DesktopEnv.KDE -> listOf(
            listOf("udisks2"),
            listOf("sudo", "tzdata", "kde-plasma-desktop", "konsole"),
            listOf("tigervnc-standalone-server", "dbus-x11"),
        )
    }

    /** Commands run after apt installs, mirroring the reference scripts. */
    fun postCommands(de: DesktopEnv): List<String> = when (de) {
        DesktopEnv.NONE -> emptyList()
        DesktopEnv.LXDE -> listOf("mv /usr/bin/lxpolkit /usr/bin/lxpolkit.bak")
        else -> emptyList()
    }

    fun xstartup(de: DesktopEnv): String = when (de) {
        DesktopEnv.XFCE -> """
            |#!/bin/bash
            |export PULSE_SERVER=127.0.0.1
            |export GTK_THEME=Yaru
            |service dbus start
            |startxfce4
            |""".trimMargin()
        DesktopEnv.LXQT -> """
            |#!/bin/bash
            |export PULSE_SERVER=127.0.0.1
            |xrdb ${'$'}HOME/.Xresources
            |startlxqt
            |""".trimMargin()
        DesktopEnv.LXDE -> """
            |#!/bin/bash
            |export PULSE_SERVER=127.0.0.1
            |xrdb ${'$'}HOME/.Xresources
            |startlxde
            |""".trimMargin()
        DesktopEnv.KDE -> """
            |#!/bin/bash
            |export PULSE_SERVER=127.0.0.1
            |xrdb ${'$'}HOME/.Xresources
            |startplasma-x11
            |""".trimMargin()
        DesktopEnv.NONE -> ""
    }

    fun vncstart(de: DesktopEnv): String = when (de) {
        DesktopEnv.NONE -> ""
        else -> """
            |#!/bin/sh
            |export DISPLAY=:1
            |export PULSE_SERVER=127.0.0.1
            |rm -rf /run/dbus/dbus.pid
            |dbus-launch ${de.startCmd}
            |""".trimMargin()
    }

    /**
     * Writes ~/.vnc/xstartup, /usr/local/bin/{vncstart,vnc-start,vnc-stop}
     * straight into the rootfs (host side, no chroot needed).
     */
    fun installScripts(rootfs: File, de: DesktopEnv, homeDir: String, geometry: String) {
        if (de == DesktopEnv.NONE) return
        RootfsProvisioner.write(rootfs, "$homeDir/.vnc/xstartup", xstartup(de))
        File(rootfs, "$homeDir/.vnc/xstartup").setExecutable(true, false)
        RootfsProvisioner.write(rootfs, "usr/local/bin/vncstart", vncstart(de))
        RootfsProvisioner.write(
            rootfs,
            "usr/local/bin/vnc-start",
            "vncserver -localhost yes -SecurityTypes VncAuth -geometry $geometry -name remote-desktop :1\n",
        )
        RootfsProvisioner.write(
            rootfs,
            "usr/local/bin/vnc-stop",
            "vncserver -kill :* ; rm -rf /tmp/.X*-lock ; rm -rf /tmp/.X11-unix/X* ; rm -rf ${'$'}HOME/.vnc/localhost:*.pid\n",
        )
        listOf("vncstart", "vnc-start", "vnc-stop").forEach {
            File(rootfs, "usr/local/bin/$it").setExecutable(true, false)
        }
    }
}
