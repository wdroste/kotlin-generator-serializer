package flow

import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test


fun flow(inform: Inform) = generate<Request, Response> {
    println("Inform: $inform")
    var resp = yield(GPV(listOf("r.1"))) as GPVResp
    println("Response 1: $resp")
    resp = yield(GPV(listOf("r.2"))) as GPVResp
    println("Response 2: $resp")
}

class GeneratorUnitTests {
    
    @Test
    fun testGenerator() {
        val g = flow(Inform("my_id"))

        var req = g.next(Start())
        println("Request 1: $req")
        // first response
        req = g.next(GPVResp(mapOf(Pair("x", "1"))))
        println("Request #2 - $req")
        // first response
        req = g.next(GPVResp(mapOf(Pair("x", "1"))))
        println("Final - $req")
    }

    @Test
    fun testSerialize() {
        runBlocking {
            val g = flow(Inform("my_id"))

            var req = g.next(Start())
            println("Request 1: $req")

            val bytes = KryoHelper.serialize(g)
            println("Data Size: ${bytes.size}")

            val g2: Generator<Request, Response> = KryoHelper.deserialize(bytes)
            // first response
            req = g2.next(GPVResp(mapOf(Pair("x", "1"))))
            println("Request #2 - $req")
            // first response
            req = g2.next(GPVResp(mapOf(Pair("x", "1"))))
            println("Final - $req")
        }
    }


}
