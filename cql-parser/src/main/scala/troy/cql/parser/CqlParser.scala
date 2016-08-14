/*
 * Copyright 2016 Tamer AbdulRadi
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

package troy.cql.ast

import troy.cql.ast.CreateIndex.IndexIdentifier
import troy.cql.ast._
import troy.cql.ast.dml.SelectStatement
import scala.util.parsing.combinator._

// Based on CQLv3.4.3: https://cassandra.apache.org/doc/latest/cql/index.html
object CqlParser extends JavaTokenParsers with Helpers {
  def parseSchema(input: String): ParseResult[Seq[DataDefinition]] =
    parse(phrase(rep(dataDefinition <~ semicolon)), input)

  def parseQuery(input: String): ParseResult[SelectStatement] =
    parse(phrase(selectStatement <~ semicolon.?), input)

  ////////////////////////////////////////// Data Definition
  def dataDefinition: Parser[DataDefinition] =
    createKeyspace | createTable | createIndex

  def createKeyspace: Parser[CreateKeyspace] = {
    import CreateKeyspace._
    val mapKey: Parser[String] = "'" ~> identifier <~ "'"
    val mapValue: Parser[String] = "'" ~> identifier <~ "'"
    val mapKeyValue = mapKey ~ (":" ~> mapValue) ^^ { case k ~ v => k -> v }
    val map: Parser[Seq[(String, String)]] = "{" ~> repsep(mapKeyValue, ",") <~ "}"
    def option: Parser[KeyspaceOption] = ("replication".i ~> "=" ~> map) ^^ Replication
    def withOptions: Parser[Seq[KeyspaceOption]] = ("WITH".i ~> rep1sep(option, "AND".i)) orEmpty

    "CREATE KEYSPACE".i ~>
      ifNotExists ~
      keyspaceName ~
      withOptions ^^^^ CreateKeyspace.apply // TODO: with properties   // <create-keyspace-stmt> ::= CREATE KEYSPACE (IF NOT EXISTS)? <identifier> WITH <properties>
  }

  def use: Parser[UseStatement] = "use".i ~> keyspaceName ^^ UseStatement

  def alterKeyspace: Parser[Cql3Statement] = ??? // <create-keyspace-stmt> ::= ALTER KEYSPACE <identifier> WITH <properties>

  def dropKeyspace: Parser[Cql3Statement] = ??? // <drop-keyspace-stmt> ::= DROP KEYSPACE ( IF EXISTS )? <identifier>

  def createTable: Parser[CreateTable] = {
    import CreateTable._

    def createTable = "create".i ~> ("table".i | "columnfamily".i)

    def columnDefinition: Parser[Column] = identifier ~ dataType ~ "STATIC".flag ~ "PRIMARY KEY".flag ^^^^ Column

    def primaryKeyDefinition: Parser[PrimaryKey] = {
      def simplePartitionKey = identifier.asSeq
      def compositePartitionKey = "(" ~> rep1sep(identifier, ",") <~ ")"
      def partitionKeys: Parser[Seq[String]] = simplePartitionKey | compositePartitionKey
      def clusteringColumns: Parser[Seq[String]] = ("," ~> rep1sep(identifier, ",")) orEmpty

      "PRIMARY KEY".i ~> "(" ~> partitionKeys ~ clusteringColumns <~ ")" ^^^^ PrimaryKey
    }

    def option: Parser[CreateTableOption] = ??? // <property> | COMPACT STORAGE | CLUSTERING ORDER
    def withOptions: Parser[Seq[CreateTableOption]] = ("WITH".i ~> rep1sep(option, "AND".i)) orEmpty

    createTable ~>
      ifNotExists ~
      tableName ~
      ("(" ~> rep1sep(columnDefinition, ",")) ~
      ("," ~> primaryKeyDefinition).? ~
      (")" ~> withOptions) ^^^^ CreateTable.apply
  }

  def createIndex: Parser[CreateIndex] = {
    import CreateIndex._

    def indexName = identifier.?
    def onTable = "ON".i ~> tableName
    def indexIdentifier: Parser[IndexIdentifier] = {
      val keys = "KEYS".i ~> "(" ~> identifier <~ ")" ^^ Keys
      val ident = identifier ^^ Identifier
      "(" ~> ((keys | ident) <~ ")")
    }
    def using = {
      def withOptions =
        "WITH".i ~> "OPTIONS".i ~> "=" ~> Literals.map

      "using".i ~> Constants.string ~ withOptions.? ^^^^ Using
    }.?

    "CREATE".i ~>
      ("CUSTOM".flag <~ "INDEX".i) ~
      ifNotExists ~
      indexName ~
      onTable ~
      indexIdentifier ~
      using ^^^^ CreateIndex.apply
  }

  ///////////////////////////////////// Queries
  def selectStatement: Parser[SelectStatement] = {
    import SelectStatement._

    def mod: Parser[Mod] = {
      def json = "JSON".i ^^^ SelectStatement.Json
      def distinct = "DISTINCT".i ^^^ SelectStatement.Distinct
      json | distinct
    }
    def select: Parser[Selection] = {
      def asterisk = "*" ^^^ Asterisk
      def select_clause: Parser[SelectClause] = {
        def select_clause_item: Parser[SelectionClauseItem] = {
          def selector: Parser[Selector] = {
            def count = "COUNT".i ~ "(*)" ^^^ SelectStatement.Count
            def term_selector = term ^^ SelectStatement.SelectTerm
            def cast = "CAST".i ~> "(" ~> selector ~ ("AS".i ~> dataType) <~ ")" ^^^^ SelectStatement.Cast
            def column_name = identifier ^^ ColumnName
            term_selector | cast | count | column_name
          }

          selector ~ ("AS".i ~> identifier).? ^^^^ SelectStatement.SelectionClauseItem
        }

        rep1sep(select_clause_item, ",") ^^ SelectClause
      }

      select_clause | asterisk
    }

    def from = "FROM" ~> tableName

    def where: Parser[WhereClause] = {
      import WhereClause._
      def relation: Parser[Relation] = {
        import Relation._

        def op: Parser[Operator] = {
          import Operator._
          def eq = "=".r ^^^ Equals
          def lt = "<".r ^^^ LessThan
          def gt = ">".r ^^^ GreaterThan
          def lte = "<=".r ^^^ LessThanOrEqual
          def gte = ">=".r ^^^ GreaterThanOrEqual
          def noteq = "!=".r ^^^ NotEquals
          def in = "IN".r ^^^ In
          def contains = "CONTAINS".i ^^^ Contains
          def containsKey = "CONTAINS KEY".i ^^^ ContainsKey

          lte | gte | eq | lt | gt | noteq | in | containsKey | contains
        }

        def columnName = identifier ^^ ColumnName
        def columnNames = "(" ~> rep1sep(columnName, ",") <~ ")"

        def simple = columnName ~ op ~ term ^^^^ Simple
        def tupled = columnNames ~ op ~ Literals.tupleLiteral ^^^^ Tupled
        def token = "TOKEN".i ~> columnNames ~ op ~ term ^^^^ Token

        simple | tupled | token
      }

      "WHERE".i ~> rep1sep(relation, "AND".i) ^^ WhereClause.apply
    }

    def limitParam: Parser[SelectStatement.LimitParam] = {
      def limitValue = Constants.integer ^^ LimitValue
      def limitVariable = bindMarker ^^ LimitVariable

      limitValue | limitVariable
    }

    def limit = "LIMIT".i ~> limitParam
    def perPartitionLimit = "PER PARTITION LIMIT".i ~> limitParam

    def allowFiltering = "ALLOW FILTERING".flag

    def orderBy: Parser[OrderBy] = {
      import OrderBy._
      def direction: Parser[Direction] = {
        def asc = "ASC".i ^^^ Ascending
        def des = "DESC".i ^^^ Descending

        asc | des
      }

      def ordering: Parser[Ordering] = {
        def column_name = identifier ^^ ColumnName
        column_name ~ direction.? ^^^^ Ordering
      }
      rep1sep(ordering, ",") ^^ OrderBy.apply
    }

    "SELECT".i ~>
      mod.? ~
      select ~
      from ~
      where.? ~
      orderBy.? ~
      perPartitionLimit.? ~
      limit.? ~
      allowFiltering ^^^^ SelectStatement.apply
  }

  ///////////////////////////////////// Data Manipulation
  def dataChangeStatement: Parser[Cql3Statement] =
    insertStatement | updateStatement | batchStatement | deleteStatement | truncateStatement

  def insertStatement: Parser[Cql3Statement] = ???

  def updateStatement: Parser[Cql3Statement] = ???

  def batchStatement: Parser[Cql3Statement] = ???

  def deleteStatement: Parser[Cql3Statement] = ???

  def truncateStatement: Parser[Cql3Statement] = ???

  //  def schemaChangeStatement: Parser[Cql3Statement] =
  //    createKeyspaceStatement | createColumnFamilyStatement | createIndexStatement | dropKeyspaceStatement |
  //      dropColumnFamilyStatement | dropIndexStatement | alterTableStatement

  def createColumnFamilyStatement: Parser[Cql3Statement] = ???

  def createIndexStatement: Parser[Cql3Statement] = ???

  def dropKeyspaceStatement: Parser[Cql3Statement] = ???

  def dropColumnFamilyStatement: Parser[Cql3Statement] = ???

  def dropIndexStatement: Parser[Cql3Statement] = ???

  def alterTableStatement: Parser[Cql3Statement] = ???

  def usingConsistencyLevelClause: Parser[ConsistencyLevel] = {
    def consistencyLevel = any | one | quorum | all | localQuorum | eachQuorum
    def any = "quorum".i ^^^ ConsistencyLevel.Any
    def one = "quorum".i ^^^ ConsistencyLevel.One
    def quorum = "quorum".i ^^^ ConsistencyLevel.Quorum
    def all = "quorum".i ^^^ ConsistencyLevel.All
    def localQuorum = "quorum".i ^^^ ConsistencyLevel.LocalQuorum
    def eachQuorum = "quorum".i ^^^ ConsistencyLevel.EachQuorum

    "using".i ~> "consistency".i ~> consistencyLevel
  }

  def semicolon: Parser[Unit] = ";".? ^^^ ((): Unit)

  /*
   * <identifier> ::= any quoted or unquoted identifier, excluding reserved keywords
   */
  def identifier: Parser[Identifier] = "[a-zA-Z0-9_]+".r.filter(k => !Keywords.contains(k.toUpperCase))

  object Constants {
    def string = "'".r ~> """[^']*""".r <~ "'"

    def integer = wholeNumber

    def float = floatingPointNumber

    def number = integer | float

    def uuid = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}".r

    def boolean = "true".i | "false".i
  }

  object Literals {

    def map: Parser[MapLiteral] = {
      val pair = term ~ (':' ~> term) ^^ { case key ~ value => key -> value }
      val mapBody = repsep(pair, ",") ^^ MapLiteral
      "{".i ~> mapBody <~ "}"
    }

    def set: Parser[SetLiteral] =
      "{".i ~> repsep(term, ",") <~ "}" ^^ SetLiteral

    def list: Parser[ListLiteral] =
      "[".i ~> repsep(term, ",") <~ "]" ^^ ListLiteral

    def collectionLiteral: Parser[CollectionLiteral] = map | set | list

    def udtLiteral: Parser[UdtLiteral] = {
      val member = identifier ~ (':' ~> term) ^^ { case key ~ value => key -> value }
      val udtBody = rep1sep(member, ",") ^^ UdtLiteral
      "{".i ~> udtBody <~ "}"
    }

    def tupleLiteral: Parser[TupleLiteral] =
      "(".i ~> rep1sep(term, ",") <~ ")" ^^ TupleLiteral

    def literal: Parser[Literal] = collectionLiteral | udtLiteral | tupleLiteral
  }

  def term: Parser[Term] = {
    def constant: Parser[Constant] = {
      import Constants._
      (string | number | uuid | boolean) ^^ Constant // | hex // TODO
    }

    def functionCall: Parser[FunctionCall] =
      identifier ~ parenthesis(repsep(term, ",")) ^^^^ FunctionCall

    def typeHint: Parser[TypeHint] =
      parenthesis(dataType) ~ term ^^^^ TypeHint

    constant | Literals.literal | functionCall | typeHint | bindMarker
  }

  def keyspaceName: Parser[KeyspaceName] = identifier ^^ KeyspaceName

  def positiveNumber = """([1-9]+)""".r

  def bindMarker: Parser[BindMarker] = {
    import BindMarker._
    def anonymous = """\?""".r ^^^ Anonymous
    def named = ":" ~> identifier ^^ Named

    anonymous | named
  }

  /*
   * <tablename> ::= (<identifier> '.')? <identifier>
   */
  def tableName: Parser[TableName] = (keyspaceName <~ ".").? ~ identifier ^^^^ TableName

  def ifNotExists: Parser[Boolean] = "if not exists".flag

  def dataType: Parser[DataType] = {
    def ascii = "ascii".i ^^^ DataType.ascii
    def bigint = "bigint".i ^^^ DataType.bigint
    def blob = "blob".i ^^^ DataType.blob
    def boolean = "boolean".i ^^^ DataType.boolean
    def counter = "counter".i ^^^ DataType.counter
    def date = "date".i ^^^ DataType.date
    def decimal = "decimal".i ^^^ DataType.decimal
    def double = "double".i ^^^ DataType.double
    def float = "float".i ^^^ DataType.float
    def inet = "inet".i ^^^ DataType.inet
    def int = "int".i ^^^ DataType.int
    def smallint = "smallint".i ^^^ DataType.smallint
    def text = "text".i ^^^ DataType.text
    def times = "times".i ^^^ DataType.times
    def timestamp = "timestamp".i ^^^ DataType.timestamp
    def timeuuid = "timeuuid".i ^^^ DataType.timeuuid
    def tinyint = "tinyint".i ^^^ DataType.tinyint
    def uuid = "uuid".i ^^^ DataType.uuid
    def varchar = "varchar".i ^^^ DataType.varchar
    def varint = "varint".i ^^^ DataType.varint
    def native: Parser[DataType.Native] =
      ascii | bigint | blob | boolean | counter | date | decimal | double | float | inet | int | smallint | text | times | timestamp | timeuuid | tinyint | uuid | varchar | varint

    def list = "list".i ~> '<' ~> native <~ '>' ^^ DataType.list
    def set = "set".i ~> '<' ~> native <~ '>' ^^ DataType.set
    def map = "map".i ~> '<' ~> native ~ (',' ~> native) <~ '>' ^^ {
      case k ~ v => DataType.map(k, v)
    }
    def collection: Parser[DataType.Collection] = list | set | map

    def tuple: Parser[DataType.Tuple] = "tuple".i ~> '<' ~> rep1sep(native, ",") <~ '>' ^^ DataType.Tuple

    native | collection | tuple // | custom // TODO
  }

  implicit class MyRichString(val str: String) extends AnyVal {
    // Ignore case
    def i: Parser[String] = ("""(?i)\Q""" + str + """\E""").r

    def flag: Parser[Boolean] = (str.i ^^^ true) orElse false
  }

  val Keywords = Set(
    "ADD",
    "ALLOW",
    "ALTER",
    "AND",
    "APPLY",
    "ASC",
    "AUTHORIZE",
    "BATCH",
    "BEGIN",
    "BY",
    "COLUMNFAMILY",
    "CREATE",
    "DELETE",
    "DESC",
    "DESCRIBE",
    "DROP",
    "ENTRIES",
    "EXECUTE",
    "FROM",
    "FULL",
    "GRANT",
    "IF",
    "IN",
    "INDEX",
    "INFINITY",
    "INSERT",
    "INTO",
    "KEYSPACE",
    "LIMIT",
    "MODIFY",
    "NAN",
    "NORECURSIVE",
    "NOT",
    "NULL",
    "OF",
    "ON",
    "OR",
    "ORDER",
    "PRIMARY",
    "RENAME",
    "REPLACE",
    "REVOKE",
    "SCHEMA",
    "SELECT",
    "SET",
    "TABLE",
    "TO",
    //    "TOKEN",
    "TRUNCATE",
    "UNLOGGED",
    "UPDATE",
    "USE",
    "USING",
    "WHERE",
    "WITH"
  )
}

