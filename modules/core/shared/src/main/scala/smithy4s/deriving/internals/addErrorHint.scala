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

package smithy4s.deriving.internals

import smithy.api.HttpError
import smithy4s.schema.Alt

// Performs a best effort addition of `@error("client") or @error("server")` in case the user forgot it.
private[deriving] object addErrorHint {
  def apply[U, A](alt: Alt[U, A]): Alt[U, A] = {
    val schema = alt.schema
    val newSchema =
      if (schema.hints.has[smithy.api.Error]) schema
      else
        schema.hints.get(HttpError) match {
          case None                            => schema.addHints(smithy.api.Error.CLIENT)
          case Some(code) if code.value >= 500 => schema.addHints(smithy.api.Error.SERVER)
          case Some(_)                         => schema.addHints(smithy.api.Error.CLIENT)
        }
    alt.copy(schema = newSchema)
  }

}
