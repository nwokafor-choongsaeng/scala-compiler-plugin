package org.example

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

object Macros {

  def impl(c: blackbox.Context): c.Expr[Unit] = {
    import c.universe._

    val enclosingObject = c.internal.enclosingOwner.owner

    val methods = enclosingObject.asType.toType.decls.collect {
      case m: MethodSymbol => m
    }

    val morphirValues = methods.map { m =>
      s"public String ${m.name.toString.trim}() { return \"${m.returnType.toString}\"; }"
    }.mkString("\n\n")


//    println(morphirValues)

    c.Expr[Unit](q"""println($morphirValues)""")
    c.Expr[Unit](q"")
  }

  def myMacro: Unit = macro impl

}
