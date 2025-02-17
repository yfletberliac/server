package com.hyperplan.domain.services

import cats.effect.IO
import cats.implicits._

import com.hyperplan.domain.repositories.{
  PredictionsRepository,
  ProjectsRepository
}
import com.hyperplan.infrastructure.logging.IOLogging

class PrivacyService(predictionsRepository: PredictionsRepository)
    extends IOLogging {

  def forgetPredictionsLinkedToEntity(name: String, id: String): IO[Int] =
    predictionsRepository.transact(
      predictionsRepository.deletePredictionsLinkedToEntity(name, id)
    )
}
