# smithy4s-deriving

`smithy4s-deriving` is a **Scala-3 only** experimental library that allows to automatically derive instances of the [smithy4s](https://disneystreaming.github.io/smithy4s/) abstractions from scala constructs.

If `smithy4s` is a tool that promotes a spec-first approach to API design, providing a code-generator that feeds of smithy specifications, the runtime interpreters it provides are written against a set of abstractions that are not inherently tied to code-generators.

`smithy4s-deriving` provides a code-first alternative way to code-generation to interact with these interpreters, by using handcrafted data-types and interfaces written directly in Scala as the source of truth, thus giving access to a large number of features provided by Smithy4s, with a lower barrier of entry. In particular, this enables usage in scala-cli projects.

## Installation

Scala 3.4.1 or newer is required.

SBT :

```
"com.neandertech" %% "smithy4s-deriving" % <version>
```

The rest of the world :

```
"com.neandertech::smithy4s-deriving:<version>"
```

You'll typically need the following imports to use the derivation :

```scala
import smithy4s.*
import smithy4s.deriving.{given, *}
import smithy.api.* // if you want to use hints from the official smithy standard library
import alloy.* // if you want to use hints from the alloy library
import scala.annotations.experimental // the derivation of API uses experimental metaprogramming features, at this time.
```


The `smithy4s-deriving` library is provided for Scala JVM and Scala JS (1.16+), and is currently compatible with smithy4s 0.18.16+.

Scala Native support will come after smithy4s upgrades to SN `0.5.+`, which will take time.

## Examples

head other to [the examples](./modules/examples/shared/src/main/scala/) directory to get a feel for how to use it.

## Derivation of case-classes schemas

`smithy4s-deriving` allows for deriving schemas from case classes.

```scala
import smithy4s.*
import smithy4s.deriving.{given, *}

case class Person(firstName: String, lastName: String) derives Schema
```

This allows to access a bunch of serialisation [utilities](https://disneystreaming.github.io/smithy4s/docs/02.1-serialisation/serialisation) and other schema-driven features from smithy4s.

It is possible to customise the behaviour of serialisers in a similar way that you'd have in spec-first smithy4s, by annotating data-types / fields with the `@hints` annotation, and passing it instances of datatypes that were generated from smithy-traits by smithy4s. For instance :

```scala
package example

import smithy.api.*

case class Person(@hints(JsonName("first-name")) firstName: String = "John", @hints(JsonName("last-name")) lastName = "Doe") derives Schema
```

is semantically equivalent to :

```smithy
namespace example

structure Person {
  @jsonName("first-name")
  @required
  firstName: String = "John"

  @jsonName("last-name")
  @required
  lastName: String = "Doe"
}
```


### NB :

* All case class fields must have implicit (given) schemas available
* Defaults are supported
* Scaladoc is converted to `@documentation` hints

## Derivation of ADTs schemas

`smithy4s-deriving` allows for deriving schemas from ADTs.

```scala
import smithy4s.*
import smithy4s.deriving.{given, *}

enum Foo derives Schema {
  case Bar(x: Int, y: Boolean)
  case Baz(z: String)
}
```

It is possible to :
* use the `@hints` annotation on ADTs and their members.
* use the `@hints.member` annotation on ADT members to distinguish whether hints should go to the member or the target shape at the smithy level.
* use the `@wrapper` on single-field case classes in order to prevent a layer of "structure" schema from being created.

For instance :

```scala
package example

import smithy4s.*
import smithy4s.deriving.{given, *}

import smithy.api.*

enum Foo derives Schema {
  @hints(Documentation("Some docs"))
  @hints.member(JsonName("bar")) // note the different annotation to target the smithy member
  case Bar(x: Int, y: Boolean)

  @hints.member(JsonName("baz"))
  @wrapper // note the wrapper annotation here
  case Baz(z: String)
}
```

Is equivalent to the combination of these smithy specs :

```smithy
namespace example

use example.foo#Bar

union Foo {
  @JsonName("baz")
  Bar: Bar
  @JsonName("bar")
  Baz: String
}
```

and

```smithy
namespace example.foo

///Some docs
structure Bar {
  @required
  x: Integer
  @required
  y: String
}
```



## Derivation of Services from interfaces/classes

```scala
import smithy4s.*
import smithy4s.deriving.{given, *}
import scala.annotations.experimental

@experimental
trait HelloWorldService derives API {

  def hello(name: String, location: Option[String]) : IO[String]

}
```

This allows to access whatever interpreters are provided by smithy4s or its downstream libraries. These interpreters are how smithy4s integrates with various libraries and protocols.

* It is possible to use hints on interfaces to customise interpreter behaviour.
* It is also possible to use the `@errors` annotation to tie error handling to either services

For instance:

```scala
package example

import smithy4s.*
import smithy4s.deriving.{given, *}
import smithy.api.*
import alloy.*
import scala.annotations.experimental

@hints(HttpError(403))
case class Bounce(message: String) extends Throwable derives Schema
@hints(HttpError(500))
case class Crash(cause: String) extends Throwable derives Schema

@experimental
@hints(SimpleRestJson())
trait HelloWorldService derives API {

  @errors[(BadLocation, Crash)]
  @hints(Http("GET", "/hello/{name}", 200))
  def hello(
    @hints(HttpLabel()) name: String,
    @hints(HttpQuery("from")) location: Option[String]
  ) : IO[String]

}
```

Is semantically equivalent to the combination of these smithy specs :

```smithy
$version: "2"

namespace example

use alloy#simpleRestJson
use example.foo#hello

@simpleRestJson
service Foo {
  operations: [hello]
}

@error("client")
@httpError(403)
structure Bounce {
  @required
  message: String
}

@error("server")
@httpError(500)
structure Crash {
  @required
  cause: String
}
```

and

```smithy
$version: "2"

namespace example.foo

use example#Bounce
use example#Crash

@http(method: GET, uri: "/hello/{name}", code: 200)
operation hello {
  input := {
    @required
    @httpLabel
    name: String

    @httpQuery("from")
    location: String
  }
  output := {
    @httpPayload
    value: String
  }

  errors: [Bounce, Crash]
}
```


### NB :

* All parameters of methods and all output types (within the effect) must have implicit (given) schemas available.
* Defaults are supported
* Scaladoc is converted to `@documentation` hints


## Difference between smithy4s.deriving.API and and smithy4s.Service

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

It is unfortunately impossible for `smithy4s-deriving` to validate, at compile time, the correct usage of the application of `@hints`. However, it is possible to automatically recreate a smithy model from the derived abstractions, and to run the smithy validators. One could use this in a unit test, for instance, to verify the correctness of their services according to the rules of smithy.

See an example of how to do that [here](./modules/examples/jvm/src/main/scala/printSpecs.scala).

### Known limitations

* Default parameters are captured in schemas of case classes, but not for methods, unless the `-Yretain-trees` compiler option is set.
* `API` derivation is using experimental features from the Scala meta-programming tooling, which implies an invasive (but justified) requirement to annotate stuff with `@experimental`
* The [smithy to openapi](https://disneystreaming.github.io/smithy4s/docs/protocols/simple-rest-json/openapi) conversion feature provided by smithy4s happens at build-time, via the build plugins. This unfortunately implies that users wanting to use smithy4s-deriving will not benefit from the simpler one-liner allowing to serve swagger-ui.
