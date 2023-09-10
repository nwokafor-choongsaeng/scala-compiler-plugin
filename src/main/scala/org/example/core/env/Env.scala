package org.example.core.env

import org.example.core.annotations._

trait Env {
    type Value
    type String <: Value
    type Bool <: Value

    @IntType
    type Int <: Value
    type RowSet[T]

    implicit def fromString(v: scala.Predef.String): String

    implicit def fromBoolean(v: scala.Boolean): Bool

    def map[A, B](mapper: A => B, dataset: RowSet[A]): RowSet[B]
}