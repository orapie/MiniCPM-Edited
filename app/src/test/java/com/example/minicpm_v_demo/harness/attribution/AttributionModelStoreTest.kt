package com.example.minicpm_v_demo.harness.attribution

import org.junit.Assert.assertEquals
import org.junit.Test

class AttributionModelStoreTest {
    @Test fun resolverNeverFallsBackToAnIneligibleMirroredModel() {
        assertEquals("minicpm5-0.9b", AttributionModelStore.resolveSelected(null).model.id)
        assertEquals("qwen3-0.6b", AttributionModelStore.resolveSelected("qwen3-0.6b").model.id)
        assertEquals("minicpm5-0.9b", AttributionModelStore.resolveSelected("minicpm-v-4").model.id)
        assertEquals("minicpm5-0.9b", AttributionModelStore.resolveSelected("missing").model.id)
    }
}
