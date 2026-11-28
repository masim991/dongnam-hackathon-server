package com.eudero.gano.data

/** 코스 이름·한 줄 소개·배지. 부산 느낌으로 지은 이름. 코스가 늘면 여기에 추가. */
object CourseInfo {
    class Info(val name: String, val tagline: String, val badge: String)
    private val table = mapOf(
        "HD-BEACHPROM" to Info("파도결 산책길", "부산 하면 가장 먼저 떠오르는 해운대 해수욕장을 따라 걷는 길이에요. 파도 소리가 곁에 있고, 이른 아침엔 모래사장이 고요해서 달리기 전에 숨 고르기 좋아요.", "🌊"),
        "HD-BEACHPROM-EXT" to Info("미포 노을길", "해운대 끝자락 미포로 이어지는 길이에요. 철길 옆으로 작은 가게와 포구가 이어져서, 해 질 녘엔 하늘 색이 바뀌는 걸 보며 달리게 돼요.", "🌅"),
        "HD-BEACHROAD" to Info("네온 해변로", "해변 앞 도로를 따라 달리는 길이에요. 밤이 되면 높은 건물과 가게 불빛이 바다에 비쳐서 도시 러닝의 맛이 나요.", "🌃"),
        "HD-DALMAJI" to Info("달맞이 고갯길", "해운대와 송정 사이의 언덕을 넘는 길이에요. 오르막에선 숨이 차도, 고개 쪽에서 바다가 열리면 그 보상이 확실해요.", "⛰"),
        "HD-DONGBAEK" to Info("동백섬 한 바퀴", "누리마루가 있는 동백섬을 도는 순환 산책로예요. 바다를 끼고 숲 그늘을 지나서, 짧아도 풍경 변화가 많은 코스예요.", "🌺"),
        "HD-GALMAET" to Info("갈맷길 해운대", "해운대 북쪽에서 갈맷길을 따라 걷는 코스예요. 굽이진 길을 지나며 긴 호흡으로 달려보세요.", "🕊"),
        "HD-MOONTAN" to Info("문탠로드", "달맞이 언덕 숲길을 따라 걷는 길이에요. 나무 사이로 바다가 비치고, 밤엔 달빛 아래 조용히 걷기 좋아요.", "🌙"),
        "HD-SONGJEONG" to Info("송정 파도길", "서핑으로 유명한 송정 해변을 따라 달리는 길이에요. 보드를 든 사람들 곁을 달리다 보면 바다가 더 가깝게 느껴져요.", "🏄"),
        "HD-SUYEONG" to Info("수영강 바람길", "수영강을 따라 이어지는 평평한 길이에요. 강바람 덕분에 오래 달려도 덜 힘들고, 천천히 거리를 늘려 가기 좋아요.", "🌬"),
    )
    fun of(code: String, fallbackName: String) = table[code] ?: Info(fallbackName.substringBefore("(").trim(), "부산에서 달리기 좋은 길", "🏃")
}
