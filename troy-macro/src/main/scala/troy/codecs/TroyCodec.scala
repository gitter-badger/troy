package troy.codecs

import com.datastax.driver.core.{ BoundStatement, Row, TypeCodec }
import troy.driver.codecs.{ OptionHasTypeCodec, HasTypeCodec }
import troy.driver.{ CassandraDataType => CT }
import troy.macros.Materializers

trait TroyCodec[S, C <: CT] {
  def getColumn(row: Row, i: Int): S
  def setVariable(bound: BoundStatement, i: Int, value: S): BoundStatement
}

class TroyPrimitiveCodec[S, C <: CT](getImpl: Row => Int => S, setImpl: BoundStatement => (Int, S) => BoundStatement) extends TroyCodec[S, C] {
  override def setVariable(bound: BoundStatement, i: Int, value: S) =
    setImpl(bound)(i, value)

  override def getColumn(row: Row, i: Int) =
    getImpl(row)(i)
}

class TroyTypeCodecWrapper[S, C <: CT](val typeCodec: TypeCodec[S]) extends TroyCodec[S, C] {
  override def setVariable(bound: BoundStatement, i: Int, value: S) =
    bound.set(i, value, typeCodec)

  override def getColumn(row: Row, i: Int) =
    row.get(i, typeCodec)
}

class TroyOptionalPrimitiveTypeCodec[S <: AnyVal, C <: CT](inner: TroyPrimitiveCodec[S, C]) extends TroyCodec[Option[S], C] {
  override def setVariable(bound: BoundStatement, i: Int, value: Option[S]) =
    value
      .map(inner.setVariable(bound, i, _))
      .getOrElse(bound.setToNull(i))

  override def getColumn(row: Row, i: Int) =
    if (row.isNull(i))
      None
    else
      Some(inner.getColumn(row, i))
}

class TroyListTypeCodec[S <: AnyRef, C <: CT.Native](implicit inner: HasTypeCodec[S, C]) extends TroyCodec[Seq[S], CT.List[C]] {
  import scala.collection.JavaConverters._

  val codec = TypeCodec.list(inner.typeCodec)
  override def setVariable(bound: BoundStatement, i: Int, value: Seq[S]): BoundStatement = bound.set(i, value.asJava, codec)
  override def getColumn(row: Row, i: Int) = row.get(i, codec).asScala
}

class TroyListOfPrimitivesTypeCodec[J <: AnyRef, S <: AnyVal, C <: CT.Native](inner: TroyListTypeCodec[J, C])(implicit converter: PrimitivesConverter[J, S]) extends TroyCodec[Seq[S], CT.List[C]] {
  override def setVariable(bound: BoundStatement, i: Int, value: Seq[S]): BoundStatement = inner.setVariable(bound, i, value.map(converter.toJava))
  override def getColumn(row: Row, i: Int) = inner.getColumn(row, i).map(converter.toScala)
}

class TroySetTypeCodec[S <: AnyRef, C <: CT.Native](implicit inner: HasTypeCodec[S, C]) extends TroyCodec[Set[S], CT.Set[C]] {
  import scala.collection.JavaConverters._

  val codec = TypeCodec.set(inner.typeCodec)
  override def setVariable(bound: BoundStatement, i: Int, value: Set[S]): BoundStatement = bound.set(i, value.asJava, codec)
  override def getColumn(row: Row, i: Int) = row.get(i, codec).asScala.toSet
}

class TroySetOfPrimitivesTypeCodec[J <: AnyRef, S <: AnyVal, C <: CT.Native](inner: TroySetTypeCodec[J, C])(implicit converter: PrimitivesConverter[J, S]) extends TroyCodec[Set[S], CT.Set[C]] {
  override def setVariable(bound: BoundStatement, i: Int, value: Set[S]): BoundStatement = inner.setVariable(bound, i, value.map(converter.toJava))
  override def getColumn(row: Row, i: Int) = inner.getColumn(row, i).map(converter.toScala)
}

class TroyMapTypeCodec[KS <: AnyRef, KC <: CT.Native, VS <: AnyRef, VC <: CT.Native](implicit keyInner: HasTypeCodec[KS, KC], valueInner: HasTypeCodec[VS, VC]) extends TroyCodec[Map[KS, VS], CT.Map[KC, VC]] {
  import scala.collection.JavaConverters._

  val codec = TypeCodec.map(keyInner.typeCodec, valueInner.typeCodec)
  override def setVariable(bound: BoundStatement, i: Int, value: Map[KS, VS]): BoundStatement = bound.set(i, value.asJava, codec)
  override def getColumn(row: Row, i: Int) = row.get(i, codec).asScala.toMap
}

object TroyCodec {
  implicit def materializeTroyCodec[S, C <: CT]: TroyCodec[S, C] = macro Materializers.materializeTroyCodec[S, C]

  def primitive[S <: AnyVal, C <: CT](getImpl: Row => Int => S, setImpl: BoundStatement => (Int, S) => BoundStatement) = {
    val codec = new TroyPrimitiveCodec[S, C](getImpl, setImpl)
    (codec, new TroyOptionalPrimitiveTypeCodec[S, C](codec))
  }

  def wrap[S <: AnyRef, C <: CT](implicit hasTypeCodec: HasTypeCodec[S, C]) =
    new TroyTypeCodecWrapper[S, C](hasTypeCodec.typeCodec)

  def optional[S <: AnyRef, C <: CT](implicit hasTypeCodec: HasTypeCodec[S, C]) =
    new TroyTypeCodecWrapper[Option[S], C](OptionHasTypeCodec(hasTypeCodec).typeCodec)
}
