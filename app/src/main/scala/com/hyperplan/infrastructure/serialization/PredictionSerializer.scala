package com.hyperplan.infrastructure.serialization

import cats.effect.IO

import org.http4s.circe.{jsonOf, jsonEncoderOf}

import io.circe.parser.decode
import io.circe.syntax._

import com.hyperplan.domain.models._
import com.hyperplan.domain.models.features.Features.Features
import com.hyperplan.domain.models.labels.{ClassificationLabel, RegressionLabel}
import com.hyperplan.infrastructure.serialization.features.FeaturesSerializer
import com.hyperplan.infrastructure.serialization.labels.{
  ClassificationLabelSerializer,
  RegressionLabelSerializer
}
import org.http4s.{EntityDecoder, EntityEncoder}

object PredictionSerializer {

  import io.circe._

  implicit val entityLinkListEncoder: Encoder[List[EntityLink]] =
    (entityLinks: List[EntityLink]) =>
      entityLinks.map(link => link.name -> link.id).toMap.asJson

  def encodeListEntityLinkNoSpaces(entityLinks: List[EntityLink]) =
    entityLinks.asJson.noSpaces

  implicit val entityLinkEncoder: Encoder[EntityLink] =
    (entityLink: EntityLink) =>
      Json.obj(
        ("name", Json.fromString(entityLink.name)),
        ("id", Json.fromString(entityLink.id))
      )

  implicit val entityLinkDecoder: Decoder[EntityLink] =
    (c: HCursor) =>
      for {
        name <- c.downField("name").as[String]
        id <- c.downField("id").as[String]
      } yield EntityLink(name, id)

  implicit val featuresEncoder: Encoder[Features] =
    FeaturesSerializer.Implicits.encoder
  implicit val featuresDecoder: Decoder[Features] =
    FeaturesSerializer.Implicits.decoder

  val problemTypeEncoder: Encoder[ProblemType] = ProblemTypeSerializer.encoder

  implicit val classificationLabelsEncoder: Encoder[Set[ClassificationLabel]] =
    Encoder.encodeSet[ClassificationLabel](
      ClassificationLabelSerializer.classificationlabelEncoder
    )

  implicit val classificationLabelsDecoder: Decoder[Set[ClassificationLabel]] =
    Decoder.decodeSet[ClassificationLabel](
      ClassificationLabelSerializer.classificationLabelDecoder
    )

  implicit val regressionLabelsEncoder: Encoder[Set[RegressionLabel]] =
    Encoder.encodeSet[RegressionLabel](
      RegressionLabelSerializer.regressionlabelEncoder
    )

  implicit val regressionLabelsDecoder: Decoder[Set[RegressionLabel]] =
    Decoder.decodeSet[RegressionLabel](
      RegressionLabelSerializer.regressionLabelDecoder
    )

  implicit val classificationPredictionEncoder
      : Encoder[ClassificationPrediction] =
    (prediction: ClassificationPrediction) =>
      Json.obj(
        ("type", problemTypeEncoder(prediction.predictionType)),
        ("id", Json.fromString(prediction.id)),
        ("projectId", Json.fromString(prediction.projectId)),
        ("algorithmId", Json.fromString(prediction.algorithmId)),
        ("features", featuresEncoder(prediction.features)),
        ("labels", classificationLabelsEncoder(prediction.labels)),
        ("examples", Json.fromValues(prediction.examples.map(Json.fromString)))
      )

  implicit val regressionPredictionEncoder: Encoder[RegressionPrediction] =
    (prediction: RegressionPrediction) =>
      Json.obj(
        ("type", problemTypeEncoder(prediction.predictionType)),
        ("id", Json.fromString(prediction.id)),
        ("projectId", Json.fromString(prediction.projectId)),
        ("algorithmId", Json.fromString(prediction.algorithmId)),
        ("features", featuresEncoder(prediction.features)),
        ("labels", regressionLabelsEncoder(prediction.labels)),
        (
          "examples",
          Json.fromValues(prediction.examples.map(Json.fromFloatOrNull))
        )
      )

  implicit val encoder: Encoder[Prediction] = {
    case prediction: ClassificationPrediction =>
      prediction.asJson
    case prediction: RegressionPrediction =>
      prediction.asJson
  }

  implicit val decoder: Decoder[Prediction] =
    (c: HCursor) =>
      c.downField("type")
        .as[ProblemType](ProblemTypeSerializer.decoder)
        .flatMap {
          case Classification =>
            for {
              id <- c.downField("id").as[String]
              projectId <- c.downField("projectId").as[String]
              algorithmId <- c.downField("algorithmId").as[String]
              features <- c.downField("features").as[Features]
              labels <- c.downField("labels").as[Set[ClassificationLabel]]
              examples <- c.downField("examples").as[List[String]]
            } yield
              ClassificationPrediction(
                id,
                projectId,
                algorithmId,
                features,
                examples,
                labels
              )
          case Regression =>
            for {
              id <- c.downField("id").as[String]
              projectId <- c.downField("projectId").as[String]
              algorithmId <- c.downField("algorithmId").as[String]
              features <- c.downField("features").as[Features]
              labels <- c.downField("labels").as[Set[RegressionLabel]]
              examples <- c.downField("examples").as[List[Float]]
            } yield
              RegressionPrediction(
                id,
                projectId,
                algorithmId,
                features,
                examples,
                labels
              )
        }

  implicit val entityDecoder: EntityDecoder[IO, Prediction] =
    jsonOf[IO, Prediction]

  implicit val entityListDecoder: EntityDecoder[IO, List[Prediction]] =
    jsonOf[IO, List[Prediction]]

  implicit val entityEncoder: EntityEncoder[IO, Prediction] =
    jsonEncoderOf[IO, Prediction]

  implicit val entityListEncoder: EntityEncoder[IO, List[Prediction]] =
    jsonEncoderOf[IO, List[Prediction]]

  def encodeJson(request: Prediction): Json = {
    request.asJson
  }

  def decodeJson(n: String): Prediction = {
    decode[Prediction](n).right.get
  }
}
