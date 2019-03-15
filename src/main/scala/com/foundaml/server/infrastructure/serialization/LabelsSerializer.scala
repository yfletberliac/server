package com.foundaml.server.infrastructure.serialization

import io.circe.generic.extras.Configuration, io.circe.generic.extras.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

import com.foundaml.server.domain.models.labels._

object LabelsSerializer {

  implicit val discriminator: Configuration =
    Configuration.default.withDiscriminator("class")
  implicit val encoder: Encoder[Labels] = implicitly[Encoder[Labels]]
  implicit val decoder: Decoder[Labels] = implicitly[Decoder[Labels]]

  def encodeJson(labels: Labels): String = {
    labels.asJson.noSpaces
  }

  def decodeJson(n: String): Labels = {
    decode[Labels](n).right.get
  }
}