trait Helpers {
  this: JavaTokenParsers =>

  def parenthesis[A](p: Parser[A]) =
    around(p, "(", ")")

  def curlyBraces[A](p: Parser[A]) =
    around(p, "{", "}")

  def squareBrackets[A](p: Parser[A]) =
    around(p, "[", "]")

  def around[A](p: Parser[A], prefix: Parser[_], postfix: Parser[_]) =
    prefix ~> p <~ postfix

  implicit class orElse[A](p: Parser[A]) {
    def orElse[B >: A](default: => B): Parser[B] = p.? ^^ (_ getOrElse default)
  }

  implicit class orEmpty[A](p: Parser[Seq[A]]) {
    def orEmpty: Parser[Seq[A]] = p orElse Seq.empty
  }

  implicit class asSeq[A](p: Parser[A]) {
    def asSeq: Parser[Seq[A]] = p ^^ { case x => Seq(x) }
  }

  implicit class as2[A, B](t: Parser[A ~ B]) {
    def ^^^^[T](co: (A, B) => T) = t map { tt => val (a ~ b) = tt; co(a, b) }
  }

  implicit class as3[A, B, C](t: Parser[A ~ B ~ C]) {
    def ^^^^[T](co: (A, B, C) => T) = t map { tt => val (a ~ b ~ c) = tt; co(a, b, c) }
  }

