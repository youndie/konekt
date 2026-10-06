package io.konekt.db.tables

import org.jetbrains.exposed.v1.core.Table

// The Exposed side of V16: where a booblik consumer of this build has got to (B-134). booblik keeps
// no consumer positions (its research, R12), so a consumer that must not lose or repeat what it
// reads keeps its position here — in the database its effects are written to, so the two can share
// one transaction. `ConsumerPositions` is the only writer.
object ConsumerPositionTable : Table("consumer_position") {
    val consumer = varchar("consumer", 64)
    val topic = varchar("topic", 64)
    val partitionId = integer("partition_id")
    val nextOffset = long("next_offset")

    override val primaryKey = PrimaryKey(consumer, topic, partitionId, name = "pk_consumer_position")
}
