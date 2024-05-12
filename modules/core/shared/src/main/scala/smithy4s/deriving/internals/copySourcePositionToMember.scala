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

import smithy4s.schema.Schema

private[deriving] object copyPositionToMember {

  def apply[A](schema: Schema[A]): Schema[A] = {
    schema.hints.get(SourcePosition) match
      case None           => schema
      case Some(position) => schema.addMemberHints(position)
  }

}
