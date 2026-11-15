package ai.pleos.pitstop

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Fail closed: editing is available only with a confirmed P gear and unrestricted UX. */
class CarGate(private val context: Context, private val changed: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var parked = false
    private var unrestricted = false
    private var running = false
    private fun publish() = changed(running && parked && unrestricted)
    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            main.post {
                if (running && value.propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                    parked = value.status == CarPropertyValue.STATUS_AVAILABLE && value.value == VehicleGear.GEAR_PARK
                    publish()
                }
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) { main.post { parked = false; publish() } }
    }
    fun start() {
        running = true; publish()
        car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            if (!running) connected.disconnect()
            else if (!ready) { parked = false; unrestricted = false; publish() }
            else runCatching {
                car = connected
                properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                properties?.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                parked = properties?.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) == VehicleGear.GEAR_PARK
                restrictions = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                unrestricted = restrictions?.currentCarUxRestrictions?.isRequiresDistractionOptimization == false
                restrictions?.registerListener { value -> main.post { unrestricted = !value.isRequiresDistractionOptimization; publish() } }
                publish()
            }.onFailure { parked = false; unrestricted = false; publish() }
        }
    }
    fun close() {
        running = false; parked = false; unrestricted = false
        runCatching { properties?.unregisterCallback(callback) }; runCatching { restrictions?.unregisterListener() }
        runCatching { car?.disconnect() }; car = null
    }
}
