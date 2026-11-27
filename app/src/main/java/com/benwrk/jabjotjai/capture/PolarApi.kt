package com.benwrk.jabjotjai.capture

import android.content.Context
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApiDefaultImpl

/**
 * The app's one Polar SDK instance. The SDK keeps a single instance per process and refuses a
 * second one with a different feature set (`BDBleApiImpl.getInstance`, SDK 9.0.0), so every part
 * of the app asks for it here, with the same features, and it lives as long as the process.
 */
object PolarApi {

    val FEATURES: Set<PolarBleSdkFeature> = setOf(
        PolarBleSdkFeature.FEATURE_HR,
        PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
        PolarBleSdkFeature.FEATURE_DEVICE_INFO,
        PolarBleSdkFeature.FEATURE_BATTERY_INFO,
    )

    @Volatile
    private var instance: PolarBleApi? = null

    fun get(context: Context): PolarBleApi = instance ?: synchronized(this) {
        instance ?: PolarBleApiDefaultImpl.defaultImplementation(context.applicationContext, FEATURES)
            .also { instance = it }
    }

    val sdkVersion: String get() = PolarBleApiDefaultImpl.versionInfo()
}
