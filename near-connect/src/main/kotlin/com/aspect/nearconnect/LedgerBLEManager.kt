package com.aspect.nearconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages BLE communication with a Ledger hardware wallet device.
 *
 * Implements the Ledger APDU exchange protocol over Bluetooth Low Energy,
 * using the standard Ledger BLE service and characteristics.
 */
class LedgerBLEManager(private val context: Context) {

    // MARK: - Published State

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _discoveredDevices = MutableStateFlow<List<LedgerDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<LedgerDevice>> = _discoveredDevices

    private val _connectedDevice = MutableStateFlow<LedgerDevice?>(null)
    val connectedDevice: StateFlow<LedgerDevice?> = _connectedDevice

    private val _isBluetoothReady = MutableStateFlow(false)
    val isBluetoothReady: StateFlow<Boolean> = _isBluetoothReady

    data class LedgerDevice(
        val id: String,
        val name: String,
        val device: BluetoothDevice,
    )

    // MARK: - Ledger BLE Protocol Constants

    private data class DeviceBLE(
        val name: String,
        val serviceUUID: UUID,
        val notifyUUID: UUID,
        val writeUUID: UUID,
        val writeCmdUUID: UUID,
    )

    // MARK: - Private State

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCmdCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDeviceBLE: DeviceBLE? = null

    private var mtuSize: Int = DEFAULT_MTU_SIZE

    private var responseBuffer = ByteArray(0)
    private var expectedResponseLength = 0
    private var responseSequence = 0

    private var mtuContinuation: CancellableContinuation<Int>? = null
    private var exchangeContinuation: CancellableContinuation<ByteArray>? = null
    private var connectContinuation: CancellableContinuation<Unit>? = null

    init {
        _isBluetoothReady.value = bluetoothManager?.adapter?.isEnabled == true
    }

    // MARK: - Public API

