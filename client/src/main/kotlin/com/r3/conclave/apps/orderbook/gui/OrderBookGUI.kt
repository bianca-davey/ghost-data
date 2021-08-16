package com.r3.conclave.apps.orderbook.gui

import com.r3.conclave.apps.orderbook.OrderBookClient
import com.r3.conclave.apps.orderbook.types.*
import com.r3.conclave.common.EnclaveInstanceInfo
import com.r3.conclave.common.EnclaveSecurityInfo
import com.r3.conclave.grpc.ConclaveGRPCClient
import com.sandec.mdfx.MDFXNode
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.fxml.FXMLLoader
import javafx.geometry.Insets
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.scene.shape.Circle
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.util.converter.CurrencyStringConverter
import kotlinx.serialization.ExperimentalSerializationApi
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private var nextOrderId = 0

fun unreachable(): Nothing = error("Unreachable")

/**
 * Controls a UI loaded from the given FXML file. The UI may generate a result object of some sort when it's closed.
 * A helper method is provided to show the UI in a modal window.
 */
open class Controller<RESULT>(fxmlPath: String, private val title: String) {
    val scene: Scene = run {
        val fxml = FXMLLoader(javaClass.getResource(fxmlPath))
        fxml.setController(this)
        fxml.load()
    }

    protected val writeableResult = ReadOnlyObjectWrapper<RESULT?>()
    fun resultProperty(): ReadOnlyObjectProperty<RESULT?> = writeableResult.readOnlyProperty
    val result: RESULT? get() = resultProperty().get()

    private var stage: Stage? = null

    /**
     * Shows the UI and blocks until the user has closed the window, returning the result object or null if the user
     * cancelled. The UI must have a button or other control that calls [close].
     */
    fun showAndWait(): RESULT? {
        val stage = Stage().also {
            it.title = title
            it.scene = scene
        }
        this.stage = stage
        stage.showAndWait()
        return result
    }

    fun close() {
        stage?.close()
    }
}

open class AuditController(ra: EnclaveInstanceInfo) : Controller<Unit>("/audit.fxml", "Audit Results") {
    lateinit var contentScroll: ScrollPane

    init {
        val markdown = if (ra.securityInfo.summary == EnclaveSecurityInfo.Summary.INSECURE) {
            """
                ## Service is not tamperproof
                
                This service is **not** currently protected from the service operator. It is running in a developer
                diagnostics mode and should not be used with sensitive data.
                
                ${ra.securityInfo.reason}
            """.trimIndent()
        } else {
            val extra = if (ra.securityInfo.summary == EnclaveSecurityInfo.Summary.STALE)
                """
                    **WARNING:** The server is behind on maintenance. There may be attacks the service operator could
                    perform to extract sensitive data. You should contact the service operator and ask them to perform 
                    the necessary maintenance.
                """.trimIndent()
            else
                ""

            """## AuditCorp Ltd

This application receives orders and emits trades. The service operator **can see**:

- When you submit an order.
- Who is submitting orders.
- When a trade matches.

The service operator **cannot see**:

- The contents of an order.
- The contents of a trade.

This policy was checked automatically and is being enforced. $extra

## Technical data

$ra
            """.trimIndent()
        }
        contentScroll.content = MDFXNode(markdown).also {
            it.padding = Insets(20.0)
        }
    }
}

class NewOrderController(
        private val userName: String
) : Controller<Order>("/new-order.fxml", "New Order") {
    lateinit var buyButton: ToggleButton
    lateinit var sellButton: ToggleButton

    init {
        // We're abusing buttons as if they were radio buttons, but JavaFX allows a button to
        // be unselected if just using a regular toggle button. So we have to enforce radio-like
        // behaviour here.
        buyButton.setOnAction { if (!buyButton.isSelected) buyButton.isSelected = true }
        sellButton.setOnAction { if (!sellButton.isSelected) sellButton.isSelected = true }
    }

    lateinit var instrumentField: TextField
    lateinit var priceField: TextField
    private val locale = Locale.US
    private val priceFieldFormatter = TextFormatter(CurrencyStringConverter(locale), 0.0)
    lateinit var quantityField: TextField

    init {
        priceField.textFormatter = priceFieldFormatter
        priceField.setOnKeyTyped {
            if (!priceField.text.startsWith("$")) {
                val pos = priceField.caretPosition
                priceField.text = "$" + priceField.text
                priceField.positionCaret(pos + 1)
            }
        }

        redUnderlineIfBlank(instrumentField)
        redUnderlineIfBlank(quantityField)
    }

    private fun redUnderlineIfBlank(textField: TextField) {
        textField.styleClass += "text-field-invalid"
        textField.setOnKeyTyped {
            if (textField.text.isBlank()) {
                textField.styleClass += "text-field-invalid"
            } else {
                textField.styleClass -= "text-field-invalid"
            }
        }
    }

    private val price get() = (priceFieldFormatter.value.toDouble() * 100).toLong()

    fun ok() {
        val buySell = when {
            buyButton.isSelected -> BuySell.BUY
            sellButton.isSelected -> BuySell.SELL
            else -> unreachable()
        }
        val quantity = NumberFormat.getIntegerInstance().parse(quantityField.text).toLong()
        writeableResult.set(Order(nextOrderId++, userName, buySell, instrumentField.text, price, quantity))
        close()
    }
}

