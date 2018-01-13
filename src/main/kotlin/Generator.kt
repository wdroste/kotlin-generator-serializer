package flow

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import org.objenesis.strategy.StdInstantiatorStrategy
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.EmptyCoroutineContext
import kotlin.coroutines.experimental.RestrictsSuspension
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn
import kotlin.coroutines.experimental.startCoroutine

// Interfaces for type-safety
interface Request

interface Response

data class Inform(val id: String)


data class Start(val id: String = "start") : Response

/**
 * Get Parameter Values Request
 */
data class GPV(val parameterName: List<String>) : Request

/**
 * Get Parameter Values Response
 */
data class GPVResp(val parameterKeyValue: Map<String, String>) : Response


/*
   ES6-style generator that can send values between coroutine and outer code in both ways.

   Note that in ES6-generators the first invocation of `next()` goes not accept a parameter, but
   just starts a coroutine until a subsequent `yield`, so to adopt it for the type-safe interface
   we must declare `next` to be always invoked with a parameter and make our coroutine receive the first
   parameter to `next` when it starts (so it is not lost). We also have to introduce an additional parameter to
   `yieldAll` to start a delegated generator.
*/

fun <T, R> generate(block: suspend GeneratorBuilder<T, R>.(R) -> Unit): Generator<T, R> {
    val coroutine = GeneratorCoroutine<T, R>()
    val initial = suspend<R> { result -> coroutine.block(result) }
    coroutine.nextStep = { param -> initial.startCoroutine(param, coroutine) }
    return coroutine
}

object KryoHelper {
    val kryo = ThreadLocal.withInitial({
        val k = Kryo()
        k.instantiatorStrategy = Kryo.DefaultInstantiatorStrategy(StdInstantiatorStrategy())
        k
    })

    fun <T, R> serialize(generator: Generator<T, R>): ByteArray {
        val o = Output(4096)
        val k = kryo.get()!!
        val g = generator as GeneratorCoroutine<T, R>
        k.writeClassAndObject(o, g)
        k.writeClassAndObject(o, g.nextStep)
        k.writeClassAndObject(o, g.lastValue)
        k.writeClassAndObject(o, g.lastException)
        k.writeClassAndObject(o, g.lastContinuation)
        return o.toBytes()!!
    }

    fun <T, R> deserialize(data: ByteArray): Generator<T, R> {
        val k = kryo.get()!!
        val input = Input(data)

        val g = k.readClassAndObject(input) as GeneratorCoroutine<T, R>
        g.nextStep = k.readClassAndObject(input) as (R) -> Unit
        g.lastValue = k.readClassAndObject(input) as T?
        g.lastException = k.readClassAndObject(input) as Exception?
        g.lastContinuation = k.readClassAndObject(input) as Continuation<R>

//        var coro = g.lastContinuation!!
//        coro::class.java.getDeclaredField("result").apply {
//            isAccessible = true
//            set(g.lastContinuation, COROUTINE_SUSPENDED)
//        }
        return g
    }
}

@RestrictsSuspension
interface GeneratorBuilder<in T, R> {
    suspend fun yield(value: T): R
    suspend fun yieldAll(generator: Generator<T, R>, param: R)
}

interface Generator<out T, in R> {
    fun next(param: R): T? // returns `null` when generator is over
}

// helper function to create suspending function
private fun <T> suspend(block: suspend (T) -> Unit): suspend (T) -> Unit = block

// Generator coroutine implementation class
internal class GeneratorCoroutine<T, R> : Generator<T, R>, GeneratorBuilder<T, R>, Continuation<Unit> {
    lateinit var nextStep: (R) -> Unit
    var lastValue: T? = null
    var lastException: Throwable? = null
    var lastContinuation: Continuation<R>? = null

    // Generator<T, R> implementation

    override fun next(param: R): T? {
        nextStep(param)
        lastException?.let { throw it }
        return lastValue
    }

    // GeneratorBuilder<T, R> implementation

    suspend override fun yield(value: T): R = suspendCoroutineOrReturn { cont ->
        lastValue = value
        nextStep = { param -> cont.resume(param) }
        lastContinuation = cont
        COROUTINE_SUSPENDED
    }

    suspend override fun yieldAll(generator: Generator<T, R>, param: R): Unit = suspendCoroutineOrReturn sc@ { cont ->
        lastValue = generator.next(param)
        if (lastValue == null) {
            // delegated coroutine does not generate anything -- resume
            return@sc Unit
        }
        nextStep = { param ->
            lastValue = generator.next(param)
            if (lastValue == null) {
                // resume when delegate is over
                cont.resume(Unit)
            }
        }
        COROUTINE_SUSPENDED
    }

    // Continuation<Unit> implementation

    override val context: CoroutineContext get() = EmptyCoroutineContext
    override fun resume(value: Unit) {
        lastValue = null
    }

    override fun resumeWithException(exception: Throwable) {
        lastException = exception
    }
}
