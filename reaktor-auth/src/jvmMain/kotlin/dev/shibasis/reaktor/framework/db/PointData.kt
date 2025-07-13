package dev.shibasis.reaktor.framework.db

import io.github.sebasbaumh.postgis.Point
import org.jetbrains.exposed.v1.core.ColumnType

data class PointData(val x: Double, val y: Double, val srid: Int = 4326)


class PointDataColumnType(val srid: Int = 4326): ColumnType<Point>() {
    override fun sqlType() = "GEOMETRY(POINT, $srid)"
    override fun valueFromDB(value: Any): Point? {
        return value as? Point
    }

    override fun notNullValueToDB(value: Point): Any {
        return value
    }
}
