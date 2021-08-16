@file:Suppress("EXPERIMENTAL_API_USAGE", "SameParameterValue")
package com.r3.conclave.apps.orderbook

import com.r3.conclave.apps.orderbook.gui.SignInException
import com.r3.conclave.apps.orderbook.types.*
import com.r3.conclave.client.EnclaveConstraint
import com.r3.conclave.common.EnclaveInstanceInfo
import com.r3.conclave.grpc.ConclaveGRPCClient
import io.grpc.ManagedChannelBuilder
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.concurrent.TimeUnit

class OrderBookClient(
        private val serviceAddress: String,
        val userName: String,
        private val password: String
) : AutoCloseable {
    private lateinit var rpcClient: ConclaveGRPCClient

    fun start(progressCallback: ((t: ConclaveGRPCClient.Progress) -> Unit)? = null) {
        val (host, port) = parseHostPort(serviceAddress, 9999)
        val channel = ManagedChannelBuilder
                .forAddress(host, port)
                .keepAliveTime(60, TimeUnit.SECONDS)
                .usePlaintext()   // for debugging

        val constraint = "S:4924CA3A9C8241A3C0AA1A24A407AA86401D2B79FA9FF84932DA798A942166D4 S:0000000000000000000000000000000000000000000000000000000000000000" +
                " PROD:1 SEC:INSECURE"
        rpcClient = ConclaveGRPCClient(
                channel,
                "com.r3.conclave.apps.orderbook.enclave.OrderBookEnclave",
                EnclaveConstraint.parse(System.getProperty("com.r3.conclave.apps.orderbook.constraint", constraint))
        )
        rpcClient.start(progressCallback)

        // Now sign in.
        val response: SignInResponse = rpcClient.sendAndReceive(SignInRequest(userName, password))!!
        if (response.errorMessage != null)
            throw SignInException(response)
    }

    override fun close() {
        rpcClient.close()
    }

    val enclaveInstanceInfo: EnclaveInstanceInfo get() = rpcClient.enclaveInstanceInfo

    private fun parseHostPort(serviceAddress: String, defaultPort: Int): Pair<String, Int> {
        val parts = serviceAddress.trim().split(':')
        require(parts.size <= 2) { "Invalid host/port address, more than 1 colon" }
        val port = if (parts.size == 2) parts[1].toInt() else defaultPort
        return Pair(parts[0], port)
    }

    fun sendOrder(order: Order) {
        rpcClient.sendMail(ProtoBuf.encodeToByteArray(order))
    }

    fun waitForActivity(timeout: Long, timeUnit: TimeUnit): OrderOrTrade? {
        val mail = rpcClient.waitForMail(timeout, timeUnit) ?: return null
        // We must explicitly specify the type variable because the protobuf
        // encoding of a top-level T? is different to T.
        return ProtoBuf.decodeFromByteArray<OrderOrTrade>(mail.bodyAsBytes)
    }

    /** A simple blocking utility to swap request and response. Returns null if there's a timeout (uses default RPC timeout from [ConclaveGRPCClient.callOptions]). */
    private inline fun <reified REQ : Message, reified RESP : Message> ConclaveGRPCClient.sendAndReceive(request: REQ): RESP? {
        sendMail(ProtoBuf.encodeToByteArray(request))
        val mail = waitForMail(5, TimeUnit.SECONDS) ?: return null
        val bytes = mail.bodyAsBytes
        return ProtoBuf.decodeFromByteArray<RESP>(bytes)
    }
}