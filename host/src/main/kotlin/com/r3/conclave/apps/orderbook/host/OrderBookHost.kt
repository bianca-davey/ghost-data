@file:JvmName("OrderBookHost")
package com.r3.conclave.apps.orderbook.host

import com.r3.conclave.grpc.ConclaveGRPCHost
import com.r3.conclave.host.AttestationParameters
import com.r3.conclave.host.EnclaveHost
import io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit


fun main() {
    // Load the enclave.
    val enclaveClass = "com.r3.conclave.apps.orderbook.enclave.OrderBookEnclave"
    val (enclave, attestationParameters) = Pair(EnclaveHost.load(enclaveClass), AttestationParameters.DCAP())

    // Start up the gRPC server and enclave.
    val port = Integer.getInteger("port", 9999)
    println("Starting order book server on port $port")
    val server = NettyServerBuilder
            .forPort(port)
            .permitKeepAliveTime(30, TimeUnit.SECONDS)
            .addService(ConclaveGRPCHost(enclave, attestationParameters).service)
            .build()

    server.start()
    Thread.sleep(Long.MAX_VALUE)
    server.shutdownNow()
}
