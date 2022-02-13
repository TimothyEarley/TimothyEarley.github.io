@file:Suppress("SpellCheckingInspection")

fun main() {

    val f = ExampleProperties.filter {
        (1 eq id) and (name eq "test")
    }

    println(f)
    println(f.build())

    val f2 = ExampleProperties.filter {
        (int(1) eq id) and (name eq string("test"))
    }

    println(f2)
    println(f2.build())

    val g = ExampleProperties.filter2 {
        (1 eq2 id) and2 (name eq2 "test")
    }

    println(g)
    println(g.build())
}

fun interface FilterPart<out T> {
    fun build(): String
}

fun string(s : String) = FilterPart<String> { "'$s'" }
fun int(i : Int) = FilterPart<String> { i.toString() }


typealias Filter = FilterPart<Boolean>

class Property<T>(
    private val name: String
) : FilterPart<T> {
    override fun build(): String = name
}

object ExampleProperties {
    val id = Property<Int>("id")
    val name = Property<String>("name")
}

interface Expressable<T> {
    fun T.asFilterPart(): FilterPart<T>
}

data class BiOpFilter<T>(
    private val left: FilterPart<*>,
    private val op: String,
    private val right: FilterPart<*>
) : FilterPart<T> {
    override fun build(): String = "(${left.build()} $op ${right.build()})"
}

object IntExpressable : Expressable<Int> {
    override fun Int.asFilterPart() = FilterPart<Int> { this.toString() }
}

object StringExpressable : Expressable<String> {
    override fun String.asFilterPart() = FilterPart<String> { "'$this'" }
}

fun <E> E.filter(f : context(Expressable<Int>, Expressable<String>) E.() -> Filter): Filter = f(IntExpressable, StringExpressable, this)

context(Expressable<T>)
infix fun <T> T.eq(other : T) : Filter = this.asFilterPart() eq other.asFilterPart()

context(Expressable<T>)
infix fun <T> T.eq(other : FilterPart<T>) : Filter = this.asFilterPart() eq other

context(Expressable<T>)
infix fun <T> FilterPart<T>.eq(other : T) : Filter = this eq other.asFilterPart()


infix fun <T> FilterPart<T>.eq(other : FilterPart<T>) : Filter = BiOpFilter(this, "=", other)

infix fun Filter.and(other: Filter): Filter = BiOpFilter(this, "and", other)


// --------------------------------

interface ExpressableAs<T, R> {
    fun T.toFilterPart(): FilterPart<R>
}

fun <T> Expressable<T>.toExpressableAs(): ExpressableAs<T, T> = object : ExpressableAs<T, T> {
    override fun T.toFilterPart(): FilterPart<T> = this.asFilterPart()
}

fun <T> filterPartExpressableAs(): ExpressableAs<FilterPart<T>, T> = object : ExpressableAs<FilterPart<T>, T> {
    override fun FilterPart<T>.toFilterPart(): FilterPart<T> = this
}

fun <E> E.filter2(f : context(ExpressableAs<Int, Int>, ExpressableAs<FilterPart<Int>, Int>,
                              ExpressableAs<String, String>, ExpressableAs<FilterPart<String>, String>,
                              ExpressableAs<FilterPart<Boolean>, Boolean>) E.() -> Filter): Filter =
        f(IntExpressable.toExpressableAs(), filterPartExpressableAs(),
          StringExpressable.toExpressableAs(), filterPartExpressableAs(),
          filterPartExpressableAs(), this)

context (ExpressableAs<A, T>, ExpressableAs<B, T>)
infix fun <A, B, T> A.eq2(other : B) : Filter =
    BiOpFilter(this.toFilterPart(), "=", other.toFilterPart()
)

context (ExpressableAs<A, Boolean>, ExpressableAs<B, Boolean>)
infix fun <A, B> A.and2(other: B): Filter = BiOpFilter(this.toFilterPart(), "and", other.toFilterPart())


