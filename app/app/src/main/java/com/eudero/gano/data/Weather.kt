package com.eudero.gano.data


/** 러닝 날씨 안내 (지금은 하드코딩 값). 조깅 날짜의 시간대별 예시. 나중에 실제 예보 API로 교체. */
object Weather {
    class Slot(val tempC: Int, val humidity: Int, val windMs: Double, val rainMm: Double)
    class Day(val sky: String, val dust: String, val slots: List<Slot>)
    class Verdict(val lead: String, val word: String, val subtitle: String, val body: String, val label: String, val score: Int, val best: String)

    val HOURS = listOf(6, 9, 12, 15, 18, 20, 22)
    private fun slots(t: IntArray, h: IntArray, w: DoubleArray, r: DoubleArray) = HOURS.indices.map { Slot(t[it], h[it], w[it], r[it]) }

    private val yesterday = Day("맑음", "보통", slots(intArrayOf(5, 8, 11, 12, 9, 8, 7), intArrayOf(58, 50, 42, 40, 48, 54, 58), doubleArrayOf(1.5, 2.0, 2.5, 3.0, 2.5, 2.0, 1.5), DoubleArray(7)))
    private val today = Day("구름 조금", "좋음", slots(intArrayOf(6, 9, 12, 13, 10, 9, 8), intArrayOf(62, 55, 47, 45, 52, 57, 60), doubleArrayOf(2.0, 2.5, 3.0, 3.5, 3.0, 2.5, 2.0), DoubleArray(7)))

    fun timeLabel(h: Int) = "%02d:00".format(h)

    /** 시간(6~24)에 맞춘 값: 3~2시간 간격 자료 사이를 이어 붙임. */
    fun slotAt(day: Day, hour: Int): Slot {
        val h = hour.coerceIn(6, 24)
        if (h >= 22) { val s = day.slots.last(); val dt = h - 22; return Slot(s.tempC - dt / 2, (s.humidity + dt).coerceAtMost(99), s.windMs, s.rainMm) }
        var i = 0; while (i < HOURS.size - 2 && HOURS[i + 1] <= h) i++
        val a = day.slots[i]; val b = day.slots[i + 1]; val t = (h - HOURS[i]).toDouble() / (HOURS[i + 1] - HOURS[i])
        fun l(x: Double, y: Double) = x + (y - x) * t
        return Slot(l(a.tempC.toDouble(), b.tempC.toDouble()).toInt(), l(a.humidity.toDouble(), b.humidity.toDouble()).toInt(), Math.round(l(a.windMs, b.windMs) * 10) / 10.0, Math.round(l(a.rainMm, b.rainMm) * 10) / 10.0)
    }

    private fun score(day: Day, s: Slot): Int {
        var p = 100
        p -= when { s.tempC in 8..16 -> 0; s.tempC in 4..7 || s.tempC in 17..22 -> 12; s.tempC in 0..3 || s.tempC in 23..27 -> 28; else -> 45 }
        p -= when { s.humidity in 30..70 -> 0; s.humidity < 30 -> 8; s.humidity <= 85 -> 10; else -> 20 }
        p -= when { s.windMs <= 4.0 -> 0; s.windMs <= 7.0 -> 10; else -> 25 }
        p -= when { s.rainMm <= 0.0 -> 0; s.rainMm < 1.0 -> 15; else -> 35 }
        if (day.dust == "나쁨") p -= 20
        return p.coerceIn(0, 100)
    }

    fun judge(past: Boolean, h: Int): Verdict {
        val day = if (past) yesterday else today
        val s = slotAt(day, h)
        val sc = score(day, s)
        val word = when { sc >= 85 -> if (past) "좋았어요" else "좋아요"; sc >= 65 -> if (past) "무난했어요" else "무난해요"; sc >= 45 -> if (past) "별로였어요" else "별로예요"; else -> if (past) "위험했어요" else "위험해요" }
        val label = when { sc >= 85 -> "좋은"; sc >= 65 -> "무난한"; sc >= 45 -> "별로인"; else -> "위험한" }
        val lead = if (past) "출발할 때는 러닝하기 " else "오늘은 러닝하기 "
        val subtitle = "${timeLabel(h)} · ${day.sky} · ${s.tempC}°C · 습도 ${s.humidity}%"
        val temp = when {
            s.tempC in 8..16 -> "몸이 금방 풀리고 오래 달려도 덥지 않아요"
            s.tempC in 4..7 -> "조금 쌀쌀하니 얇은 겉옷을 챙기고 충분히 몸을 풀어주세요"
            s.tempC in 17..22 -> "조금 따뜻해서 땀이 나니 물을 챙기세요"
            s.tempC < 4 -> "많이 추워서 근육이 굳기 쉬우니 준비운동이 꼭 필요해요"
            else -> "더워서 탈수 위험이 있어요"
        }
        val hum = when {
            s.humidity < 30 -> "낮아서 공기가 건조해요. 출발 전에 물을 챙겨요"
            s.humidity <= 55 -> "땀이 비교적 잘 마르는 편이라 달릴 때 몸의 열을 식히기 좋아요"
            s.humidity <= 70 -> "보통 범위예요. 기온이 오르면 같은 습도에서도 더 덥게 느껴질 수 있어요"
            s.humidity <= 85 -> "높은 편이라 땀이 천천히 마르고, 평소 페이스에서도 더 덥게 느낄 수 있어요"
            else -> "매우 높아 땀이 잘 마르지 않아요. 더운 날에는 속도를 줄이고 쉬어 가요"
        }
        val wind = when {
            s.windMs <= 2.0 -> "바람은 거의 없어서 일정한 페이스를 지키기 좋아요"
            s.windMs <= 4.0 -> "바람은 초속 ${s.windMs}m로 산들바람이라 오히려 시원해요"
            s.windMs <= 7.0 -> "바람이 초속 ${s.windMs}m로 불어서 해변길 맞바람 구간에서는 페이스가 느려질 수 있어요"
            else -> "바람이 초속 ${s.windMs}m로 세게 불어서 해변길에서는 위험할 수 있어요"
        }
        val rain = when {
            s.rainMm <= 0.0 -> "비 소식이 없어 길이 말라 있어요"
            s.rainMm < 1.0 -> "약한 비(${s.rainMm}mm)가 와서 길이 살짝 젖을 수 있어요"
            else -> "비(${s.rainMm}mm)가 내려 길이 미끄러우니 실내 운동을 권해요"
        }
        val sb = StringBuilder()
        sb.append("${timeLabel(h)}에는 ${day.sky} 하늘에 기온이 ${s.tempC}도${if (past) "였고" else "라서"} $temp. ")
        sb.append("습도는 ${s.humidity}%로 $hum. ")
        sb.append("$wind. ")
        sb.append("그리고 $rain. ")
        sb.append("미세먼지는 ${day.dust} 수준이에요.")
        if (h >= 20) sb.append(" 밤에는 어두우니 밝은 길로 달리고 반사 밴드를 착용하세요.")
        if (h <= 6) sb.append(" 이른 시간이라 해가 뜨기 전에는 어두울 수 있어요.")
        val bh = (6..24).maxByOrNull { score(day, slotAt(day, it)) } ?: h
        val best = if (bh == h) "이날 중 가장 좋은 시간이에요" else "이날은 ${timeLabel(bh)}에 달리는 게 가장 좋${if (past) "았어요" else "아요"}"
        return Verdict(lead, word, subtitle, sb.toString(), label, sc, best)
    }
}
