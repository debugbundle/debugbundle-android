package com.debugbundle.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleOperationEvidenceTest {
    @Test
    fun `nested and copied operation owners revoke after completion and close`() = runBlocking {
        val operations = DebugBundleOperations(); val outer = operations.begin(); val copied = operations.storage.asContextElement(outer.lease)
        val inner = operations.begin(); assertNotEquals(outer.operationId, inner.operationId)
        inner.close(); assertEquals(outer.operationId, operations.current()?.operationId)
        outer.close(); withContext(copied) { assertNull(operations.current()) }
        operations.close(); assertNull(operations.begin().operationId)
    }
    @Test
    fun `concurrent suspending operations isolate scope and preserve result and original exception`() = runBlocking {
        val operations = DebugBundleOperations()
        val ids = (1..2).map { async(Dispatchers.Default) { operations.runSuspending { val id = operations.current()?.operationId; delay(10); assertEquals(id, operations.current()?.operationId); id } } }.awaitAll()
        assertEquals(2, ids.distinct().size); assertTrue(ids.all { DebugBundleEvidenceWire.freshId(it) }); assertNull(operations.current())
        val original = IllegalStateException("same")
        try { operations.run<Unit> { throw original }; fail("callback should throw") } catch (caught: IllegalStateException) { assertSame(original, caught) }
        assertNull(operations.current())
    }
}
