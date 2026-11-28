package com.eudero.gano.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        fun section(key: String) = when (key) {
            "guide" -> "이용 안내" to """
                Guest로 바로 달릴 수 있어요.

                지도에서 길을 누르면 코스 정보를 볼 수 있어요. ‘이 길로 가기’를 누르면 방향과 목표를 정할 수 있어요.

                내 주변을 눌러 글이나 사진을 남기고, 다른 사람의 버블을 눌러 이야기를 읽어보세요.

                러닝 중 자물쇠를 잠그면 지도가 내 위치를 따라와요. 두 손가락으로 확대할 수 있고, 자물쇠를 풀면 지도를 옮길 수 있어요.

                달리기를 마치면 기록 탭에서 시간과 거리, 사진을 볼 수 있어요.
            """.trimIndent()
            "credits" -> "출처 및 라이선스" to (
                "지도 © Kakao\n산책길 기초 데이터 © OpenStreetMap 기여자 (ODbL, https://www.openstreetmap.org/copyright)\n날씨 Open-Meteo\n산책길·횡단보도·경사 정보 동남권 해커톤 팀 데이터\n\n" +
                "샘플 사진 속 사람과 상황은 AI로 생성했어요.\n\n" +
                "글꼴 SUITE © 2023 SUNN (http://sun.fo/suite) — SIL Open Font License 1.1\n\n" +
                assets.open("licenses/SUITE-OFL.txt").bufferedReader().use { it.readText() })
            else -> "개인정보" to """
                달리기와 위치
                현재 위치를 이용해 주변 코스를 보여주고 달린 거리와 경로를 기록해요. 러닝 기록과 사진은 기기에 저장해요.

                건강 앱 연동
                허용한 항목만 Health Connect와 연동해요. 달리기 시간과 거리를 기록하고, 휴대폰에서 측정한 걸음 수도 함께 기록할 수 있어요. 같은 시간에 건강 앱이나 워치가 기록한 걸음 수와 심박수는 뛰고보자의 러닝 기록에서 확인할 수 있어요.

                Health Connect에서 연결한 다른 운동 앱도 사용자가 허용한 항목을 읽을 수 있어요. 권한은 휴대폰 설정의 Health Connect에서 바꿀 수 있어요.
            """.trimIndent()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            val keys = if (intent.getStringExtra("section") == "all") listOf("guide", "privacy", "credits")
                else listOf(intent.getStringExtra("section") ?: "privacy")
            for (key in keys) {
                val (title, body) = section(key)
                addView(TextView(context).apply { text = title; textSize = 24f; typeface = Fonts.bold; setTextColor(Color.WHITE) })
                addView(TextView(context).apply { text = body; textSize = 16f; setPadding(0, pad, 0, pad); setTextColor(Color.WHITE) })
            }
        }
        setContentView(Glass.detailScreen(this, ScrollView(this).apply { addView(col) }))
    }
}
