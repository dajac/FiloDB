package filodb.spark

import org.apache.spark.sql.types._

import filodb.core.metadata.Column

object TypeConverters {
  val colTypeToSqlType: Map[Column.ColumnType, DataType] = Map(
    Column.ColumnType.IntColumn    -> IntegerType,
    Column.ColumnType.DoubleColumn -> DoubleType,
    Column.ColumnType.LongColumn   -> LongType,
    Column.ColumnType.StringColumn -> StringType,
    Column.ColumnType.BitmapColumn -> BooleanType
  )

  def columnsToSqlFields(columns: Seq[Column]): Seq[StructField] =
    columns.map { case Column(name, _, _, colType, _, false, false) =>
      StructField(name, colTypeToSqlType(colType), true)
    }

  def columnsToSqlTypes(columns: Seq[Column]): Seq[DataType] =
    columns.map { column => colTypeToSqlType(column.columnType) }

  val sqlTypeToColType = colTypeToSqlType.map { case (ct, st) => st -> ct }.toMap

  def structToColTypes(struct: StructType): Seq[Column.ColumnType] =
    struct.map { field => sqlTypeToColType(field.dataType) }
}
