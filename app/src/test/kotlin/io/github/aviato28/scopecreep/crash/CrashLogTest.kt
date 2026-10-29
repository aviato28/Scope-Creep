package io.github.aviato28.scopecreep.crash

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class CrashLogTest {
    @Test fun `report has versions, device and the stack trace`() {
        val text = CrashLog.format(IllegalStateException("boom"), "main", "1.2.3", "14", 34, "Google Pixel 8")
        assertTrue("App version: 1.2.3" in text && "Android: 14 (API 34)" in text && "Device: Google Pixel 8" in text)
        assertTrue("IllegalStateException: boom" in text && "CrashLogTest" in text)
    }
}
