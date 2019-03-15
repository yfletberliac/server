package com.foundaml.server.utils

import com.foundaml.server.domain.models._
import com.foundaml.server.domain.models.features._
import com.foundaml.server.domain.models.labels._
import com.foundaml.server.domain.models.backends._

import java.util.UUID

object ProjectGenerator {

  val computed = TensorFlowClassificationLabels(
    List(
      TensorFlowClassicationLabel(
        List(1, 2, 3),
        List(0.0f, 0.1f, 0.2f),
        List("class1", "class2", "class3"),
        List(0.0f, 0.0f, 0.0f)
      )
    )
  )
  val projectId = UUID.randomUUID().toString
  val defaultAlgorithm = Algorithm(
    "algorithm id",
    Local(computed),
    projectId
  )

  def withLocalBackend() = Project(
    projectId,
    "example project",
    Classification(),
    "tf.cl",
    "tf.cl",
    Map.empty,
    DefaultAlgorithm(defaultAlgorithm)
  )
}
