// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core.platform

import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.ref.Reference
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// 本项目只交付 x86_64；以下是 Windows SDK 中对应结构的 64 位布局。
private const val QUERY_REQUEST_BYTES = 64L
private const val QUERY_RESULT_BYTES = 32L
private const val QUERY_CANCEL_BYTES = 32L
private const val REQUEST_NAME_OFFSET = 8L
private const val REQUEST_TYPE_OFFSET = 16L
internal const val REQUEST_CALLBACK_OFFSET = 48L
private const val RESULT_STATUS_OFFSET = 4L
private const val RESULT_RECORDS_OFFSET = 16L
private const val RECORD_TYPE_OFFSET = 16L
private const val RECORD_LENGTH_OFFSET = 18L
private const val RECORD_DATA_OFFSET = 32L
private const val SRV_PRIORITY_OFFSET = 40L
private const val SRV_WEIGHT_OFFSET = 42L
private const val SRV_PORT_OFFSET = 44L
private const val SRV_DATA_BYTES = 16
private const val DNS_TYPE_SRV = 33
private const val DNS_RECORD_LIST = 1
private const val DNS_NAME_ERROR = 9003
private const val DNS_NO_RECORDS = 9501
internal const val DNS_QUERY_PENDING = 9506
private const val MAX_DNS_RECORDS = 256
private const val UNSIGNED_WORD_MASK = 0xffff
private const val QUERY_TIMEOUT_MILLIS = 1_500L

private val windowsDns by lazy { NativeLibrary.getInstance("dnsapi") }

/** 超时后原生查询仍可能回调；保留请求、缓冲与回调到完成通知，避免访问已回收内存。 */
private val pendingQueries = ConcurrentHashMap<Long, WindowsSrvQuery>()

private fun interface DnsCompletion : StdCallCallback {
    fun invoke(
        context: Pointer?,
        result: Pointer,
    )
}

internal fun queryWindowsSrvRecords(name: String): List<String> = WindowsSrvQuery(name).execute()

internal class WindowsSrvQuery(
    name: String,
    private val start: (Pointer, Pointer, Pointer) -> Int = { request, result, cancel ->
        windowsDns.getFunction("DnsQueryEx").invokeInt(arrayOf(request, result, cancel))
    },
    private val cancel: (Pointer) -> Unit = {
        windowsDns.getFunction("DnsCancelQuery").invokeInt(arrayOf(it))
    },
    private val freeRecords: (Pointer) -> Unit = {
        windowsDns.getFunction("DnsRecordListFree").invokeVoid(arrayOf(it, DNS_RECORD_LIST))
    },
) {
    private val queryName = Memory((name.length + 1L) * Native.WCHAR_SIZE).apply { setWideString(0, name) }
    private val request = Memory(QUERY_REQUEST_BYTES).apply { clear() }
    private val result =
        Memory(QUERY_RESULT_BYTES).apply {
            clear()
            setInt(0, 1)
        }
    private val cancelHandle = Memory(QUERY_CANCEL_BYTES).apply { clear() }
    private val completed = CountDownLatch(1)
    private val completionClaimed = AtomicBoolean()
    private val key = Pointer.nativeValue(result)
    private val callback = DnsCompletion { _, response -> complete(response) }

    @Volatile private var outcome: Result<List<String>>? = null

    init {
        request.setInt(0, 1)
        request.setPointer(REQUEST_NAME_OFFSET, queryName)
        request.setShort(REQUEST_TYPE_OFFSET, DNS_TYPE_SRV.toShort())
        request.setPointer(REQUEST_CALLBACK_OFFSET, CallbackReference.getFunctionPointer(callback))
    }

    fun execute(timeoutMillis: Long = QUERY_TIMEOUT_MILLIS): List<String> {
        try {
            beginQuery()
            if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                cancel(cancelHandle)
                throw SocketTimeoutException("Windows system DNS SRV query timed out")
            }
            return checkNotNull(outcome).getOrThrow()
        } catch (interrupted: InterruptedException) {
            cancel(cancelHandle)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Windows system DNS SRV query interrupted").apply { initCause(interrupted) }
        } finally {
            // DnsCancelQuery 与回调均可能同步执行；二者结束前不能释放取消句柄。
            Reference.reachabilityFence(this)
        }
    }

    private fun beginQuery() {
        pendingQueries[key] = this
        val status =
            runCatching { start(request, result, cancelHandle) }
                .onFailure { pendingQueries.remove(key) }
                .getOrThrow()
        if (status != DNS_QUERY_PENDING) {
            if (status != 0) result.setInt(RESULT_STATUS_OFFSET, status)
            complete(result)
        }
    }

    private fun complete(response: Pointer) {
        if (!completionClaimed.compareAndSet(false, true)) return
        val records = response.getPointer(RESULT_RECORDS_OFFSET)
        try {
            // 异常跨越原生回调会丢失调用上下文；在等待线程上原样重抛。
            outcome =
                runCatching {
                    when (val status = response.getInt(RESULT_STATUS_OFFSET)) {
                        0 -> readWindowsSrvRecords(records)
                        DNS_NAME_ERROR, DNS_NO_RECORDS -> emptyList()
                        else -> throw IOException("Windows system DNS SRV query failed: error=$status")
                    }
                }
        } finally {
            try {
                if (records != null) freeRecords(records)
            } finally {
                pendingQueries.remove(key)
                completed.countDown()
            }
        }
    }
}

internal fun readWindowsSrvRecords(head: Pointer?): List<String> =
    generateSequence(head) { it.getPointer(0) }
        .take(MAX_DNS_RECORDS)
        .mapNotNull { record ->
            val type = record.getShort(RECORD_TYPE_OFFSET).toInt() and UNSIGNED_WORD_MASK
            val bytes = record.getShort(RECORD_LENGTH_OFFSET).toInt() and UNSIGNED_WORD_MASK
            if (type != DNS_TYPE_SRV || bytes < SRV_DATA_BYTES) return@mapNotNull null
            val target = record.getPointer(RECORD_DATA_OFFSET)?.getWideString(0) ?: return@mapNotNull null
            val priority = record.getShort(SRV_PRIORITY_OFFSET).toInt() and UNSIGNED_WORD_MASK
            val weight = record.getShort(SRV_WEIGHT_OFFSET).toInt() and UNSIGNED_WORD_MASK
            val port = record.getShort(SRV_PORT_OFFSET).toInt() and UNSIGNED_WORD_MASK
            "$priority $weight $port $target"
        }.toList()
