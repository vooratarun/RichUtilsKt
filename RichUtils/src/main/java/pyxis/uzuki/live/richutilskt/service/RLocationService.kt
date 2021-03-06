package pyxis.uzuki.live.richutilskt.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.util.Log

import android.view.Surface
import android.view.Display
import android.view.WindowManager
import android.content.ContentValues.TAG
import android.location.Address
import android.location.Geocoder
import java.io.IOException
import java.util.*


@Suppress("SENSELESS_COMPARISON")
@SuppressLint("MissingPermission")
class RLocationService : Service() {

    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var display: Display? = null
    private val gpsLocationListener = LocationChangeListener()
    private val networkLocationListener = LocationChangeListener()
    private val sensorEventListener = SensorListener()
    private val localBinder = LocalBinder()

    private val TWO_MINUTES = 1000 * 60 * 2
    private val MIN_BEARING_DIFF = 2.0f
    private val FASTEST_INTERVAL_IN_MS = 1000L
    private val TAG = "RLocationService"

    private var bearing: Float = 0f
    private var axisX: Int = 0
    private var axisY: Int = 0
    var currentBestLocation: Location? = null
    private var locationCallback: LocationCallback? = null

    override fun onBind(intent: Intent?) = localBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        getLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUpdates()
        sensorManager?.unregisterListener(sensorEventListener)
    }

    /**
     * get location of user.
     * this service using 3 methods for fetch location. (Mobile, GPS, Sensor)
     */
    fun getLocation() {
        val lastKnownGpsLocation = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val lastKnownNetworkLocation = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        var bestLastKnownLocation = currentBestLocation

        if (lastKnownGpsLocation != null && isBetterLocation(lastKnownGpsLocation, bestLastKnownLocation)) {
            bestLastKnownLocation = lastKnownGpsLocation
        }

        if (lastKnownNetworkLocation != null && isBetterLocation(lastKnownNetworkLocation, bestLastKnownLocation)) {
            bestLastKnownLocation = lastKnownNetworkLocation
        }

        currentBestLocation = bestLastKnownLocation
        val gpsEnabled = locationManager?.allProviders?.contains(LocationManager.GPS_PROVIDER) as Boolean
                && locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) as Boolean

        if (gpsEnabled) {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, FASTEST_INTERVAL_IN_MS, 0.0f, gpsLocationListener)
        }

        val networkEnabled = locationManager?.allProviders?.contains(LocationManager.NETWORK_PROVIDER) as Boolean
                && locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) as Boolean

        if (networkEnabled) {
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, FASTEST_INTERVAL_IN_MS, 0.0f, networkLocationListener)
        }

        if (bestLastKnownLocation != null) {
            Log.i(TAG, "Received last known location via old API: " + bestLastKnownLocation)
            if (bearing != null) {
                bestLastKnownLocation.bearing = bearing
            }
            locationCallback?.handleNewLocation(currentBestLocation as Location)
        }

        val mSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorManager?.registerListener(sensorEventListener, mSensor, SensorManager.SENSOR_DELAY_NORMAL * 5)
    }

    /**
     * Set callback for location service.
     * any location updates will invoke this callback (except new location data is not-best-location.)
     */
    fun setLocationCallback(callback: (Location) -> Unit) {
        locationCallback = object : LocationCallback, (Location) -> Unit {
            override fun invoke(location: Location) {
                callback.invoke(location)
            }

            override fun handleNewLocation(location: Location) {
                callback.invoke(location)
            }
        }
    }

    /**
     * stop location update service
     */
    fun stopUpdates() {
        locationManager?.removeUpdates(gpsLocationListener)
        locationManager?.removeUpdates(networkLocationListener)
        sensorManager?.unregisterListener(sensorEventListener)
    }

    /**
     * get Address from CurrentBestLocation
     *
     * @return List<Address> or null
     */
    fun Context.getGeocoderAddress(): List<Address>? {
        if (currentBestLocation != null) {
            val geocoder = Geocoder(this, Locale.ENGLISH)
            try {
                return geocoder.getFromLocation((currentBestLocation as Location).latitude, (currentBestLocation as Location).longitude, 1)
            } catch (e: IOException) {
                Log.e(TAG, "Impossible to connect to Geocoder", e)
            }
        }
        return null
    }

    /**
     * get AddressLine
     *
     * @return addressLine of current Best Location
     */
    fun Context.getAddressLine(): String {
        val addresses = getGeocoderAddress()

        if (addresses != null && addresses.isNotEmpty()) {
            val address = addresses[0]
            return address.getAddressLine(0)
        } else {
            return ""
        }
    }

    /**
     * get locality
     *
     * @return locality of current Best Location
     */
    fun Context.getLocality(): String {
        val addresses = getGeocoderAddress()

        if (addresses != null && addresses.isNotEmpty()) {
            val address = addresses[0]
            return address.locality
        } else {
            return ""
        }
    }

    /**
     * get postal code
     *
     * @return postal code of current Best Location
     */
    fun Context.getPostalCode(): String {
        val addresses = getGeocoderAddress()

        if (addresses != null && addresses.isNotEmpty()) {
            val address = addresses[0]
            return address.postalCode
        } else {
            return ""
        }
    }

    /**
     * get country name
     *
     * @return country name of current Best Location
     */
    fun Context.getCountryName(): String {
        val addresses = getGeocoderAddress()
        if (addresses != null && addresses.isNotEmpty()) {
            val address = addresses[0]
            return address.countryName
        } else {
            return ""
        }
    }

    private fun isBetterLocation(location: Location, currentBestLocation: Location?): Boolean {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            // 이전에 저장한 것이 없다면 새로 사용
            return true
        }

        val timeDelta = location.time - currentBestLocation.time
        val isSignificantlyNewer = timeDelta > TWO_MINUTES // 시간차 2분 이상?
        val isSignificantlyOlder = timeDelta < -TWO_MINUTES // 아니면 더 오래되었는지
        val isNewer = timeDelta > 0 // 신규 위치정보 파악

        // If it’s been more than two minutes since the current location, use the new location
        // because the user has likely moved
        // 만일 2분이상 차이난다면 새로운거 사용 (유저가 움직이기 때문)
        if (isSignificantlyNewer) {
            return true
        } else if (isSignificantlyOlder) {
            return false
        }

        // Check whether the new location fix is more or less accurate
        // 정확도 체크
        val accuracyDelta = (location.accuracy - currentBestLocation.accuracy).toInt()
        val isLessAccurate = accuracyDelta > 0 // 기존거가 더 정확함
        val isMoreAccurate = accuracyDelta < 0 // 신규가 더 정확함
        val isSignificantlyLessAccurate = accuracyDelta > 200 // 200이상 심각하게 차이남
        val isFromSameProvider = isSameProvider(location.provider, currentBestLocation.provider) // 같은 프로바이더인지

        if (isMoreAccurate) {
            // 더 정확하면?
            return true
        } else if (isNewer && !isLessAccurate) {
            // 새로운 데이터이고 신규가 정확하거나 같을때
            return true
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            // 새로운 데이터이고 너무 차이나지 않고 같은 프로바이더인 경우
            return true
        }
        return false
    }

    private fun isSameProvider(provider1: String?, provider2: String?): Boolean {
        if (provider1 == null) {
            return provider2 == null
        }
        return provider1 == provider2
    }

    private inner class LocationChangeListener : android.location.LocationListener {
        override fun onLocationChanged(location: Location?) {
            if (location == null) {
                return
            }

            if (isBetterLocation(location, currentBestLocation)) {
                currentBestLocation = location
                if (bearing != null) {
                    (currentBestLocation as Location).bearing = bearing
                }
                locationCallback?.handleNewLocation(currentBestLocation as Location)
            }
        }

        override fun onProviderDisabled(provider: String?) {}
        override fun onProviderEnabled(provider: String?) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private inner class SensorListener : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                Log.i(TAG, "Rotation sensor accuracy changed to: " + accuracy)
            }
        }

        override fun onSensorChanged(event: SensorEvent?) {
            val rotationMatrix = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event?.values)
            val orientationValues = FloatArray(3)

            readDisplayRotation()

            SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, rotationMatrix)
            SensorManager.getOrientation(rotationMatrix, orientationValues)
            val azimuth = Math.toDegrees(orientationValues[0].toDouble())

            val newBearing = azimuth
            bearing = if (bearing == null) 0f else bearing

            val abs = Math.abs(bearing.minus(newBearing).toFloat()) > MIN_BEARING_DIFF

            if (bearing == null || abs) {
                bearing = newBearing.toFloat()
                if (currentBestLocation != null) {
                    (currentBestLocation as Location).bearing = bearing
                }
            }
        }
    }

    private fun readDisplayRotation() {
        axisX = SensorManager.AXIS_X
        axisY = SensorManager.AXIS_Y
        when (display?.rotation) {
            Surface.ROTATION_0 -> {}
            Surface.ROTATION_90 -> {
                axisX = SensorManager.AXIS_Y
                axisY = SensorManager.AXIS_MINUS_X
            }
            Surface.ROTATION_180 -> axisY = SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> {
                axisX = SensorManager.AXIS_MINUS_Y
                axisY = SensorManager.AXIS_X
            }
            else -> {}
        }
    }

    inner class LocalBinder : Binder() {
        val service: RLocationService
            get() = this@RLocationService
    }

    interface LocationCallback {
        fun handleNewLocation(location: Location)
    }
}