@Suppress("UNCHECKED_CAST")
class MainUIController(userName: String, private val client: OrderBookClient) : Controller<Unit>("/main.fxml", "Order Book Demo") {
    class Item<T : OrderOrTrade>(val date: Instant, val contained: T)

    lateinit var ordersTable: TableView<Item<Order>>
    lateinit var matchesTable: TableView<Item<Trade>>
    lateinit var userButton: MenuButton
    lateinit var photoView: ImageView

    private val orders = FXCollections.observableArrayList<Item<Order>>()
    private val trades = FXCollections.observableArrayList<Item<Trade>>()

    init {
        userButton.text = userName.capitalize()
        matchesTable.items = trades
        ordersTable.items = orders

        if (userName.toLowerCase() == "alice")
            photoView.image = Image("/face1.png")
        else
            photoView.image = Image("/face2.jpg")
        val radius = photoView.boundsInLocal.width / 2.0
        photoView.clip = Circle(radius).also { it.centerX = radius; it.centerY = radius }

        configureOrdersTable()
        configureMatchesTables()
        listenForMatches()
    }

    private fun <T : OrderOrTrade> setColFromString(column: TableColumn<Item<T>, *>?, body: (Item<T>) -> String) {
        (column as TableColumn<Item<T>, String>).setCellValueFactory {
            Bindings.createStringBinding({ body(it.value) })
        }
    }

    private val formatter = NumberFormat.getCurrencyInstance(Locale.US)

    private fun Long.toPriceString(): String = formatter.format(toDouble() / 100.0)
    private fun configureMatchesTables() {
        matchesTable.columns.centerTableColumns()

        setColFromString(matchesTable.columns[0].columns[0]) { it.contained.orderId.toString() }
        setColFromString(matchesTable.columns[0].columns[1]) { it.contained.matchingOrder.id.toString() }
        configureTimestampColumn(matchesTable, 1)
        setColFromString(matchesTable.columns[2]) { it.contained.matchingOrder.party.capitalize() }
        setColFromString(matchesTable.columns[3]) { it.contained.matchingOrder.side.toString() }
        setColFromString(matchesTable.columns[4]) { it.contained.matchingOrder.instrument }
        setColFromString(matchesTable.columns[5]) { it.contained.matchingOrder.price.toPriceString() }
        setColFromString(matchesTable.columns[6]) { it.contained.matchingOrder.quantity.toString() }
    }

    private fun configureOrdersTable() {
        ordersTable.columns.centerTableColumns()

        setColFromString(ordersTable.columns[0]) { it.contained.id.toString() }
        configureTimestampColumn(ordersTable, 1)
        setColFromString(ordersTable.columns[2]) { it.contained.side.toString() }
        setColFromString(ordersTable.columns[3]) { it.contained.instrument }
        setColFromString(ordersTable.columns[4]) { it.contained.price.toPriceString() }
        setColFromString(ordersTable.columns[5]) { it.contained.quantity.toString() }

    }

    private fun <T : OrderOrTrade> List<TableColumn<Item<T>, *>>.centerTableColumns() {
        for (column in this) {
            if (column.columns.isNotEmpty()) {
                // Handle sub-columns.
                column.columns.centerTableColumns()
            } else {
                (column as TableColumn<Item<T>, Any?>).setCellFactory {
                    object : TableCell<Item<T>, Any?>() {
                        override fun updateItem(item: Any?, empty: Boolean) {
                            super.updateItem(item, empty)
                            val quantity = when (val c = tableRow.item?.contained) {
                                null -> 0L
                                is Order -> c.quantity
                                is Trade -> c.matchingOrder.quantity
                                else -> unreachable()
                            }
                            text = if (empty) "" else item?.toString() ?: ""
                            style = "-fx-alignment: center"
                            if (quantity == 0L) style += "; -fx-text-fill: -color-4"
                        }
                    }
                }
            }
        }
    }

