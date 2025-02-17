package com.hyperplan.domain.models.labels

case class Labels(labels: Set[Label])

sealed trait Label

case class ClassificationLabel(
    label: String,
    probability: Float,
    correctExampleUrl: String,
    incorrectExampleUrl: String
) extends Label

case class RegressionLabel(label: Float, correctExampleUrl: String)
    extends Label
