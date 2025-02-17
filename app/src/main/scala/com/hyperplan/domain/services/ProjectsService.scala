package com.hyperplan.domain.services

import cats.effect.IO
import cats.implicits._
import cats.data._
import com.hyperplan.domain.repositories.DomainRepository
import com.hyperplan.application.controllers.requests.PostProjectRequest
import com.hyperplan.domain.models._
import com.hyperplan.domain.errors.ProjectError
import com.hyperplan.domain.errors.ProjectError._
import com.hyperplan.domain.models.features._
import com.hyperplan.domain.repositories.ProjectsRepository
import com.hyperplan.infrastructure.logging.IOLogging
import doobie.free.connection.{ConnectionIO, AsyncConnectionIO}
import doobie.util.invariant.UnexpectedEnd
import scalacache.Cache
import scalacache.CatsEffect.modes._

class ProjectsService(
    projectsRepository: ProjectsRepository,
    domainService: DomainService,
    cache: Cache[Project]
) extends IOLogging {

  type ProjectValidationResult[A] = ValidatedNec[ProjectError, A]

  def createEmptyClassificationProject(
      projectRequest: PostProjectRequest
  ): EitherT[IO, NonEmptyChain[ProjectError], Project] =
    for {
      _ <- EitherT.fromEither[IO](
        validateCreateProject(projectRequest).toEither
      )
      streamConfiguration <- EitherT.pure[IO, NonEmptyChain[ProjectError]](
        projectRequest.topic.map(topic => StreamConfiguration(topic))
      )
      features <- EitherT
        .fromOptionF[IO, NonEmptyChain[ProjectError], FeatureVectorDescriptor](
          domainService.readFeatures(projectRequest.featuresId),
          NonEmptyChain(
            ProjectError.FeaturesDoesNotExistError(
              ProjectError.FeaturesDoesNotExistError.message(
                projectRequest.featuresId
              )
            )
          )
        )
      labels <- EitherT
        .fromOptionF[IO, NonEmptyChain[ProjectError], LabelVectorDescriptor](
          domainService.readLabels(projectRequest.labelsId.getOrElse("")),
          NonEmptyChain(
            ProjectError.LabelsDoesNotExistError(
              ProjectError.LabelsDoesNotExistError.message(
                projectRequest.labelsId.getOrElse("")
              )
            )
          )
        )
    } yield
      ClassificationProject(
        projectRequest.id,
        projectRequest.name,
        ClassificationConfiguration(
          features,
          labels,
          streamConfiguration
        ),
        Nil,
        NoAlgorithm()
      )

  def validateAlphanumericalProjectId(
      id: String
  ): ProjectValidationResult[String] =
    Either
      .cond(
        id.matches("^[a-zA-Z0-9]*$"),
        id,
        ProjectIdIsNotAlphaNumericalError(
          ProjectIdIsNotAlphaNumericalError.message(id)
        )
      )
      .toValidatedNec

  def validateProjectIdNotEmpty(id: String): ProjectValidationResult[String] =
    Either
      .cond(
        id.nonEmpty,
        id,
        ProjectIdIsEmptyError()
      )
      .toValidatedNec

  def validateProjectNameNotEmpty(id: String): ProjectValidationResult[String] =
    Either
      .cond(
        id.nonEmpty,
        id,
        ProjectNameIsEmptyError()
      )
      .toValidatedNec

  def validateLabels(
      projectRequest: PostProjectRequest
  ): ProjectValidationResult[Unit] = projectRequest.problem match {
    case Classification if projectRequest.labelsId.isEmpty =>
      Validated.invalid(
        NonEmptyChain(ProjectLabelsAreRequiredForClassificationError())
      )
    case Classification if projectRequest.labelsId.isDefined =>
      Validated.valid(Unit)
    case Regression =>
      Validated.valid(Unit)
  }

  def validateCreateProject(
      projectRequest: PostProjectRequest
  ): ProjectValidationResult[Unit] =
    (
      validateAlphanumericalProjectId(projectRequest.id),
      validateProjectIdNotEmpty(projectRequest.id),
      validateProjectNameNotEmpty(projectRequest.name),
      validateLabels(projectRequest)
    ).mapN((_, _, _, _) => Unit)

  def createEmptyRegressionProject(
      projectRequest: PostProjectRequest
  ): EitherT[IO, NonEmptyChain[ProjectError], Project] =
    for {
      _ <- EitherT.fromEither[IO](
        validateCreateProject(projectRequest).toEither
      )
      streamConfiguration <- EitherT.pure[IO, NonEmptyChain[ProjectError]](
        projectRequest.topic.map(topic => StreamConfiguration(topic))
      )
      features <- EitherT
        .fromOptionF[IO, NonEmptyChain[ProjectError], FeatureVectorDescriptor](
          domainService.readFeatures(projectRequest.featuresId),
          NonEmptyChain(
            ProjectError.FeaturesDoesNotExistError(
              ProjectError.FeaturesDoesNotExistError.message(
                projectRequest.featuresId
              )
            )
          )
        )
    } yield
      RegressionProject(
        projectRequest.id,
        projectRequest.name,
        RegressionConfiguration(
          features,
          streamConfiguration
        ),
        Nil,
        NoAlgorithm()
      )

  def createEmptyProject(
      projectRequest: PostProjectRequest
  ): EitherT[IO, NonEmptyChain[ProjectError], Project] =
    (projectRequest.problem match {
      case Classification => createEmptyClassificationProject(projectRequest)
      case Regression => createEmptyRegressionProject(projectRequest)
    }).flatMap { project =>
      EitherT(projectsRepository.insert(project).map {
        case Left(err) =>
          // we need a NonEmptyChain of errors but insert returns a single error
          NonEmptyChain(err).asLeft
        case Right(count) => count.asRight
      }).flatMap {
        case insertedCount if insertedCount > 0 =>
          EitherT.liftF[IO, NonEmptyChain[ProjectError], Project](
            cache.remove[IO](project.id).map(_ => project)
          )
        case insertedCount if insertedCount <= 0 =>
          EitherT.liftF[IO, NonEmptyChain[ProjectError], Project](
            IO.raiseError(
              new Exception(
                "IO returned successfully but nothing was inserted in the database"
              )
            )
          )
      }
    }

  def updateProject(
      projectId: String,
      name: Option[String],
      policy: Option[AlgorithmPolicy]
  ): EitherT[IO, NonEmptyChain[ProjectError], Project] =
    EitherT
      .fromEither[IO](
        name
          .fold[ProjectValidationResult[String]](
            Validated.valid("").toValidatedNec
          )(validateProjectNameNotEmpty)
          .toEither
      )
      .flatMap { _ =>
        EitherT(
          projectsRepository
            .transact(
              projectsRepository
                .read(projectId)
                .flatMap[Project] {
                  case Some(project: ClassificationProject) =>
                    (project.copy(
                      name = name.getOrElse(project.name),
                      policy = policy.getOrElse(project.policy)
                    ): Project).pure[ConnectionIO]
                  case Some(project: RegressionProject) =>
                    (project.copy(
                      name = name.getOrElse(project.name),
                      policy = policy.getOrElse(project.policy)
                    ): Project).pure[ConnectionIO]
                  case None =>
                    AsyncConnectionIO.raiseError(
                      ProjectDoesNotExistError(
                        ProjectDoesNotExistError.message(projectId)
                      )
                    )
                }
                .flatMap { project =>
                  val validated = project.policy match {
                    case NoAlgorithm() => Validated.valid(Unit)
                    case DefaultAlgorithm(algorithmId) =>
                      Either
                        .cond(
                          project.algorithms.map(_.id).contains(algorithmId),
                          Unit,
                          ProjectPolicyAlgorithmDoesNotExist(
                            ProjectPolicyAlgorithmDoesNotExist
                              .message(algorithmId)
                          )
                        )
                        .toValidatedNec
                    case WeightedAlgorithm(weights) =>
                      val projectAlgorithmIds = project.algorithms.map(_.id)
                      val algorithmsMissing: Seq[String] =
                        weights.map(_.algorithmId).collect {
                          case id if !projectAlgorithmIds.contains(id) => id
                        }
                      Either
                        .cond(
                          algorithmsMissing.isEmpty,
                          Unit,
                          ProjectPolicyAlgorithmDoesNotExist(
                            ProjectPolicyAlgorithmDoesNotExist
                              .message(algorithmsMissing)
                          )
                        )
                        .toValidatedNec
                  }
                  validated.fold[ConnectionIO[Project]](
                    err => AsyncConnectionIO.raiseError(err.head),
                    _ =>
                      projectsRepository.update(project) *> project
                        .pure[ConnectionIO]
                  )

                }
            )
            .flatMap { project =>
              cache.remove[IO](projectId).map(_ => project.asRight)
            }
            .handleErrorWith {
              case err: ProjectDoesNotExistError =>
                IO.pure(NonEmptyChain(err).asLeft)
              case err: ProjectPolicyAlgorithmDoesNotExist =>
                IO.pure(NonEmptyChain(err).asLeft)
              case err =>
                IO.raiseError(err)
            }
        )

      }

  def readProjects =
    projectsRepository.transact(projectsRepository.readAll)

  def readProject(id: String): IO[Option[Project]] =
    cache.get[IO](id).flatMap { cacheElement =>
      cacheElement.fold(
        projectsRepository.transact(projectsRepository.read(id))
      )(
        project => IO.pure(project.some)
      )
    }

}