    private fun <T : OrderOrTrade> configureTimestampColumn(tableView: TableView<Item<T>>, i: Int) {
        setColFromString(tableView.columns[i]) { it ->
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG).format(it.date.atZone(ZoneId.systemDefault()))
        }
    }

    fun newTrade() {
        // Open the new trade screen in a new window and wait for it to be dismissed.
        val order: Order? = NewOrderController(client.userName).showAndWait()
        if (order != null) {
            client.sendOrder(order)
            orders.add(Item(Instant.now(), order))
        }
    }

    fun showAuditResults() {
        AuditController(client.enclaveInstanceInfo).showAndWait()
    }

    private fun listenForMatches() {
        thread(isDaemon = true, name = "listenForMatches") {
            while (!Thread.interrupted()) {
                try {
                    val orderOrTrade = client.waitForActivity(Long.MAX_VALUE, TimeUnit.SECONDS)!!
                    println(orderOrTrade)
                    Platform.runLater {
                        when (orderOrTrade) {
                            is Trade -> {
                                // Add the trade to the record.
                                val trade = orderOrTrade
                                trades.add(Item(Instant.now(), trade))
                                // Find the order it refers to and adjust it.
                                for (i in orders.indices) {
                                    val order = orders[i].contained
                                    if (order.id == trade.orderId) {
                                        // Update the timestamp, quantity.
                                        val newQuantity = order.quantity - minOf(order.quantity, trade.matchingOrder.quantity)
                                        check(newQuantity >= 0)

                                        orders[i] = Item(Instant.now(), order.copy(
                                                quantity = newQuantity
                                        ))
                                    }
                                }
                            }
                            is Order -> orders.add(Item(Instant.now(), orderOrTrade))
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }
}

class LoginController : Controller<Unit>("/intro.fxml", "Login") {
    lateinit var serviceAddress: TextField
    lateinit var userName: TextField
    lateinit var password: TextField
    lateinit var connectButton: Button

    // The UI shown during connect.
    lateinit var progressLabel: Label
    lateinit var verifyingImage: Parent
    lateinit var communicatingImage: Parent
    lateinit var signinImage: Parent

    init {
        // The UI has some dummy state to make visual editing easier, clear it now.
        progressLabel.text = ""
        verifyingImage.opacity = 0.0
        communicatingImage.opacity = 0.0
        signinImage.opacity = 0.0

        userName.text = "alice"
        password.text = "alice"
        serviceAddress.text = "localhost:9999"
    }

    fun onConnect() {
        connectButton.isDisable = true
        progressLabel.text = "Verifying server behaviour ..."
        verifyingImage.opacity = 1.0
        val task = ConnectTask(serviceAddress.text, userName.text, password.text)
        task.progressEnumProperty.addListener { _, _, new ->
            when (new) {
                ConclaveGRPCClient.Progress.COMPLETING -> {
                    // Setting up the mail stream.
                    communicatingImage.opacity = 1.0
                    progressLabel.text = "Encrypting communication ..."
                }
                ConclaveGRPCClient.Progress.COMPLETE -> {
                    // Connection may be complete, but not the signing in part ...
                    progressLabel.text = "Signing in ..."
                    signinImage.opacity = 1.0
                }
            }
        }
        Thread(task, "Connect Thread").also { it.isDaemon = true }.start()
        task.setOnSucceeded {
            OrderBookGUI.primaryStage.scene = MainUIController(userName.text, client = task.value).scene
        }
        task.setOnFailed {
            throw task.exception
        }
    }
}

class OrderBookGUI : Application() {
    override fun start(primaryStage: Stage) {
        for (w in listOf("Regular", "Bold", "BoldItalic", "Italic", "SemiBold", "SemiBoldItalic"))
            checkNotNull(Font.loadFont(javaClass.getResourceAsStream("/fonts/Nunito/Nunito-$w.ttf"), 0.0))

        OrderBookGUI.primaryStage = primaryStage
        primaryStage.title = "Conclave Order Book"
        primaryStage.scene = LoginController().scene
        primaryStage.isMaximized = true
        primaryStage.show()

        // These two lines tell JFX that we don't want to resize the window to fit the scene as it changes.
        // Without it, the window would change size as we navigated between the login and main screens.
        primaryStage.width = primaryStage.width
        primaryStage.height = primaryStage.height

        // Pop up a message on crash.
        Thread.setDefaultUncaughtExceptionHandler { _, original ->
            println("Error in thread " + Thread.currentThread().name)
            original.printStackTrace()
            var e = original
            while (e.cause != null) e = e.cause
            Platform.runLater {
                Alert(Alert.AlertType.ERROR, e.message, ButtonType.CLOSE).showAndWait()
                exitProcess(1)
            }
        }
    }

    companion object {
        lateinit var primaryStage: Stage

        @JvmStatic
        fun main(args: Array<String>) = launch(OrderBookGUI::class.java, *args)
    }
}

class SignInException(response: SignInResponse) : Exception("Sign in failed: ${response.errorMessage}")


class ConnectTask(
        private val serviceAddress: String,
        private val userName: String,
        private val password: String
) : Task<OrderBookClient>() {
    private val _progressEnumProperty = ReadOnlyObjectWrapper<ConclaveGRPCClient.Progress?>(this, "progressEnum")
    val progressEnumProperty: ReadOnlyObjectProperty<ConclaveGRPCClient.Progress?> = _progressEnumProperty.readOnlyProperty

    @OptIn(ExperimentalSerializationApi::class)
    override fun call(): OrderBookClient {
        val client = OrderBookClient(serviceAddress, userName, password)
        client.start { Platform.runLater { _progressEnumProperty.set(it) } }
        return client
    }
}