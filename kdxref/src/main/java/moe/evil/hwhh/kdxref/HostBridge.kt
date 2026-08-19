package moe.evil.hwhh.kdxref

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod

interface HostBridgeOwner {
    val hostLoader: ClassLoader?
    fun requireHostBridge(): HostBridge
}

@JvmInline
value class HostBridge internal constructor(private val bridge: DexKitBridge) {
    fun findMethod(init: FindMethod.() -> Unit) = bridge.findMethod(init).map(::HostMethodData)

    fun findClass(init: FindClass.() -> Unit) = bridge.findClass(init).map(::HostClassData)

    fun findField(init: FindField.() -> Unit) = bridge.findField(init).map(::HostFieldData)

    companion object {
        private val nativeLibrary = lazy { System.loadLibrary("dexkit") }

        fun <R> open(loader: ClassLoader, block: (HostBridge) -> R): R {
            nativeLibrary.value
            return DexKitBridge.create(loader, true).use { block(HostBridge(it)) }
        }
    }
}
