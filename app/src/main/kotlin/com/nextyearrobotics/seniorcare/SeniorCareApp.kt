package com.nextyearrobotics.seniorcare

import android.app.Application
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk

class SeniorCareApp : Application() {
    val runtime by lazy { com.nextyearrobotics.seniorcare.service.SessionRuntime(this) }
    override fun onCreate() {
        super.onCreate()
        // 키가 없으면(CI, 키 미발급) 지도 초기화를 건너뛴다. 지도 화면은 "지도를 사용할 수 없음"으로 열화된다(명세 6.2).
        val key = BuildConfig.KAKAO_NATIVE_APP_KEY
        if (key.isNotBlank() && key != "dummy") {
            try { KakaoMapSdk.init(this, key) } catch (_: Exception) {
                Log.w("SeniorCareApp", "Map initialization unavailable")
            }
        } else {
            Log.w("SeniorCareApp", "Kakao native app key missing; map disabled")
        }
    }
}
