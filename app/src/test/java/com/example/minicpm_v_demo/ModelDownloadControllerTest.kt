package com.example.minicpm_v_demo

import com.example.minicpm_v_demo.ModelDownloadController.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadControllerTest {
    @Test fun concurrentModelsKeepIndependentProgressAndResults() {
        val controller = ModelDownloadController
        val first = "model-download-controller-test-first"
        val second = "model-download-controller-test-second"
        try {
            assertTrue(controller.requestStart(first))
            assertTrue(controller.requestStart(second))
            assertFalse(controller.requestStart(first))
            controller.setStatus(first, Status.Running())
            controller.setStatus(second, Status.Running())

            controller.publishProgress(first, "25%")
            controller.publishProgress(second, "40%")
            assertEquals(Status.Running("25%"), controller.statuses.value[first])
            assertEquals(Status.Running("40%"), controller.statuses.value[second])

            controller.setStatus(first, Status.Cancelling)
            controller.publishProgress(first, "26%")
            assertEquals(Status.Cancelling, controller.statuses.value[first])
            assertFalse(controller.requestStart(first))
            controller.setStatus(first, Status.Cancelled)
            controller.setStatus(second, Status.Completed)
            assertEquals(Status.Cancelled, controller.statuses.value[first])
            assertEquals(Status.Completed, controller.statuses.value[second])

            assertTrue(controller.requestStart(first))
            controller.acknowledge(first, Status.Cancelled)
            assertEquals(Status.Starting, controller.statuses.value[first])
            assertEquals(Status.Completed, controller.statuses.value[second])
        } finally {
            for (id in listOf(first, second)) {
                controller.setStatus(id, Status.Cancelled)
                controller.acknowledge(id, Status.Cancelled)
            }
        }
    }
}
