package com.r3.conclave.apps.orderbook.cli

import com.r3.conclave.apps.orderbook.OrderBookClient
import com.r3.conclave.apps.orderbook.types.BuySell
import com.r3.conclave.apps.orderbook.types.Order
import picocli.CommandLine
import picocli.CommandLine.Option
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class OrderBookCLI : Runnable {
    @Option(names = ["--user"], required = true)
    lateinit var userName: String

    @Option(names = ["--password"], required = true, interactive = true)
    lateinit var password: String

    @Option(names = ["--host"])
    var host: String = "localhost:8080"

    @Option(names = ["--id"], required = true)
    var orderId: Int = 0

    @Option(names = ["-t"], description = ["Valid values: \${COMPLETION-CANDIDATES}"], required = true)
    lateinit var type: BuySell

    @Option(names = ["--instrument"], required = true)
    lateinit var instrument: String

    @Option(names = ["--price"], required = true)
    var price: Long = 0L

    @Option(names = ["--quantity"], required = true)
    var quantity: Long = 0L

    @Option(names = ["--and-wait"])
    var andWait: Boolean = false

    override fun run() {
        val client = OrderBookClient(host, userName, password)
        client.start { println(it) }
        client.use {
            val order = Order(orderId, userName, type, instrument, price, quantity)
            client.sendOrder(order)
            if (andWait) {
                while (true) {
                    println(client.waitForActivity(Long.MAX_VALUE, TimeUnit.SECONDS))
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            exitProcess(CommandLine(OrderBookCLI()).execute(*args))
        }
    }
}