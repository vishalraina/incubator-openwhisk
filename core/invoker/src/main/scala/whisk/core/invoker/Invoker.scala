/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.invoker

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

import com.redis.RedisClient

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import whisk.common.AkkaLogging
import whisk.common.Scheduler
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.connector.MessagingProvider
import whisk.core.connector.PingMessage
import whisk.core.entity.ExecManifest
import whisk.core.entity.InstanceId
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskEntityStore
import whisk.http.BasicHttpService
import whisk.spi.SpiLoader
import whisk.utils.ExecutionContextFactory

object Invoker {

  /**
   * An object which records the environment variables required for this component to run.
   */
  def requiredProperties =
    Map(servicePort -> 8080.toString(), dockerRegistry -> null, dockerImagePrefix -> null) ++
      ExecManifest.requiredProperties ++
      WhiskEntityStore.requiredProperties ++
      WhiskActivationStore.requiredProperties ++
      kafkaHost ++
      redisHost ++
      wskApiHost ++ Map(
      dockerImageTag -> "latest",
      invokerNumCore -> "4",
      invokerCoreShare -> "2",
      invokerContainerPolicy -> "",
      invokerContainerDns -> "",
      invokerContainerNetwork -> null,
      invokerUseRunc -> "true") ++
      Map(invokerName -> null)

  def main(args: Array[String]): Unit = {
    implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
    implicit val actorSystem: ActorSystem =
      ActorSystem(name = "invoker-actor-system", defaultExecutionContext = Some(ec))
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

    // load values for the required properties from the environment
    implicit val config = new WhiskConfig(requiredProperties)

    def abort() = {
      logger.error(this, "Bad configuration, cannot start.")
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
      sys.exit(1)
    }

    if (!config.isValid) {
      abort()
    }

    val execManifest = ExecManifest.initialize(config)
    if (execManifest.isFailure) {
      logger.error(this, s"Invalid runtimes manifest: ${execManifest.failed.get}")
      abort()
    }

    val proposedInvokerId: Option[Int] = args.headOption.map(_.toInt)
    val assignedInvokerId = proposedInvokerId
      .map { id =>
        logger.info(this, s"invokerReg: using proposedInvokerId ${id}")
        id
      }
      .getOrElse {
        val invokerName = config.invokerName
        val redisClient = new RedisClient(config.redisHostName, config.redisHostPort.toInt)
        val assignedId = redisClient
          .hget("controller:registar:idAssignments", invokerName)
          .map { oldId =>
            logger.info(this, s"invokerReg: invoker ${invokerName} was assigned its previous invokerId ${oldId}")
            oldId.toInt
          }
          .getOrElse {
            // If key not present, incr initializes to 0 before applying increment.
            // Convert from 1-based to 0-based invokerIds by subtracting 1 from incr's result
            val newId = redisClient
              .incr("controller:registrar:nextInvokerId")
              .map { id =>
                id.toInt - 1
              }
              .getOrElse {
                logger.error(this, "Failed to increment invokerId")
                abort()
              }
            redisClient.hset("controller:registar:idAssignments", invokerName, newId)
            logger.info(this, s"invokerReg: invoker ${invokerName} was assigned invokerId ${newId}")
            newId
          }
        redisClient.quit
        assignedId
      }
    val invokerInstance = InstanceId(assignedInvokerId);
    val msgProvider = SpiLoader.get[MessagingProvider]
    val producer = msgProvider.getProducer(config, ec)
    val invoker = new InvokerReactive(config, invokerInstance, producer)

    Scheduler.scheduleWaitAtMost(1.seconds)(() => {
      producer.send("health", PingMessage(invokerInstance)).andThen {
        case Failure(t) => logger.error(this, s"failed to ping the controller: $t")
      }
    })

    val port = config.servicePort.toInt
    BasicHttpService.startService(new InvokerServer().route, port)(actorSystem, ActorMaterializer.create(actorSystem))
  }
}
