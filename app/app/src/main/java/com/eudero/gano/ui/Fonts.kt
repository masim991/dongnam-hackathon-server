package com.eudero.gano.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.eudero.gano.R
import com.eudero.maplib.MapFonts

/** 앱 전체 글꼴: SUITE (SIL OFL 1.1, SUNN). 폰트 파일은 수정 없이 그대로 사용. */
object Fonts {
    var bold: Typeface = Typeface.DEFAULT_BOLD; private set
    fun init(c: Context) {
        runCatching {
            val reg = ResourcesCompat.getFont(c, R.font.suite_family) ?: return
            bold = Typeface.create(reg, 700, false)
            MapFonts.bold = bold
        }
    }
}
