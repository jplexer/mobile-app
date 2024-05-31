package io.rebble.cobble.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import io.rebble.cobble.bluetooth.ble.util.GattCharacteristicBuilder
import io.rebble.cobble.bluetooth.ble.util.GattDescriptorBuilder
import io.rebble.cobble.bluetooth.ble.util.GattServiceBuilder
import io.rebble.libpebblecommon.ble.LEConstants
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.CoroutineContext

class PPoGService(private val scope: CoroutineScope) : GattService {
    private val dataCharacteristic = GattCharacteristicBuilder()
            .withUuid(UUID.fromString(LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER))
            .withProperties(BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
            .withPermissions(BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED)
            .addDescriptor(
                    GattDescriptorBuilder()
                            .withUuid(UUID.fromString(LEConstants.UUIDs.CHARACTERISTIC_CONFIGURATION_DESCRIPTOR))
                            .withPermissions(BluetoothGattCharacteristic.PERMISSION_WRITE)
                            .build()
            )
            .build()

    private val metaCharacteristic = GattCharacteristicBuilder()
            .withUuid(UUID.fromString(LEConstants.UUIDs.META_CHARACTERISTIC_SERVER))
            .withProperties(BluetoothGattCharacteristic.PROPERTY_READ)
            .withPermissions(BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
            .build()

    private val bluetoothGattService = GattServiceBuilder()
            .withType(BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .withUuid(UUID.fromString(LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER))
            .addCharacteristic(metaCharacteristic)
            .addCharacteristic(dataCharacteristic)
            .build()

    private val ppogConnections = mutableMapOf<String, PPoGServiceConnection>()
    private var gattServer: GattServer? = null
    private val deviceRxFlow = MutableSharedFlow<PPoGConnectionEvent>(replay = 1)
    private val deviceTxFlow = MutableSharedFlow<Pair<BluetoothDevice, ByteArray>>()

    /**
     * Filter flow for events related to a specific device
     * @param deviceAddress Address of the device to filter for
     * @return Function to filter events, used in [Flow.filter]
     */
    private fun filterFlowForDevice(deviceAddress: String) = { event: ServerEvent ->
        when (event) {
            is ServiceEvent -> event.device.address == deviceAddress
            else -> false
        }
    }

    open class PPoGConnectionEvent(val device: BluetoothDevice) {
        class LinkError(device: BluetoothDevice, val error: Throwable) : PPoGConnectionEvent(device)
        class PacketReceived(device: BluetoothDevice, val packet: ByteArray) : PPoGConnectionEvent(device)
    }

    private suspend fun runService(eventFlow: SharedFlow<ServerEvent>) {
        eventFlow.collect {
            when (it) {
                is CharacteristicReadEvent -> {
                    if (it.characteristic.uuid == metaCharacteristic.uuid) {
                        Timber.d("Meta characteristic read request")
                        it.respond(CharacteristicResponse(BluetoothGatt.GATT_SUCCESS, 0, LEConstants.SERVER_META_RESPONSE))
                    } else {
                        Timber.w("Unknown characteristic read request: ${it.characteristic.uuid}")
                        it.respond(CharacteristicResponse.Failure)
                    }
                }
                is ServerInitializedEvent -> {
                    Timber.d("Server initialized")
                    gattServer = it.server
                }
                is ConnectionStateEvent -> {
                    if (gattServer == null) {
                        Timber.w("Server not initialized yet")
                        return@collect
                    }
                    Timber.d("Connection state changed: ${it.newState} for device ${it.device.address}")
                    if (it.newState == GattConnectionState.Connected) {
                        if (ppogConnections.containsKey(it.device.address)) {
                            Timber.w("Connection already exists for device ${it.device.address}")
                            ppogConnections[it.device.address]?.resetDebouncedClose()
                            return@collect
                        }

                        if (ppogConnections.isEmpty()) {
                            Timber.d("Creating new connection for device ${it.device.address}")
                            val supervisor = SupervisorJob(scope.coroutineContext[Job])
                            val connectionScope = CoroutineScope(scope.coroutineContext + supervisor)
                            val connection = PPoGServiceConnection(
                                    connectionScope,
                                    this@PPoGService,
                                    it.device,
                                    eventFlow
                                        .onSubscription {
                                            Timber.d("Subscription started for device ${it.device.address}")
                                        }
                                        .buffer()
                                        .filterIsInstance<ServiceEvent>()
                                        .filter(filterFlowForDevice(it.device.address))
                            )
                            connectionScope.launch {
                                Timber.d("Starting connection for device ${it.device.address}")
                                connection.start().collect { packet ->
                                    deviceRxFlow.emit(PPoGConnectionEvent.PacketReceived(it.device, packet))
                                }
                            }
                            ppogConnections[it.device.address] = connection
                        } else {
                            //TODO: Handle multiple connections
                            Timber.w("Multiple connections not supported yet")
                        }
                    } else if (it.newState == GattConnectionState.Disconnected) {
                        if (ppogConnections[it.device.address]?.debouncedClose() == true) {
                            Timber.d("Connection for device ${it.device.address} closed")
                            ppogConnections.remove(it.device.address)
                        }
                    }
                }
            }
        }
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    suspend fun sendData(device: BluetoothDevice, data: ByteArray): Boolean {
        return gattServer?.let { server ->
            val result = scope.async {
                server.getFlow()
                        .filterIsInstance<NotificationSentEvent>()
                        .onEach { Timber.d("Notification sent: ${it.device.address}") }
                        .first { it.device.address == device.address }
            }
            server.notifyCharacteristicChanged(device, dataCharacteristic, false, data)
            val res = result.await().status == BluetoothGatt.GATT_SUCCESS
            res
        } ?: false
    }

    @SuppressLint("MissingPermission")
    override fun register(eventFlow: SharedFlow<ServerEvent>): BluetoothGattService {
        scope.launch {
            runService(eventFlow)
        }
        scope.launch {
            deviceTxFlow.buffer(8).collect {
                val connection = ppogConnections[it.first.address]
                connection?.sendPebblePacket(it.second)
                        ?: Timber.w("No connection for device ${it.first.address}")
            }
        }
        return bluetoothGattService
    }

    fun rxFlowFor(device: BluetoothDevice): Flow<PPoGConnectionEvent> {
        return deviceRxFlow.filter { it.device.address == device.address }
    }

    suspend fun emitPacket(device: BluetoothDevice, packet: ByteArray) {
        deviceTxFlow.emit(Pair(device, packet))
    }
}