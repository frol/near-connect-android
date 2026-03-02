package com.aspect.nearconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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

    private var bondReceiver: BroadcastReceiver? = null

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

        // Use an unfiltered scan to maximise discovery.  Some Ledger models
        // (particularly Flex) only advertise the service UUID in the scan
        // response, which Android's filtered scan may miss.  We filter in
        // the onScanResult callback instead.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: LedgerDevice) {
        stopScanning()

        // Ledger devices use LE Secure Connections with Numeric Comparison.
        // If the device is not yet bonded, call createBond() first so that
        // the system shows its pairing dialog.  Without this, connectGatt()
        // triggers pairing at the link layer and the dialog can end up
        // hidden behind the app's full-screen UI.
        if (device.device.bondState != BluetoothDevice.BOND_BONDED) {
            ensureBonded(device.device)
        }

        return suspendCancellableCoroutine { cont ->
            connectContinuation = cont
            cont.invokeOnCancellation {
                gatt?.disconnect()
                cleanUpConnection()
            }
            gatt = device.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
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

    // MARK: - Bonding

    /**
     * Ensure the device is bonded (paired) before opening a GATT connection.
     * Suspends until the system pairing flow completes or fails.
     */
    @SuppressLint("MissingPermission")
    private suspend fun ensureBonded(device: BluetoothDevice) {
        // Already bonded — nothing to do
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val bondDevice: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (bondDevice?.address != device.address) return

                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.d(TAG, "Bonding succeeded")
                            unregisterBondReceiver()
                            cont.resume(Unit)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            Log.w(TAG, "Bonding failed or was cancelled")
                            unregisterBondReceiver()
                            cont.resumeWithException(LedgerBLEError.ConnectionFailed())
                        }
                        // BOND_BONDING — pairing in progress, wait
                    }
                }
            }

            bondReceiver = receiver
            context.registerReceiver(
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            )

            cont.invokeOnCancellation { unregisterBondReceiver() }

            if (!device.createBond()) {
                unregisterBondReceiver()
                cont.resumeWithException(LedgerBLEError.ConnectionFailed())
            }
        }
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) { /* already unregistered */ }
        }
        bondReceiver = null
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
            // Match by advertised service UUID …
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val matchedByUuid = serviceUuids.any { it in ALL_SERVICE_UUIDS }

            // … or by device name (covers cases where service UUID is absent
            // from the advertisement, e.g. bonded Ledger devices).
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            val matchedByName = name.contains("Ledger", ignoreCase = true) ||
                name.contains("Nano", ignoreCase = true)

            if (!matchedByUuid && !matchedByName) return

            val displayName = name.ifEmpty { "Ledger Device" }
            val id = result.device.address
            val current = _discoveredDevices.value
            if (current.none { it.id == id }) {
                Log.d(TAG, "Discovered: $displayName ($id)")
                _discoveredDevices.value = current + LedgerDevice(
                    id = id,
                    name = displayName,
                    device = result.device,
                )
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
                    Log.d(TAG, "Disconnected from GATT server (status=$status)")
                    cleanUpConnection()
                    connectContinuation?.resumeWithException(LedgerBLEError.ConnectionFailed())
                    connectContinuation = null
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

            // Match characteristics by UUID, with property-based fallback
            for (char in matchedService.characteristics) {
                when (char.uuid) {
                    matchedSpec.writeUUID -> writeCharacteristic = char
                    matchedSpec.writeCmdUUID -> writeCmdCharacteristic = char
                    matchedSpec.notifyUUID -> notifyCharacteristic = char
                }
            }
            if (writeCharacteristic == null && writeCmdCharacteristic == null) {
                for (char in matchedService.characteristics) {
                    if (char == notifyCharacteristic) continue
                    val props = char.properties
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                        writeCmdCharacteristic = char
                    } else if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                        writeCharacteristic = char
                    }
                }
            }
            if (notifyCharacteristic == null) {
                for (char in matchedService.characteristics) {
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        notifyCharacteristic = char
                        break
                    }
                }
            }

            val hasWrite = writeCharacteristic != null || writeCmdCharacteristic != null
            if (!hasWrite || notifyCharacteristic == null) {
                Log.e(TAG, "Missing characteristics: write=${writeCharacteristic != null} " +
                    "writeCmd=${writeCmdCharacteristic != null} notify=${notifyCharacteristic != null}")
                connectContinuation?.resumeWithException(LedgerBLEError.CharacteristicNotFound())
                connectContinuation = null
                return
            }

            // Enable notifications.  The connect continuation is resumed in
            // onDescriptorWrite once the BLE stack confirms the subscription.
            val nc = notifyCharacteristic!!
            gatt.setCharacteristicNotification(nc, true)
            val descriptor = nc.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            if (descriptor != null) {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                Log.d(TAG, "writeDescriptor(CCCD) initiated: $ok")
                if (!ok) {
                    // Descriptor write failed to start — resume anyway and hope
                    // for the best (some devices work without explicit CCCD write).
                    finishConnect(gatt)
                }
                // Otherwise wait for onDescriptorWrite callback
            } else {
                // No CCCD descriptor — resume immediately
                Log.w(TAG, "No CCCD descriptor on notify characteristic")
                finishConnect(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(TAG, "onDescriptorWrite status=$status")
            if (connectContinuation != null) {
                // Request a larger ATT MTU before finishing the connect handshake.
                // The callback chain continues in onMtuChanged.
                if (!gatt.requestMtu(517)) {
                    finishConnect(gatt)
                }
                // else wait for onMtuChanged
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status")
            // ATT overhead is 3 bytes; usable payload = mtu - 3
            if (status == BluetoothGatt.GATT_SUCCESS && mtu - 3 > mtuSize) {
                mtuSize = mtu - 3
            }
            finishConnect(gatt)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == connectedDeviceBLE?.notifyUUID) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                handleNotification(data)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == connectedDeviceBLE?.notifyUUID) {
                handleNotification(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun finishConnect(gatt: BluetoothGatt) {
        if (connectContinuation == null) return
        val devices = _discoveredDevices.value
        _connectedDevice.value = devices.firstOrNull {
            it.device.address == gatt.device.address
        } ?: LedgerDevice(
            id = gatt.device.address,
            name = gatt.device.name ?: "Ledger",
            device = gatt.device,
        )
        Log.d(TAG, "Connect handshake complete (mtu=$mtuSize)")
        connectContinuation?.resume(Unit)
        connectContinuation = null
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
