package org.example.core

import scala.annotation.Annotation

package object annotations {

  class Type extends Annotation
  class StringType extends Type
  class IntType extends Type
  class BoolType extends Type
  
}