    @SuppressLint("MissingPermission")
    fun startScanning() {
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled")
            return
        }

        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        val filters = ALL_SERVICE_UUIDS.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: LedgerDevice) {
        stopScanning()
        return suspendCancellableCoroutine { cont ->
            connectContinuation = cont
            cont.invokeOnCancellation {
                gatt?.disconnect()
                cleanUpConnection()
            }
            gatt = device.device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        cleanUpConnection()
    }

    @SuppressLint("MissingPermission")
    suspend fun negotiateMTU(): Unit {
        if (gatt == null) throw LedgerBLEError.NotConnected()

        val negotiatedMTU = suspendCancellableCoroutine { cont: CancellableContinuation<Int> ->
            mtuContinuation = cont
            val mtuRequest = byteArrayOf(0x08, 0x00, 0x00, 0x00, 0x00)
            writeToDevice(mtuRequest)
        }

        if (negotiatedMTU > DEFAULT_MTU_SIZE) {
            mtuSize = negotiatedMTU
        }
        Log.d(TAG, "Negotiated MTU size: $mtuSize")
    }

    @SuppressLint("MissingPermission")
    suspend fun exchange(apdu: ByteArray): ByteArray {
        if (gatt == null) throw LedgerBLEError.NotConnected()

        responseBuffer = ByteArray(0)
        expectedResponseLength = 0
        responseSequence = 0

        return suspendCancellableCoroutine { cont ->
            exchangeContinuation = cont
            val frames = frameAPDU(apdu)
            for (frame in frames) {
                writeToDevice(frame)
            }
        }
    }

    // MARK: - Writing

    @SuppressLint("MissingPermission")
    private fun writeToDevice(data: ByteArray) {
        val g = gatt ?: return
        val characteristic = writeCmdCharacteristic ?: writeCharacteristic ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = if (writeCmdCharacteristic != null) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            g.writeCharacteristic(characteristic, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = if (writeCmdCharacteristic != null) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
    }

    // MARK: - APDU Framing

    private fun frameAPDU(apdu: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        var sequenceIndex = 0

        // First frame: 5-byte header (tag + seq + length)
        val firstHeaderSize = 5
        val firstChunkSize = minOf(apdu.size, mtuSize - firstHeaderSize)
        val firstFrame = ByteArray(firstHeaderSize + firstChunkSize)
        firstFrame[0] = 0x05 // tag
        firstFrame[1] = (sequenceIndex shr 8).toByte()
        firstFrame[2] = (sequenceIndex and 0xFF).toByte()
        firstFrame[3] = (apdu.size shr 8).toByte()
        firstFrame[4] = (apdu.size and 0xFF).toByte()
        System.arraycopy(apdu, offset, firstFrame, firstHeaderSize, firstChunkSize)
        frames.add(firstFrame)
        offset += firstChunkSize
        sequenceIndex++

        // Subsequent frames: 3-byte header (tag + seq)
        while (offset < apdu.size) {
            val headerSize = 3
            val chunkSize = minOf(apdu.size - offset, mtuSize - headerSize)
            val frame = ByteArray(headerSize + chunkSize)
            frame[0] = 0x05 // tag
            frame[1] = (sequenceIndex shr 8).toByte()
            frame[2] = (sequenceIndex and 0xFF).toByte()
            System.arraycopy(apdu, offset, frame, headerSize, chunkSize)
            frames.add(frame)
            offset += chunkSize
            sequenceIndex++
        }

        return frames
    }

    private fun handleNotification(data: ByteArray) {
        if (data.isEmpty()) return

        val tag = data[0].toInt() and 0xFF

        // MTU negotiation response (tag 0x08)
        if (tag == 0x08) {
            if (data.size >= 6) {
                val negotiatedMTU = data[5].toInt() and 0xFF
                Log.d(TAG, "MTU negotiation response: $negotiatedMTU")
                mtuContinuation?.resume(negotiatedMTU)
                mtuContinuation = null
            }
            return
        }

        // APDU response frame (tag 0x05)
        if (tag != 0x05) {
            Log.w(TAG, "Unknown notification tag: 0x${tag.toString(16)}")
            return
        }
        if (data.size < 3) return

        val seq = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)

        if (seq == 0) {
            // First frame: [tag] [seq(2)] [length(2)] [data...]
            if (data.size < 5) return
            expectedResponseLength = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
            responseBuffer = ByteArray(0)
            responseSequence = 0

            if (data.size > 5) {
                responseBuffer = data.copyOfRange(5, data.size)
            }
        } else {
            // Continuation frame: [tag] [seq(2)] [data...]
            if (data.size > 3) {
                responseBuffer = responseBuffer + data.copyOfRange(3, data.size)
            }
        }

        responseSequence++

        if (responseBuffer.size >= expectedResponseLength && expectedResponseLength > 0) {
            val response = responseBuffer.copyOfRange(0, expectedResponseLength)
            Log.d(TAG, "APDU response complete: ${response.size} bytes")
            exchangeContinuation?.resume(response)
            exchangeContinuation = null
        }
    }

    // MARK: - BLE Callbacks

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "Ledger Device"
            val id = device.address
            val current = _discoveredDevices.value
            if (current.none { it.id == id }) {
                _discoveredDevices.value = current + LedgerDevice(id = id, name = name, device = device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    cleanUpConnection()
                    exchangeContinuation?.resumeWithException(LedgerBLEError.Disconnected())
                    exchangeContinuation = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectContinuation?.resumeWithException(LedgerBLEError.ServiceNotFound())
                connectContinuation = null
                return
            }

            // Find the first Ledger service that matches any supported device model
            var matchedService: BluetoothGattService? = null
            var matchedSpec: DeviceBLE? = null
            for (service in gatt.services) {
                val spec = deviceBLE(service.uuid)
                if (spec != null) {
                    matchedService = service
                    matchedSpec = spec
                    break
                }
            }

            if (matchedService == null || matchedSpec == null) {
                connectContinuation?.resumeWithException(LedgerBLEError.ServiceNotFound())
                connectContinuation = null
                return
            }

            connectedDeviceBLE = matchedSpec
            Log.d(TAG, "Discovered Ledger ${matchedSpec.name} service")

            for (char in matchedService.characteristics) {
                when (char.uuid) {
                    matchedSpec.writeUUID -> {
                        writeCharacteristic = char
                        Log.d(TAG, "Found write characteristic (0002)")
                    }

                    matchedSpec.writeCmdUUID -> {
                        writeCmdCharacteristic = char
                        Log.d(TAG, "Found writeCmd characteristic (0003)")
                    }

                    matchedSpec.notifyUUID -> {
                        notifyCharacteristic = char
                        gatt.setCharacteristicNotification(char, true)
                        // Enable notifications via descriptor
                        val descriptor = char.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(
                                    descriptor,
                                    BluetoothGattCharacteristic.ENABLE_NOTIFICATION_VALUE,
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattCharacteristic.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                        Log.d(TAG, "Found notify characteristic (0001), subscribing")
                    }
                }
            }

            val hasWrite = writeCharacteristic != null || writeCmdCharacteristic != null
            if (hasWrite && notifyCharacteristic != null) {
                val devices = _discoveredDevices.value
                _connectedDevice.value = devices.firstOrNull {
                    it.device.address == gatt.device.address
                } ?: LedgerDevice(
                    id = gatt.device.address,
                    name = gatt.device.name ?: "Ledger",
                    device = gatt.device,
                )
                connectContinuation?.resume(Unit)
                connectContinuation = null
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == connectedDeviceBLE?.notifyUUID) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                Log.d(TAG, "Notification: ${data.joinToString(" ") { "%02x".format(it) }}")
                handleNotification(data)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == connectedDeviceBLE?.notifyUUID) {
                Log.d(TAG, "Notification: ${value.joinToString(" ") { "%02x".format(it) }}")
                handleNotification(value)
            }
        }
    }

    private fun cleanUpConnection() {
        _connectedDevice.value = null
        connectedDeviceBLE = null
        writeCharacteristic = null
        writeCmdCharacteristic = null
        notifyCharacteristic = null
        mtuSize = DEFAULT_MTU_SIZE
        responseBuffer = ByteArray(0)
        expectedResponseLength = 0
        responseSequence = 0
    }

    companion object {
        private const val TAG = "LedgerBLE"
        private const val DEFAULT_MTU_SIZE = 20

        /**
         * All BLE-capable Ledger device models and their UUIDs.
         * UUID pattern: 13D63400-2C97-{model}04-{role}-4C6564676572
         */
        private val SUPPORTED_DEVICES = listOf(
            DeviceBLE(
                name = "Nano X",
                serviceUUID = UUID.fromString("13D63400-2C97-0004-0000-4C6564676572"),
                notifyUUID = UUID.fromString("13D63400-2C97-0004-0001-4C6564676572"),
                writeUUID = UUID.fromString("13D63400-2C97-0004-0002-4C6564676572"),
                writeCmdUUID = UUID.fromString("13D63400-2C97-0004-0003-4C6564676572"),
            ),
            DeviceBLE(
                name = "Stax",
                serviceUUID = UUID.fromString("13D63400-2C97-6004-0000-4C6564676572"),
                notifyUUID = UUID.fromString("13D63400-2C97-6004-0001-4C6564676572"),
                writeUUID = UUID.fromString("13D63400-2C97-6004-0002-4C6564676572"),
                writeCmdUUID = UUID.fromString("13D63400-2C97-6004-0003-4C6564676572"),
            ),
            DeviceBLE(
                name = "Flex",
                serviceUUID = UUID.fromString("13D63400-2C97-3004-0000-4C6564676572"),
                notifyUUID = UUID.fromString("13D63400-2C97-3004-0001-4C6564676572"),
                writeUUID = UUID.fromString("13D63400-2C97-3004-0002-4C6564676572"),
                writeCmdUUID = UUID.fromString("13D63400-2C97-3004-0003-4C6564676572"),
            ),
            DeviceBLE(
                name = "Nano Gen5",
                serviceUUID = UUID.fromString("13D63400-2C97-8004-0000-4C6564676572"),
                notifyUUID = UUID.fromString("13D63400-2C97-8004-0001-4C6564676572"),
                writeUUID = UUID.fromString("13D63400-2C97-8004-0002-4C6564676572"),
                writeCmdUUID = UUID.fromString("13D63400-2C97-8004-0003-4C6564676572"),
            ),
            DeviceBLE(
                name = "Nano Gen5",
                serviceUUID = UUID.fromString("13D63400-2C97-9004-0000-4C6564676572"),
                notifyUUID = UUID.fromString("13D63400-2C97-9004-0001-4C6564676572"),
                writeUUID = UUID.fromString("13D63400-2C97-9004-0002-4C6564676572"),
                writeCmdUUID = UUID.fromString("13D63400-2C97-9004-0003-4C6564676572"),
            ),
        )

        private val ALL_SERVICE_UUIDS = SUPPORTED_DEVICES.map { it.serviceUUID }

        private fun deviceBLE(serviceUUID: UUID): DeviceBLE? =
            SUPPORTED_DEVICES.firstOrNull { it.serviceUUID == serviceUUID }
    }
}

/**
 * Errors specific to Ledger BLE communication.
 */
sealed class LedgerBLEError(override val message: String) : Exception(message) {
    class NotConnected : LedgerBLEError("Ledger device is not connected")
    class ConnectionFailed : LedgerBLEError("Failed to connect to Ledger device")
    class Disconnected : LedgerBLEError("Ledger device disconnected")
    class ServiceNotFound : LedgerBLEError("Ledger BLE service not found on device")
    class CharacteristicNotFound : LedgerBLEError("Ledger BLE characteristic not found")
    class ExchangeTimeout : LedgerBLEError("Ledger APDU exchange timed out")
    class BluetoothNotAvailable : LedgerBLEError("Bluetooth is not available")
}
