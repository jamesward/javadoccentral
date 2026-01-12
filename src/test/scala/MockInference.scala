import zio.*
import zio.http.*

object MockInference:

  object Mock extends SymbolSearch.HerokuInference:
    def req(prompt: String): ZIO[Client & Scope, Throwable, Response] =
      val messageBody = if prompt.contains("hello") then
        "hello"
      else if prompt.contains("`org.springframework.ai.mcp`") || prompt.contains("AsyncMcpToolCallback") then
        """[
          |  {
          |    "groupId": "org.springframework.ai",
          |    "artifactId": "spring-ai-mcp"
          |  }
          |]
          |""".stripMargin
      else if prompt.contains("`zio.cache.Cache`") then
        """[
          |  {
          |    "groupId": "dev.zio",
          |    "artifactId": "zio-cache_3"
          |  }
          |]
          |""".stripMargin
      else
        "blah"

      val response = SymbolSearch.MessageResponse(
        "mock",
        "chat.completion",
        1745619466,
        "mock",
        List(
          SymbolSearch.MessageChoice(
            0,
            SymbolSearch.Message("assistant", messageBody),
            "stop"
          )
        ),
        SymbolSearch.MessageUsage(0, 0, 0)
      )

      ZIO.succeed:
        Response(body = Body.json(response))

  val layer: ZLayer[Any, Nothing, SymbolSearch.HerokuInference] = ZLayer.succeed(Mock)
