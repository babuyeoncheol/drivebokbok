package kr.ansimgwiga.car.data

import kr.ansimgwiga.car.core.LatLng
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 내비 데이터 객체(CurrentLocationInfo 등)에서 위도·경도·장소 이름을 꺼낸다.
 * 필드 이름이 문서에 없어서 이름 패턴(lat / lon·lng, name)으로 찾는다. 한 단계 안쪽 객체까지 본다.
 */
object ObjectFields {

    fun latLng(obj: Any?, depth: Int = 2): LatLng? {
        if (obj == null || depth < 0) return null
        val fields = fieldsOf(obj)
        val lat = fields.firstNumber(obj) { it.startsWith("lat") }
        val lng = fields.firstNumber(obj) { it.startsWith("lon") || it.startsWith("lng") }
        if (lat != null && lng != null && valid(lat, lng)) return LatLng(lat, lng)
        for (f in fields) {
            val inner = read(f, obj) ?: continue
            if (isLeaf(inner)) continue
            latLng(inner, depth - 1)?.let { return it }
        }
        return null
    }

    fun placeName(obj: Any?): String? {
        if (obj == null) return null
        val fields = fieldsOf(obj)
        val preferred = listOf("poiname", "name", "title", "address", "roadaddress")
        for (want in preferred) {
            val f = fields.firstOrNull { it.name.lowercase() == want } ?: continue
            (read(f, obj) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return fields.firstNotNullOfOrNull { f ->
            if (!f.name.lowercase().contains("name")) null else (read(f, obj) as? String)?.takeIf { it.isNotBlank() }
        }
    }

    private fun valid(lat: Double, lng: Double) =
        lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    private fun List<Field>.firstNumber(obj: Any, nameMatch: (String) -> Boolean): Double? =
        firstNotNullOfOrNull { f -> if (nameMatch(f.name.lowercase())) (read(f, obj) as? Number)?.toDouble() else null }

    private fun fieldsOf(obj: Any): List<Field> {
        val out = mutableListOf<Field>()
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java && !c.name.startsWith("java.")) {
            c.declaredFields.filterTo(out) { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            c = c.superclass
        }
        return out
    }

    private fun read(f: Field, obj: Any): Any? = runCatching {
        f.isAccessible = true
        f.get(obj)
    }.getOrNull()

    private fun isLeaf(v: Any) = v is Number || v is CharSequence || v is Boolean || v is Enum<*> ||
        v.javaClass.isArray || v is Collection<*> || v is Map<*, *> || v.javaClass.name.startsWith("java.")
}
