package com.r3.conclave.grpc

import com.r3.conclave.client.EnclaveConstraint
import com.r3.conclave.client.InvalidEnclaveException
import com.r3.conclave.common.EnclaveInstanceInfo
import com.r3.conclave.mail.*
import io.grpc.*
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

// TODO: This class is revealing that the mail API isn't there yet.

/**
 * A wrapper around a GRPC [Channel] that verifies a constraint and then allows you to send/receive mail.
 *
 * On calling [start] the client synchronously fetches the [EnclaveInstanceInfo] from the server and
 * verifies it against the [EnclaveConstraint]. Mail is delivered asynchronously, and mail received from the enclave
 * may be 'picked up' in a blocking manner using [waitForMail].
 *
 * @param channelBuilder A builder for RPC channels, e.g. from [ManagedChannelBuilder.forAddress].
 * @param enclaveClassName The Java class name of the `Enclave` subclass in the enclave module.
 * @param constraint A description of what enclave modules will be accepted (see the tutorial for more information).
 * If null then the client imposes no constraint at all, which means your program is insecure. Don't use except for
 * development purposes.
 * @param callOptions The gRPC options for the two calls (getting attestation and setting up the mail stream). Contains
 * timeouts, security options etc.
 */
class ConclaveGRPCClient(private val channelBuilder: ManagedChannelBuilder<*>,
                         private val enclaveClassName: String,
                         private val constraint: EnclaveConstraint?,
                         callOptions: CallOptions = CallOptions.DEFAULT
) : AutoCloseable {
    // TODO: Deadlines need thought: both RPCs are intended to last forever but that means we can't tell if there's been
    //       a connectivity drop?
    private val callOptions = callOptions.withDeadline(null)

    /** Where the client is up to in the connection process. */
    enum class Progress {
        /** Requesting the [EnclaveInstanceInfo] object and verifying it. */
        REMOTE_ATTESTATION,
        /** Requesting the mail stream API. */
        COMPLETING,
        /** Done. */
        COMPLETE
    }


    private var started = false
    private lateinit var channel: ManagedChannel
    private val methods = ConclaveGRPCMethods(enclaveClassName)

    private val events = LinkedBlockingQueue<Any>()
    private lateinit var sender: StreamObserver<ByteArray>  // Protected by 'this'
    private lateinit var postOffice: PostOffice           // Protected by 'this'
    private lateinit var attestation: EnclaveInstanceInfo   // Protected by 'this'

    /**
     * Returns the [EnclaveInstanceInfo] encapsulating the remotely attestation of the enclave identity.
     */
    val enclaveInstanceInfo: EnclaveInstanceInfo
        get() {
            check(this::attestation.isInitialized) { "You may not call this method until the client has been started" }
            return attestation
        }

    /**
     * Starts the client, invoking the [progressCallback] (if not null) as the setup proceeds.
     *
     * @param progressCallback A function that will be invoked with a member of the [Progress] enum as the connection
     * setup proceeds once [start] is called. Note: you may not call other methods on this class until [Progress.COMPLETE]
     * is reached with the exception of [waitForMail], which may be called at any time.
     */
    @JvmOverloads
    @Throws(InvalidEnclaveException::class)
    fun start(progressCallback: Consumer<Progress>? = null) {
        synchronized(this) {
            check(!started) { "You may not call start more than once." }
            progressCallback?.accept(Progress.REMOTE_ATTESTATION)
            channel = channelBuilder.build()
            try {
                // TODO: Log changes in channel state.
                setupEnclaveInstanceInfo()
                constraint?.check(attestation)
                progressCallback?.accept(Progress.COMPLETING)
                val (sender, postOffice) = setupPostOffice()
                this.postOffice = postOffice
                this.sender = sender
            } catch (e: Exception) {
                channel.shutdownNow()
                throw e
            }
            started = true
        }

        // This must be the last thing in the method, after setting started do true. This is so users can put logic
        // that uses the class in the callback.
        progressCallback?.accept(Progress.COMPLETE)
    }

    /**
     * Call when you are done with this client. The connection will be shut down. Note that an in-flight delivery of
     * mail may still occur whilst the caller is blocked in this method.
     */
    override fun close() {
        synchronized(this) {
            if (!started) return
            sender.onCompleted()
            channel.shutdown()
        }
        channel.awaitTermination(Integer.getInteger("com.r3.conclave.grpc.closeTimeout", 5).toLong(), TimeUnit.SECONDS)
    }

    // TODO: Delete "from" header.

    /**
     * Encrypts the given message and sends it to the enclave. This method is thread safe.
     */
    @Synchronized
    fun sendMail(message: ByteArray) {
        check(started) { "You must call start() before using this API." }
        val mail = postOffice.encryptMail(message)
        sender.onNext(mail)
    }

    /**
     * A blocking call to wait for delivery of a mail on this client. The timeout can be specified in the same way as
     * for other utilities in [java.util.concurrent], for example as (1, TimeUnit.SECONDS). This method may be
     * called from multiple threads at once.
     *
     * @return null if the timeout expires, an [EnclaveMail] otherwise.
     * @throws RuntimeException with the underlying exception from gRPC as the cause.
     * @throws InterruptedException if the thread is interrupted.
     */
    @Throws(InterruptedException::class)
    fun waitForMail(timeout: Long, timeUnit: TimeUnit): EnclaveMail? {
        // We don't check for startup here, as there's currently no reason to and it may be convenient to wait for
        // mail before the client is started (or in parallel with it).
        val event = events.poll(timeout, timeUnit) ?: return null
        if (event is EnclaveMail)
            return event
        check(event is Throwable)
        throw RuntimeException(event)
    }

    // TODO: Add a non-blocking interface as well.

    private fun setupEnclaveInstanceInfo() {
        assert(Thread.holdsLock(this))
        val call = channel.newCall(methods.instanceInfosMethodDescriptor, callOptions)
        // The server can send us a stream of attestations as they expire, but we need at least one.
        val attestationFuture = CompletableFuture<EnclaveInstanceInfo>()
        ClientCalls.asyncServerStreamingCall(call, Unit, object : StreamObserver<EnclaveInstanceInfo> {
            override fun onNext(value: EnclaveInstanceInfo) {
                attestationFuture.complete(value)
            }

            override fun onError(t: Throwable) {
                events.add(Exception(t))
                onCompleted()
            }

            override fun onCompleted() {
            }
        })
        // Wait for the attestation to be received or an error to occur.
        attestation = attestationFuture.get()
        println(attestation)
        // If there's anything on the events queue at this point, it must be from onError above.
        events.poll()?.let { throw it as Throwable }
    }

    private fun setupPostOffice(): Pair<StreamObserver<ByteArray>, PostOffice> {
        assert(Thread.holdsLock(this))
        val call = channel.newCall(methods.mailMethodDescriptor, callOptions.withDeadline(null))
        val sender = ClientCalls.asyncBidiStreamingCall(call, object : StreamObserver<ByteArray> {
            override fun onNext(value: ByteArray) {
                try {
                    val postOffice = synchronized(this@ConclaveGRPCClient) { postOffice }
                    events.add(postOffice.decryptMail(value))
                } catch (e: Exception) {
                    events.add(e)
                }
            }

            override fun onError(t: Throwable) {
                events.add(t)
            }

            override fun onCompleted() {
                // Never completes until disconnect.
            }
        })
        val postOffice = attestation.createPostOffice()
        return Pair(sender, postOffice)
    }
}