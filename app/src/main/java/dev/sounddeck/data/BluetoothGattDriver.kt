package dev.sounddeck.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import dev.sounddeck.protocol.GattDriver
import dev.sounddeck.protocol.SonyGattProfile
import java.util.UUID

/** Public Android GATT APIs only. No scan, bonding, cache refresh, MTU/PHY/priority changes, or audio-profile operations. */
@SuppressLint("MissingPermission") // The repository checks BLUETOOTH_CONNECT before constructing this driver.
class BluetoothGattDriver(private val context: Context, private val device: BluetoothDevice) : GattDriver {
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var listener: GattDriver.Listener? = null
    private var closed = false
    private val serviceId = UUID.fromString(SonyGattProfile.SERVICE)
    private val txId = UUID.fromString(SonyGattProfile.TX)
    private val rxId = UUID.fromString(SonyGattProfile.RX)
    private val cccdId = UUID.fromString(SonyGattProfile.CCCD)
    private fun current() = synchronized(lock) { if (closed) null else gatt }
    private fun target(g: BluetoothGatt, id: UUID): BluetoothGattCharacteristic? =
        g.services.filter { it.uuid == serviceId }.singleOrNull()?.characteristics?.filter { it.uuid == id }?.singleOrNull()
    private fun receiver(g: BluetoothGatt): GattDriver.Listener? = synchronized(lock) { if (!closed && gatt === g) listener else null }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) receiver(g)?.disconnected(status)
            else if (newState == BluetoothProfile.STATE_CONNECTED || status != BluetoothGatt.GATT_SUCCESS)
                receiver(g)?.connected(status, newState == BluetoothProfile.STATE_CONNECTED)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            receiver(g)?.services(status, if (status == BluetoothGatt.GATT_SUCCESS) g.services.map { s ->
                SonyGattProfile.Service(s.uuid.toString(), s.characteristics.map { c ->
                    SonyGattProfile.Characteristic(c.uuid.toString(), c.properties, c.descriptors.map { it.uuid.toString() })
                })
            } else emptyList())
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val c = descriptor.characteristic
            receiver(g)?.descriptorWritten(status, c.service.uuid.toString(), c.uuid.toString(), descriptor.uuid.toString())
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            receiver(g)?.characteristicWritten(status, characteristic.service.uuid.toString(), characteristic.uuid.toString())
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            receiver(g)?.mtuChanged(mtu, status)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receiver(g)?.notification(characteristic.service.uuid.toString(), characteristic.uuid.toString(), value.copyOf())
        }
        @Deprecated("Legacy callback for Android 8–12")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) receiver(g)?.notification(characteristic.service.uuid.toString(), characteristic.uuid.toString(), characteristic.value?.copyOf() ?: byteArrayOf())
        }
        override fun onServiceChanged(g: BluetoothGatt) {
            // A changed database invalidates the allowlist handles. Never keep using stale characteristics.
            receiver(g)?.connected(BluetoothGatt.GATT_FAILURE, false)
        }
    }
    override fun open(listener: GattDriver.Listener): Boolean = synchronized(lock) {
        check(!closed && gatt == null)
        this.listener = listener
        gatt = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt != null
    }
    override fun discover(): Boolean = current()?.discoverServices() == true
    override fun enableNotifications(): Boolean {
        val g = current() ?: return false
        return target(g, rxId)?.let { g.setCharacteristicNotification(it, true) } == true
    }
    @Suppress("DEPRECATION")
    override fun subscribe(indication: Boolean): Boolean {
        val g = current() ?: return false
        val descriptor = target(g, rxId)?.descriptors?.filter { it.uuid == cccdId }?.singleOrNull() ?: return false
        val value = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        else { descriptor.value = value.copyOf(); g.writeDescriptor(descriptor) }
    }
    @Suppress("DEPRECATION")
    override fun write(value: ByteArray, withResponse: Boolean): Boolean {
        val g = current() ?: return false
        val characteristic = target(g, txId) ?: return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(characteristic, value.copyOf(), type) == BluetoothStatusCodes.SUCCESS
        else { characteristic.writeType = type; characteristic.value = value.copyOf(); g.writeCharacteristic(characteristic) }
    }
    override fun close() {
        val old = synchronized(lock) { if (closed) return; closed = true; listener = null; gatt.also { gatt = null } }
        try { old?.disconnect() } finally { old?.close() }
    }
}
