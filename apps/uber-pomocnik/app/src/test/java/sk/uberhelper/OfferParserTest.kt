package sk.uberhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferParserTest {

    private val acc = Config().acceptList()

    private fun p(vararg lines: String) = OfferParser.parse(lines.toList(), acc)

    @Test
    fun slovenskaPonuka() {
        val o = p(
            "UberX", "4,85", "12,50 €",
            "5 min (1,3 km) od vás", "Hlavná 12, Bratislava",
            "24 min (18,2 km) jazda", "Letisko", "Prijať"
        )
        assertEquals(1.3, o.pickupKm!!, 0.01)
        assertEquals(18.2, o.tripKm!!, 0.01)
        assertEquals(12.5, o.fareEur!!, 0.01)
        assertTrue(o.hasAcceptButton)
    }

    @Test
    fun bezPopisiekPlatiPoradie() {
        val o = p("UberX", "9,80 €", "3 min", "2,5 km", "21 min", "14,0 km", "Prijať")
        assertEquals(2.5, o.pickupKm!!, 0.01)
        assertEquals(14.0, o.tripKm!!, 0.01)
    }

    @Test
    fun metreSaPrepocitaju() {
        val o = p("800 m od vás", "Prijať")
        assertEquals(0.8, o.pickupKm!!, 0.01)
    }

    @Test
    fun minutySaNepovazujuZaMetre() {
        val o = p("74 min", "Prijať")
        assertTrue(o.allKm.isEmpty())
        assertNull(o.pickupKm)
    }

    /** Obrazovka po prijati jazdy nesmie byt povazovana za ponuku. */
    @Test
    fun prijatPlatbuNieJePrijat() {
        val o = p("Piaristengasse 47", "Wien", "74 min", "72,8 km", "Prijať platbu")
        assertFalse(o.hasAcceptButton)
    }

    @Test
    fun hotovostSaRozpozna() {
        val o = p("Iba hotovosť", "4,0 km od vás", "8,00 €", "Prijať")
        assertTrue(o.cashOnly)
    }

    @Test
    fun filterDojazdu() {
        val c = Config(pickupMaxKm = 5f, dryRun = true)
        val blizko = p("2,0 km od vás", "10,00 €", "Prijať")
        val daleko = p("7,5 km od vás", "10,00 €", "Prijať")
        assertEquals(OfferParser.Action.KEEP, OfferParser.decide(blizko, c).action)
        assertEquals(OfferParser.Action.DECLINE, OfferParser.decide(daleko, c).action)
    }

    @Test
    fun krokPolkilometra() {
        val c = Config(pickupMaxKm = 2.5f)
        assertEquals(OfferParser.Action.KEEP, OfferParser.decide(p("2,5 km od vás"), c).action)
        assertEquals(OfferParser.Action.DECLINE, OfferParser.decide(p("3,0 km od vás"), c).action)
    }

    @Test
    fun neznamyUdajStandardneNeklika() {
        val c = Config(pickupMaxKm = 5f, tripMinKm = 10f, declineOnUnknown = false)
        val o = p("2,0 km od vás", "Prijať")
        assertEquals(OfferParser.Action.WAIT, OfferParser.decide(o, c).action)
    }
}
