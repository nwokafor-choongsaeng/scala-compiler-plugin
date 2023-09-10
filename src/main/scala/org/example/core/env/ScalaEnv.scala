package org.example.core.env

trait ScalaEnv extends Env {
    override type Value = scala.Any
    override type String = Predef.String
    override type Bool = Boolean

    override type RowSet[T] = List[T]

    override def fromString(v: scala.Predef.String): String = v

    override def fromBoolean(v: scala.Boolean): Bool = v

    override def map[A, B](mapper: A => B, dataset: List[A]): List[B] = dataset.map(mapper)
}
