@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.r3.conclave.apps.orderbook.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

interface Message

@Serializable
data class SignInRequest(
        @ProtoNumber(1) val userName: String,
        @ProtoNumber(2) val password: String
) : Message

@Serializable
class SignInResponse(
        @ProtoNumber(1) val errorMessage: String? = null
) : Message

@Serializable
enum class BuySell { BUY, SELL }

@Serializable
sealed class OrderOrTrade

@Serializable
data class Order(
        @ProtoNumber(1) val id: Int,
        @ProtoNumber(2) val party: String,
        @ProtoNumber(3) val side: BuySell,
        @ProtoNumber(4) val instrument: String,
        @ProtoNumber(5) val price: Long,
        @ProtoNumber(6) val quantity: Long
) : OrderOrTrade()

@Serializable
data class Trade(
        @ProtoNumber(1) val orderId: Int,
        @ProtoNumber(2) val instrument: String,
        @ProtoNumber(3) val matchingOrder: Order
) : OrderOrTrade()