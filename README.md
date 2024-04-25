# smithy4s-deriving

`smithy4s-deriving` is a **Scala-3 only** experimental library that allows to derive instances for the [smithy4s](https://disneystreaming.github.io/smithy4s/) abstractions.

If `smithy4s` is a tool that promotes a code-first approach to API design by providing a code-generator that feeds of smithy specifications, the runtime interpreters it provides are written against a set of abstractions that are not inherently tied to code-generators.

`smithy4s-deriving` provides an alternative way to interact with the smithy4s interpreters, by deriving instances of those abstractions for interfaces and data-types written in pure Scala, thus giving access to a large number of features provided by Smithy4s, with a lower barrier of entry, which allows it to be used in scala-cli projects.

## Installation

The derivation library is provided for the JVM, JS, and Native platforms

```
libraryDependencies ++= "com.neandertech" %%% "smithy4s-deriving" % <version>
```

You'll typically need the following imports to use the derivation :

```scala
import smithy4s.*
import smithy4s.deriving.{given, *}
import smithy.api.* // if you want to use hints from the official smithy standard library
import alloy.* // if you want to use hints from the alloy library
import scala.annotations.experimental // the derivation of API uses experimental metaprogramming features, at this time.
```

## Examples

head other to [the examples](./modules/examples/shared/src/main/scala/) directory to get a feel for how to use it.


## Features

### Derivation of Schemas from case-classes, ADTs and enumerations.

```scala
import smithy4s.*
import smithy4s.deriving.{given, *}

case class Person(firstName: String, lastName: String) derives Schema
```

This allows to access a bunch of serialisation [utilities](https://disneystreaming.github.io/smithy4s/docs/02.1-serialisation/serialisation) and other schema-driven features from smithy4s.

* All case class fields have to have Schemas themselves
* Defaults are supported
* Scaladoc is converted to `@documentation` hints
* Hints can be added on case classes and members alike as such

```scala
import smithy.api._

@hints("")
case class Person(@hints(JsonName("first-name") firstName: String, @hints(JsonName("last-name") lastName))
```

### Derivation of Services from interfaces and classes

```scala
import smithy4s.*
import smithy4s.deriving.{given, *}
import scala.annotations.experimental

@experimental
trait HelloWorldService derives API {

  def hello(name: String, location: Option[String]) : IO[String]

}
```

As you may have noted, instead of deriving `smithy4s.Service`, we're deriving `smithy4s.deriving.API` instead. That is because the `Service` abstraction expects a polymorphic type, whereas our interface is monomorphic. Therefore, the `API` construct allows to turn instances of our interface into a "virtual" interface that does abide by the kind that `smithy4s.Service` expects. Because of this slight mismatch, the user is expected to perform a call to the `.liftService` extension on the instance of the interface when wiring it into interpreters that are coming from smithy4s, such as :

```scala
SimpleRestJsonBuilder
  .routes(new HelloWorldService().liftService[IO])
  .resource
  .map(_.orNotFound)
```

The derivation works for monomorphic interfaces (and concrete classes) that carry methods that are homogenous in effect type. This means that the derivation will fail for an interface like this.

```scala
trait Foo {
  def bar() : IO[Boolean]
  def baz() : Int
}
```

It will however work for direct-style interfaces, such as :

```scala
trait Foo {
  def bar() : Boolean
  def baz() : Int
}
```

or for any other mono-functor effect (`IO`, `Future`, type aliases to `type Result[A] = Either[String, A]`, etc).

### Transformations

It is possible to apply generic transformations to an implementation of a service :

```scala
class Foo() derives API {
  def bar(x: Int) : IO[Int] = IO(x)
}

val addDelay = new PolyFunction[IO, IO]{
  def apply[A](io: IO[A]) : IO[A] = IO.sleep(1.second) *> io
}

new Foo().transform(addDelay)
```

A lot more is possible to achieve, but I don't have time to write much docs.

### Stubs

smithy4s-deriving, combined with the `export` keyword introduced by Scala 3, makes it reasonably easy to implement mocks/stubs for interfaces that have many methods :

```scala
trait Foo() derives API {
  def foo(x: Int): Try[Int]

  def bar(x: Int): Try[Int]
}

// creates a stub that will implement all methods by returning `Failure(Boom)`
val stub = API[Foo].default[Try](Failure(Boom))
val instance = new Foo {
  export stub.{foo => _, *}
  override def foo(x: Int): Try[Int] = Success(x + 2)
}
```

### Re-creating a smithy-model from the derived constructs (JVM only)

See example [here](./modules/examples/jvm/src/main/scala/printSpecs.scala).

### Known limitations

* Default parameters are captured in schemas for case classes, but for methods the `-Yretain-trees` compiler option should be provided.
* `API` derivation is using experimental features from the Scala metaprogramming tooling, which implies an invasive (but justified) requirement to annotate stuff with `@experimental`
* The [smithy to openapi](https://disneystreaming.github.io/smithy4s/docs/protocols/simple-rest-json/openapi) conversion feature provided by smithy4s happens at build-time, via the build plugins. This unfortunately implies that users wanting to use smithy4s-deriving will not benefit from the simpler one-liner allowing to serve swagger-ui.