  implicit class as4[A, B, C, D](t: Parser[A ~ B ~ C ~ D]) {
    def ^^^^[T](co: (A, B, C, D) => T) = t map { tt => val (a ~ b ~ c ~ d) = tt; co(a, b, c, d) }
  }

  implicit class as5[A, B, C, D, E](t: Parser[A ~ B ~ C ~ D ~ E]) {
    def ^^^^[T](co: (A, B, C, D, E) => T) = t map { tt => val (a ~ b ~ c ~ d ~ e) = tt; co(a, b, c, d, e) }
  }

  implicit class as6[A, B, C, D, E, F](t: Parser[A ~ B ~ C ~ D ~ E ~ F]) {
    def ^^^^[T](co: (A, B, C, D, E, F) => T) = t map { tt => val (a ~ b ~ c ~ d ~ e ~ f) = tt; co(a, b, c, d, e, f) }
  }

  implicit class as7[A, B, C, D, E, F, G](t: Parser[A ~ B ~ C ~ D ~ E ~ F ~ G]) {
    def ^^^^[T](co: (A, B, C, D, E, F, G) => T) = t map { tt => val (a ~ b ~ c ~ d ~ e ~ f ~ g) = tt; co(a, b, c, d, e, f, g) }
  }

  implicit class as8[A, B, C, D, E, F, G, H](t: Parser[A ~ B ~ C ~ D ~ E ~ F ~ G ~ H]) {
    def ^^^^[T](co: (A, B, C, D, E, F, G, H) => T) = t map { tt => val (a ~ b ~ c ~ d ~ e ~ f ~ g ~ h) = tt; co(a, b, c, d, e, f, g, h) }
  }

}
