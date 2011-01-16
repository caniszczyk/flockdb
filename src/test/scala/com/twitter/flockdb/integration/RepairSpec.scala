/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.flockdb.integration

import com.twitter.gizzard.thrift.conversions.Sequences._
import com.twitter.ostrich.Stats
import com.twitter.util.Time
import com.twitter.util.TimeConversions._
import thrift._
import conversions.ExecuteOperations._
import conversions.SelectOperation._
import com.twitter.gizzard.shards.{Busy, ShardId, ShardInfo}
import com.twitter.gizzard.nameserver.Forwarding
import com.twitter.flockdb.shards.{SqlShard}
import com.twitter.flockdb.jobs._

class RepairSpec extends IntegrationSpecification {

  val FOLLOWS = 1

  val alice = 1L
  val bob = 2L
  val carl = 3L
  val darcy = 4L

  "Repair" should {
    doBefore {
      reset(config)
      val queryEvaluator = config.edgesQueryEvaluator()(config.databaseConnection)
      for (graph <- (1 until 10)) {
        Seq("forward", "backward").foreach { direction =>
          val tableId = if (direction == "forward") graph else graph * -1
          val shardId = ShardId("localhost", direction + "_2_" + graph)
          val replicatingShardId = ShardId("localhost", "replicating_" + direction + "_" + graph)

          nameServer.createShard(ShardInfo(shardId,
            "com.twitter.flockdb.SqlShard", "INT UNSIGNED", "INT UNSIGNED", Busy.Normal))
          nameServer.createShard(ShardInfo(replicatingShardId,
            "com.twitter.gizzard.shards.ReplicatingShard", "", "", Busy.Normal))
          nameServer.addLink(replicatingShardId, shardId, 1)

          queryEvaluator.execute("DELETE FROM " + direction + "_2_" + graph + "_edges")
          queryEvaluator.execute("DELETE FROM " + direction + "_2_" + graph + "_metadata")
        }
      }

      nameServer.reload()
    }

    val replicatingShardId = ShardId("localhost", "replicating_forward_1")
    val (shard1id, shard2id) = (ShardId("localhost", "forward_2_1"), ShardId("localhost", "forward_1"))
    lazy val shard1 = nameServer.findShardById(shard1id)
    lazy val shard2 = nameServer.findShardById(shard2id)

    "differing shards should become the same" in {
      shard1.add(1L, 2L, 1L, Time.now) // same
      shard2.add(1L, 2L, 1L, Time.now)

      shard1.archive(2L, 1L, 2L, Time.now) // one archived, one normal
      shard2.add(2L, 1L, 2L, Time.now)

      shard1.add(1L, 3L, 3L, Time.now) // only on one shard

      shard2.add(1L, 4L, 4L, Time.now)  // only on two shard

      shard1.selectAll(Repair.START, Repair.COUNT)._1 must_!= shard2.selectAll(Repair.START, Repair.COUNT)._1

      flock.repair_shard(new thrift.ShardId(shard1id.hostname, shard1id.tablePrefix), new thrift.ShardId(shard2id.hostname, shard2id.tablePrefix), 1, 0)
      shard1.selectAll(Repair.START, Repair.COUNT)._1.map(e=>(e.sourceId, e.destinationId, e.position, e.state)) must eventually(verify(s => s sameElements shard2.selectAll(Repair.START, Repair.COUNT)._1.map(e=>(e.sourceId, e.destinationId, e.position, e.state))))
    }
  }
}