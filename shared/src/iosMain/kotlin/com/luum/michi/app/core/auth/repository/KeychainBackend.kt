@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package com.luum.michi.app.core.auth.repository

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The tiny slice of the Keychain API the token storage needs. Keeping it behind
 * an interface lets the storage logic be tested without touching the runner's
 * Keychain (Kotlin/Native tests may not have the host app's entitlement).
 */
internal interface KeychainBackend {
    fun read(service: String, account: String): ByteArray?
    fun write(service: String, account: String, data: ByteArray): Boolean
    fun delete(service: String, account: String): Boolean
}

/** `kSecClassGenericPassword` backend, one item per service + account. */
@OptIn(ExperimentalForeignApi::class)
internal class SecItemKeychainBackend : KeychainBackend {

    override fun read(service: String, account: String): ByteArray? {
        val refs = RetainedRefs()
        return try {
            val query = listOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to refs.string(service),
                kSecAttrAccount to refs.string(account),
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            )
            withDictionary(query) { dictionary ->
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    if (SecItemCopyMatching(dictionary, result.ptr) != errSecSuccess) {
                        return@memScoped null
                    }
                    val data = CFBridgingRelease(result.value) as? NSData
                        ?: return@memScoped null
                    data.toByteArray()
                }
            }
        } finally {
            refs.releaseAll()
        }
    }

    override fun write(service: String, account: String, data: ByteArray): Boolean {
        val refs = RetainedRefs()
        return try {
            val query = listOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to refs.string(service),
                kSecAttrAccount to refs.string(account),
            )
            val value = listOf(kSecValueData to refs.data(data))
            var status = update(query, value)
            if (status == errSecItemNotFound) {
                status = withDictionary(
                    query + listOf(
                        kSecValueData to value.single().second,
                        kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                    ),
                ) { SecItemAdd(it, null) }
            }
            if (status == errSecDuplicateItem) {
                status = update(query, value)
            }
            status == errSecSuccess
        } finally {
            refs.releaseAll()
        }
    }

    override fun delete(service: String, account: String): Boolean {
        val refs = RetainedRefs()
        return try {
            val query = listOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to refs.string(service),
                kSecAttrAccount to refs.string(account),
            )
            val status = withDictionary(query) { SecItemDelete(it) }
            status == errSecSuccess || status == errSecItemNotFound
        } finally {
            refs.releaseAll()
        }
    }

    private fun update(
        query: List<Pair<CFStringRef?, CFTypeRef?>>,
        value: List<Pair<CFStringRef?, CFTypeRef?>>,
    ): Int = withDictionary(query) { q ->
        withDictionary(value) { v -> SecItemUpdate(q, v) }
    }

    /**
     * Bridges the Kotlin values used in a query and releases them once the
     * Security call returns. CFString/CFData objects need retain/release when
     * they cross the CoreFoundation boundary.
     */
    private class RetainedRefs {
        private val refs = mutableListOf<CFTypeRef?>()

        fun string(value: String): CFTypeRef? =
            CFBridgingRetain(NSString.create(string = value)).also { refs += it }

        fun data(value: ByteArray): CFTypeRef? =
            CFBridgingRetain(value.toNSData()).also { refs += it }

        fun releaseAll() {
            refs.forEach { CFBridgingRelease(it) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withDictionary(
    entries: List<Pair<CFStringRef?, CFTypeRef?>>,
    block: (CFDictionaryRef?) -> T,
): T {
    val dictionary = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        entries.size.toLong(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
    entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
    return try {
        block(dictionary)
    } finally {
        CFRelease(dictionary)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val pointer: CPointer<ByteVar> = bytes?.reinterpret() ?: return ByteArray(0)
    return ByteArray(size) { pointer[it] }
}
