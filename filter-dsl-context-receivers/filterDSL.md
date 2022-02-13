---
title: DSL with new context receivers
mainfont: Cantarell, "Helvetica Neue", Helvetica, Arial, sans-serif
header-includes: |
  <style>
  pre.sourceCode {
      margin: 10px;
  }
  </style>
---

![](images/title.jpg){ width=100% }

The problem
-----------

Kotlin has amazing capabilities for writing DSLs. A nice use case of this is in SQL or SQL-like where clauses. Consider the following example:

```kotlin
People.filter { (name eq "test") and (id eq 1) }
```

This reads nicely and many libraries support this style, for example, [Exposed](https://github.com/JetBrains/Exposed/wiki/DSL#read), [Ktorm](https://www.ktorm.org/en/query.html#where) and [kdbc](https://github.com/edvin/kdbc).

When implementing this myself (not for SQL) I ran into a problem: How to limit the types used in the query. This was important because I only knew how to convert certain types into the query format (such as String, Int, LocalDate, but not Lists, ByteArray, etc.) [^1].

Setup
-----

Let's first get an understanding of the basic setup. An entity is defined via its properties [^2]. Here this consists of a generic type and a name.

```kotlin
data class Property<T>(val name : String)

object People {
    val name = Property<String>("name")
    val id = Property<Int>("id")
}
```

A `Filter`{.kotlin} is made up of parts that are joined together by operators. We want each part of a filter to be typed and know how to convert itself to the output query. A filter then is nothing special: Just a part that happens to have a type of Boolean.

```kotlin
fun interface FilterPart<T> {
    fun build() : String
}

typealias Filter = FilterPart<Boolean>
```

We are now ready for the first version of the filter function. It is quite trivial:

```kotlin
fun <E> E.filter(f : E.() -> Filter): Filter = f(this)
```
The interesting parts are the the `eq`{.kotlin} and `and`{.kotlin} function.

All good things come in twos
----------------------------

Before going into their implementation we note that both functions take two existing parts and combine them. So let's create a quick helper class for this.

```kotlin
class BiOpFilterPart<T>(
    val left : FilterPart<*>,
    val op : String,
    val right : FilterPart<*>
) : FilterPart<T> {
    override fun build(): String =
        "(${left.build()} $op ${right.build()})"
}
```

Since the types of the left and right parts are irrelevant, only the output type is used, we can just star them out.

A first draft
-------------

A simple implementation of `and`{.kotlin} and `eq`{.kotlin} looks like this:

```kotlin
infix fun Filter.and(other : Filter) : Filter =
    BiOpFilterPart(left, "and", right)

infix fun <T> FilterPart<T>.eq(other : FilterPart<T>) : Filter =
    BiOpFilterPart(left, "eq", right)
```

Does that cover the DSL? Well, no. We cannot simply write `name eq "test"`{.kotlin} since neither name nor "test" are `FilterPart`s. For the name property this is a simple fix, by letting `Property<T>`{.kotlin} implement `FilterPart<T>`{.kotlin}.

```kotlin
class Property<T>(
    private val name: String
) : FilterPart<T> {
    override fun build(): String = name
}
```

The same cannot be done for `"test"`{.kotlin}. We cannot change the String class.

A second attempt
----------------

So let's just let our `eq` function take `T`:

```kotlin
infix fun <T> FilterPart<T>.eq(other : T) : Filter =
    BiOpFilterPart(left, "eq", wrap(right))
```

Then just define a function `wrap` that switches on type T to create a fitting `FilterPart`. This is how [Exposed does it](https://github.com/JetBrains/Exposed/blob/3070d054119c7d8840e9e8fd0376a3dbf1a9692b/exposed-core/src/main/kotlin/org/jetbrains/exposed/sql/SQLExpressionBuilder.kt#L140). However, this is where my problem comes in. Exposed can do this because it knows the type T will be a type it can handle. I do not.

A compromise
------------

My initial compromise was to create wrapper functions.
```kotlin
fun string(s : String) = FilterPart<String> { "'$s'" }
fun int(i : Int) = FilterPart<String> { i.toString() }
```

We then have to bite the bullet and write `(id eq int(1)) and (name eq string("test"))`{.kotlin}. This still reads fine and uses the simple definitions in the first draft.


Here come the context receivers
-------------------------------

![](images/grass.jpg){ width=100% }


What we really want is to tell the `eq` function to take in any `T`, but only if there is also a way to express that type in the query output. Enter the [new context receivers](https://github.com/Kotlin/KEEP/blob/master/proposals/context-receivers.md). We define an interface `Expressable`:

```kotlin
interface Expressable<T> {
    fun T.asFilterPart(): FilterPart<T>
}
```

and require it for the `eq` function. We can delegate back to the function from the first draft:

```kotlin
context(Expressable<T>)
infix fun <T> FilterPart<T>.eq(other : T) : Filter =
    this eq other.asFilterPart()
```

To inject this into the scope where needed we modify the `filter` function

```kotlin
object IntExpressable : Expressable<Int> { ... }
object StringExpressable : Expressable<String> { ... }

fun <E> E.filter(
    f : context(Expressable<Int>, Expressable<String>) E.() -> Filter
): Filter =
    f(IntExpressable, StringExpressable, this)
```

And voilà; our goal has been achieved. We can use constants in the filter without wrapping them first and effectively control which types are allowed [^3]. 


Going further (than necessary?)
-------------------------------

The previous solution has a drawback in that we need to overload the `eq` method to achieve both `name eq "test"`{.kotlin} and `"test" eq name`{.kotlin}. Can we unify them into one mega-function? My idea here is to create a new "type class" `ExpressableAs<T, R>`{.kotlin} which denotes that we know how to express something of type T into a query of type R.

```kotlin
interface ExpressableAs<T, R> {
    fun T.toFilterPart(): FilterPart<R>
}
```

The simple cases `ExpressableAs<String, String>`{.kotlin} and `ExpressableAs<Int, Int>`{.kotlin} can be derived from `IntExpressable`{.kotlin} and `StringExpressable`{.kotlin} respectively. The new case of `ExpressableAs<Property<T>, T>`{.kotlin} is actually a special case of `ExpressableAs<FilterPart<T>, T>`{.kotlin}. This is easily implemented by 

```kotlin
fun <T> filterPartExpressableAs(): ExpressableAs<FilterPart<T>, T> =
    object : ExpressableAs<FilterPart<T>, T> {
        override fun FilterPart<T>.toFilterPart(): FilterPart<T> =
            this
    }
```

Armed with the new interface we define a single `eq`{.kotlin} function as follows (and also a new `and`, because why not)

```kotlin
context (ExpressableAs<A, T>, ExpressableAs<B, T>)
infix fun <A, B, T> A.eq2(other : B) : Filter =
    BiOpFilter(this.toFilterPart(), "=", other.toFilterPart())

context (ExpressableAs<A, Boolean>, ExpressableAs<B, Boolean>)
infix fun <A, B> A.and(other: B): Filter =
    BiOpFilter(this.toFilterPart(), "and", other.toFilterPart())
```

Sadly, there is one snag: Even though we can create `ExpressableAs<FilterPart<T>, T>`{.kotlin} for any type T, to actually use for a specific type we need to bring a specific instance into scope. The `filter`{.kotlin} function now looks like a mess[^4]:

```kotlin
fun <E> E.filter(
    f : context(ExpressableAs<Int, Int>,
                ExpressableAs<FilterPart<Int>, Int>,
                ExpressableAs<String, String>,
                ExpressableAs<FilterPart<String>, String>,
                ExpressableAs<FilterPart<Boolean>, Boolean>
        ) E.() -> Filter): Filter = TODO()
```

Maybe we should have stopped at the previous solution.


Conclusion
----------

We have seen how context receivers can help keep our DSL clean by plumbing through needed information. I'm looking forward to what other uses the community can up using this new Kotlin feature.

If anyone comes up with a better solution please let me know. Maybe an alternative would be [Arrow proofs](https://arrow-kt.io/docs/meta/proofs/)? If it gets picked back up again.


[^1]: And the types in the entities could contain such an unsupported type
[^2]: In an actual system it would also contain further metadata such as the table name
[^3]: If a client knows how to express some other types as well, it can easily add them to the context.
[^4]: Can you spot why we need `ExpressableAs<FilterPart<Boolean>, Boolean>`?