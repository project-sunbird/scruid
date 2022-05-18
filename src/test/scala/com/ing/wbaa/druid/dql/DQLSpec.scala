/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ing.wbaa.druid.dql

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.stream.scaladsl.Sink
import com.ing.wbaa.druid._
import com.ing.wbaa.druid.client.DruidHttpClient
import com.ing.wbaa.druid.definitions._
import com.ing.wbaa.druid.dql.DSL._
import com.ing.wbaa.druid.util._
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalatest.concurrent._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DQLSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(1 minute, 100 millis)

  implicit val config = DruidConfig(clientBackend = classOf[DruidHttpClient])

  implicit val mat = config.client.actorMaterializer

  private val totalNumberOfEntries = 39244

  private val expectedRequestAsJson =
    """{
      |"aggregations":[{"name":"count","type":"count"}],
      |"intervals":["2011-06-01/2017-06-01"],
      |"filter":null,
      |"granularity":"hour",
      |"descending":"true",
      |"postAggregations":[],
      |"context":{"queryId":"some_custom_id","priority":1,"useCache":false,"skipEmptyBuckets":true}
      |}""".toOneLine

  case class TimeseriesCount(count: Int)
  case class GroupByIsAnonymous(isAnonymous: String, count: Int)
  case class TopCountry(count: Int, countryName: Option[String])
  case class AggregatedFilteredAnonymous(count: Int, isAnonymous: String, filteredCount: Int)
  case class PostAggregationAnonymous(count: Int, isAnonymous: String, halfCount: Double)
  case class AggregationJavascript(value: Double)

  case class AggregatedCardinality(cardinalityValue: Double)

  case class SelectResult(channel: Option[String],
                          cityName: Option[String],
                          countryIsoCode: Option[String],
                          user: Option[String])

  case class ScanResult(channel: Option[String],
                        cityName: Option[String],
                        countryIsoCode: Option[String],
                        user: Option[String])

  "DQL TimeSeriesQuery" should {
    "successfully be interpreted by Druid" in {

      val query: TimeSeriesQuery = DQL.timeseries
        .granularity(GranularityType.Hour)
        .interval("2011-06-01/2017-06-01")
        .agg(count as "count")
        .setQueryContextParam(QueryContext.Priority, 1)
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        response.list[TimeseriesCount].map(_.count).sum shouldBe totalNumberOfEntries
      }
    }

    "extract the data and return a map with the timestamps as keys" in {
      val query: TimeSeriesQuery = DQL.timeseries
        .granularity(GranularityType.Hour)
        .interval("2011-06-01/2017-06-01")
        .agg(count as "count")
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        response
          .series[TimeseriesCount]
          .flatMap { case (_, items) => items.map(_.count) }
          .sum shouldBe totalNumberOfEntries
      }
    }
  }

  "DQL GroupByQuery" should {
    "successfully be interpreted by Druid" in {
      val query: GroupByQuery = DQL
        .interval("2011-06-01/2017-06-01")
        .agg(count as "count")
        .groupBy(d"isAnonymous")
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        response.list[GroupByIsAnonymous].map(_.count).sum shouldBe totalNumberOfEntries
      }
    }

    "successfully be interpreted by Druid when using lower granularity" in {
      val query = DQL
        .interval("2011-06-01/2017-06-01")
        .agg(count as "count")
        .groupBy(d"isAnonymous")
        .granularity(GranularityType.Hour)
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        response.list[GroupByIsAnonymous].map(_.count).sum shouldBe totalNumberOfEntries
      }
    }
  }

  "DQL TopNQuery" should {
    "successfully be interpreted by Druid" in {
      val topNLimit = 5

      val query = DQL
        .agg(count as "count")
        .interval("2011-06-01/2017-06-01")
        .topN(dimension = d"countryName", metric = "count", threshold = topNLimit)
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        val topN = response.list[TopCountry]

        topN.size shouldBe topNLimit

        topN.head shouldBe TopCountry(count = 35445, countryName = None)
        topN(1) shouldBe TopCountry(count = 528, countryName = Some("United States"))
        topN(2) shouldBe TopCountry(count = 256, countryName = Some("Italy"))
        topN(3) shouldBe TopCountry(count = 234, countryName = Some("United Kingdom"))
        topN(4) shouldBe TopCountry(count = 205, countryName = Some("France"))
      }
    }

    "also work with a filter" in {

      val query = DQL
        .agg(count as "count")
        .interval("2011-06-01/2017-06-01")
        .topN(dimension = d"countryName", metric = "count", threshold = 5)
        .where(d"countryName" === "United States" or d"countryName" === "Italy")
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        val topN = response.list[TopCountry]
        topN.size shouldBe 2
        topN.head shouldBe TopCountry(count = 528, countryName = Some("United States"))
        topN(1) shouldBe TopCountry(count = 256, countryName = Some("Italy"))
      }
    }
  }

  "DQL also work with 'in' filtered aggregations" should {
    "successfully be interpreted by Druid" in {

      val query = DQL
        .agg(d"count".count as "count")
        .agg(
          d"channel".inFiltered(d"count".count, "#en.wikipedia", "#de.wikipedia") as "filteredCount"
        )
        .interval("2011-06-01/2017-06-01")
        .topN(dimension = d"isAnonymous", metric = "count", threshold = 5)
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        val topN = response.list[AggregatedFilteredAnonymous]
        topN.size shouldBe 2
        topN.head shouldBe AggregatedFilteredAnonymous(count = 35445,
                                                       filteredCount = 12374,
                                                       isAnonymous = "false")
        topN(1) shouldBe AggregatedFilteredAnonymous(count = 3799,
                                                     filteredCount = 1698,
                                                     isAnonymous = "true")
      }
    }
  }

  "DQL also work with 'selector' filtered aggregations" should {
    "successfully be interpreted by Druid" in {

      val query = DQL
        .topN(d"isAnonymous", metric = "count", threshold = 5)
        .agg(d"count".count as "count")
        .agg(
          d"channel".selectorFiltered(
            aggregator = d"count".count,
            value = "#en.wikipedia"
          ) as "filteredCount"
        )
        .interval("2011-06-01/2017-06-01")
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        val topN = response.list[AggregatedFilteredAnonymous]
        topN.size shouldBe 2
        topN.head shouldBe AggregatedFilteredAnonymous(count = 35445,
                                                       filteredCount = 9993,
                                                       isAnonymous = "false")
        topN(1) shouldBe AggregatedFilteredAnonymous(count = 3799,
                                                     filteredCount = 1556,
                                                     isAnonymous = "true")
      }
    }
  }

  "DQL also work with 'cardinality' aggregations" should {

    "successfully computing cardinality by value" in {

      val expectedValue = 1148.0

      val query = DQL.timeseries
        .agg(cardinality("cardinalityValue", d"countryName", d"cityName").setRound(true))
        .interval("2011-06-01/2017-06-01")
        .build()

      val request = query.execute()
      whenReady(request) { response =>
        val result = response.list[AggregatedCardinality]
        result.size shouldBe 1
        result.head.cardinalityValue shouldBe expectedValue
      }
    }

    "successfully computing cardinality by row" in {

      val expectedValue = 1152.0

      val query = DQL.timeseries
        .agg(
          cardinality("cardinalityValue", d"countryName", d"cityName")
            .set(byRow = true, round = true)
        )
        .interval("2011-06-01/2017-06-01")
        .build()

      val request = query.execute()
      whenReady(request) { response =>
        val result = response.list[AggregatedCardinality]
        result.size shouldBe 1
        result.head.cardinalityValue shouldBe expectedValue
      }
    }

    "successfully computing cardinality by row, using an extraction function to a dimension " in {

      val expectedValue = 509.0

      val query = DQL.timeseries
        .agg(
          cardinality(
            "cardinalityValue",
            d"countryName",
            d"cityName".extract(SubstringExtractionFn(0, Some(1))).as("city_first_char")
          ).set(byRow = true, round = true)
        )
        .interval("2011-06-01/2017-06-01")
        .build()

      val request = query.execute()
      whenReady(request) { response =>
        val result = response.list[AggregatedCardinality]
        result.size shouldBe 1
        result.head.cardinalityValue shouldBe expectedValue
      }
    }
  }

  "DQL also work with 'arithmetic' post-aggregations" should {
    "successfully be interpreted by Druid" in {

      val query: TopNQuery = DQL
        .topN(d"isAnonymous", metric = "count", threshold = 5)
        .agg(count)
        .postAgg((d"count" / 2) as "halfCount")
        .interval("2011-06-01/2017-06-01")
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        val topN = response.list[PostAggregationAnonymous]
        topN.size shouldBe 2
        topN.head shouldBe PostAggregationAnonymous(count = 35445,
                                                    halfCount = 17722.5,
                                                    isAnonymous = "false")
        topN(1) shouldBe PostAggregationAnonymous(count = 3799,
                                                  halfCount = 1899.5,
                                                  isAnonymous = "true")
      }
    }
  }

  "DQL also work with 'javascript' post-aggregations" should {
    "successfully be interpreted by Druid" in {

      val query: TopNQuery = DQL
        .topN(d"isAnonymous", metric = "count", threshold = 5)
        .agg(count)
        .postAgg(javascript("halfCount", Seq(d"count"), "function(count) { return count/2; }"))
        .interval("2011-06-01/2017-06-01")
        .build()

      val request = query.execute()

      whenReady(request) { response =>
        val topN = response.list[PostAggregationAnonymous]
        topN.size shouldBe 2
        topN.head shouldBe PostAggregationAnonymous(count = 35445,
                                                    halfCount = 17722.5,
                                                    isAnonymous = "false")
        topN(1) shouldBe PostAggregationAnonymous(count = 3799,
                                                  halfCount = 1899.5,
                                                  isAnonymous = "true")
      }
    }
  }

  "DQL query with context" should {

    "withQueryContext produce the desired JSON" in {

      val query = DQL.timeseries
        .granularity(GranularityType.Hour)
        .interval("2011-06-01/2017-06-01")
        .setDescending(true)
        .agg(count as "count")
        .withQueryContext(
          Map(
            QueryContext.QueryId          -> "some_custom_id",
            QueryContext.Priority         -> 1,
            QueryContext.UseCache         -> false,
            QueryContext.SkipEmptyBuckets -> true
          )
        )
        .build()

      val requestJson = query.asJson.noSpaces

      requestJson shouldBe expectedRequestAsJson

      val resultF = query.execute()

      whenReady(resultF) { response =>
        response.list[TimeseriesCount].map(_.count).sum shouldBe totalNumberOfEntries
      }
    }

    "setQueryContextParam produce the desired JSON" in {
      val query = DQL.timeseries
        .granularity(GranularityType.Hour)
        .interval("2011-06-01/2017-06-01")
        .agg(count as "count")
        .setDescending(true)
        .setQueryContextParam(QueryContext.QueryId, "some_custom_id")
        .setQueryContextParam(QueryContext.Priority, 1)
        .setQueryContextParam(QueryContext.UseCache, false)
        .setQueryContextParam(QueryContext.SkipEmptyBuckets, true)
        .build()

      val requestJson = query.asJson.noSpaces

      requestJson shouldBe expectedRequestAsJson

      val resultF = query.execute()

      whenReady(resultF) { response =>
        response.list[TimeseriesCount].map(_.count).sum shouldBe totalNumberOfEntries
      }
    }

  }

  "DQL also work with 'javascript' aggregations" should {
    "successfully be interpreted by Druid" in {
      val expectedValue = 22642.0

      val query: TimeSeriesQuery = DQL.timeseries
        .agg(
          javascript(
            name = "value",
            fields = Seq(d"cityName", d"countryIsoCode"),
            fnAggregate = """
                |function(current, countryIsoCode, cityName) {
                |  return ((countryIsoCode != null && cityName != null) ?
                |    cityName.length + countryIsoCode.length + current : current);
                |}
                |""".stripMargin,
            fnCombine = "function(partialA, partialB) { return partialA + partialB; }",
            fnReset = "function() { return 0; }"
          )
        )
        .interval("2011-06-01/2017-06-01")
        .build()

      val resultF = query.execute()

      whenReady(resultF) { response =>
        val result = response.list[AggregationJavascript]
        result.size shouldBe 1
        result.head.value shouldBe expectedValue
      }
    }
  }

  "DQL also work with 'scan' queries" should {
    val numberOfResults = 100

    val query: ScanQuery = DQL
      .scan()
      .columns("channel", "cityName", "countryIsoCode", "user")
      .granularity(GranularityType.Day)
      .interval("2011-06-01/2017-06-01")
      .virtualColumns(List(
        ExpressionColumn("expression", "state", "lookup(derived_loc_state,'stateSlugLookup')", "STRING")))
      .batchSize(10)
      .limit(numberOfResults)
      .build()

    "successfully be interpreted by Druid" in {

      val request = query.execute()
      whenReady(request) { response =>
        val resultList = response.list[ScanResult]
        resultList.size shouldBe numberOfResults

        val resultSeries = response.series[ScanResult]
        resultSeries.size shouldBe numberOfResults
      }
    }

    "successfully be streamed" in {
      val requestSeq = query.streamAs[ScanResult].runWith(Sink.seq)
      whenReady(requestSeq) { response =>
        response.size shouldBe numberOfResults
      }

      val requestSeries = query.streamSeriesAs[ScanResult].runWith(Sink.seq)
      whenReady(requestSeries) { response =>
        response.size shouldBe numberOfResults
      }
    }
  }

  "DQL also work with 'search' queries" should {

    val query: SearchQuery = DQL
      .granularity(GranularityType.Hour)
      .interval("2011-06-01/2017-06-01")
      .search(ContainsInsensitive("GR"))
      .dimensions("countryIsoCode")
      .build()

    "successfully be interpreted by Druid" in {
      val request = query.execute()

      whenReady(request) { response =>
        val resultList = response.list
        resultList.size shouldBe 1

        val result = resultList.head

        result.dimension shouldBe "countryIsoCode"
        result.value shouldBe "GR"
        result.count shouldBe 19
      }

    }

    "successfully be streamed" in {
      val request = query.stream().runWith(Sink.seq)

      whenReady(request) { results =>
        results.size shouldBe 1

        val result = results.head

        result.dimension shouldBe "countryIsoCode"
        result.value shouldBe "GR"
        result.count shouldBe 19
      }
    }
  }

}
