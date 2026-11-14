package kr.ansimgwiga.car.core

/**
 * Vehicle SDK가 돌려주는 값을 우리 신호로 바꾸는 규칙.
 * SDK 값의 타입(Boolean, Int, enum …)이 문서마다 달라서, 어떤 모양이 와도 최대한 읽고 모르면 null로 둔다.
 * null이면 신호를 만들지 않고 진단 화면에 원래 값만 보여 준다.
 */
object SdkValues {

    /** 켜짐/경고/전개/체결 = true, 꺼짐/정상/닫힘 = false, 모르면 null. */
    fun asBool(v: Any?): Boolean? = when (v) {
        null -> null
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is Enum<*> -> word(v.name)
        is CharSequence -> v.toString().toBooleanStrictOrNull() ?: word(v.toString())
        is Collection<*> -> v.map { asBool(it) }.let { list -> if (list.any { it == true }) true else if (list.all { it == false } && list.isNotEmpty()) false else null }
        is Map<*, *> -> asBool(v.values)
        else -> null
    }

    /** 와이퍼: OFF·STOP·PARK만 꺼짐, 나머지 모드(LOW, HIGH, AUTO, INTERMITTENT …)는 켜짐. */
    fun wiperOn(v: Any?): Boolean? = when (v) {
        null -> null
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is Enum<*>, is CharSequence -> {
            val u = (if (v is Enum<*>) v.name else v.toString()).uppercase()
            when {
                "UNKNOWN" in u || "UNAVAILABLE" in u -> null
                "OFF" in u || "STOP" in u || "PARK" in u || "NONE" in u -> false
                else -> true
            }
        }
        else -> null
    }

    private fun word(raw: String): Boolean? {
        val u = raw.uppercase()
        return when {
            "UNKNOWN" in u || "UNAVAILABLE" in u || "NOT_AVAILABLE" in u -> null
            "ABNORMAL" in u || "WARN" in u || "LOW" in u || "CRITICAL" in u || "ERROR" in u || "FAULT" in u -> true
            u.startsWith("NOT") || "_NOT" in u || u.startsWith("UN") || "OFF" in u || "NONE" in u ||
                "NORMAL" in u || "FALSE" in u || "INACTIVE" in u || "CLOSE" in u || u == "OK" -> false
            "ON" in u || "TRUE" in u || "DEPLOY" in u || "BUCKLED" in u || "OPEN" in u || "ACTIVE" in u -> true
            else -> null
        }
    }

    /** 숫자 값(거리 등). 모르면 null. */
    fun asDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is CharSequence -> v.toString().toDoubleOrNull()
        else -> null
    }

    /**
     * 좌석 영역 이름 → 좌석. 국내 차(왼쪽 운전석) 기준: ROW_1_LEFT = 운전석.
     * ROW_2 이상은 모두 뒷좌석으로 본다.
     */
    fun seatPos(area: String?): SeatPos? {
        val u = area?.uppercase() ?: return null
        val row = Regex("ROW_(\\d)").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val side = when {
            "LEFT" in u -> "L"
            "RIGHT" in u -> "R"
            "CENTER" in u || "MIDDLE" in u -> "C"
            else -> return null
        }
        return if (row == 1) {
            when (side) { "L" -> SeatPos.DRIVER; "R" -> SeatPos.PASSENGER; else -> null }
        } else {
            when (side) { "L" -> SeatPos.REAR_LEFT; "R" -> SeatPos.REAR_RIGHT; else -> SeatPos.REAR_CENTER }
        }
    }

    /** 문 영역 이름 → 문. 후드·트렁크 등은 null. */
    fun doorPos(area: String?): DoorPos? = when (seatPos(area)) {
        SeatPos.DRIVER -> DoorPos.DRIVER
        SeatPos.PASSENGER -> DoorPos.PASSENGER
        SeatPos.REAR_LEFT -> DoorPos.REAR_LEFT
        SeatPos.REAR_RIGHT -> DoorPos.REAR_RIGHT
        else -> null
    }

    /** 진단 화면용 짧은 글자. 객체 주소(@1a2b3c)는 지운다. */
    fun describe(v: Any?): String {
        val text = when (v) {
            null -> "null"
            is Enum<*> -> v.name
            is Array<*> -> v.contentDeepToString()
            is IntArray -> v.contentToString()
            is FloatArray -> v.contentToString()
            is BooleanArray -> v.contentToString()
            else -> v.toString()
        }
        return text.replace(Regex("([A-Za-z_$][\\w$]*\\.)+([A-Za-z_$][\\w$]*)@[0-9a-fA-F]+"), "$2").take(300)
    }
}
