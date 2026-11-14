package kr.ansimgwiga.car.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerApiTest {

    @Test
    fun normalizesWhateverUrlIsPasted() {
        assertEquals("https://abcd.supabase.co", ServerApi.normalizeBase("https://abcd.supabase.co/"))
        assertEquals("https://abcd.supabase.co", ServerApi.normalizeBase("  abcd.supabase.co "))
        assertEquals("https://abcd.supabase.co", ServerApi.normalizeBase("https://abcd.supabase.co/rest/v1/events"))
        assertEquals("http://10.0.2.2:54321", ServerApi.normalizeBase("http://10.0.2.2:54321/rest/v1/"))
        assertEquals("", ServerApi.normalizeBase("   "))
        assertEquals("https://abcd.supabase.co/rest/v1/events", ServerApi.eventsUrl("https://abcd.supabase.co/rest/v1/"))
        assertEquals("https://abcd.supabase.co/rest/v1/rpc/car_cheers", ServerApi.rpcUrl("abcd.supabase.co", "car_cheers"))
    }

    @Test
    fun headersDependOnKeyKind() {
        assertEquals(mapOf("apikey" to "sb_publishable_x"), ServerApi.authHeaders(" sb_publishable_x "))
        assertEquals(mapOf("apikey" to "eyJhbGc", "Authorization" to "Bearer eyJhbGc"), ServerApi.authHeaders("eyJhbGc"))
        assertTrue(ServerApi.authHeaders("").isEmpty())
    }

    @Test
    fun parsesCheers_skippingUnknownAndBroken() {
        val json = """[{"id":12,"preset":"LOVE","created_at":"2026-11-06T05:00:00+00:00"},
            {"id":9,"preset":"SAFE","created_at":"2026-11-06T04:59:00+00:00"},
            {"id":13,"preset":"HACK","created_at":"x"}, {"preset":"SLOW"}]"""
        assertEquals(
            listOf(ServerApi.Cheer(9, CheerPreset.SAFE), ServerApi.Cheer(12, CheerPreset.LOVE)),
            ServerApi.parseCheers(json)
        )
        assertTrue(ServerApi.parseCheers("[]").isEmpty())
        assertTrue(ServerApi.parseCheers("{\"message\":\"permission denied\"}").isEmpty())
        assertEquals("{\"p_code\":\"K7M2QX\",\"p_after_id\":5}", ServerApi.carCheersBody("K7M2QX", 5))
    }
}
