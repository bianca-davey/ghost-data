@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.r3.conclave.apps.orderbook.enclave

import com.r3.conclave.apps.orderbook.types.*
import com.r3.conclave.common.SHA256Hash
import com.r3.conclave.enclave.Enclave
import com.r3.conclave.mail.EnclaveMail
import kotlinx.serialization.protobuf.ProtoBuf
import java.lang.IllegalArgumentException
import java.security.PublicKey
import java.util.*
import kotlin.collections.LinkedHashMap

/**
 * Implements a dark pool enclave, with basic user sign-in support.
 */
class OrderBookEnclave : Enclave() {
    // A user "signs in" by presenting a username/password pair that was previously unknown.
    private inner class UserData(
            val userName: String,
            val password: String,
            val publicKey: PublicKey,
            private val routingHint: String
    ) {
        fun mailUser(bytes: ByteArray) {
            postMail(
                postOffice(publicKey).encryptMail(bytes),
                routingHint
            )
        }
    }

    init {
        println("Hello!")
    }

    private val usernameToUserData = object : LinkedHashMap<String, UserData>() {
        fun findByKey(publicKey: PublicKey) = values.firstOrNull {
            it.publicKey == publicKey
        }
    }

    private val orderBook = LinkedList<Order>()

    // If true then we send all orders to everyone.
    private var transparent = false

    @Synchronized
    override fun receiveMail(id: Long, mail: EnclaveMail, routingHint: String?) {
        checkNotNull(routingHint)

        val user: UserData = lookupOrCreateAccount(mail, routingHint) ?: return
        val order: Order = decodeOrder(mail, user)
        val trades: List<Pair<Trade, Trade>> = matchOrderAndUpdateBook(order)

        if (transparent)
            notifyAllUsersOfOrder(user, order)

        // Notify the users of crossed trades.
        fun sendTrade(a: Trade, b: Trade) {
            val userDataB = usernameToUserData[b.matchingOrder.party]!!
            userDataB.mailUser(ProtoBuf.encodeToByteArray(OrderOrTrade.serializer(), a), )
        }

        for ((a, b) in trades) {
            sendTrade(a, b)
            sendTrade(b, a)
        }
    }

    private fun decodeOrder(mail: EnclaveMail, user: UserData): Order {
        val order = ProtoBuf.decodeFromByteArray(Order.serializer(), mail.bodyAsBytes)
        // If signed in, should be only sending us order messages.
        require(order.party == user.userName) {
            "Order submitted with party ${order.party} but by user signed in as ${user.userName}"
        }
        return order
    }

    private fun notifyAllUsersOfOrder(user: UserData, order: Order) {
        for (u in usernameToUserData.values) {
            // Don't mail the user back with their own order.
            if (u == user) continue
            u.mailUser(ProtoBuf.encodeToByteArray(OrderOrTrade.serializer(), order))
        }
    }

    private fun matchOrderAndUpdateBook(orderToMatch: Order): List<Pair<Trade, Trade>> {
        var order = orderToMatch
        val trades = LinkedList<Pair<Trade, Trade>>()
        val iterator = orderBook.iterator()
        while (iterator.hasNext()) {
            val cursor = iterator.next()
            if (matches(order, cursor)) {
                val quantity = minOf(order.quantity, cursor.quantity)
                trades += makeTrades(order, cursor, quantity)
                order = order.copy(quantity = order.quantity - quantity)
                iterator.remove()
                if (cursor.quantity - quantity != 0L) {
                    orderBook.add(cursor.copy(quantity = cursor.quantity - quantity))
                    return trades
                }
            }
        }
        check(order.quantity > 0) { order.quantity }
        orderBook.add(order)
        return trades
    }

    private fun matches(a: Order, b: Order): Boolean {
        if (a.party == b.party || a.instrument != b.instrument || a.side == b.side) return false
        val (buy, sell) = if (a.side == BuySell.BUY) Pair(a, b) else Pair(b, a)
        return buy.price >= sell.price
    }

    private fun makeTrades(a: Order, b: Order, quantity: Long): List<Pair<Trade, Trade>> {
        check(a.instrument == b.instrument) { "${a.instrument} / ${b.instrument}" }
        val tradeA = Trade(a.id, a.instrument, b.copy(quantity = quantity))
        val tradeB = Trade(b.id, b.instrument, a.copy(quantity = quantity))
        return listOf(Pair(tradeA, tradeB))
    }

    /**
     * Handles user authentication. If the public key that sent the user is known then return the user data for it.
     * If it's not, the mail should contain a valid sign-in request, which will be checked against the details used
     * last time. If there's a prior sign-in from that user the password must match, otherwise an exception is thrown.
     * If there's no known prior sign-in then we create the user.
     */
    private fun lookupOrCreateAccount(request: EnclaveMail, routingHint: String): UserData? {

        // If we've seen this pubkey before and they're authenticated already, return the user data.
        usernameToUserData.findByKey(request.authenticatedSender)?.let { return it }

        // Not seen this pubkey before (new client instance). First message should be a sign in.
        val signIn = ProtoBuf.decodeFromByteArray(SignInRequest.serializer(), request.bodyAsBytes)
        val user = usernameToUserData[signIn.userName]
        if (user != null) {
            // User has signed in before from a different client. Check the password matches.
            if (!comparePasswords(signIn.password, user.password)) {
                val reply = ProtoBuf.encodeToByteArray(SignInResponse.serializer(), SignInResponse("Invalid password"))
                postMail(postOffice(request).encryptMail(reply), routingHint)
                return null   // Handled
            }

            // Otherwise the password is a match, but the client is restarted and has a new key (or two instances
            // are trying to sign in simultaneously but we don't support that yet).
            //
            // TODO: Support multiple simultaneous instances.
        }

        // If we get here either the client is restarted and successfully re-authed, or the user has never signed in
        // before, either way we re-create the sign-in record with the submitted data.
        //
        // The mutable mail will be re-used to send replies (to ensure sequencing).
        val userData = UserData(
                signIn.userName,
                signIn.password,
                request.authenticatedSender,
                routingHint
        )
        usernameToUserData[signIn.userName] = userData
        userData.mailUser(ProtoBuf.encodeToByteArray(SignInResponse.serializer(), SignInResponse(null)))

        if (transparent) {
            // Now also send all the orders.
            for (order in orderBook) {
                userData.mailUser(ProtoBuf.encodeToByteArray(OrderOrTrade.serializer(), order))
            }
        }

        return null   // Handled
    }

    private fun comparePasswords(password1: String, password2: String): Boolean {
        var bytes1 = password1.encodeToByteArray()
        var bytes2 = password2.encodeToByteArray()

        // Grind a bit to slow down the host if it tries to brute force a login.
        for (i in 0..1024*1024) {
            bytes1 = SHA256Hash.hash(bytes1).bytes
            bytes2 = SHA256Hash.hash(bytes2).bytes
        }

        return ConstantTime.compare(bytes1, bytes2)
    }
}

/**
 * Utilities for doing operations in constant time.
 */
object ConstantTime {
    /**
     * Compares two byte arrays such that the timing doesn't leak anything about the contents of the arrays. Even if the
     * very first byte matches, all bytes will be compared regardless. When buffer sizes don't match the number of
     * comparisons is always equal to the largest size.
     */
    fun compare(first: ByteArray, second: ByteArray): Boolean {
        var answer = false
        for (i in 0 until maxOf(first.size, second.size)) {
            val a: Byte = first[minOf(i, first.size - 1)]
            val b: Byte = second[minOf(i, second.size - 1)]
            answer = answer or (a == b)
        }
        return answer
    }
}
