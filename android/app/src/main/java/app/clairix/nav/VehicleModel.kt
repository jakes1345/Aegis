package app.clairix.nav

/**
 * Fuel use of the car being driven.
 *
 * [litersPer100kmAt] is steady-speed consumption in L/100 km at a speed in km/h; [idleLph] what
 * the engine burns standing still; [stopPenaltyLiters] the extra fuel for one stop and
 * re-acceleration back to urban speed.
 */
data class VehicleModel(
    val idleLph: Double,
    val litersPer100kmAt: (Double) -> Double,
    val stopPenaltyLiters: Double,
) {
    /** Fuel for [lengthM] metres driven at [kph]. */
    fun litersFor(lengthM: Double, kph: Double): Double = lengthM / 100_000.0 * litersPer100kmAt(kph)

    /** Fuel for [seconds] spent idling. */
    fun idleLiters(seconds: Double): Double = idleLph * seconds / 3600.0

    companion object {
        private const val LITERS_PER_US_GALLON = 3.785411784
        private const val KM_PER_MILE = 1.609344

        private fun mpgToL100(mpg: Double) = 100.0 * LITERS_PER_US_GALLON / (mpg * KM_PER_MILE)

        /**
         * 2017 Nissan Rogue Sport 2.0 L with CVT, EPA 25 mpg city / 32 mpg highway.
         *
         * Steady-speed consumption is modelled as A/v + B + C·v²: A/v is the fixed per-hour
         * burn of a running engine (friction, accessories, low-load inefficiency) spread over
         * distance, B rolling resistance, C·v² aerodynamic drag. The minimum is placed at
         * 75 km/h, which fixes A = 2·C·75³; B and C then follow from the two EPA figures:
         * the highway cycle (HWFET, mean 77.7 km/h, no stops) must equal 32 mpg, and the city
         * cycle (FTP-75, mean 34.1 km/h, 17.77 km) must equal 25 mpg once its stops are added.
         */
        val DEFAULT: VehicleModel by lazy {
            val massKg = 1_500.0
            // Re-accelerating to 50 km/h: kinetic energy over a CVT petrol engine's ~25 % brake
            // efficiency during acceleration, at 32 MJ per litre of gasoline.
            val v50 = 50.0 / 3.6
            val stopPenalty = 0.5 * massKg * v50 * v50 / (0.25 * 32.0e6)
            val idle = 0.8

            val city = mpgToL100(25.0)
            val hwy = mpgToL100(32.0)
            val vCity = 34.1
            val vHwy = 77.7
            val vMin = 75.0
            // FTP-75 has 23 stops, most from well under 50 km/h; by kinetic energy they amount
            // to about ten full stops from 50 km/h over the 17.77 km cycle.
            val cityStopsL100 = 10.0 * stopPenalty * 100.0 / 17.77
            val aOverC = 2.0 * vMin * vMin * vMin
            // hwy  = C·(aOverC/vHwy + vHwy²) + B
            // city = C·(aOverC/vCity + vCity²) + B + cityStopsL100
            val kH = aOverC / vHwy + vHwy * vHwy
            val kC = aOverC / vCity + vCity * vCity
            val c = (city - cityStopsL100 - hwy) / (kC - kH)
            val b = hwy - c * kH
            val a = c * aOverC
            VehicleModel(
                idleLph = idle,
                litersPer100kmAt = { kph ->
                    // Below walking pace the A/v term diverges; idling dominates there anyway.
                    val v = kph.coerceIn(5.0, 200.0)
                    a / v + b + c * v * v
                },
                stopPenaltyLiters = stopPenalty,
            )
        }
    }
}
