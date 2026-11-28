package com.eudero.gano

import android.app.Application
import com.eudero.gano.ui.Fonts

class App : Application() {
    override fun onCreate() { super.onCreate(); Fonts.init(this); com.eudero.maplib.EudMapView.kakaoKey = BuildConfig.KAKAO_APP_KEY; com.eudero.maplib.EudMapView.dimColor = BuildConfig.MAP_DIM }
}
