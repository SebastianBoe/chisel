/*
 Copyright (c) 2011, 2012, 2013 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

package Chisel
import Node._
import ChiselError._

abstract class Num extends Bits {
  type T <: Num;

  def undefinedError(opName: String, b: Num) = {
    ChiselErrors += ChiselError(
      opName + " not defined on "
        + this.getClass.toString + " " + b.getClass.toString,
      Thread.currentThread().getStackTrace);
  }

  def <<(b: Num): Num = { undefinedError("<<", b); this }

  def >>(b: Num): Num = { undefinedError(">>", b); this }

  // def >>>(b: Num): Num = { undefinedError(">>>", b); this }

  def +(b: Num): Num = { undefinedError("+", b); this }

  def *(b: Num): Num = { undefinedError("*", b); this }

  def ^(b: Num): Num = { undefinedError("^", b); this }

  def ?(b: Num): Num = { undefinedError("?", b); this }

  def -(b: Num): Num = { undefinedError("-", b); this }

  def ##(b: Num): Num = { undefinedError("##", b); this }

  def ===(b: Num): Bool = { undefinedError("===", b); Bool(false) }

  def !=(b: Num): Bool = { undefinedError("!=", b); Bool(false) }

  def >(b: Num): Bool = { undefinedError(">", b); Bool(false) }

  def <=(b: Num): Bool = { undefinedError("<=", b); Bool(false) }

  def >=(b: Num): Bool = { undefinedError(">=", b); Bool(false) }

  def &&(b: Num): Bool = { undefinedError("&&", b); Bool(false) }

  def ||(b: Num): Bool = { undefinedError("||", b); Bool(false) }

  def &(b: Num): Num = { undefinedError("&", b); this }

  def |(b: Num): Num = { undefinedError("|", b); this }

}
