package app.clairix

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The USB attach filter must name exactly the dongles SdrUsbDriver will open. */
class UsbDeviceFilterTest {

    @Test
    fun filterMatchesKnownDongles() {
        val xml = File("src/main/res/xml/usb_device_filter.xml").readText()
        val inFilter = Regex("""vendor-id="(\d+)"\s+product-id="(\d+)"""")
            .findAll(xml)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toSet()
        assertEquals(SdrUsbDriver.KNOWN_DONGLES.toSet(), inFilter)
    }

    @Test
    fun tunableRangeIsTheR820TRange() {
        assertEquals(false, SdrUsbDriver.isTunable(23_999_999L))
        assertEquals(true, SdrUsbDriver.isTunable(24_000_000L))
        assertEquals(true, SdrUsbDriver.isTunable(1_090_000_000L))
        assertEquals(true, SdrUsbDriver.isTunable(1_766_000_000L))
        assertEquals(false, SdrUsbDriver.isTunable(1_766_000_001L))
    }
}
