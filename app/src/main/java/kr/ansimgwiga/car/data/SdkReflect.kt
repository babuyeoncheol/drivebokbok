package kr.ansimgwiga.car.data

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Vehicle SDK 도메인(Safety, Seat …)을 메서드 이름으로 찾아 부르는 도우미.
 *
 * 왜 리플렉션인가: 공개 문서에 메서드 이름은 있지만 매개변수·콜백 모양이 버전마다 조금씩 다르다.
 * 이름으로 찾아서 매개변수 타입을 보고 인자를 만들면, 모양이 달라도 빌드가 깨지지 않고
 * 안 맞는 것은 진단 화면에 '실패'로만 뜬다. 공식 샘플 앱도 같은 방식(kotlin-reflect)으로 SDK를 부른다.
 *
 * 인자 규칙
 *  - 콜백(Function0~3): 이름에 fail이 들어가면 실패 콜백, 아니면 값 콜백
 *  - 리스너(ai.pleos.playground.vehicle.listener.*): 프록시로 만든다. onFailed → 실패, 그 밖의 on… → 값
 *  - 영역(…Area): 영역 클래스의 생성자 enum에서 이름으로 만든다(ROW_1_LEFT 등)
 *  - 기본값이 있는 매개변수는 비워 둔다
 */
class SdkDomain(val name: String, private val apiClass: KClass<*>, val instance: Any) {

    val functions: List<KFunction<*>> by lazy {
        runCatching { apiClass.declaredMemberFunctions.toList() }.getOrDefault(emptyList())
    }

    /** 접두사 중 하나로 시작하고 조건에 맞는 메서드. Deprecated가 아닌 것, 이름이 짧은 것을 먼저. */
    fun find(prefixes: List<String>, match: (String) -> Boolean): KFunction<*>? =
        functions
            .filter { f -> prefixes.any { f.name.startsWith(it) } && match(f.name) }
            .sortedWith(compareBy<KFunction<*>>({ f -> f.annotations.any { it is Deprecated } }, { it.name.length }))
            .firstOrNull()

    fun methodNames(): List<String> = functions.map { it.name }.distinct().sorted()
}

class SdkCalls(private val onError: (where: String, error: Throwable) -> Unit) {

    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    /** 영역 매개변수가 있으면 그 영역의 모든 이름(ROW_1_LEFT …), 없으면 null. */
    fun areaNames(fn: KFunction<*>): List<String>? {
        val p = fn.parameters.firstOrNull { it.kind == KParameter.Kind.VALUE && isArea(it) } ?: return null
        val cls = p.type.classifier as? KClass<*> ?: return null
        return areaEnumConstants(cls)?.map { it.name }
    }

    /** 현재 값 읽기. 값은 콜백으로 오거나(대부분) 반환값으로 온다. */
    fun read(domain: SdkDomain, fn: KFunction<*>, area: String?, onValue: (Any?) -> Unit) {
        val where = where(domain, fn, area)
        try {
            val args = buildArgs(fn, domain.instance, area, null, { values -> onValue(values.lastOrNull()) }) {
                onError(where, it)
            }
            val result = fn.callBy(args)
            if (result != null && result != Unit) onValue(result)
        } catch (t: Throwable) {
            onError(where, unwrap(t))
        }
    }

    /** 값이 바뀔 때마다 받기. 해제는 [release]에서 한 번에. */
    fun register(domain: SdkDomain, fn: KFunction<*>, area: String?, onValue: (Any?) -> Unit) {
        val where = where(domain, fn, area)
        try {
            val listenerParam = fn.parameters.firstOrNull { it.kind == KParameter.Kind.VALUE && isListener(it) }
            val listener = (listenerParam?.type?.classifier as? KClass<*>)?.let { cls ->
                listenerFor(cls, { values -> onValue(values.lastOrNull()) }) { onError(where, it) }
            }
            val args = buildArgs(fn, domain.instance, area, listener, { values -> onValue(values.lastOrNull()) }) {
                onError(where, it)
            }
            val result = fn.callBy(args)
            if (result == false) {
                onError(where, IllegalStateException("등록 실패(false)"))
                return
            }
            val unregister = domain.functions.firstOrNull { it.name == "un" + fn.name }
            if (unregister != null && listener != null) {
                cleanups += {
                    unregister.callBy(buildArgs(unregister, domain.instance, area, listener, {}, {}))
                }
            }
        } catch (t: Throwable) {
            onError(where, unwrap(t))
        }
    }

    fun release() {
        cleanups.forEach { runCatching { it() } }
        cleanups.clear()
    }

    // ------------------------------------------------------------------

