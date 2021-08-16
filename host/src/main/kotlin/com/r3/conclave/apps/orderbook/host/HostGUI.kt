package com.r3.conclave.apps.orderbook.host

import javax.swing.JButton
import javax.swing.JFrame
import com.r3.conclave.apps.orderbook.host.main as ServerMain

object HostGUI {
    @JvmStatic
    fun main(args: Array<String>) {
        ServerMain()
    }
}