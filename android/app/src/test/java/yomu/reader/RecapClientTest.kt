package yomu.reader

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class RecapClientTest {

    private var server: HttpServer? = null
    private val requests = AtomicInteger()
    private var requestAuthorization: String? = null

    private val page = "UN RAGAZZO DEL VILLAGGIO, MONKEY D. RUFY / VOGLIO DIVENTARE UN PIRATA! / " +
        "IL CAPITANO DEI PIRATI, SHANKS / BOOOM / NEL PORTO E' ATTRACCATA UNA NAVE PIRATA"

    private fun completion(content: String): String {
        val escaped = content.replace("\n", "\\n")
        return """{"choices":[{"message":{"role":"assistant","content":"$escaped"},"finish_reason":"stop"}]}"""
    }

    private fun serve(respond: (body: String, call: Int) -> Pair<Int, String>): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            requestAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val (status, answer) = respond(body, requests.incrementAndGet())
            val bytes = answer.encodeToByteArray()
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.responseHeaders.add("retry-after", "1")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun scripted(chronicle: String, approved: String = "1,2,3") = serve { body, _ ->
        200 to completion(
            when {
                body.contains("Scegli le 14 righe") -> "1,2,3,4"
                body.contains("Controlli la cronaca") -> approved
                else -> chronicle
            },
        )
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        requests.set(0)
    }

    private fun build(url: String, cache: RecapCache = RecapCache(), onProgress: (RecapProgress) -> Unit = {}) =
        runBlocking {
            RecapClient(mockk(), mockk(), mockk()).build(
                OkHttpClient(),
                ModelEndpoint(url, "test"),
                listOf(MemoryChapter(1.0, listOf(page))),
                cache,
                onProgress,
            )
        }

    @Test
    fun `keeps what the pages support and removes what the model invented`() {
        val url = scripted(
            "CAPITOLO 1\n- Monkey D. Rufy vuole diventare pirata.\n- Rufy raggiunge l'Isola delle Fate con Zoro.",
        )

        val (recap, _) = build(url)

        assertNull(requestAuthorization)
        assertEquals(listOf("Monkey D. Rufy vuole diventare pirata."), recap.chapters.single().sentences)
        assertEquals(1, recap.removed)
        assertTrue(recap.characters.contains("Monkey D. Rufy"))
        assertFalse(recap.characters.any { it.contains("Zoro") })
    }

    @Test
    fun `the final review removes a sentence that is correct word by word but makes no sense`() {
        val url = scripted(
            "CAPITOLO 1\n- Monkey D. Rufy vuole diventare pirata.\n- Il porto diventa capitano del villaggio.\n" +
                "PERSONAGGI: Monkey D. Rufy, Shanks, Zoro",
            approved = "1",
        )

        val (recap, _) = build(url)

        assertEquals(listOf("Monkey D. Rufy vuole diventare pirata."), recap.chapters.single().sentences)
        assertEquals(1, recap.removed)
        assertEquals(listOf("Monkey D. Rufy", "Shanks"), recap.characters)
    }

    @Test
    fun `an unchanged chapter is answered from the cache without calling the service`() {
        val url = scripted("CAPITOLO 1\n- Monkey D. Rufy vuole diventare pirata.")
        val (first, cache) = build(url)
        val calls = requests.get()

        val (second, _) = build(url, cache)

        assertEquals(first, second)
        assertEquals(calls, requests.get())
    }

    @Test
    fun `waits and retries when the free service is busy`() {
        val url = serve { body, call ->
            when {
                call == 1 -> 402 to """{"error":"busy"}"""
                body.contains("Scegli le 14 righe") -> 200 to completion("1,2,3,4")
                else -> 200 to completion("CAPITOLO 1\n- Monkey D. Rufy vuole diventare pirata.")
            }
        }
        val progress = ArrayList<RecapProgress>()

        val (recap, _) = build(url, onProgress = { progress += it })

        assertEquals(1, recap.chapters.size)
        assertTrue(progress.contains(RecapProgress.Waiting(1)))
    }

    @Test
    fun `falls back to the key lines when nothing written can be trusted`() {
        val url = scripted("CAPITOLO 1\n- Zoro sconfigge Mihawk sull'Isola delle Fate.")

        val (recap, cache) = build(url)

        assertTrue(recap.chapters.isEmpty())
        assertTrue(recap.lastLines.isNotEmpty())
        assertNull(cache.key)
    }

    @Test
    fun `a page with only sound effects has nothing to summarize`() {
        val error = assertThrows(RecapClient.RecapException::class.java) {
            runBlocking {
                RecapClient(mockk(), mockk(), mockk()).build(
                    OkHttpClient(),
                    ModelEndpoint("http://127.0.0.1:1", "test"),
                    listOf(MemoryChapter(1.0, listOf("BOOOM / !! / ZZZ"))),
                    RecapCache(),
                ) {}
            }
        }

        assertEquals(MR.strings.yomu_summary_empty, error.reason)
    }
}
