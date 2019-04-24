package controllers

import akka.actor.ActorSystem
import com.google.inject.Inject
import models.{CharacterRepo, SchemaDefinition}
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import sangria.execution._
import sangria.execution.deferred.DeferredResolver
import sangria.marshalling.playJson._
import sangria.parser.{QueryParser, SyntaxError}
import sangria.renderer.SchemaRenderer

import scala.concurrent.Future
import scala.util.{Failure, Success}

class Application @Inject()(system: ActorSystem, config: Configuration) extends InjectedController {
  import system.dispatcher

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  def gql(query: String, variables: Option[String], operation: Option[String]) = Action.async { request ⇒
    executeQuery(query, variables map parseVariables)
  }


  /**
    * Example Query
    * {
    * "query": "{ hero { name friends { name } } }"
    * }
    *
    * {
    * "query": "query FragmentExample { human(id: \"1003\") { ...Common homePlanet } droid(id: \"2001\") { ...Common primaryFunction } } fragment Common on Character { name appearsIn }"
    * }
    *
    * {
    * "query": "query
    *   FragmentExample {
    *     human(id: \"1003\") {
    *       ...Common
    *       homePlanet
    *     }
    *     droid(id: \"2001\") {
    *       ...Common
    *       primaryFunction
    *      }
    *   }
    *   fragment Common on Character { name appearsIn }"
    * }
    *
    */
  def gqlReq = Action.async(parse.json) { request ⇒
    val query = (request.body \ "query").as[String]

    val variables = (request.body \ "variables").toOption.flatMap {
      case JsString(vars) ⇒ Some(parseVariables(vars))
      case obj: JsObject ⇒ Some(obj)
      case _ ⇒ None
    }

    executeQuery(query, variables)
  }

  private def parseVariables(variables: String) =
    if (variables.trim == "" || variables.trim == "null") Json.obj() else Json.parse(variables).as[JsObject]

  private def executeQuery(query: String, variables: Option[JsObject]) =
    QueryParser.parse(query) match {

      // query parsed successfully, time to execute it!
      case Success(queryAst) ⇒
        Executor.execute(SchemaDefinition.StarWarsSchema, queryAst, new CharacterRepo,
            variables = variables getOrElse Json.obj(),
            deferredResolver = DeferredResolver.fetchers(SchemaDefinition.characters),
            exceptionHandler = exceptionHandler,
            queryReducers = List(
              QueryReducer.rejectMaxDepth[CharacterRepo](15),
              QueryReducer.rejectComplexQueries[CharacterRepo](4000, (_, _) ⇒ TooComplexQueryError)))
          .map(Ok(_))
          .recover {
            case error: QueryAnalysisError ⇒ BadRequest(error.resolveError)
            case error: ErrorWithResolver ⇒ InternalServerError(error.resolveError)
          }

      // can't parse GraphQL query, return error
      case Failure(error: SyntaxError) ⇒
        Future.successful(BadRequest(Json.obj(
          "syntaxError" → error.getMessage,
          "locations" → Json.arr(Json.obj(
            "line" → error.originalError.position.line,
            "column" → error.originalError.position.column)))))

      case Failure(error) ⇒
        throw error
    }

  def renderSchema = Action {
    Ok(SchemaRenderer.renderSchema(SchemaDefinition.StarWarsSchema))
  }

  lazy val exceptionHandler = ExceptionHandler {
    case (_, error @ TooComplexQueryError) ⇒ HandledException(error.getMessage)
    case (_, error @ MaxQueryDepthReachedError(_)) ⇒ HandledException(error.getMessage)
  }

  case object TooComplexQueryError extends Exception("Query is too expensive.")
}
