/*
 * Copyright 2015 Heiko Seeberger
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

package de.heikoseeberger.akkahttpplayjson

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{ RejectionError, ValidationRejection }
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.ActorMaterializer
import org.scalatest.{ AsyncWordSpec, BeforeAndAfterAll, Matchers }
import play.api.libs.json.{ Format, Json }
import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object PlayJsonSupportSpec {

  final case class Foo(bar: String) {
    require(bar == "bar", "bar must be 'bar'!")
  }

  implicit val fooFormat: Format[Foo] =
    Json.format[Foo]
}

final class PlayJsonSupportSpec extends AsyncWordSpec with Matchers with BeforeAndAfterAll {
  import PlayJsonSupport._
  import PlayJsonSupportSpec._

  private implicit val system = ActorSystem()
  private implicit val mat    = ActorMaterializer()

  "PlayJsonSupport" should {
    import system.dispatcher

    "enable marshalling and unmarshalling objects for which `Writes` and `Reads` exist" in {
      val foo = Foo("bar")
      Marshal(foo)
        .to[RequestEntity]
        .flatMap(Unmarshal(_).to[Foo])
        .map(_ shouldBe foo)
    }

    "provide proper error messages for requirement errors" in {
      val entity = HttpEntity(MediaTypes.`application/json`, """{ "bar": "baz" }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ should have message "requirement failed: bar must be 'bar'!")
    }

    "provide stringified error representation for parsing errors" in {
      val entity = HttpEntity(MediaTypes.`application/json`, """{ "bar": 5 }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map({ err =>
          err shouldBe a[RejectionError]
          err match {
            case RejectionError(ValidationRejection(message, Some(PlayJsonError(error)))) =>
              message should be("""{"obj.bar":[{"msg":["error.expected.jsstring"],"args":[]}]}""")
              error.errors should have length 1
              error.errors.head._1.toString() should be("/bar")
              error.errors.head._2.flatMap(_.messages) should be(Seq("error.expected.jsstring"))
            case _ => fail("Did not throw correct validation error.")
          }
        })
    }

    "fail with NoContentException when unmarshalling empty entities" in {
      val entity = HttpEntity.empty(`application/json`)
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ shouldBe Unmarshaller.NoContentException)
    }

    "fail with UnsupportedContentTypeException when Content-Type is not `application/json`" in {
      val entity = HttpEntity("""{ "bar": "bar" }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ shouldBe UnsupportedContentTypeException(`application/json`))
    }

    "allow unmarshalling with passed in Content-Types" in {
      val foo = Foo("bar")
      val `application/json-home` =
        MediaType.applicationWithFixedCharset("json-home", HttpCharsets.`UTF-8`, "json-home")

      final object CustomPlayJsonSupport extends PlayJsonSupport {
        override def unmarshallerContentTypes = List(`application/json`, `application/json-home`)
      }
      import CustomPlayJsonSupport._

      val entity = HttpEntity(`application/json-home`, """{ "bar": "bar" }""")
      Unmarshal(entity).to[Foo].map(_ shouldBe foo)
    }
  }

  override protected def afterAll() = {
    Await.ready(system.terminate(), 42.seconds)
    super.afterAll()
  }
}
