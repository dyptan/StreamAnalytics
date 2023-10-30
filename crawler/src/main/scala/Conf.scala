package com.dyptan.crawler

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import io.circe.generic.auto._
import org.apache.kafka.common.Uuid
import sttp.client3.circe._
import sttp.client3.{UriContext, basicRequest}
import sttp.model
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde.Serde
import zio.stream.ZStream
import zio.{Queue, ZIO, ZLayer}
import io.circe._
import io.circe.generic.semiauto._
import java.io.File

object Conf {
  val config = ConfigFactory.parseFile(new File("application.conf"))
  val authKey = model.QueryParams().param("api_key", config.getConfig("crawler").getString("autoriaApiKey"))
  val infoBase = uri"https://developers.ria.com/auto/info".addParams(authKey)
  val searchBase = uri"https://developers.ria.com/auto/search".addParams(authKey).addParam("countpage", "100")
  val searchDefault = searchBase.addParams("category_id" -> "1", "s_yers[0]" -> "2000", "price_ot" -> "3000",
    "price_do" -> "60000", "countpage" -> "100", "top" -> "1")
  var searchWithParameters = searchDefault

  def searchRequest = basicRequest
    .get(searchWithParameters)
    .response(asJson[searchRoot])

  case class L2(ids: Array[String], count: Int)

  case class L1(search_result: L2)

  case class searchRoot(result: L1)

  case class Geography(stateId: Int, cityId: Int, regionNameEng: String)

  case class Details(year: Int, autoId: Int, bodyId: Int, raceInt: Int, fuelId: Int,
                     fuelNameEng: String, gearBoxId: Int, gearboxName: String, driveId: Int, driveName: String,
                     categoryId: Int, categoryNameEng: String, subCategoryNameEng: String)

  case class advRoot(USD: Int, addDate: String, soldDate: String, autoData: Details,
                     markId: Int, markNameEng: String, modelId: Int, modelNameEng: String, linkToView: String,
                     stateData: Geography)

  val actorSystem: ZLayer[Any, Throwable, ActorSystem] =
    ZLayer
      .scoped(
        ZIO.acquireRelease(ZIO.attempt(ActorSystem("Test", config.getConfig("akkaConf"))))(sys => ZIO.fromFuture(_ => sys.terminate()).either)
      )

  def kafkaProducerLayer =
    ZLayer.scoped(
      Producer.make(
        settings = ProducerSettings(List(
          config.getConfig("producer").getString("kafkaServer")
        )
        )
      )
    )

  /* Publishes stream of records from internal Q to a kafka topic
  * */
  def producerKafka(records: Queue[String]): ZStream[Producer, Throwable, Nothing] =
    ZStream.fromQueue(records)
      .mapZIO { record =>
        Producer.produce[Any, Long, String](
          topic = config.getConfig("producer").getString("kafkaTopic"),
          key = Uuid.randomUuid().hashCode(),
          value = record,
          keySerializer = Serde.long,
          valueSerializer = Serde.string
        )
      }
      .drain

}