    private fun buildArgs(
        fn: KFunction<*>,
        target: Any,
        area: String?,
        listener: Any?,
        onValues: (List<Any?>) -> Unit,
        onFail: (Throwable) -> Unit
    ): Map<KParameter, Any?> {
        val args = LinkedHashMap<KParameter, Any?>()
        for (p in fn.parameters) {
            if (p.kind == KParameter.Kind.INSTANCE) {
                args[p] = target
                continue
            }
            val cls = p.type.classifier as? KClass<*>
            val isFailure = p.name?.contains("fail", ignoreCase = true) == true
            when {
                cls == null -> require(p.isOptional) { "${p.name}: 타입을 알 수 없음" }
                cls in FUNCTION_TYPES -> args[p] = callbackFor(cls, isFailure, onValues, onFail)
                isListener(p) -> args[p] = requireNotNull(listener) { "${p.name}: 리스너 필요" }
                isArea(p) -> {
                    if (area != null) args[p] = makeArea(cls, area)
                    else require(p.isOptional) { "${p.name}: 영역 필요" }
                }
                p.isOptional -> Unit
                p.type.isMarkedNullable -> args[p] = null
                else -> throw IllegalArgumentException("${p.name}: ${cls.simpleName} 매개변수는 자동으로 못 채움")
            }
        }
        return args
    }

    private fun callbackFor(
        cls: KClass<*>,
        isFailure: Boolean,
        onValues: (List<Any?>) -> Unit,
        onFail: (Throwable) -> Unit
    ): Any {
        fun deliver(values: List<Any?>) {
            val error = values.firstOrNull() as? Throwable
            if (isFailure || error != null) onFail(error ?: IllegalStateException(values.joinToString()))
            else onValues(values)
        }
        return when (cls) {
            Function0::class -> { val f: () -> Unit = { deliver(emptyList()) }; f }
            Function1::class -> { val f: (Any?) -> Unit = { a -> deliver(listOf(a)) }; f }
            Function2::class -> { val f: (Any?, Any?) -> Unit = { a, b -> deliver(listOf(a, b)) }; f }
            Function3::class -> { val f: (Any?, Any?, Any?) -> Unit = { a, b, c -> deliver(listOf(a, b, c)) }; f }
            else -> throw IllegalArgumentException("콜백 타입 $cls")
        }
    }

    private fun listenerFor(cls: KClass<*>, onValues: (List<Any?>) -> Unit, onFail: (Throwable) -> Unit): Any {
        val type = cls.java
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, rawArgs ->
            when (method.name) {
                "equals" -> proxy === rawArgs?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AnsimListener(${type.simpleName})"
                else -> {
                    val values = rawArgs?.toList().orEmpty()
                    if (method.name.contains("fail", ignoreCase = true)) {
                        onFail(values.firstOrNull() as? Throwable ?: IllegalStateException(method.name))
                    } else {
                        onValues(values)
                    }
                    if (method.returnType == Void.TYPE) null else Unit
                }
            }
        }
    }

    /**
     * 영역 객체 만들기. 실제 SDK는 `SeatArea(vararg SeatValue)`처럼 값을 여러 개 받는 생성자라
     * 매개변수 타입이 enum이 아니라 enum 배열로 보인다. 둘 다 받는다.
     */
    private fun makeArea(areaClass: KClass<*>, name: String): Any {
        val (ctor, enumClass) = areaCtor(areaClass)
            ?: throw IllegalArgumentException("${areaClass.simpleName}: 영역 생성자 없음")
        val value = enumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
            ?: throw IllegalArgumentException("${enumClass.simpleName}.$name 없음")
        val arg: Any = if ((ctor.parameters[0].type.classifier as? KClass<*>)?.java?.isArray == true) {
            java.lang.reflect.Array.newInstance(enumClass, 1).also { java.lang.reflect.Array.set(it, 0, value) }
        } else {
            value
        }
        return ctor.call(arg) as Any
    }

    private fun areaEnumConstants(areaClass: KClass<*>): List<Enum<*>>? =
        areaCtor(areaClass)?.second?.enumConstants?.map { it as Enum<*> }

    /** 매개변수 하나(enum 또는 enum 배열/vararg)짜리 생성자와 그 enum 클래스. */
    private fun areaCtor(areaClass: KClass<*>): Pair<KFunction<*>, Class<*>>? =
        areaClass.constructors.firstNotNullOfOrNull { c ->
            val p = c.parameters.singleOrNull() ?: return@firstNotNullOfOrNull null
            val cls = p.type.classifier as? KClass<*> ?: return@firstNotNullOfOrNull null
            val enumClass = when {
                cls.java.isEnum -> cls.java
                // vararg SeatValue → SeatValue[] (KClass 비교로는 안 잡혀서 자바 배열로 본다)
                cls.java.isArray -> cls.java.componentType
                else -> null
            }
            if (enumClass?.isEnum == true) c to enumClass else null
        }

    private fun isArea(p: KParameter) = (p.type.classifier as? KClass<*>)?.simpleName?.endsWith("Area") == true

    private fun isListener(p: KParameter) =
        (p.type.classifier as? KClass<*>)?.qualifiedName?.startsWith(LISTENER_PACKAGE) == true

    private fun where(d: SdkDomain, fn: KFunction<*>, area: String?) =
        "${d.name}.${fn.name}" + (area?.let { "($it)" } ?: "")

    private fun unwrap(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t

    private companion object {
        const val LISTENER_PACKAGE = "ai.pleos.playground.vehicle.listener."
        val FUNCTION_TYPES = setOf(Function0::class, Function1::class, Function2::class, Function3::class)
    }
}
