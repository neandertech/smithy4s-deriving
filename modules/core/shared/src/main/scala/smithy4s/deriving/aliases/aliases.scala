/*
 * Copyright 2024 Neandertech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package smithy4s.deriving.aliases

import smithy4s.Hints
import smithy4s.deriving.HintsProvider
import smithy.api.*
import alloy.Discriminated
import alloy.Untagged
import alloy.SimpleRestJson
import alloy.proto.ProtoIndex

case class httpGet(uri: String, code: Int = 200) extends HintsProvider {
  def hints = Hints(Http(NonEmptyString("GET"), NonEmptyString(uri), code))
}

case class httpPost(uri: String, code: Int = 200) extends HintsProvider {
  def hints = Hints(Http(NonEmptyString("POST"), NonEmptyString(uri), code))
}

case class httpPatch(uri: String, code: Int = 200) extends HintsProvider {
  def hints = Hints(Http(NonEmptyString("PATCH"), NonEmptyString(uri), code))
}

case class httpDelete(uri: String, code: Int = 200) extends HintsProvider {
  def hints = Hints(Http(NonEmptyString("DELETE"), NonEmptyString(uri), code))
}

case class httpPut(uri: String, code: Int = 200) extends HintsProvider {
  def hints = Hints(Http(NonEmptyString("PUT"), NonEmptyString(uri), code))
}

case class httpLabel() extends HintsProvider {
  def hints = Hints(HttpLabel())
}

case class httpQuery(name: String) extends HintsProvider {
  def hints = Hints(HttpQuery(name))
}

case class httpHeader(name: String) extends HintsProvider {
  def hints = Hints(HttpHeader(name))
}

case class httpPayload() extends HintsProvider {
  def hints = Hints(HttpPayload())
}

case class httpError(code: Int) extends HintsProvider {
  def hints = Hints(HttpError(code))
}

case class readonly() extends HintsProvider {
  def hints = Hints(Readonly())
}

case class idempotent() extends HintsProvider {
  def hints = Hints(Idempotent())
}

case class jsonName(name: String) extends HintsProvider {
  def hints = Hints(JsonName(name))
}

case class simpleRestJson() extends HintsProvider {
  def hints = Hints(SimpleRestJson())
}

case class discriminated(fieldName: String) extends HintsProvider {
  def hints = Hints(Discriminated(fieldName))
}

case class untagged() extends HintsProvider {
  def hints = Hints(Untagged())
}

case class protoIndex(value: Int) extends HintsProvider {
  def hints = Hints(ProtoIndex(value))
}
